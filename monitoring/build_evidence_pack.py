#!/usr/bin/env python3
"""Rebuild per-run evidence from recorded files; never starts a load or alters EC2."""
import collections
import csv
import datetime as dt
import hashlib
import html
import json
import math
import re
import shutil
import statistics
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'results/a2-ec2'
DEST = ROOT/'output/assignment2-evidence'


def read_rows(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def stat(values):
    values = [v for v in values if isinstance(v, (int, float)) and math.isfinite(v)]
    return {'sampleMean': statistics.mean(values), 'sampleMax': max(values), 'samples': len(values)} if values else None


def save_csv(path, rows):
    if not rows:
        return
    fields = list(dict.fromkeys(k for row in rows for k in row))
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def figsave(figure, path):
    figure.tight_layout()
    figure.savefig(path, dpi=125)
    plt.close(figure)


def client_metrics(text):
    def section(name):
        marker = '--- '+name+' ---'
        return text.split(marker, 1)[1].split('\n---', 1)[0] if marker in text else ''
    def number(pattern, value=text):
        match = re.search(pattern, value)
        return float(match.group(1)) if match else None
    total = section('Total ingest')
    latency = section('HTTP attempt latency')
    result = {k: number(pattern) for k, pattern in {
        'mainThroughput': r'Main throughput: ([\d.]+)',
        'endToEndThroughput': r'End-to-end throughput: ([\d.]+)',
        'drainSeconds': r'Pipeline drain and final WebSocket verification: ([\d.]+)',
        'attempts': r'Application request attempts: (\d+)',
        'retries': r'Application request retries: (\d+)',
        'programRuntimeSeconds': r'Total program runtime: ([\d.]+)',
        'webSocketUpdates': r'Total leaderboard updates: (\d+)'
    }.items()}
    for key, pattern in [('successful', r'Successful: (\d+)'), ('failed', r'Failed: (\d+)'),
                         ('ingestRuntimeSeconds', r'Runtime: ([\d.]+)'),
                         ('ingestThroughput', r'\nThroughput: ([\d.]+)')]:
        result[key] = number(pattern, total)
    for key in ['Mean', 'Median', 'p95', 'p99', 'Min', 'Max']:
        result['http'+key+'Ms'] = number(key+r': ([\d.]+)', latency)
    result['webSocketReconnections'] = sum(int(x) for x in re.findall(r'reconnections=(\d+)', section('WebSocket subscriptions')))
    for key in ['attempts','retries','successful','failed','webSocketUpdates','webSocketReconnections']:
        if result.get(key) is not None:result[key]=int(result[key])
    return result


def link(path):
    import os
    return html.escape(os.path.relpath(path, DEST))


def build(run_path):
    name = run_path.name
    out = DEST/name
    out.mkdir(exist_ok=True)
    config = json.loads((run_path/'run.json').read_text())
    text = (run_path/'client.log').read_text()
    shutil.copy2(run_path/'client.log', out/'client-output.txt')
    start = dt.datetime.fromisoformat(config['clientStartUTC']).timestamp()
    end = dt.datetime.fromisoformat(config['clientEndUTC']).timestamp()
    result = {'run': name, 'startUTC': config['clientStartUTC'], 'endUTC': config['clientEndUTC'],
              'events': config['events'], 'clientThreads': config['threads'],
              'clientExitCode': config.get('clientExitCode'), 'client': client_metrics(text),
              'system': {}, 'gaps': [], 'originalDirectory': str(run_path)}
    raw = {p.stem: read_rows(p) for p in (run_path/'monitor').glob('*.jsonl')}
    result['ingestCount'] = sum(k.startswith('ingest') for k in raw)
    result['provenance'] = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                            for p in [run_path/'run.json', run_path/'client.log', *sorted((run_path/'monitor').glob('*.jsonl'))]}
    result['notes'] = ['Statistics use the client execution window, including warmup and drain.',
                       'Queue means/rates are arithmetic sample means; peaks are sampled observations.',
                       'Generated charts are recorded-metric plots, not AWS or RabbitMQ console screenshots.',
                       'Network rates use physical ens*/eth*/enp* interfaces only to avoid Docker bridge double counting.',
                       'Consumer lag is a cumulative-sum difference across baseline/final samples; not a per-run percentile.',
                       'Request distribution uses ingest application counters, not ALB access logs or CloudWatch per-target metrics.']
    for role, rows in raw.items():
        errors = collections.Counter(r.get('appError') or r.get('hostError') for r in rows
                                     if start <= r.get('timestamp', 0) <= end and (r.get('appError') or r.get('hostError')))
        if errors:
            result['gaps'].append(role+' collection errors: '+json.dumps(dict(errors)))
    # Queue monitoring is independent of the slower consumer /metrics endpoint.
    queue = []
    for r in raw.get('rabbitmq', []):
        if not start <= r['timestamp'] <= end:
            continue
        row = {'seconds': r['timestamp']-start, 'timestamp': r['timestamp']}
        for key in ['ready', 'unacked', 'publishRate', 'deliverRate', 'ackRate']:
            row[key] = sum(q.get(key, 0) for q in r['queues']) if 'queues' in r else math.nan
        row['total'] = row['ready']+row['unacked']
        row['deadReady'] = r.get('deadReady', math.nan)
        queue.append(row)
    result['queue'] = {key: stat([r[key] for r in queue]) for key in ['ready','unacked','total','publishRate','deliverRate','ackRate','deadReady']}
    if not queue:
        result['gaps'].append('No RabbitMQ queue samples during client execution')
    save_csv(out/'queue-timeseries.csv', queue)
    figure, axes = plt.subplots(2, 1, figsize=(11, 7), sharex=True)
    for key in ['ready', 'unacked', 'total']:
        axes[0].plot([r['seconds'] for r in queue], [r[key] for r in queue], label=key)
    axes[0].axhline(1000, color='red', linestyle='--', label='target 1000')
    axes[0].set_ylabel('Messages / ten raw resort queues')
    for key in ['publishRate', 'deliverRate', 'ackRate']:
        axes[1].plot([r['seconds'] for r in queue], [r[key] for r in queue], label=key)
    axes[1].set_ylabel('Broker-reported messages/s'); axes[1].set_xlabel('Seconds since client launch (includes drain)')
    for ax in axes:
        ax.legend(); ax.grid(alpha=.2)
    figure.suptitle(name+'\nRecorded RabbitMQ API data; not a console screenshot')
    figsave(figure, out/'queue.png')
    figure, axes = plt.subplots(3, 2, figsize=(13, 10), sharex=True)
    role_colors={'consumer':'#1f77b4','ingest1':'#ff7f0e','ingest2':'#2ca02c','ingest3':'#d62728','ingest4':'#9467bd','rabbitmq':'#8c564b'}
    for role, rows in sorted(raw.items()):
        host = []
        for x, y in zip(rows, rows[1:]):
            if not start <= y['timestamp'] <= end or 'host' not in x or 'host' not in y:
                continue
            a, b = x['host'], y['host']; elapsed = y['timestamp']-x['timestamp']
            ticks = [v-u for u,v in zip(a['cpuTicks'], b['cpuTicks'])]; total = sum(ticks)
            if elapsed<=0 or total<=0 or min(ticks)<0:
                continue
            r = {'seconds':y['timestamp']-start, 'cpuPercent':100*(total-ticks[3]-ticks[4])/total,
                 'iowaitPercent':100*ticks[4]/total,
                 'memoryUsedMiB':(b['memoryKiB']['MemTotal']-b['memoryKiB']['MemAvailable'])/1024}
            interfaces = [k for k in b['network'] if k.startswith(('ens','eth','enp'))]
            for key in ['rx', 'tx']:
                r[key+'MiBPerSecond'] = sum(max(0, b['network'][k][key]-a['network'].get(k,b['network'][k])[key]) for k in interfaces)/(elapsed*1024**2) if interfaces else math.nan
            disks = a.get('diskCounters', {}).keys() & b.get('diskCounters', {}).keys()
            r['writeIOPS'] = sum(b['diskCounters'][k]['writes']-a['diskCounters'][k]['writes'] for k in disks)/elapsed if disks else math.nan
            host.append(r)
        result['system'][role] = {k:stat([r[k] for r in host]) for k in ['cpuPercent','iowaitPercent','memoryUsedMiB','rxMiBPerSecond','txMiBPerSecond','writeIOPS']}
        save_csv(out/(role+'-system.csv'), host)
        for ax, key in zip(axes.flat, ['cpuPercent','memoryUsedMiB','rxMiBPerSecond','txMiBPerSecond','iowaitPercent','writeIOPS']):
            ax.plot([r['seconds'] for r in host], [r[key] for r in host], label=role,color=role_colors.get(role))
            ax.set_ylabel(key); ax.grid(alpha=.2)
    for ax in axes[-1]: ax.set_xlabel('Seconds since client launch')
    axes[0,0].legend(fontsize=8)
    figure.suptitle(name+' — measured EC2 host resources')
    figsave(figure, out/'system.png')
    apps = [r['app'] for r in raw.get('consumer',[]) if isinstance(r.get('app'),dict)]
    if apps:
        a,b = apps[0],apps[-1]
        result['consumerConfiguration'] = {k:a.get(k) for k in ['configuredThreads','dynamicScaling','prefetch','storeBatchSize','storeBatchWaitMs','ackBatchSize']}
        n = b.get('consumerLagSamples',0)-a.get('consumerLagSamples',0)
        lag_reset=any(y.get('consumerLagSamples',0)<x.get('consumerLagSamples',0) for x,y in zip(apps,apps[1:]))
        if lag_reset or n<=0:result['gaps'].append('Consumer lag counters reset or did not advance; run-specific lag mean is unavailable')
        result['consumer'] = {'processedDelta':b['processed']-a['processed'], 'duplicateDelta':b['duplicates']-a['duplicates'],
                              'failedAttemptDelta':b.get('failedAttempts',0)-a.get('failedAttempts',0),
                              'finalReady':b['queueReady'],'finalInFlight':b['inFlight'],'finalOutbox':b['pendingOutbox'],
                              'runMeanLagMs':(b['consumerLagAvgMs']*b['consumerLagSamples']-a['consumerLagAvgMs']*a['consumerLagSamples'])/n if n>0 and not lag_reset else None}
    distribution = {}
    for role, rows in raw.items():
        if not role.startswith('ingest'): continue
        apps = [r['app'] for r in rows if isinstance(r.get('app'),dict)]
        if apps:
            a,b = apps[0],apps[-1]
            distribution[role] = {'requestAttemptDelta':b['requests']-a['requests'],
                                  'publishFailedDelta':b['publishesFailed']-a['publishesFailed'],
                                  'publishConfirmedDelta':b['publishesConfirmed']-a['publishesConfirmed'],
                                  'failureReasonDeltas':{k:v-a.get('publishFailureReasons',{}).get(k,0) for k,v in b.get('publishFailureReasons',{}).items()},
                                  'initialAvailableChannels':a.get('producerChannelsAvailable')}
    result['ingestDistribution'] = distribution
    figure, ax = plt.subplots(figsize=(8,4))
    names = sorted(distribution); values = [distribution[k]['requestAttemptDelta'] for k in names]
    bars = ax.bar(names, values)
    ax.bar_label(bars, fmt='%.0f'); ax.set_ylim(0, max(values or [1])*1.2 or 1)
    ax.set_ylabel('Application request-counter delta')
    ax.set_title(name+'\nApplication counters, not ALB console metrics', fontsize=10)
    figsave(figure, out/'distribution.png')
    snapshots = sorted((run_path/'results').glob('final-snapshots*.json'))
    result['webSocketEvidence'] = [str(p.relative_to(ROOT)) for p in snapshots]
    if not snapshots: result['gaps'].append('No saved final-snapshots JSON found; inspect client output for WebSocket evidence')
    result['consoleScreenshots'] = []
    if (run_path/'screenshot-context.json').exists():
        context = json.loads((run_path/'screenshot-context.json').read_text())
        result['consoleScreenshots'] = context.get('rabbitmqScreenshots', [])
        result['screenshotContext'] = context
        shutil.copytree(run_path/'screenshots', out/'screenshots', dirs_exist_ok=True)
    elif name.endswith('20261108T125240Z'):
        result['consoleScreenshots'] = ['rabbitmq-overview-first.png','rabbitmq-overview-after-exit.png']
    elif name.endswith('20261108T130828Z'):
        result['consoleScreenshots'] = ['rabbitmq-1m-stress-during-load.png','rabbitmq-1m-stress-drained.png']
    if not result['consoleScreenshots']:
        result['gaps'].append('No run-specific RabbitMQ console screenshot identified; generated plots do not replace it')
    (out/'summary.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    imgs = ''.join('<h2>'+title+'</h2><img width="100%" src="'+file+'">' for title,file in [('Queue depth and rates','queue.png'),('CPU, memory, network and disk','system.png'),('Per-ingest request distribution','distribution.png')])
    import os
    refs = [run_path/'run.json', *(run_path/'results').glob('final-snapshots*.json')]
    refhtml = ''.join('<li><a href="'+html.escape(os.path.relpath(p,out))+'">'+html.escape(p.name)+'</a></li>' for p in refs)
    console_files=result['consoleScreenshots']+result.get('screenshotContext',{}).get('albScreenshots',[])
    refhtml+=''.join('<li><a href="'+html.escape(p)+'">Original console: '+html.escape(Path(p).name)+'</a></li>' for p in console_files if (out/p).exists())
    (out/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>'+name+'</title><style>body{font:16px system-ui;max-width:1100px;margin:30px auto}pre{white-space:pre-wrap}img{border:1px solid #ddd}</style><a href="../index.html">All runs</a><h1>'+name+'</h1><a href="client-output.txt">Complete original client output</a> | <a href="summary.json">Metrics and limitations</a><ul>'+refhtml+'</ul><h2>Evidence gaps</h2><pre>'+html.escape('\n'.join(result['gaps']) or 'None identified')+'</pre>'+imgs+'<h2>Full recorded client output</h2><pre>'+html.escape(text)+'</pre>')
    return result


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    results = []
    for run in sorted(SOURCE.glob('instrumented-*')):
        config = json.loads((run/'run.json').read_text())
        if not config.get('clientEndUTC') or not (run/'client.log').exists(): continue
        results.append(build(run))
        print('Built '+run.name, flush=True)
    (DEST/'all-runs.json').write_text(json.dumps(results,indent=2)+'\n')
    flat=[]
    for r in results:
        q=r['queue']; c=r.get('consumer',{})
        flat.append({'run':r['run'],'ingests':r['ingestCount'],'events':r['events'],'clientThreads':r['clientThreads'],
                     'consumerThreads':r.get('consumerConfiguration',{}).get('configuredThreads'),
                     **r['client'],'readyPeak':(q.get('ready') or {}).get('sampleMax'),
                     'readySampleMean':(q.get('ready') or {}).get('sampleMean'),
                     'totalQueuePeak':(q.get('total') or {}).get('sampleMax'),
                     'publishRateSampleMean':(q.get('publishRate') or {}).get('sampleMean'),
                     'deliveryRateSampleMean':(q.get('deliverRate') or {}).get('sampleMean'),
                     'meanLagMs':c.get('runMeanLagMs'),'processedDelta':c.get('processedDelta'),
                     'gapCount':len(r['gaps'])})
    save_csv(DEST/'all-runs.csv',flat)
    rows=''
    for r in flat:
        cells=[f'<a href="{r["run"]}/index.html">{r["run"]}</a>']+[html.escape(str(r.get(k))) for k in ['ingests','events','clientThreads','consumerThreads','endToEndThroughput','readyPeak','meanLagMs','retries','gapCount']]
        rows+='<tr>'+''.join('<td>'+v+'</td>' for v in cells)+'</tr>'
    header=['Run','Ingests','Events','Client threads','Consumer threads','E2E /s','Ready peak','Mean lag ms','Retries','Gaps']
    (DEST/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Assignment 2 evidence</title><style>body{font:14px system-ui;margin:25px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:7px}th{background:#eee}p{max-width:1100px}</style><h1>Assignment 2: recorded experiment evidence</h1><p>Every page contains the full original client output, measured queue/resource plots, distribution counters, provenance and collection gaps. No loads were rerun. Plots are NOT console screenshots. Comparisons mix tuning configurations; inspect each summary before making causal claims. Consumer lag is a run-specific mean, not a latency percentile.</p><a href="all-runs.csv">All-run CSV</a> | <a href="all-runs.json">Full summaries</a><table><tr>'+''.join('<th>'+x+'</th>' for x in header)+'</tr>'+rows+'</table>')
    print('Evidence index: '+str(DEST/'index.html'),flush=True)


if __name__=='__main__':
    main()
