#!/usr/bin/env python3
"""Compare all repeated fixed-worker measurements with their original observations."""
import csv,json
from pathlib import Path
import build_evidence_pack as evidence
ROOT=Path(__file__).resolve().parents[1]

def main():
    session=Path(json.loads((ROOT/'results/a2-repeat/latest.json').read_text())['root'])
    dest=ROOT/'output'/('assignment2-repeat-'+session.name);dest.mkdir(exist_ok=True)
    evidence.DEST=dest/'evidence';evidence.DEST.mkdir(exist_ok=True)
    original=json.loads((ROOT/'output/assignment2-fresh-20261108T135433Z/all-fresh-runs.json').read_text())
    old={r['consumerConfiguration']['configuredThreads']:r for r in original if r['category']=='consumer-sweep'}
    summaries=[]
    for p in sorted(session.glob('phase-*/*/run.json')):
        m=json.loads(p.read_text())
        if m.get('clientExitCode')==0 and m.get('evidenceCollectorsPassed'):
            r=evidence.build(p.parent);r['category']='consumer-sweep-repeat';summaries.append(r)
    summaries.sort(key=lambda r:r['consumerConfiguration']['configuredThreads'])
    lines=['# Fixed-worker sweep: original and repeat','','Four ingests, 500,000 events per run, 128 client threads, 10 resorts, Top-N 20, seed 6650. Only worker count changes within this repeat. Persistent database state is retained. The original and repeat are separated by an EC2 stop/start, so cache, accumulated data and CPU-credit state may differ. These are repeated observations, not an isolated causal experiment. Both runs are retained; no best-result selection.','','Queue depth is reported both as Ready and Ready + Unacked. Means/peaks are sampled over the client window including warmup and drain. Mean lag is not a per-event maximum.','','| Workers | Observation | HTTP main/s | End-to-end/s | HTTP p99 ms | Retries | Ready peak | Ready+Unacked peak | Mean lag ms |','|---|---|---:|---:|---:|---:|---:|---:|---:|']
    flat=[]
    for new in summaries:
        n=new['consumerConfiguration']['configuredThreads']
        for label,r in [('Original',old[n]),('Repeat',new)]:
            c=r['client'];a=r['consumer'];q=r['queue']
            row={'workers':n,'observation':label,'mainHTTPPerSecond':c['mainThroughput'],'endToEndPerSecond':c['endToEndThroughput'],'httpP99Ms':c['httpp99Ms'],'retries':c['retries'],'readyPeak':q['ready']['sampleMax'],'totalPeak':q['total']['sampleMax'],'meanLagMs':a['runMeanLagMs']}
            flat.append(row)
            def f(v):return 'Unavailable' if v is None else f'{v:,.2f}'
            lines.append('| '+str(n)+' | '+label+' | '+' | '.join(f(row[k]) for k in ['mainHTTPPerSecond','endToEndPerSecond','httpP99Ms','retries','readyPeak','totalPeak','meanLagMs'])+' |')
    lines += ['', '## Change from the original observation', '',
              '| Workers | End-to-end throughput change | Original mean lag ms | Repeat mean lag ms | Original Ready peak | Repeat Ready peak |',
              '|---|---:|---:|---:|---:|---:|']
    for new in summaries:
        n=new['consumerConfiguration']['configuredThreads'];before=old[n]
        change=100*(new['client']['endToEndThroughput']/before['client']['endToEndThroughput']-1)
        def f(v):return 'Unavailable' if v is None else f'{v:,.2f}'
        lines.append('| '+str(n)+' | '+f'{change:+.2f}%'+' | '+f(before['consumer']['runMeanLagMs'])+' | '+f(new['consumer']['runMeanLagMs'])+' | '+f(before['queue']['ready']['sampleMax'])+' | '+f(new['queue']['ready']['sampleMax'])+' |')
    lines += ['', '## Cache-state evidence', '', '| Workers | Consumer device reads MiB | Mean device read IOPS |', '|---|---:|---:|']
    import datetime
    cache_rows=[]
    for r in summaries:
        raw=Path(r['originalDirectory'])/'monitor/consumer.jsonl'
        begin=datetime.datetime.fromisoformat(r['startUTC']).timestamp();finish=datetime.datetime.fromisoformat(r['endUTC']).timestamp()
        samples=[json.loads(line) for line in raw.read_text().splitlines()]
        samples=[v for v in samples if begin<=v['timestamp']<=finish and isinstance(v.get('host'),dict)]
        if len(samples)>=2:
            a,b=samples[0],samples[-1];seconds=b['timestamp']-a['timestamp']
            x=a['host']['diskCounters']['nvme0n1'];y=b['host']['diskCounters']['nvme0n1']
            reads=(y['readSectors']-x['readSectors'])*512/1048576;iops=(y['reads']-x['reads'])/seconds
            n=r['consumerConfiguration']['configuredThreads'];cache_rows.append({'workers':n,'readsMiB':reads,'readIOPS':iops})
            lines.append(f'| {n} | {reads:,.2f} | {iops:,.2f} |')
    lines += ['', 'Device read workload differs between sequential observations. Declining reads are consistent with cache warming; a lag difference therefore cannot be attributed only to worker count. These are OS device counters over the sampled client window, not EBS CloudWatch IOPS. Tests retain their original sequential order and do not randomize or reset cache state.', '']
    lines += ['', 'Each worker configuration now has two observations. This remains too little evidence to attribute every change to the worker count. Mean lag below 100 ms does not establish every-event latency below 100 ms. The Ready threshold and Ready + Unacked threshold must be interpreted separately.', '', '## Repeat evidence and validation', '']
    for r in summaries:
        c=r['client'];a=r['consumer'];n=r['consumerConfiguration']['configuredThreads']
        assert c['successful']==500000 and c['failed']==0 and a['processedDelta']==500000
        assert all(a[k]==0 for k in ['duplicateDelta','finalReady','finalInFlight','finalOutbox'])
        lines += [f"### {n} workers",'',f"UTC: {r['startUTC']} to {r['endUTC']}",'',f"Successful={c['successful']}, finalFailed={c['failed']}, retries={c['retries']}, processedDelta={a['processedDelta']}, duplicates={a['duplicateDelta']}. Final Ready/in-flight/outbox=0/0/0.",'',f"[Client output](evidence/{r['run']}/client-output.txt) | [Metrics](evidence/{r['run']}/summary.json)",'',f"![Queue depth and rates](evidence/{r['run']}/queue.png)",'',f"![System metrics](evidence/{r['run']}/system.png)",'','Collection gaps: '+('; '.join(r['gaps']) or 'None detected.'),'']
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(1,3,figsize=(12,3.8))
    worker_counts=[r['consumerConfiguration']['configuredThreads'] for r in summaries]
    for ax,title,getter in [(axes[0],'End-to-end events/s',lambda r:r['client']['endToEndThroughput']), (axes[1],'Ready peak (log scale)',lambda r:r['queue']['ready']['sampleMax']), (axes[2],'Mean lag ms (log scale)',lambda r:r['consumer']['runMeanLagMs'])]:
        ax.bar([i-.19 for i in range(len(summaries))],[getter(old[n]) for n in worker_counts],width=.38,label='Original',color='#9bbbd4')
        ax.bar([i+.19 for i in range(len(summaries))],[getter(r) for r in summaries],width=.38,label='Repeat',color='#236b8e')
        ax.set_xticks(range(len(summaries)),worker_counts);ax.set_xlabel('Consumer workers');ax.set_title(title);ax.grid(axis='y',alpha=.2)
    axes[1].set_yscale('log');axes[2].set_yscale('log');axes[0].legend()
    fig.tight_layout();fig.savefig(dest/'comparison.png',dpi=150);plt.close(fig)
    lines[2:2]=['![Original versus repeat](comparison.png)','']
    (dest/'comparison.md').write_text('\n'.join(lines)+'\n')
    (dest/'repeat-runs.json').write_text(json.dumps(summaries,indent=2)+'\n')
    with (dest/'comparison.csv').open('w',newline='') as f:
        w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
    print('Comparison:',dest/'comparison.md',flush=True)

if __name__=='__main__':main()
