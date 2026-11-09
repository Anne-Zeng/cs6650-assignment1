#!/usr/bin/env python3
"""Briefly stop one existing ingest, reconnect a test WebSocket, then restore it.

Run with load clients stopped. This verifies harness-managed reconnection, not
the Java client's automatic retry implementation. Uses an unused resort/day.
"""
import argparse
import datetime
import json
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError, URLError
from ec2_monitor import HOSTS, ROOT
from verify_pipeline import WebSocket, http_json


def ssh(role, command):
    host, alias = HOSTS[role]
    args = ['ssh', '-T', '-i', str(ROOT/'cs6650-lab1-key.pem'),
            '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', 'ConnectTimeout=10']
    if alias:
        args += ['-o', 'HostKeyAlias='+alias]
    result = subprocess.run(args+['ec2-user@'+host, command],
                            capture_output=True, text=True, timeout=75, check=True)
    return result.stdout


def health(role):
    return json.loads(ssh(role, 'curl -fsS --max-time 10 http://127.0.0.1:8080/health'))


def wait_for(label, operation, timeout=150):
    deadline = time.monotonic()+timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = operation()
            if value:
                return value
        except (OSError, ValueError, AssertionError, subprocess.SubprocessError) as error:
            last = type(error).__name__
        time.sleep(2)
    raise RuntimeError(label+' timed out; last error='+str(last))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://skitrak-alb-1400622109.us-west-2.elb.amazonaws.com')
    parser.add_argument('--consumer-url', default='http://127.0.0.1:18081')
    parser.add_argument('--day', type=int, default=349)
    parser.add_argument('--output', default='results/a2-ec2/live-websocket-failover.json')
    args = parser.parse_args()
    if not 1 <= args.day <= 366:
        parser.error('day must be 1..366')
    base = args.base_url.rstrip('/')
    consumer = args.consumer_url.rstrip('/')
    roles = [role for role in HOSTS if role.startswith('ingest')]
    assert len(roles) == 4, 'Expected four configured ingests'
    result = {'passed': False, 'timestampUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'dayID': args.day, 'resortID': 9,
              'reconnectionMode': 'Python test harness retries a new WebSocket through ALB'}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    original = replacement = None
    stopped = None
    try:
        _, metrics, _ = http_json(consumer+'/drain')
        assert metrics.get('drained'), 'Stop load producers and wait for pipeline drain'
        initial = {role: health(role) for role in roles}
        assert all(m['status']=='UP' and m['localSessions']==0 for m in initial.values()), 'Stop other WebSocket clients first'
        snapshot_url = consumer+f'/leaderboards/9?dayID={args.day}'
        _, empty, _ = http_json(snapshot_url)
        assert empty['processed']==0 and not empty['top'], 'Choose an unused test day'
        ws_url = base.replace('https://','wss://',1).replace('http://','ws://',1)+f'/leaderboard/9?dayID={args.day}'
        original = WebSocket(ws_url)
        original.wait(lambda s: s['processed']==0)
        owners = [role for role in roles if health(role)['localSessions']==1]
        assert len(owners)==1, 'Cannot unambiguously identify the WebSocket owner'
        owner = owners[0]
        result['originalServer'] = initial[owner]['serverId']

        def publish(event_id):
            status, body, _ = http_json(base+f'/skiers/9/seasons/2026/days/{args.day}/skiers/123',
                                       {'time':217,'liftID':21}, {'Idempotency-Key':event_id})
            assert status==201
            return body

        def correct(s, count):
            return s['processed']==count and len(s['top'])==1 and str(s['top'][0]['skierID'])=='123' and s['top'][0]['vertical']==267*count

        publish(str(uuid.uuid4()))
        result['beforeFailure'] = original.wait(lambda s: correct(s,1))
        print('Stopping WebSocket owner: '+owner, flush=True)
        started = time.monotonic()
        stopped = owner  # Restore even if SSH disconnects during the stop command.
        ssh(owner, 'sudo -n systemctl stop skitrak-ingest')
        wait_for('Original socket disconnect', lambda: bool(original.errors), timeout=30)
        result['disconnectObserved'] = True
        original.close()
        original = None

        def reconnect():
            candidate = WebSocket(ws_url)
            try:
                candidate.wait(lambda s: correct(s,1), timeout=15)
                return candidate
            except BaseException:
                candidate.close()
                raise

        replacement = wait_for('ALB WebSocket reconnection', reconnect)
        surviving = [role for role in roles if role!=owner]
        new_owners = [role for role in surviving if health(role)['localSessions']==1]
        assert len(new_owners)==1
        result['replacementServer'] = initial[new_owners[0]]['serverId']
        assert result['replacementServer']!=result['originalServer']
        result['reconnectSecondsFromStopStart'] = round(time.monotonic()-started,3)
        event_id = str(uuid.uuid4())
        # Preserve the same key if an ALB request temporarily reaches a dead target.
        wait_for('Post-failure publish', lambda: publish(event_id))
        result['afterFailure'] = replacement.wait(lambda s: correct(s,2), timeout=30)
        _, authoritative, _ = http_json(snapshot_url)
        assert correct(authoritative,2)
        result['functionalChecksPassed'] = True
    finally:
        if original:
            original.close()
        if replacement:
            replacement.close()
        try:
            if stopped:
                print('Restoring stopped ingest: '+stopped, flush=True)
                ssh(stopped, 'sudo -n systemctl start skitrak-ingest')
                wait_for('Restored ingest health', lambda: health(stopped)['status']=='UP')
                result['restoredIngestHealthy'] = True
                seen = set()
                def all_targets():
                    _, h, _ = http_json(base+'/health', headers={'Connection':'close'})
                    seen.add(h['serverId'])
                    return len(seen)==len(roles)
                wait_for('All four ALB targets routing', all_targets)
                result['restoredALBTargets'] = sorted(seen)
            result['passed'] = bool(result.get('functionalChecksPassed') and result.get('restoredIngestHealthy') and len(result.get('restoredALBTargets',[]))==4)
        finally:
            output.write_text(json.dumps(result,indent=2)+'\n')
            print(json.dumps(result,indent=2),flush=True)
    if not result['passed']:
        raise SystemExit(1)


if __name__=='__main__':
    main()
