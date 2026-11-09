#!/usr/bin/env python3
"""Run a fresh assignment phase with verified routing, metrics and real UI captures.

Requires the user to register the intended ALB targets first. Never changes ALB.
Original evidence and reports are never overwritten.
"""
import argparse
import concurrent.futures
import datetime
import json
import subprocess
import sys
import time
from pathlib import Path
from ec2_monitor import HOSTS, ROOT
from verify_ec2_failover import health
from verify_pipeline import http_json

ALB='http://skitrak-alb-1400622109.us-west-2.elb.amazonaws.com'
PYTHON=sys.executable
JAVA='/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java'


def get(url):
    return http_json(url)[1]


def save(path, value):
    path.write_text(json.dumps(value,indent=2)+'\n')


def capture_windows(session):
    lines=['# ALB screenshot windows - UTC', '',
           'Use the dates in each UTC timestamp. Choose Custom > Absolute, timezoneUTC, period1minute. RequestCount:Sum; TargetResponseTime:Average. Keep titles/statistic/period/timezone visible. Boundary-minute buckets can contain adjacent runs; use client.log for exact request counts.', '',
           '| Ingests | Events | Client threads | Workers | Start UTC | End UTC |',
           '|---|---|---|---|---|---|']
    records=[json.loads(p.read_text()) for p in session.glob('phase-*/*/run.json')]
    for m in sorted(records,key=lambda v:v.get('clientStartUTC',v['startUTC'])):
        lines.append('| '+' | '.join(str(m.get(k,'Pending')) for k in ['ingestCount','events','threads','consumerThreads','clientStartUTC','clientEndUTC'])+' |')
    (session/'ALB-CAPTURE-WINDOWS.md').write_text('\n'.join(lines)+'\n')


