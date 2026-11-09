#!/usr/bin/env python3
"""Collect target distribution and verify ALB cookie stickiness; never mutates infrastructure."""
import argparse, collections, http.cookiejar, json, time
from pathlib import Path
from urllib.request import build_opener, HTTPCookieProcessor, Request


def request(opener, url):
    with opener.open(Request(url.rstrip('/') + '/health', headers={'Connection': 'close'}), timeout=10) as response:
        data = json.load(response)
        server = response.headers.get('X-Server-Id') or data.get('serverId')
        if not server:
            raise ValueError('Health response must identify X-Server-Id/serverId')
        return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base_url')
    parser.add_argument('--requests', type=int, default=100)
    parser.add_argument('--sticky-requests', type=int, default=10)
    parser.add_argument('--expected-targets', type=int)
    parser.add_argument('--cookie-file', default='results/alb-cookies.txt')
    parser.add_argument('--expect-target-removed')
    parser.add_argument('--output', default='results/alb-verification.json')
    args = parser.parse_args()
    if args.requests < 1 or args.sticky_requests < 2:
        parser.error('requests must be positive, sticky-requests >=2')
    distribution = collections.Counter(request(build_opener(), args.base_url) for _ in range(args.requests))
    cookie_path = Path(args.cookie_file); cookie_path.parent.mkdir(parents=True, exist_ok=True)
    jar = http.cookiejar.MozillaCookieJar(str(cookie_path))
    if cookie_path.exists(): jar.load(ignore_discard=True, ignore_expires=False)
    sticky = build_opener(HTTPCookieProcessor(jar))
    targets = [request(sticky, args.base_url) for _ in range(args.sticky_requests)]
    jar.save(ignore_discard=True, ignore_expires=False)
    cookies = [cookie.name for cookie in jar]
    result = {'timestamp': time.time(), 'distribution': dict(distribution), 'stickyTargets': targets,
              'cookieNames': cookies, 'stickyPassed': len(set(targets)) == 1 and 'AWSALB' in cookies,
              'distributionTargetsPassed': args.expected_targets is None or len(distribution) == args.expected_targets,
              'failoverTested': args.expect_target_removed is not None,
              'failoverPassed': None if args.expect_target_removed is None else args.expect_target_removed not in targets}
    path = Path(args.output); path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
    if not result['stickyPassed'] or not result['distributionTargetsPassed'] or result['failoverPassed'] is False:
        raise SystemExit(1)


if __name__ == '__main__': main()
