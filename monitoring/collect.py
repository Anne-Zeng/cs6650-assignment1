#!/usr/bin/env python3
"""Read-only RabbitMQ/app monitoring. Errors stay errors, never become zero depth."""
import argparse, base64, csv, json, os, statistics, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.parse import quote


def get_json(url, auth=None):
    request = Request(url)
    if auth:
        request.add_header('Authorization', 'Basic ' + base64.b64encode(auth.encode()).decode())
    with urlopen(request, timeout=5) as response:
        return json.load(response)


def host_sample():
    """Host raw counters: derive rates from consecutive samples; Linux EC2 only."""
    result = {}
    try:
        result['cpuTicks'] = [int(x) for x in Path('/proc/stat').read_text().splitlines()[0].split()[1:]]
        result['memoryKiB'] = {line.split(':')[0]: int(line.split()[1])
                               for line in Path('/proc/meminfo').read_text().splitlines()
                               if line.startswith(('MemTotal:', 'MemAvailable:', 'SwapTotal:', 'SwapFree:'))}
        result['network'] = {line.split(':')[0].strip(): {'rxBytes': int(line.split(':')[1].split()[0]),
                                                           'txBytes': int(line.split(':')[1].split()[8])}
                             for line in Path('/proc/net/dev').read_text().splitlines()[2:] if ':' in line}
    except (OSError, ValueError, IndexError) as error:
        result['unavailable'] = str(error)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rabbit-url', default='http://localhost:15672')
    parser.add_argument('--consumer-url', default='http://localhost:8081')
    parser.add_argument('--ingest-url', action='append', default=[])
    parser.add_argument('--vhost', default=os.getenv('RABBITMQ_VHOST', '/'))
    parser.add_argument('--interval', type=float, default=1)
    parser.add_argument('--duration', type=float, default=300)
    parser.add_argument('--output', default='results/a2-monitoring')
    args = parser.parse_args()
    if args.interval <= 0 or args.duration <= 0:
        parser.error('interval and duration must be positive')
    user, password = os.getenv('RABBITMQ_USER', 'skitrak'), os.environ.get('RABBITMQ_PASSWORD')
    if not password:
        parser.error('Set RABBITMQ_PASSWORD; credentials are not accepted as command-line arguments')
    prefix = Path(args.output)
    prefix.parent.mkdir(parents=True, exist_ok=True)
    depths, rates, errors = [], [], 0
    deadline = time.monotonic() + args.duration
    with prefix.with_suffix('.jsonl').open('w') as raw, prefix.with_suffix('.csv').open('w', newline='') as flat:
        writer = csv.writer(flat)
        writer.writerow(['timestamp', 'ready', 'unacked', 'publishRate', 'deliverRate', 'ackRate', 'error'])
        while time.monotonic() < deadline:
            started = time.monotonic()
            sample = {'timestamp': time.time(), 'host': host_sample()}
            error = ''
            try:
                queues = get_json(args.rabbit_url.rstrip('/') + '/api/queues/' + quote(args.vhost, safe=''),
                                  user + ':' + password)
                sample['queues'] = [{'name': q['name'], 'ready': q.get('messages_ready', 0),
                                     'unacked': q.get('messages_unacknowledged', 0),
                                     'publishRate': q.get('message_stats', {}).get('publish_details', {}).get('rate', 0),
                                     'deliverRate': q.get('message_stats', {}).get('deliver_get_details', {}).get('rate', 0),
                                     'ackRate': q.get('message_stats', {}).get('ack_details', {}).get('rate', 0)}
                                    for q in queues if q['name'] in {'resort.' + str(i) for i in range(1, 11)}]
                sample['deadQueues'] = [{'name': q['name'], 'ready': q.get('messages_ready', 0)}
                                        for q in queues if q['name'].endswith('.dead')]
                totals = {key: sum(q[key] for q in sample['queues'])
                          for key in ('ready', 'unacked', 'publishRate', 'deliverRate', 'ackRate')}
                if len(sample['queues']) != 10:
                    raise ValueError('Expected 10 resort queues, saw ' + str(len(sample['queues'])))
                depths.append(totals['ready'])
                rates.append(totals['ackRate'])
            except Exception as failure:
                error = str(failure)
                totals = {key: None for key in ('ready', 'unacked', 'publishRate', 'deliverRate', 'ackRate')}
                sample['rabbitError'] = error
                errors += 1
            for label, url in [('consumer', args.consumer_url)] + [('ingest' + str(i), url) for i, url in enumerate(args.ingest_url)]:
                try:
                    sample[label] = get_json(url.rstrip('/') + '/metrics')
                except Exception as failure:
                    sample[label] = {'error': str(failure)}
            raw.write(json.dumps(sample) + '\n'); raw.flush()
            writer.writerow([sample['timestamp']] + list(totals.values()) + [error]); flat.flush()
            time.sleep(max(0, args.interval - (time.monotonic() - started)))
    summary = {'samples': len(depths), 'rabbitErrors': errors, 'peakReady': max(depths) if depths else None,
               'averageReady': statistics.mean(depths) if depths else None,
               'averageAckRate': statistics.mean(rates) if rates else None,
               'queueDepthTargetMet': bool(depths) and max(depths) < 1000 and errors == 0,
               'note': 'Depth alone does not prove no loss or lag <100ms. Preserve raw metrics and business reconciliation.'}
    prefix.with_name(prefix.name + '-summary.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
