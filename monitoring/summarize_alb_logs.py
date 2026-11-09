#!/usr/bin/env python3
"""Count recorded ALB access-log requests by actual target address, without AWS dependencies."""
import argparse, collections, gzip, json, shlex
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('files', nargs='+')
    parser.add_argument('--output', default='results/alb-access-distribution.json')
    args = parser.parse_args()
    targets, statuses, ignored, invalid = collections.Counter(), collections.Counter(), 0, 0
    for filename in args.files:
        opener = gzip.open if filename.endswith('.gz') else open
        with opener(filename, 'rt') as stream:
            for line in stream:
                try:
                    fields = shlex.split(line)
                    # ALB fields: type timestamp elb client:port target:port ... elb_status target_status ... request.
                    if len(fields) < 13: raise ValueError('Too few ALB fields')
                    if '/skiers/' not in fields[12]: ignored += 1; continue
                    targets[fields[4]] += 1
                    statuses[fields[8] + '/' + fields[9]] += 1
                except ValueError: invalid += 1
    result = {'targetRequestCounts': dict(targets), 'elbAndTargetStatuses': dict(statuses),
              'ignoredNonSwipeRequests': ignored, 'invalidLines': invalid}
    output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
    if invalid: raise SystemExit(1)


if __name__ == '__main__': main()
