#!/usr/bin/env python3
"""Bounded SSH monitoring of this assignment deployment. No cloud services are changed."""
import argparse,csv,datetime,json,signal,subprocess,sys,threading,time
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
HOSTS={'ingest1':('52.33.179.53','34.219.193.18'),'ingest2':('35.166.232.248','34.213.52.138'),'ingest3':('54.184.255.211','34.219.242.250'),'ingest4':('34.217.36.129','52.11.50.119'),'consumer':('52.88.220.13','54.191.190.202'),'rabbitmq':('44.243.227.111','16.145.247.185')}

def sample_host(previous,current):
    memory=current['memoryKiB'];cpu=network_rx=network_tx=''
    if previous:
        elapsed=current['timestamp']-previous['timestamp']
        deltas=[b-a for a,b in zip(previous['cpuTicks'],current['cpuTicks'])]
        total=sum(deltas)
        if total>0 and all(v>=0 for v in deltas):cpu=100*(total-deltas[3]-deltas[4])/total
        if elapsed>0:
            network_rx=sum(max(0,n['rx']-previous['network'].get(k,n)['rx']) for k,n in current['network'].items())/elapsed
            network_tx=sum(max(0,n['tx']-previous['network'].get(k,n)['tx']) for k,n in current['network'].items())/elapsed
    return [cpu,memory['MemTotal']-memory['MemAvailable'],memory['MemTotal'],network_rx,network_tx]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duration',type=int,default=1800)
    parser.add_argument('--interval',type=float,default=2)
    parser.add_argument('--output',required=True)
    parser.add_argument('--ingests',type=int,choices=[1,2,4],default=4)
    args=parser.parse_args()
    if not 1<=args.duration<=7200 or args.interval<1:parser.error('duration1..7200, interval>=1 required')
    output=Path(args.output).resolve();output.mkdir(parents=True,exist_ok=True)
    selected={k:v for k,v in HOSTS.items() if not k.startswith('ingest') or int(k[6:])<=args.ingests}
    probe=(ROOT/'monitoring/ec2_probe.py').read_text()
    status={role:{'samples':0,'errors':0} for role in selected};lock=threading.Lock()
    stopping=threading.Event();processes=[]
    def stop(signum,frame):
        stopping.set()
        for process in list(processes):
            if process.poll() is None:process.terminate()
    signal.signal(signal.SIGTERM,stop)
    signal.signal(signal.SIGINT,stop)
    def save_status():
        temporary=output/'status.tmp'
        temporary.write_text(json.dumps(status,indent=2))
        temporary.replace(output/'status.json')
    def collect(role,host,alias):
        command=['ssh','-T','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ServerAliveInterval=15','-o','ServerAliveCountMax=2','-i',str(ROOT/'cs6650-lab1-key.pem')]
        if alias:command+=['-o','HostKeyAlias='+alias] # Verify the previously trusted broker identity at its new IP.
        command+=['ec2-user@'+host,'sudo','-n','python3','-u','-',role,str(args.interval),str(args.duration)]
        previous=None
        with (output/(role+'.jsonl')).open('w') as raw,(output/(role+'-host.csv')).open('w',newline='') as host_file,(output/(role+'-ssh.log')).open('w') as errors:
            host_writer=csv.writer(host_file);host_writer.writerow(['timestamp','cpuPercent','memoryUsedKiB','memoryTotalKiB','rxBytesPerSec','txBytesPerSec'])
            with (output/(role+'-app.csv')).open('w',newline='') as app_file:
                app_writer=csv.writer(app_file)
                app_writer.writerow(['timestamp','ready','unacked','publishRate','deliverRate','ackRate','consumerLagAvgMs','consumerLagP95Ms','consumerLagP99Ms','error'])
                process=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=errors,text=True)
                processes.append(process)
                if stopping.is_set():process.terminate()
                try:
                    process.stdin.write(probe);process.stdin.close()
                    for line in process.stdout:
                        try:
                            sample=json.loads(line);raw.write(line);raw.flush()
                            if 'host' in sample:
                                current=dict(sample['host'],timestamp=sample['timestamp'])
                                host_writer.writerow([sample['timestamp']]+sample_host(previous,current));host_file.flush();previous=current
                            values=['']*8
                            if 'queues' in sample:
                                values[:5]=[sum(q[k] for q in sample['queues']) for k in ['ready','unacked','publishRate','deliverRate','ackRate']]
                            elif 'app' in sample:
                                app=sample['app'];values[:2]=[app.get('queueReady',''),app.get('inFlight','')]
                                values[5:]=[app.get(k,'') for k in ['consumerLagAvgMs','consumerLagP95Ms','consumerLagP99Ms']]
                            app_writer.writerow([sample['timestamp']]+values+[sample.get('appError','')]);app_file.flush()
                            with lock:
                                status[role]['samples']+=1
                                status[role]['errors']+=int('appError' in sample or 'hostError' in sample)
                                save_status()
                        except (ValueError,KeyError,TypeError):
                            with lock:status[role]['errors']+=1;save_status()
                    code=process.wait()
                    with lock:status[role]['sshExitCode']=code;save_status()
                finally:
                    if process.poll() is None:process.terminate();process.wait(timeout=10)
    manifest={'startUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'durationSeconds':args.duration,'intervalSeconds':args.interval,'hosts':{k:v[0] for k,v in selected.items()},'note':'Host counters are measured on each EC2. Filter samples to the actual client run boundaries; idle monitoring tail is not load evidence.'}
    (output/'configuration.json').write_text(json.dumps(manifest,indent=2))
    threads=[threading.Thread(target=collect,args=(role,*target)) for role,target in selected.items()]
    for thread in threads:thread.start()
    for thread in threads:thread.join()
    print(json.dumps(status,indent=2),flush=True)
    if not stopping.is_set() and any(v['samples']==0 or v['errors'] or v.get('sshExitCode')!=0 for v in status.values()):raise SystemExit(1)

if __name__=='__main__':main()
