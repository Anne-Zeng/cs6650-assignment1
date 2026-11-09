#!/usr/bin/env python3
"""Render recorded queue data only; never invents missing samples."""
import argparse, csv, datetime
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('csv_file')
    parser.add_argument('--output')
    args = parser.parse_args()
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
    except ImportError:
        parser.error('Install matplotlib in a virtualenv: python3 -m pip install matplotlib')
    with open(args.csv_file, newline='') as source: rows = list(csv.DictReader(source))
    valid = [row for row in rows if not row.get('error') and row.get('ready')]
    if not valid: parser.error('No successful queue samples')
    start = float(rows[0]['timestamp'])
    # NaN leaves visible gaps for failed API samples instead of implying depth0.
    values = lambda key: [float(row[key]) if not row.get('error') and row.get(key) else float('nan') for row in rows]
    seconds = [float(row['timestamp']) - start for row in rows]
    figure, (depth, rates) = plt.subplots(2, 1, figsize=(10, 6), sharex=True)
    depth.plot(seconds, values('ready'), label='Ready')
    depth.plot(seconds, values('unacked'), label='Unacknowledged')
    depth.axhline(1000, color='#b91c1c', linestyle='--', label='Operating target1000')
    depth.set_ylabel('Messages'); depth.legend(); depth.grid(alpha=.2)
    for key, label in [('publishRate', 'Publish'), ('deliverRate', 'Delivery'), ('ackRate', 'Ack')]:
        rates.plot(seconds, values(key), label=label)
    rates.set_ylabel('Messages / second'); rates.set_xlabel('Seconds since first sample')
    rates.legend(); rates.grid(alpha=.2)
    figure.suptitle('RabbitMQ resort queues — ' + datetime.datetime.fromtimestamp(start, datetime.timezone.utc).isoformat())
    figure.tight_layout()
    output = Path(args.output) if args.output else Path(args.csv_file).with_suffix('.png')
    output.parent.mkdir(parents=True, exist_ok=True)
    figure.savefig(output, dpi=160)
    print(output)


if __name__ == '__main__': main()
