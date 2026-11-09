#!/usr/bin/env python3
"""LOCAL-ONLY fault verification for the skitrak-a2 Docker Compose stack.
Stops/restarts only its consumer and RabbitMQ services; never deletes volumes.
Run without concurrent producers. This is functional evidence, not an EC2 benchmark.
"""
import argparse
import collections
import json
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from verify_pipeline import WebSocket, http_json, load_lifts

ROOT = Path(__file__).resolve().parents[1]


def loopback(url):
    parsed = urlparse(url)
    if parsed.scheme not in ('http', 'https') or parsed.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise ValueError('Fault injection refuses non-loopback endpoint: ' + url)
    if parsed.username or parsed.password:
        raise ValueError('Credentials in endpoint URLs are not allowed')
    return url.rstrip('/')


def wait_for(description, action, timeout=60):
    deadline, last = time.monotonic() + timeout, None
    while time.monotonic() < deadline:
        try:
            value = action()
            if value: return value
        except (HTTPError, URLError, OSError, ValueError, AssertionError) as error:
            last = str(error)
        time.sleep(0.2)
    raise AssertionError(description + ' timed out' + (': ' + last if last else ''))


def get(url):
    return http_json(url)[1]


def post(url, body, event_id):
    try:
        status, payload, _ = http_json(url, body, {'Idempotency-Key': event_id})
        return status, payload
    except HTTPError as error:
        # Error bodies contain application status, never environment or broker credentials.
        try: payload = json.loads(error.read().decode())
        except (ValueError, UnicodeError): payload = {'error': 'Non-JSON HTTP error'}
        return error.code, payload


