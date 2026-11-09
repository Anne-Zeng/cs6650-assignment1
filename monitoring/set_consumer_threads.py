#!/usr/bin/env python3
"""Set a fixed consumer worker count on the existing course deployment."""
import argparse
import subprocess
from pathlib import Path
from ec2_monitor import HOSTS

REMOTE = r'''
import datetime,json,pathlib,subprocess,sys,time,urllib.request
n=int(sys.argv[1])
def metrics():
    with urllib.request.urlopen('http://127.0.0.1:8081/metrics',timeout=10) as r:
        return json.load(r)
before=metrics()
assert before['healthy'] and before['queueReady']==0 and before['inFlight']==0 and before['pendingOutbox']==0 and 0<=before['queueMetricsAgeMs']<=3000, 'Stop producers and wait until the pipeline is drained'
path=pathlib.Path('/etc/skitrak/consumer.env')
original=path.read_bytes()
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
backup=path.with_name('consumer.env.before-sweep-'+stamp)
backup.touch(mode=0o600,exist_ok=False)
backup.write_bytes(original)
changes={'CONSUMER_THREADS':str(n),'CONSUMER_MIN_THREADS':'5','CONSUMER_DYNAMIC_SCALING':'false'}
lines=[line for line in original.decode().splitlines() if line.split('=',1)[0].strip() not in changes]
lines.extend(k+'='+v for k,v in changes.items())
try:
    path.write_text('\n'.join(lines)+'\n')
    subprocess.run(['systemctl','restart','skitrak-consumer'],check=True)
    for _ in range(90):
        try:
            m=metrics()
            if m['healthy'] and m['configuredThreads']==n and m['dynamicScaling'] is False and m['processed']>=before['processed']:
                print(json.dumps({k:m[k] for k in ['healthy','processed','configuredThreads','dynamicScaling','maxAggregationParallelism','prefetch','storeBatchSize','ackBatchSize']},indent=2))
                print('Configuration backup: '+str(backup))
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Consumer did not become ready with the requested configuration')
except BaseException:
    path.write_bytes(original)
    subprocess.run(['systemctl','restart','skitrak-consumer'],check=False)
    raise
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('threads',type=int,choices=[5,10,15,20,40,80])
    args=parser.parse_args()
    host,alias=HOSTS['consumer']
    key=Path(__file__).resolve().parents[1]/'cs6650-lab1-key.pem'
    command=['ssh','-T','-i',str(key),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10']
    if alias:command+=['-o','HostKeyAlias='+alias]
    command+=['ec2-user@'+host,'sudo','-n','python3','-',str(args.threads)]
    subprocess.run(command,input=REMOTE,text=True,check=True)

if __name__=='__main__':
    main()
