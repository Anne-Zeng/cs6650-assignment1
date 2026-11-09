#!/usr/bin/env python3
"""Pair bounded EC2 monitoring with a load run; never run without initial samples."""
import argparse,datetime,json,subprocess,time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
from ec2_monitor import HOSTS

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--events',type=int,default=100000)
    parser.add_argument('--threads',type=int,default=64)
    parser.add_argument('--base-url',default='http://skitrak-alb-1400622109.us-west-2.elb.amazonaws.com')
    parser.add_argument('--consumer-url',default='http://127.0.0.1:18081')
    parser.add_argument('--java',default='/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java')
    args=parser.parse_args()
    if args.events<32000 or args.threads<1:parser.error('events>=32000, threads>=1 required')
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    out=ROOT/'results/a2-ec2'/('instrumented-'+str(args.events)+'-c'+str(args.threads)+'-'+stamp)
    out.mkdir(parents=True,exist_ok=False)
    manifest=dict(vars(args),startUTC=stamp,output=str(out),monitoredHosts={k:v[0] for k,v in HOSTS.items()},ingestCount=sum(k.startswith('ingest') for k in HOSTS))
    (out/'run.json').write_text(json.dumps(manifest,indent=2))
    print('Evidence: '+str(out),flush=True)
    # A single foreground parent owns all processes. The monitor never starts an unrelated load.
    import sys
    with (out/'monitor.log').open('w') as log:
        monitor=subprocess.Popen([sys.executable,str(ROOT/'monitoring/ec2_monitor.py'),'--duration','1800','--interval','2','--output',str(out/'monitor')],stdout=log,stderr=subprocess.STDOUT)
        client=None
        try:
            ready=False
            for _ in range(150):
                status=out/'monitor/status.json'
                if status.exists():
                    samples=json.loads(status.read_text())
                    if any(v.get('errors',0) or v.get('sshExitCode',0) for v in samples.values()):raise RuntimeError('Monitor failed; see monitor SSH logs')
                    if set(samples)==set(HOSTS) and all(v.get('samples',0)>=2 for v in samples.values()):ready=True;break
                if monitor.poll() is not None:raise RuntimeError('Monitor exited before load')
                time.sleep(.2)
            if not ready:raise RuntimeError('No complete monitor baseline within30s; load not started')
            manifest['clientStartUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            command=[args.java,'-jar',str(ROOT/'client-v2/target/client-v2.jar'),args.base_url,str(args.events),str(args.threads),'1000','10','buffered','6650',args.consumer_url,'180']
            with (out/'client.log').open('w') as client_log:
                client=subprocess.Popen(command,cwd=out,stdout=client_log,stderr=subprocess.STDOUT)
                while client.poll() is None:
                    if monitor.poll() is not None:
                        client.terminate();raise RuntimeError('Monitoring stopped during load; run marked failed')
                    time.sleep(1)
            manifest['clientExitCode']=client.returncode
            manifest['clientEndUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
            print('Client exit='+str(client.returncode),flush=True)
            if client.returncode:raise RuntimeError('Load failed; inspect client.log')
        finally:
            if client is not None and client.poll() is None:client.terminate();client.wait(timeout=15)
            monitor.terminate()
            try:monitor.wait(timeout=15)
            except subprocess.TimeoutExpired:monitor.kill();monitor.wait()
            (out/'run.json').write_text(json.dumps(manifest,indent=2))

if __name__=='__main__':main()
