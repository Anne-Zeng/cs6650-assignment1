#!/usr/bin/env python3
"""Deterministic HTTP + WebSocket business check, using only Python standard library.
Use an unused day and the same official lift table as the ingest servers.
"""
import argparse, base64, collections, csv, hashlib, http.client, json, os, socket, ssl, struct
import threading, time, uuid
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def http_json(url, body=None, headers=None):
    request = Request(url, data=None if body is None else json.dumps(body).encode(),
                      headers={'Content-Type': 'application/json', **(headers or {})})
    with urlopen(request, timeout=10) as response:
        return response.status, json.load(response), dict(response.headers)


class WebSocket:
    """Small RFC6455 test client: validates upgrade, masking, ping/pong and text fragmentation."""
    def __init__(self, url):
        uri = urlparse(url)
        if uri.scheme not in ('ws', 'wss'): raise ValueError('Expected ws:// or wss://')
        port = uri.port or (443 if uri.scheme == 'wss' else 80)
        raw = socket.create_connection((uri.hostname, port), timeout=10)
        self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=uri.hostname) if uri.scheme == 'wss' else raw
        self.sock.settimeout(10)
        self.lock = threading.Condition(); self.send_lock = threading.Lock()
        self.snapshots = []; self.errors = []; self.closed = False
        key = base64.b64encode(os.urandom(16)).decode()
        path = uri.path or '/'
        if uri.query: path += '?' + uri.query
        request = (f'GET {path} HTTP/1.1\r\nHost: {uri.hostname}:{port}\r\nUpgrade: websocket\r\n'
                   f'Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n')
        self.sock.sendall(request.encode())
        self.buffer = b''
        while b'\r\n\r\n' not in self.buffer:
            chunk = self.sock.recv(4096)
            if not chunk: raise EOFError('Server closed during WebSocket handshake')
            self.buffer += chunk
            if len(self.buffer) > 65536: raise ValueError('Oversized upgrade headers')
        headers, self.buffer = self.buffer.split(b'\r\n\r\n', 1)
        lines = headers.decode().split('\r\n')
        if ' 101 ' not in lines[0]: raise ValueError('WebSocket handshake failed: ' + lines[0])
        parsed = dict(line.split(':', 1) for line in lines[1:] if ':' in line)
        parsed = {k.lower(): v.strip() for k, v in parsed.items()}
        expected = base64.b64encode(hashlib.sha1((key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
        if parsed.get('sec-websocket-accept') != expected: raise ValueError('Bad WebSocket accept')
        self.sock.settimeout(1)
        self.thread = threading.Thread(target=self._read, daemon=True); self.thread.start()

    def _bytes(self, count):
        while len(self.buffer) < count:
            try: chunk = self.sock.recv(max(4096, count - len(self.buffer)))
            except socket.timeout:
                if self.closed: raise EOFError()
                continue
            if not chunk: raise EOFError()
            self.buffer += chunk
        data, self.buffer = self.buffer[:count], self.buffer[count:]
        return data

    def _send(self, opcode, data=b''):
        mask = os.urandom(4)
        size = len(data)
        prefix = bytes([0x80 | opcode])
        if size < 126: prefix += bytes([0x80 | size])
        elif size <= 65535: prefix += bytes([0x80 | 126]) + struct.pack('!H', size)
        else: prefix += bytes([0x80 | 127]) + struct.pack('!Q', size)
        with self.send_lock:
            self.sock.sendall(prefix + mask + bytes(value ^ mask[i % 4] for i, value in enumerate(data)))

    def _read(self):
        fragments = bytearray()
        try:
            while not self.closed:
                first, second = self._bytes(2)
                opcode, final = first & 15, bool(first & 0x80)
                if second & 0x80: raise ValueError('Server frames must not be masked')
                size = second & 127
                if size == 126: size = struct.unpack('!H', self._bytes(2))[0]
                elif size == 127: size = struct.unpack('!Q', self._bytes(8))[0]
                if size > 8 * 1024 * 1024: raise ValueError('Oversized frame')
                data = self._bytes(size)
                if opcode == 8: raise EOFError('Server closed socket')
                if opcode == 9: self._send(10, data); continue
                if opcode == 10: continue
                if opcode not in (0, 1): raise ValueError('Unexpected frame type')
                fragments.extend(data)
                if final:
                    snapshot = json.loads(fragments.decode()); fragments.clear()
                    with self.lock:
                        self.snapshots.append(snapshot); self.lock.notify_all()
        except Exception as error:
            if not self.closed:
                with self.lock: self.errors.append(str(error)); self.lock.notify_all()

    def wait(self, predicate, timeout=20):
        deadline = time.monotonic() + timeout
        with self.lock:
            while time.monotonic() < deadline:
                for snapshot in reversed(self.snapshots):
                    if predicate(snapshot): return snapshot
                if self.errors: raise AssertionError('WebSocket error: ' + '; '.join(self.errors))
                self.lock.wait(min(0.25, max(0, deadline - time.monotonic())))
        raise AssertionError('Timed out waiting for expected WebSocket snapshot')

    def close(self):
        self.closed = True
        try: self._send(8)
        except OSError: pass
        self.sock.close()


def load_lifts(path):
    text = Path(path).read_text()
    if Path(path).suffix.lower() == '.json':
        data = json.loads(text)
        if isinstance(data, dict) and 'lifts' in data: data = data['lifts']
        if isinstance(data, dict): return {int(key): int(value) for key, value in data.items()}
        return {int(row.get('liftID', row.get('liftId'))): int(row['vertical']) for row in data}
    return {int(row['liftID']): int(row['vertical']) for row in csv.DictReader(
        line for line in text.splitlines() if not line.startswith('#'))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ingest-url', action='append', required=True,
                        help='Repeat for two direct nodes to prove connection locality')
    parser.add_argument('--consumer-url', default='http://localhost:8081')
    parser.add_argument('--lift-file')
    parser.add_argument('--demo-lifts', action='store_true', help='Explicitly use local synthetic fixture')
    parser.add_argument('--day', type=int, default=366)
    parser.add_argument('--top-n', type=int, default=20)
    parser.add_argument('--output', default='results/pipeline-verification.json')
    args = parser.parse_args()
    if not 1 <= args.day <= 366: parser.error('day must be1..366')
    if not args.lift_file and not args.demo_lifts: parser.error('Supply official --lift-file or explicit --demo-lifts')
    fixture = Path(__file__).resolve().parents[1] / 'common/src/main/resources/demo-lift-vertical.csv'
    lifts = load_lifts(args.lift_file or fixture)
    if set(lifts) != set(range(1, 41)) or any(not 60 <= value <= 400 for value in lifts.values()):
        parser.error('Lift table must cover1..40 with vertical60..400')
    urls = [url.rstrip('/') for url in args.ingest_url]
    consumer = args.consumer_url.rstrip('/')
    snapshots = {}
    for resort in (1, 2):
        _, snapshot, _ = http_json(f'{consumer}/leaderboards/{resort}?dayID={args.day}')
        if snapshot.get('processed', 0) != 0 or snapshot.get('top'):
            parser.error(f'Day {args.day} resort {resort} is not empty. Choose an unused test day.')
    sockets = []
    try:
        for url in urls:
            ws = url.replace('https://', 'wss://', 1).replace('http://', 'ws://', 1)
            for resort in (1, 2):
                client = WebSocket(f'{ws}/leaderboard/{resort}?dayID={args.day}')
                client.wait(lambda snapshot: snapshot.get('version') == 0 and snapshot.get('top') == [])
                sockets.append((url, resort, client))
        expected = {1: collections.defaultdict(int), 2: collections.defaultdict(int)}
        events = []
        # More skiers than Top-N, ties, repeated skier increments, and all40 lift lookups.
        for i in range(100):
            resort, skier, lift = i % 2 + 1, i % 31 + 1, i % 40 + 1
            event = {'eventId': str(uuid.uuid4()), 'resort': resort, 'skier': skier,
                     'time': i % 360 + 1, 'lift': lift}
            events.append(event)
            expected[resort][str(skier)] += lifts[lift]
        server_counts = collections.Counter()
        for index, event in enumerate(events):
            url = urls[index % len(urls)]
            path = f"/skiers/{event['resort']}/seasons/2026/days/{args.day}/skiers/{event['skier']}"
            body = {'time': event['time'], 'liftID': event['lift']}
            code, response, headers = http_json(url + path, body, {'Idempotency-Key': event['eventId']})
            if code != 201: raise AssertionError('Ingest did not accept valid event')
            server_counts[headers.get('X-Server-Id', headers.get('X-Server-ID', url))] += 1
            # Replay on another ingest node: timestamp/serverId changes must not change business identity.
            if index % 5 == 0:
                code, _, _ = http_json(urls[(index + 1) % len(urls)] + path, body,
                                       {'Idempotency-Key': event['eventId']})
                if code != 201: raise AssertionError('Identical replay should remain accepted')
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            for resort in (1, 2):
                _, snapshot, _ = http_json(f'{consumer}/leaderboards/{resort}?dayID={args.day}')
                snapshots[resort] = snapshot
            if all(snapshot.get('processed') == 50 for snapshot in snapshots.values()): break
            time.sleep(0.05)
        for resort in (1, 2):
            snapshot = snapshots[resort]
            if snapshot.get('processed') != 50: raise AssertionError('Unique processed count differs from input')
            # Server numeric skier ID tie-break. Compare normalized business fields only.
            ordered = sorted(expected[resort].items(), key=lambda pair: (-pair[1], int(pair[0])))[:args.top_n]
            target = [{'rank': rank, 'skierID': skier, 'vertical': vertical}
                      for rank, (skier, vertical) in enumerate(ordered, 1)]
            actual = [{'rank': entry['rank'], 'skierID': str(entry['skierID']), 'vertical': entry['vertical']}
                      for entry in snapshot['top']]
            if actual != target: raise AssertionError(f'Wrong Top-N for resort {resort}: {actual} != {target}')
            for url, subscribed_resort, client in sockets:
                if subscribed_resort != resort: continue
                observed = client.wait(lambda message: message.get('version', -1) >= snapshot['version'])
                if observed['top'] != snapshot['top']: raise AssertionError('WebSocket does not match real aggregate')
        result = {'passed': True, 'demoLifts': args.demo_lifts and not args.lift_file,
                  'dayID': args.day, 'uniqueEvents': len(events), 'duplicateRequests': 20,
                  'ingestUrls': urls, 'serverRequestCounts': dict(server_counts), 'finalSnapshots': snapshots,
                  'note': 'Functional correctness evidence only, not EC2 performance evidence.'}
        output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2)); print(json.dumps(result, indent=2))
    finally:
        for _, _, client in sockets: client.close()


if __name__ == '__main__': main()