def run_one(args, session, threads):
    expected={f'ingest-{i}' for i in range(1,args.ingests+1)}
    consumer='http://127.0.0.1:18081'
    before=get(consumer+'/drain')
    assert before.get('drained'), 'Pipeline must be fully drained before each run'
    assert before['configuredThreads']==args.consumer_threads and before['dynamicScaling'] is False, 'Set the expected fixed consumer worker count first'
    assert before['prefetch']==128 and before['storeBatchSize']==64 and before['storeBatchWaitMs']==10 and before['ackBatchSize']==16, 'Unexpected consumer tuning configuration'
    roles=[f'ingest{i}' for i in range(1,args.ingests+1)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        initial=dict(zip(roles,pool.map(health,roles)))
    assert all(v['producerReady'] and v['backplaneReady'] and v['producerChannelsAvailable']==args.channel_pool_size and v['localSessions']==0 for v in initial.values()), 'Expected healthy ingests with the requested channel pool and no other subscribers'
    targets=set()
    for _ in range(60):
        targets.add(get(ALB+'/health')['serverId'])
    assert targets==expected, 'Observed ALB target IDs '+repr(targets)+'; expected '+repr(expected)
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    folder=session/f'phase-{args.ingests}ingest-workers{args.consumer_threads}-pool{args.channel_pool_size}'/f'instrumented-{args.events}-c{threads}-{stamp}'
    folder.mkdir(parents=True,exist_ok=False)
    selected={k:v for k,v in HOSTS.items() if not k.startswith('ingest') or int(k[6:])<=args.ingests}
    manifest={'events':args.events,'threads':threads,'consumerThreads':args.consumer_threads,
              'channelPoolSize':args.channel_pool_size,'ingestCount':args.ingests,'base_url':ALB,'consumer_url':consumer,
              'startUTC':stamp,'output':str(folder),'freshSession':session.name,
              'monitoredHosts':{k:v[0] for k,v in selected.items()},
              'verifiedALBTargetIds':sorted(targets),'seed':6650,'topN':20,'resorts':10,
              'category':getattr(args,'category','scaling'),
              'databaseState':'Persistent accumulated state retained; not reset between runs'}
    save(folder/'run.json',manifest)
    save(folder/'consumer-before.json',before)
    save(folder/'ingest-before.json',initial)
    print('Evidence: '+str(folder),flush=True)
    monitor=capture=client=None
    with (folder/'monitor.log').open('w') as monlog,(folder/'capture.log').open('w') as caplog:
        try:
            monitor=subprocess.Popen([sys.executable,str(ROOT/'monitoring/ec2_monitor.py'),'--duration','1800','--interval','2','--ingests',str(args.ingests),'--output',str(folder/'monitor')],stdout=monlog,stderr=subprocess.STDOUT)
            capture=subprocess.Popen([PYTHON,str(ROOT/'monitoring/capture_rabbitmq_console.py'),'--output',str(folder/'screenshots')],stdout=caplog,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+75
            while time.monotonic()<deadline:
                if monitor.poll() is not None or capture.poll() is not None:
                    raise RuntimeError('Monitoring/screenshot process exited before load; inspect logs')
                status=folder/'monitor/status.json'
                if status.exists():
                    state=json.loads(status.read_text())
                    if any(v['errors'] for v in state.values()):raise RuntimeError('Initial monitoring samples contain errors')
                    if set(state)==set(selected) and all(v['samples']>=2 for v in state.values()) and (folder/'screenshots/capture-ready.json').exists():break
                time.sleep(.5)
            else:raise RuntimeError('Evidence collectors did not become ready')
            manifest['clientStartUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            command=[JAVA,'-jar',str(ROOT/'client-v2/target/client-v2.jar'),ALB,str(args.events),str(threads),'1000','10','buffered','6650',consumer,'600']
            manifest['clientCommand']=command
            save(folder/'run.json',manifest)
            with (folder/'client.log').open('w') as log:
                client=subprocess.Popen(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT)
                while client.poll() is None:
                    if monitor.poll() is not None:raise RuntimeError('Monitor exited during load')
                    time.sleep(1)
            manifest['clientExitCode']=client.returncode
            manifest['clientEndUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            save(folder/'run.json',manifest)
            if client.returncode:raise RuntimeError('Client failed; evidence retained')
            after=get(consumer+'/drain')
            save(folder/'consumer-after.json',after)
            assert after.get('drained') and after['processed']-before['processed']==args.events, 'Final pipeline count/drain verification failed'
            # Broker UI updates every few seconds; preserve a final real UI capture.
            time.sleep(6)
            (folder/'screenshots/stop-capture').touch()
            capture.wait(timeout=30)
            if capture.returncode:raise RuntimeError('Console screenshot capture failed; inspect capture.log')
            captures=json.loads((folder/'screenshots/captures.json').read_text())
            save(folder/'screenshot-context.json',{'runDirectory':str(folder),
                 'rabbitmqScreenshots':['screenshots/'+v['file'] for v in captures],
                 'notes':['Actual RabbitMQ Management UI in UTC, captured through a private tunnel.',
                          'Overview aggregates all queues; raw queue metrics cover only resort.1 through resort.10.',
                          'Chart range may include earlier runs; use clientStartUTC/clientEndUTC to identify this run.']})
            manifest['evidenceCollectorsPassed']=True
            print(f'Completed: ingests={args.ingests}, workers={args.consumer_threads}, events={args.events}, clientThreads={threads}',flush=True)
        except BaseException as error:
            manifest['failure']=type(error).__name__+': '+str(error)
            raise
        finally:
            if client is not None and client.poll() is None:
                client.terminate()
                try:client.wait(timeout=15)
                except subprocess.TimeoutExpired:client.kill();client.wait()
            if capture is not None and capture.poll() is None:
                (folder/'screenshots/stop-capture').touch()
                try:capture.wait(timeout=15)
                except subprocess.TimeoutExpired:capture.terminate();capture.wait(timeout=10)
            if monitor is not None:
                monitor.terminate()
                try:monitor.wait(timeout=15)
                except subprocess.TimeoutExpired:monitor.kill();monitor.wait()
            save(folder/'run.json',manifest)
            capture_windows(session)
    return folder


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ingests',type=int,choices=[1,2,4],required=True)
    parser.add_argument('--events',type=int,choices=[500000,1000000],default=500000)
    parser.add_argument('--threads',type=int,nargs='+',choices=[64,128,256,512],default=[64,128,256,512])
    parser.add_argument('--consumer-threads',type=int,choices=[5,10,15,20],default=10)
    parser.add_argument('--category',choices=['scaling','consumer-sweep','channel-pool'],default='scaling')
    parser.add_argument('--channel-pool-size',type=int,choices=[32,64],default=64)
    parser.add_argument('--cooldown-seconds',type=int,default=70)
    parser.add_argument('--session',type=Path,help='Explicit evidence session; leaves the active formal report unchanged')
    args=parser.parse_args()
    session=args.session or Path(json.loads((ROOT/'results/a2-fresh/active.json').read_text())['root'])
    for threads in args.threads:
        print(f'Quiet interval before the next benchmark: {args.cooldown_seconds}s',flush=True)
        deadline=time.monotonic()+args.cooldown_seconds
        while time.monotonic()<deadline:time.sleep(min(5,deadline-time.monotonic()))
        run_one(args,session,threads)


if __name__=='__main__':main()
