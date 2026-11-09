#!/usr/bin/env python3
"""Build a NEW report from this fresh session only; old reports are never read."""
import argparse
import csv
import datetime
import html
import json
import os
import subprocess
import sys
from pathlib import Path
import build_evidence_pack as evidence

ROOT=Path(__file__).resolve().parents[1]


def value(v, digits=2):
    return 'Not measured' if v is None else f'{v:,.{digits}f}'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pdf',action='store_true')
    args=parser.parse_args()
    session=Path(json.loads((ROOT/'results/a2-fresh/active.json').read_text())['root'])
    plan=json.loads((session/'session.json').read_text())
    out=ROOT/'output'/('assignment2-fresh-'+session.name)
    out.mkdir(parents=True,exist_ok=True)
    evidence.DEST=out/'evidence';evidence.DEST.mkdir(exist_ok=True)
    summaries=[]
    failed=[]
    for path in sorted(session.glob('phase-*/*/run.json')):
        config=json.loads(path.read_text())
        if config.get('clientExitCode')!=0 or not config.get('evidenceCollectorsPassed'):
            if config.get('failure') or config.get('clientExitCode') not in (None,0):failed.append({'directory':str(path.parent.relative_to(ROOT)),**config})
            continue
        summary=evidence.build(path.parent)
        summary['category']=config.get('category','scaling')
        summaries.append(summary)
    summaries.sort(key=lambda r:r['startUTC'])
    expected={(r['ingests'],r['events'],r['clientThreads'],r['consumerThreads'],r.get('category','scaling')) for r in plan['plannedRuns']}
    observed={(r['ingestCount'],r['events'],r['clientThreads'],r['consumerConfiguration']['configuredThreads'],r['category']) for r in summaries}
    missing=sorted(expected-observed)
    (out/'matrix-coverage.json').write_text(json.dumps({'missing':missing,'observedUniqueCases':len(expected & observed),'expectedCases':len(expected)},indent=2)+'\n')
    (out/'all-fresh-runs.json').write_text(json.dumps(summaries,indent=2)+'\n')
    links=''.join('<li><a href="'+html.escape(r['run'])+'/index.html">'+html.escape(r['run'])+'</a></li>' for r in summaries)
    (evidence.DEST/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Fresh benchmark evidence</title><style>body{font:16px system-ui;margin:30px}li{margin:10px}</style><h1>Fresh-session benchmark evidence</h1><a href="../report.md">New report source</a><p>Original client output, metrics, generated plots and actual console captures for each completed run:</p><ul>'+links+'</ul>')
    lines=['# Assignment 2 - Fresh test results', '',
           '**Status: IN PROGRESS - not ready for submission.**' if missing else '**Fresh benchmark matrix collected; review remaining functional and console evidence before submission.**',
           '', 'This report uses only the new session '+session.name+'. Earlier reports and tuning experiments are excluded. Original records are under '+str(session.relative_to(ROOT))+'.',
           '', '## Method and configuration', '',
           'Four existing ingest EC2 instances are registered in stages: one, two, then four. Each stage is checked through fresh ALB health requests before load. The client runs on the Mac through HTTP/WebSocket ALB endpoints; Consumer metrics use a private SSH tunnel. Every benchmark uses ten resorts, Top-N = 20, random seed 6650, persistent official lift values, prefetch 128, maximum database batch 64,10 ms flush trigger, ACK batch 16 and publisher channel pool 64. Main scaling runs use ten fixed consumer workers; the separate sweep uses 5/10/15/20 fixed workers. SQLite FULL durability is retained.',
           '', 'Existing durable data is retained, not deleted or reset. Database size/cache state can change over sequential runs, so this is a measured deployment comparison rather than a perfectly isolated capacity experiment. Health routing probes identify responding servers; console target screenshots separately establish registered-target state.',
           '', 'For each run, monitoring starts before the client and continues through final drain. RabbitMQ UI screenshots are captured from the actual Management console in an ephemeral UTC browser; generated queue/resource graphs are separately labeled. No absent samples are replaced with zero.',
           '', '## Benchmark results', '',
           '| Ingests | Events | Client threads | Workers | HTTP main/s | End-to-end/s | HTTP p99 ms | Retries | Ready peak | Mean lag ms |',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    infrastructure=session/'infrastructure.json'
    if infrastructure.exists():
        data=json.loads(infrastructure.read_text())
        section=['## Recorded infrastructure', '', 'Instance metadata captured at '+data['capturedUTC']+'. RAM below is Linux MemTotal, not the advertised instance capacity.', '',
                 '| Role | EC2 type | vCPUs | Linux RAM GiB | Private IPv4 |','|---|---|---:|---:|---|']
        for role,v in data['instances'].items():
            section.append('| '+' | '.join([role,str(v.get('instance-type')),str(v.get('vcpus')),value(v.get('memoryTotalKiB',0)/1024**2),str(v.get('local-ipv4'))])+' |')
        section+=['', 'Fresh clock tracking is preserved in '+str((session/'clock-synchronization.json').relative_to(ROOT))+'. This is a point-in-time check, not continuous clock calibration; the Mac client is not calibrated by the EC2 check.', '',
                  '`TargetResponseTime` describes the ALB-to-target response phase, while client HTTP latency includes the client-side round trip. WebSocket snapshot-to-receipt wall-clock differences are not calibrated one-way latencies. [AWS metric definitions](https://docs.aws.amazon.com/elasticloadbalancing/latest/application/load-balancer-cloudwatch-metrics.html).', '']
        idx=lines.index('## Benchmark results');lines[idx:idx]=section
    for r in summaries:
        c=r['client'];q=r['queue'];a=r.get('consumer',{})
        lines.append('| '+' | '.join([str(r['ingestCount']),str(r['events']),str(r['clientThreads']),str(r['consumerConfiguration']['configuredThreads']),value(c['mainThroughput']),value(c['endToEndThroughput']),value(c['httpp99Ms']),value(c['retries'],0),value((q['ready'] or {}).get('sampleMax'),0),value(a.get('runMeanLagMs'))])+' |')
    lines+=['', '## Scaling comparison and interpretation', '',
            'The following compares the latest completed scaling observation for each node/thread configuration with the corresponding single-ingest run. All attempts remain listed in this report; observations are not selected by best throughput. Persistent database/cache state and time of day differ, so percentage changes are descriptive rather than statistically established causal effects.', '',
            '| Client threads | Ingests | Main HTTP change vs1 | End-to-end change vs1 |','|---|---:|---:|---:|']
    matched={(r['ingestCount'],r['clientThreads']):r for r in summaries if r['category']=='scaling' and r['events']==500000}
    for t in [64,128,256,512]:
        baseline=matched.get((1,t))
        if not baseline:continue
        for n in [2,4]:
            r=matched.get((n,t))
            if r:
                h=(r['client']['mainThroughput']/baseline['client']['mainThroughput']-1)*100
                e=(r['client']['endToEndThroughput']/baseline['client']['endToEndThroughput']-1)*100
                lines.append(f'| {t} | {n} | {h:+.2f}% | {e:+.2f}% |')
    fig,axes=evidence.plt.subplots(1,2,figsize=(11,4))
    for t in [64,128,256,512]:
        pairs=[(n,matched[(n,t)]) for n in [1,2,4] if (n,t) in matched]
        for ax,key,title in zip(axes,['mainThroughput','endToEndThroughput'],['Main HTTP ingestion','End-to-end completion']):
            ax.plot([n for n,r in pairs],[r['client'][key] for n,r in pairs],marker='o',label=str(t)+' client threads')
            ax.set_xticks([1,2,4]);ax.set_xlabel('Active ingest targets');ax.set_ylabel('Events/s');ax.set_title(title);ax.grid(alpha=.2)
    axes[0].legend(fontsize=8);evidence.figsave(fig,out/'scaling-comparison.png')
    lines+=['', '![Fresh scaling observations](scaling-comparison.png)', '',
            'If main HTTP throughput rises while end-to-end throughput changes little and queues/drain grow, the improvement is at ingestion rather than completed processing. Conversely, little scaling at low client concurrency may reflect the admitted workload rather than a saturated ingest. Neither high average I/O wait nor a brief broker CPU peak alone identifies an exclusive bottleneck.', '',
            'Fixed-worker comparisons must consider both lag and queue depth alongside throughput. Ten ordered resort queues cap useful aggregation parallelism at ten; configuring 15/20 workers does not create15/20 independent resort lanes. Sampled bounds and mean lag do not prove per-event limits.', '']
    if failed:
        lines+=['## Failed or incomplete attempts retained', '']
        for attempt in failed:lines.append('- '+attempt['directory']+': '+str(attempt.get('failure','clientExitCode='+str(attempt.get('clientExitCode')))))
    lines+=['', '## Per-run evidence', '',
            'All queue statistics below cover only raw resort.1 through resort.10 queues. UI Overview charts include other queues as well. Means are arithmetic sample means over the client window including warmup and drain. Broker delivery rate is transport delivery, not necessarily committed aggregation. Consumer lag is the run-specific mean obtained from cumulative sums/counts; it does not establish a bound on every event. Network rates use physical interfaces to avoid Docker bridge double counting.', '',
            'RabbitMQ Consumers counts AMQP subscriptions, not Java worker threads: ten raw-queue subscriptions plus WebSocket backplane subscriptions can remain present with only five aggregation workers. Worker counts are verified from Consumer configuredThreads/coreThreads/dynamicScaling metrics. Additional drain time includes final WebSocket verification, not only queue processing. The client output Remaining in queue refers to its local send-work queue, not RabbitMQ depth.', '']
    for r in summaries:
        d=evidence.DEST/r['run'];rel=os.path.relpath(d,out);c=r['client'];q=r['queue'];a=r.get('consumer',{})
        lines += ['### '+r['run'], '',
                  f"UTC: {r['startUTC']} to {r['endUTC']}. Ingests={r['ingestCount']}; clientThreads={r['clientThreads']}; consumer={json.dumps(r['consumerConfiguration'])}.", '',
                  f"[Complete client output]({rel}/client-output.txt) | [Full metrics, provenance and limits]({rel}/summary.json) | [Evidence page]({rel}/index.html)", '',
                  '| Measurement | Result |','|---|---:|',
                  f"| Ingestion runtime seconds | {value(c['ingestRuntimeSeconds'],3)} |",
                  f"| Program runtime seconds | {value(c['programRuntimeSeconds'],3)} |",
                  f"| Successful / final failed / retries | {value(c['successful'],0)} / {value(c['failed'],0)} / {value(c['retries'],0)} |",
                  f"| HTTP mean / p95 / p99 ms | {value(c['httpMeanMs'])} / {value(c['httpp95Ms'])} / {value(c['httpp99Ms'])} |",
                  f"| Ready sampled mean / peak | {value((q['ready'] or {}).get('sampleMean'))} / {value((q['ready'] or {}).get('sampleMax'),0)} |",
                  f"| Ready + Unacked peak | {value((q['total'] or {}).get('sampleMax'),0)} |",
                  f"| Mean broker publish / delivery / ACK rates per second | {value((q['publishRate'] or {}).get('sampleMean'))} / {value((q['deliverRate'] or {}).get('sampleMean'))} / {value((q['ackRate'] or {}).get('sampleMean'))} |",
                  f"| Additional processed / duplicate count | {a.get('processedDelta')} / {a.get('duplicateDelta')} |",
                  f"| Final Ready / in-flight / outbox | {a.get('finalReady')} / {a.get('finalInFlight')} / {a.get('finalOutbox')} |",
                  f"| WebSocket updates / reconnections | {c['webSocketUpdates']} / {c['webSocketReconnections']} |", '',
                  'Per-host CPU, memory, physical network and disk sample means/maxima are preserved in summary.json and per-host CSVs. Application counter distribution is not an ALB per-target console metric.', '',
                  f'![Raw resort queues: depth and rates]({rel}/queue.png)', '',
                  f'![EC2 CPU, memory, network and disk]({rel}/system.png)', '',
                  f'![Ingest application request distribution]({rel}/distribution.png)', '',
                  'Actual RabbitMQ console captures:', '']
        for pic in r.get('consoleScreenshots',[]):lines.append(f'- [{Path(pic).name}]({rel}/{pic})')
        alb_pics=r.get('screenshotContext',{}).get('albScreenshots',[])
        if alb_pics:
            lines += ['', 'Supplied AWS CloudWatch captures:', '']
            for pic in alb_pics:
                lines += [f'![{Path(pic).name}]({rel}/{pic})', '']
        lines += ['', 'Collection gaps: '+('; '.join(r['gaps']) or 'None detected in the recorded collectors.'), '']
    lines += ['## Functional and recovery evidence', '', 'Only result files generated under this fresh session are used; older functional/recovery results are excluded.', '']
    verifications=sorted((session/'verification').glob('*.json'))
    if not verifications:lines+=['Fresh functional/recovery tests are still pending.', '']
    for path in verifications:
        data=json.loads(path.read_text())
        selected={k:data[k] for k in ['passed','demoLifts','dayID','uniqueEvents','duplicateRequests','serverRequestCounts','originalServer','replacementServer','reconnectSecondsFromStopStart','brokerDownPublishStatus','restoredIngestHealthy','restoreErrors','distribution','stickyPassed','distributionTargetsPassed','failoverTested'] if k in data}
        lines+=['### '+path.stem, '', 'Source: '+str(path.relative_to(ROOT)), '', '```json',json.dumps(selected,indent=2),'```', '']
        for stage in data.get('stages',[]):lines.append('- '+stage['stage']+': '+str(stage['uniqueEvents'])+' unique events verified against official lift totals and subscribed standings.')
        if 'reconnectionMode' in data:lines+=['', 'Reconnection mode: '+data['reconnectionMode']+'. This is not evidence of the Java load client automatically reconnecting.', '']
    regression=session/'local-regression/summary.json'
    if regression.exists():
        data=json.loads(regression.read_text());lines+=['## Fresh local regression', '',json.dumps(data['counts'])+'. Build success: '+str(data['buildSuccess'])+'.', '', data['environmentNote'], '', 'Logs and JUnit XML: '+str(regression.parent.relative_to(ROOT))+'.', '']
    topology=session/'queue-topology.json'
    if topology.exists():
        data=json.loads(topology.read_text());lines+=['## Recorded RabbitMQ topology', '', 'Captured at '+data['capturedUTC']+'. Raw queues: '+str(len(data['queues']))+'; raw routing bindings: '+str(len(data['rawBindings']))+'.', '',
                   'Exchanges: '+', '.join(e['name']+' ('+e['type']+')' for e in data['exchanges'])+'.', '',
                   'First raw queue arguments (the full record includes all ten): `'+json.dumps(data['queues'][0]['arguments'])+'`.', '']
    lines += ['## Included AWS console evidence', '',
              'Supplied console captures are included in the corresponding per-run evidence sections above. The inventory below reports recorded attachments rather than an outstanding screenshot request.', '',
              '| Run | Attached ALB screenshots |', '|---|---:|']
    for r in summaries:
        if r['category']=='scaling':
            count=len(r.get('screenshotContext',{}).get('albScreenshots',[]))
            lines.append('| '+r['run']+' | '+str(count)+' |')
    lines += ['', '### ALB registered targets', '']
    aws_dir=out/'evidence/aws-console';aws_dir.mkdir(parents=True,exist_ok=True)
    import shutil
    for name,label in [('single-deregistration-in-progress.png','Single-ingest deregistration transition; not a final one-target screenshot.'),('two-targets-healthy.png','Two registered Healthy targets.'),('four-targets-healthy.png','Four registered Healthy targets.')]:
        source=session/'aws-console'/name
        if source.exists():
            shutil.copy2(source,aws_dir/name)
            lines += ['![ALB targets](evidence/aws-console/'+name+')',label,'']
    lines += ['## Submission review items', '',
              'Review the student AI-use reflection and verify publication of the A2 source folders to the linked repository. Supplied scaling-run AWS screenshots are included, not an outstanding collection task. Retain the disclosed queue/lag target violations, collection gaps, retries and distinction between harness-managed and Java-client automatic reconnection.', '']
    (out/'report.md').write_text('\n'.join(lines)+'\n')
    rows=[]
    for r in summaries:
        rows.append({'run':r['run'],'ingests':r['ingestCount'],'events':r['events'],'clientThreads':r['clientThreads'],
                     'consumerThreads':r['consumerConfiguration']['configuredThreads'],**r['client'],
                     'readyPeak':(r['queue']['ready'] or {}).get('sampleMax'),
                     'meanLagMs':r.get('consumer',{}).get('runMeanLagMs')})
    if rows:
        with (out/'results.csv').open('w',newline='') as f:
            w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
    if args.pdf:subprocess.run([sys.executable,str(ROOT/'monitoring/render_fresh_pdf.py')],check=True)
    print('New report: '+str(out/'report.md'))
    print('Completed fresh benchmarks: '+str(len(summaries))+'/'+str(len(plan['plannedRuns'])))


if __name__=='__main__':main()