def idle(metrics):
    return metrics.get('healthy') is True and metrics.get('queueReady') == 0 \
        and metrics.get('inFlight') == 0 and metrics.get('pendingOutbox') == 0 \
        and metrics.get('queueMetricsUpdatedAt', 0) > 0 \
        and 0 <= metrics.get('queueMetricsAgeMs', -1) <= 3000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ingest-url', action='append', help='Exactly two loopback endpoints; defaults8080/8082')
    parser.add_argument('--consumer-url', default='http://localhost:8081')
    parser.add_argument('--day', type=int, default=365)
    parser.add_argument('--resort', type=int, default=3)
    parser.add_argument('--output', default='results/a2-local/recovery-verification.json')
    args = parser.parse_args()
    urls = [loopback(url) for url in (args.ingest_url or ['http://localhost:8080', 'http://localhost:8082'])]
    consumer = loopback(args.consumer_url)
    if len(urls) != 2 or len(set(urls)) != 2: parser.error('Provide two distinct local ingest endpoints')
    if not 1 <= args.day <= 366 or not 1 <= args.resort <= 10: parser.error('day1..366, resort1..10 required')
    env_file = ROOT / 'deployment/.env'
    if not env_file.is_file(): parser.error('deployment/.env is required; this script never prints it')
    compose = ['docker', 'compose', '--project-name', 'skitrak-a2', '--env-file', str(env_file),
               '-f', str(ROOT / 'deployment/compose.yaml')]
    result = {'passed': False, 'localOnly': True, 'composeProject': 'skitrak-a2',
              'dayID': args.day, 'resortID': args.resort, 'stages': [], 'bindingTransitions': [],
              'note': 'Local functional fault evidence only. Persistent volumes were preserved.'}
    touched, sockets = set(), []
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    lifts = load_lifts(ROOT / 'deployment/config/lift_vertical.json')
    expected, events = collections.defaultdict(int), []

    def compose_action(action, service):
        if service not in ('consumer', 'rabbitmq') or action not in ('stop', 'start'):
            raise ValueError('Only local consumer/rabbitmq stop/start are allowed')
        completed = subprocess.run(compose + [action, service], cwd=ROOT, capture_output=True, text=True, timeout=75)
        if completed.returncode:
            raise RuntimeError('Local Compose ' + action + ' ' + service + ' failed: ' + completed.stderr[-500:])

    def metrics(index): return get(urls[index] + '/metrics')
    def health(index):
        value = get(urls[index] + '/health')
        return value if value.get('producerReady') and value.get('backplaneReady') else None
    def snapshot(): return get(f'{consumer}/leaderboards/{args.resort}?dayID={args.day}')

    def bind_state(index, sessions, bindings):
        def check():
            value = metrics(index)
            if value.get('localSessions') == sessions and value.get('backplaneBindings') == bindings:
                return {'serverId': value.get('serverId'), 'localSessions': sessions, 'backplaneBindings': bindings}
        observed = wait_for('Local subscription/binding transition', check)
        result['bindingTransitions'].append(observed)
        return observed

    def connect(index, minimum_version=0):
        ws_url = urls[index].replace('https://', 'wss://', 1).replace('http://', 'ws://', 1)
        client = WebSocket(f'{ws_url}/leaderboard/{args.resort}?dayID={args.day}')
        client.wait(lambda value: value.get('version', -1) >= minimum_version)
        return client

    def new_event(skier, lift, minute):
        event = {'eventId': str(uuid.uuid4()), 'skier': skier, 'lift': lift, 'time': minute}
        events.append(event)
        expected[str(skier)] += lifts[lift]
        return event

    def submit(event, index):
        path = f"/skiers/{args.resort}/seasons/2026/days/{args.day}/skiers/{event['skier']}"
        return post(urls[index] + path, {'time': event['time'], 'liftID': event['lift']}, event['eventId'])

    def verify_stage(label, unique_count):
        def processed():
            value = snapshot()
            return value if value.get('processed') == unique_count else None
        current = wait_for(label + ' unique event count', processed)
        ordered = sorted(expected.items(), key=lambda pair: (-pair[1], int(pair[0])))[:20]
        target = [{'rank': rank, 'skierID': skier, 'vertical': score}
                  for rank, (skier, score) in enumerate(ordered, 1)]
        actual = [{'rank': row['rank'], 'skierID': str(row['skierID']), 'vertical': row['vertical']}
                  for row in current['top']]
        if actual != target: raise AssertionError(label + ' totals/Top20 differ from independent expected values')
        for client in sockets:
            observed = client.wait(lambda value: value.get('version', -1) >= current['version'], timeout=30)
            if observed['version'] != current['version'] or observed['top'] != current['top']:
                raise AssertionError(label + ' WebSocket does not match authoritative final snapshot')
        wait_for(label + ' full queue drain', lambda: idle(get(consumer + '/metrics')))
        stage = {'stage': label, 'uniqueEvents': unique_count, 'version': current['version'], 'top': current['top']}
        result['stages'].append(stage)
        return current

    try:
        running = subprocess.run(compose + ['ps', '--services', '--status', 'running'], cwd=ROOT,
                                 capture_output=True, text=True, check=True, timeout=15)
        if not {'rabbitmq', 'consumer', 'ingest1', 'ingest2'}.issubset(set(running.stdout.split())):
            raise AssertionError('All four services of the local skitrak-a2 project must already be running')
        for index in (0, 1):
            wait_for('Initial ingest readiness', lambda index=index: health(index))
            if metrics(index).get('demoLifts') is not False:
                raise AssertionError('This verifier requires the supplied official lift table, not demo lifts')
            bind_state(index, 0, 0)  # Refuse a run with unrelated local subscribers.
        wait_for('Initial consumer healthy and drained', lambda: idle(get(consumer + '/metrics')))
        before = snapshot()
        if before.get('processed') != 0 or before.get('top'):
            raise AssertionError('Chosen resort/day is not unused; choose another --day before running')
        sockets.extend([connect(0), connect(1)])
        bind_state(0, 1, 1); bind_state(1, 1, 1)
        extra = connect(0)
        try: bind_state(0, 2, 1)
        finally: extra.close()
        bind_state(0, 1, 1)
        sockets[0].close(); bind_state(0, 0, 0)
        sockets[0] = connect(0); bind_state(0, 1, 1)

        for index, (skier, lift) in enumerate([(11, 21), (22, 1), (11, 40)]):
            code, _ = submit(new_event(skier, lift, index + 1), index % 2)
            if code != 201: raise AssertionError('Initial valid swipe was not accepted')
        initial = verify_stage('initial', 3)

        touched.add('consumer'); compose_action('stop', 'consumer')
        for index in range(20):
            code, _ = submit(new_event(100 + index % 5, index % 40 + 1, index + 10), index % 2)
            if code != 201: raise AssertionError('Ingest should queue swipes while consumer is stopped')
        for index, event in enumerate(events[:3]):
            code, _ = submit(event, (index + 1) % 2)
            if code != 201: raise AssertionError('Replay while consumer stopped was not accepted')
        compose_action('start', 'consumer')
        wait_for('Restarted consumer readiness', lambda: get(consumer + '/health').get('status') in ('UP', 'healthy')
                 or get(consumer + '/metrics').get('healthy') is True)
        restored = verify_stage('consumer-restarted-with-backlog-and-duplicates', 23)
        if restored['version'] <= initial['version']: raise AssertionError('Durable version did not advance after restart')

        # Stop the broker only after consumer recovery is fully verified.
        touched.add('rabbitmq'); compose_action('stop', 'rabbitmq')
        def unavailable():
            values = [metrics(index) for index in (0, 1)]
            return all(not value.get('producerReady') and not value.get('backplaneReady') for value in values)
        wait_for('Producer/backplane failure detection', unavailable)
        pending = {'eventId': str(uuid.uuid4()), 'skier': 77, 'lift': 21, 'time': 99}
        code, _ = submit(pending, 0)
        if code != 503: raise AssertionError('Stopped-broker publish must return503, got ' + str(code))
        result['brokerDownPublishStatus'] = code
        for index in (0, 1):
            try:
                get(urls[index] + '/health')
                raise AssertionError('Ingest health must be non-success while broker is down')
            except HTTPError as error:
                if error.code != 503: raise

        compose_action('start', 'rabbitmq')
        for index in (0, 1):
            wait_for('Producer/backplane reconnect', lambda index=index: health(index), timeout=90)
            bind_state(index, 1, 1)
        wait_for('Consumer reconnect', lambda: get(consumer + '/metrics').get('healthy') is True, timeout=90)
        # A failed HTTP attempt can be ambiguous. Reuse the same identity when retrying it.
        events.append(pending); expected[str(pending['skier'])] += lifts[pending['lift']]
        code, _ = submit(pending, 1)
        if code != 201: raise AssertionError('Retry with the same identity did not recover')
        code, _ = submit(pending, 0)
        if code != 201: raise AssertionError('Post-recovery duplicate was not accepted')
        code, _ = submit(new_event(77, 40, 100), 0)
        if code != 201: raise AssertionError('Fresh post-recovery event was not accepted')
        final = verify_stage('broker-restarted-replay-and-new-event', 25)
        if final['version'] <= restored['version']: raise AssertionError('Recovered stream did not advance version')
        for index in (0, 1):
            current = metrics(index)
            result.setdefault('recoveredNodes', []).append({
                key: current.get(key) for key in ('serverId', 'producerReady', 'backplaneReady',
                                                  'producerConnectionsEstablished', 'localSessions', 'backplaneBindings')})
        result['passed'] = True
    except Exception as error:
        result['failure'] = str(error)
        raise
    finally:
        for client in sockets: client.close()
        restore_errors = []
        # Always restore our touched services, in dependency order. Never delete/recreate data volumes.
        for service in ('rabbitmq', 'consumer'):
            if service in touched:
                try: compose_action('start', service)
                except Exception as error: restore_errors.append(str(error))
        if restore_errors:
            result['restoreErrors'] = restore_errors; result['passed'] = False
        result['timestampUTC'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())
        output.write_text(json.dumps(result, indent=2))
        print(json.dumps({'passed': result['passed'], 'stages': [stage['stage'] for stage in result['stages']],
                          'evidence': str(output), 'failure': result.get('failure'),
                          'restoreErrors': restore_errors}))
        if restore_errors: raise SystemExit(1)


if __name__ == '__main__': main()
