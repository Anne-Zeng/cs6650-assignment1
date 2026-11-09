#!/usr/bin/env python3
"""Run required client sweeps against verified deployed consumer configuration."""
import argparse, datetime, json, subprocess
from pathlib import Path
from urllib.request import urlopen


def metrics(url):
    with urlopen(url.rstrip('/') + '/metrics', timeout=10) as response: return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--consumer-url', required=True)
    parser.add_argument('--instances', type=int, choices=[1, 2, 4], required=True)
    parser.add_argument('--consumer-threads', type=int, choices=[5, 10, 15, 20], required=True,
                        help='Verified against the deployed /metrics configuredThreads')
    parser.add_argument('--client-threads', type=int, nargs='+', default=[64, 128, 256, 512])
    parser.add_argument('--events', type=int, help='Default500000; use1000000 for required4-instance stress run')
    parser.add_argument('--output', default='results/a2-benchmarks')
    args = parser.parse_args()
    events = args.events or 500000
    if events < 32000: parser.error('events must cover32x1000 warmup')
    root = Path(__file__).resolve().parents[1]
    jar = root / 'client-v2/target/client-v2.jar'
    if not jar.is_file(): parser.error('Build first using ./deployment/build.sh')
    actual = metrics(args.consumer_url)
    if actual.get('configuredThreads') != args.consumer_threads:
        parser.error('Deployed configuredThreads=' + str(actual.get('configuredThreads'))
                     + ' differs from --consumer-threads=' + str(args.consumer_threads))
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
    output = root / args.output / stamp
    output.mkdir(parents=True)
    metadata = vars(args).copy(); metadata['timestampUTC'] = stamp; metadata['consumerBefore'] = actual
    (output / 'configuration.json').write_text(json.dumps(metadata, indent=2))
    failed = False
    for threads in args.client_threads:
        before = metrics(args.consumer_url)
        if before.get('configuredThreads') != args.consumer_threads:
            raise SystemExit('Consumer configuration changed during sweep; aborting mislabeled run')
        command = ['java', '-jar', str(jar), args.base_url, str(events), str(threads), '1000', '10',
                   'buffered', '6650', args.consumer_url, '180']
        name = f'instances{args.instances}-consumer{args.consumer_threads}-client{threads}-events{events}'
        run = output / name; run.mkdir()
        (run / 'consumer-before.json').write_text(json.dumps(before, indent=2))
        with (run / 'client.log').open('w') as log:
            result = subprocess.run(command, cwd=run, stdout=log, stderr=subprocess.STDOUT, check=False)
        # Each run owns cwd/results, so CSVs and final-snapshot evidence remain paired with its log.
        try: after = metrics(args.consumer_url)
        except Exception as error: after = {'error': str(error)}
        (run / 'consumer-after.json').write_text(json.dumps(after, indent=2))
        (run / 'result.json').write_text(json.dumps({'exitCode': result.returncode, 'command': command}, indent=2))
        print(name + ': exit=' + str(result.returncode) + ', evidence=' + str(run), flush=True)
        failed |= result.returncode != 0
    if failed: raise SystemExit(1)


if __name__ == '__main__': main()
