#!/usr/bin/env python3
"""运行交替顺序的远程对照实验，保留日志，计算 main 吞吐量及 GC 暂停。
显式执行才发负载；不自动启动/停止 EC2，也不修改服务器。
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import urllib.request

MODULE = Path(__file__).resolve().parents[1]


def parse_run(log):
    """只比较 main 阶段；缺失字段或失败运行必须显式报错。"""
    main = log.split('--- Main ---', 1)[1].split('--- Total ingest ---', 1)[0]
    total = log.split('--- Total ingest ---', 1)[1].split('--- HTTP transport:', 1)[0]
    return {
        'main_throughput': float(re.search(r'Main throughput: ([\d.]+)', main)[1]),
        'main_seconds': float(re.search(r'Runtime: ([\d.]+)', main)[1]),
        'successful': int(re.search(r'Successful: (\d+)', total)[1]),
        'failed': int(re.search(r'Failed: (\d+)', total)[1]),
        'retries': int(re.search(r'Application request retries: (\d+)', log)[1]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', required=True, help='当前 EC2 HTTP 地址，包含 /ski-servlet')
    parser.add_argument('--java', required=True, help='Java 11 的 bin/java 完整路径')
    parser.add_argument('--events', type=int, default=500000)
    parser.add_argument('--threads', type=int, default=128)
    parser.add_argument('--warmup', type=int, default=1000)
    parser.add_argument('--resorts', type=int, default=10)
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--seed', type=int, default=6650)
    args = parser.parse_args()
    if args.rounds < 1 or args.threads < 1 or args.resorts < 1 or args.warmup < 1 or args.events <= 32*args.warmup:
        parser.error('要求正参数并且 events > 32 * warmup，确保存在 main 阶段')
    version = subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True)
    if not re.search(r'version "11[.]', version.stderr + version.stdout):
        parser.error('对照实验要求显式使用 Java 11')
    cp_file = MODULE / 'target/dependency-classpath.txt'
    if not cp_file.exists():
        parser.error('先按 METRICS_EXPERIMENT.md 构建并导出 dependency-classpath.txt')
    classpath = str(MODULE / 'target/classes') + os.pathsep + cp_file.read_text().strip()
    run_id = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    out = MODULE.parent / 'results/experiments' / run_id
    out.mkdir(parents=True, exist_ok=False)
    # 保存真实公网 health 响应作为文本证据；截图仍需展示实际终端/控制台。
    url = args.base_url.rstrip('/') + '/health'
    with urllib.request.urlopen(url, timeout=10) as response:
        body = response.read().decode()
        (out / 'health.txt').write_text(f'UTC: {run_id}\nGET {url}\nHTTP {response.status}\n{response.headers}\n{body}\n')
        if response.status != 200 or json.loads(body).get('status') != 'UP':
            raise RuntimeError('Health check did not report UP')
    metadata = vars(args) | {'java_version': version.stderr + version.stdout,
                             'utc_start': run_id}
    # 记录源码版本及工作树状态，避免把未提交代码误认为某个 commit 的实测。
    for label, command in [('commit', ['git', 'rev-parse', 'HEAD']),
                           ('working_tree', ['git', 'status', '--short'])]:
        metadata[label] = subprocess.check_output(command, cwd=MODULE, text=True).strip()
    (out / 'configuration.json').write_text(json.dumps(metadata, indent=2))
    records = []
    modes = ['shared', 'buffered', 'off']
    # 每轮轮换顺序：ABC、BCA、CAB，减少固定顺序与环境变化的混淆。
    for round_index in range(args.rounds):
        order = modes[round_index % 3:] + modes[:round_index % 3]
        for mode in order:
            stem = f'round-{round_index+1}-{mode}'
            gc_path = out / (stem + '-gc.log')
            command = [args.java, '-Duser.language=en', '-Duser.country=US', '-Xms512m', '-Xmx512m', f'-Xlog:gc:file={gc_path}',
                       '-cp', classpath, 'LoadClient', args.base_url.rstrip('/'),
                       str(args.events), str(args.threads), str(args.warmup),
                       str(args.resorts), mode, str(args.seed)]
            print(f'Running {stem}; log: {out / (stem + ".txt")}', flush=True)
            with (out / (stem + '.txt')).open('w') as stream:
                result = subprocess.run(command, cwd=MODULE, stdout=stream,
                                        stderr=subprocess.STDOUT, timeout=7200)
            if result.returncode:
                raise RuntimeError(f'{stem} failed; inspect its log (no performance conclusion generated)')
            record = parse_run((out / (stem + '.txt')).read_text())
            record.update(mode=mode, round=round_index+1)
            # GC 暂停覆盖整个 JVM 运行，含初始化/导出；不能当成 main 阶段因果分解。
            gc = gc_path.read_text()
            record['whole_program_gc_pause_ms'] = sum(float(x) for x in
                re.findall(r'Pause[^\n]*? ([\d.]+)ms\s*$', gc, re.M))
            records.append(record)
            (out / 'measurements.json').write_text(json.dumps(records, indent=2))
            if record['failed'] or record['successful'] != args.events:
                raise RuntimeError(f'{stem} has failed/incomplete events; inspect evidence before comparing')
    lines = ['# Metrics experiment', '', '| Mode | Main throughput median (min–max) | Median whole-program GC pause ms |',
             '|---|---:|---:|']
    medians = {}
    for mode in modes:
        selected = [r for r in records if r['mode'] == mode]
        rates = [r['main_throughput'] for r in selected]
        medians[mode] = statistics.median(rates)
        lines.append(f'| {mode} | {medians[mode]:.2f} ({min(rates):.2f}–{max(rates):.2f}) | '
                     f'{statistics.median(r["whole_program_gc_pause_ms"] for r in selected):.3f} |')
    improvement = (medians['buffered']/medians['shared']-1)*100
    overhead = (1-medians['buffered']/medians['off'])*100
    lines += ['', f'Buffered versus shared median throughput change: {improvement:+.2f}%.',
              f'Buffered throughput shortfall versus off: {overhead:+.2f}%.', '',
              'These are observed differences, not isolated CPU-cost percentages. Shared → buffered changes both storage and timestamp representation.',
              'GC pauses cover the entire JVM run, not just main. Network/server drift and retries can still confound results.',
              'off is a diagnostic only and cannot replace the full Part 2 submission.',
              'Check configuration.json, raw logs, retry counts, and min–max variation before drawing conclusions.']
    (out / 'SUMMARY.md').write_text('\n'.join(lines)+'\n')
    print(f'Results: {out}')


if __name__ == '__main__':
    main()
