#!/usr/bin/env python3
"""Preserve both channel-pool repeat observations and their original evidence."""
import json
from pathlib import Path
import build_evidence_pack as evidence
from ec2_monitor import ROOT

def main():
    session=Path(json.loads((ROOT/'results/a2-pool-repeat/latest.json').read_text())['root'])
    dest=ROOT/'output'/('assignment2-pool-repeat-'+session.name)
    dest.mkdir(exist_ok=True)
    evidence.DEST=dest/'evidence';evidence.DEST.mkdir(exist_ok=True)
    records=[]
    for p in sorted(session.glob('phase-*/*/run.json')):
        m=json.loads(p.read_text())
        if not m.get('evidenceCollectorsPassed'):continue
        r=evidence.build(p.parent.resolve());r['channelPoolSize']=m['channelPoolSize'];records.append(r)
    assert len(records)==2,'Both complete observations required'
    records.sort(key=lambda r:r['channelPoolSize'])
    lines=['# Channel pool size: repeated comparison','','Two ingests; 500,000 events per run; 512 client threads; 10 fixed Consumer workers; prefetch 128; ACK batch 16; DB batch 64 with a 10 ms flush trigger. Seed 6650, ten resorts, Top-N 20. Pool 32 runs before pool 64. Persistent database and cache state are retained, so differences are descriptive observations from one run per setting.','','| Pool | Main HTTP/s | End-to-end/s | HTTP p99 ms | Retries | Final failures | Ready peak | Ready + Unacked peak | Mean lag ms |','|---|---:|---:|---:|---:|---:|---:|---:|---:|']
    def f(v):return 'Unavailable' if v is None else f'{v:,.2f}'
    for r in records:
        c,a,q=r['client'],r['consumer'],r['queue']
        assert c['successful']==500000 and c['failed']==0 and a['processedDelta']==500000
        assert all(a[k]==0 for k in ['duplicateDelta','finalReady','finalInFlight','finalOutbox'])
        lines.append('| '+str(r['channelPoolSize'])+' | '+' | '.join(f(v) for v in [c['mainThroughput'],c['endToEndThroughput'],c['httpp99Ms'],c['retries'],c['failed'],q['ready']['sampleMax'],q['total']['sampleMax'],a['runMeanLagMs']])+' |')
    lines+=['','Ready and Total are different queue measures. Peaks and rates are sampled over the client window, including warmup and drain. Mean lag does not prove a per-event latency bound. Application request counters provide distribution evidence; they are not AWS per-target CloudWatch measurements.']
    for r in records:
        pool=r['channelPoolSize'];name=r['run'];base=f'evidence/{name}'
        lines+=['',f'## Pool {pool}','',f"Client UTC window: **{r['startUTC']} – {r['endUTC']}**.",'',f'[Full client output]({base}/client-output.txt) | [Recorded metrics and limitations]({base}/summary.json)','',f"Publisher counters: `{json.dumps(r['ingestDistribution'],sort_keys=True)}`",'',f'![Queue depth and message rates]({base}/queue.png)','',f'![System metrics]({base}/system.png)','',f'![Application request distribution]({base}/distribution.png)','', 'Collection gaps: '+('; '.join(r['gaps']) or 'None detected.'),'']
        for s in r['consoleScreenshots']:
            lines += [f'![Actual RabbitMQ console, pool {pool}]({base}/{s})','']
    lines+=['','## AWS console evidence','','Run-matched AWS ALB screenshots are collected separately using these client UTC windows: whole-LoadBalancer RequestCount (Sum) and TargetResponseTime (Average), 60-second period. Do not reuse an earlier run screenshot. AWS statistics use minute buckets and may include boundary activity; exact successful/retry counts come from the client log.']
    (dest/'comparison.md').write_text('\n'.join(lines)+'\n')
    (dest/'repeat-runs.json').write_text(json.dumps(records,indent=2)+'\n')
    md=ROOT/'TUNING_PARAMETERS.md';text=md.read_text()
    marker='### Fresh two-ingest channel-pool repeat'
    if marker not in text:
        section=['',marker,'',f'[Complete repeat comparison, client output, system/queue metrics and original RabbitMQ screenshots](output/{dest.name}/comparison.md)','']+lines[4:8]
        for r in records:
            section+=['',f"**Pool {r['channelPoolSize']}: {r['startUTC']} – {r['endUTC']} UTC.**",'']
            shots=r['consoleScreenshots']
            # Preserve every console capture in the comparison; display one in-load and final here.
            chosen=[shots[len(shots)//2],shots[-1]] if len(shots)>1 else shots
            for s in chosen:section += [f"![Actual RabbitMQ queue depth and rates, pool {r['channelPoolSize']}](output/{dest.name}/evidence/{r['run']}/{s})",'']
        section+=['These new observations supplement the historical runs above. Both settings were tested sequentially against retained database state. The linked comparison includes all captured RabbitMQ screenshots; matching ALB captures are added separately.','']
        text=text.replace('\n## Overall conclusion','\n'+'\n'.join(section)+'\n## Overall conclusion',1);md.write_text(text)
    print(dest/'comparison.md')

if __name__=='__main__':main()
