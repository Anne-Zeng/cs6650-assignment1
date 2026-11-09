#!/usr/bin/env python3
"""Change ONE consumer tuning variable (ACK_BATCH_SIZE or PREFETCH_COUNT) on the course consumer.

Run on the Mac with producers stopped and the pipeline drained. Backs up
/etc/skitrak/consumer.env and rolls back if the consumer does not become healthy.
"""
import argparse
import subprocess
from pathlib import Path
from ec2_monitor import HOSTS

REMOTE = r'''
import datetime,json,pathlib,subprocess,sys,time,urllib.request
name,value=sys.argv[1],sys.argv[2]
def metrics():
    with urllib.request.urlopen('http://127.0.0.1:8081/metrics',timeout=10) as r:
        return json.load(r)
before=metrics()
assert before['healthy'] and before['queueReady']==0 and before['inFlight']==0 and before['pendingOutbox']==0, 'Stop producers and wait until the pipeline is drained'
path=pathlib.Path('/etc/skitrak/consumer.env')
original=path.read_bytes()
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
backup=path.with_name('consumer.env.before-tuning-'+stamp)
backup.touch(mode=0o600,exist_ok=False)
backup.write_bytes(original)
lines=[l for l in original.decode().splitlines() if l.split('=',1)[0].strip()!=name]
lines.append(name+'='+value)
key={'ACK_BATCH_SIZE':'ackBatchSize','PREFETCH_COUNT':'prefetch'}[name]
try:
    path.write_text('\n'.join(lines)+'\n')
    subprocess.run(['systemctl','restart','skitrak-consumer'],check=True)
    for _ in range(90):
        try:
            m=metrics()
            if m['healthy'] and str(m[key])==value:
                print(json.dumps({k:m.get(k) for k in ['healthy','processed','configuredThreads','dynamicScaling','prefetch','storeBatchSize','ackBatchSize']},indent=2))
                print('Backup: '+str(backup))
                break
        except Exception:
            pass
        time.sleep(1)
    else:
        raise RuntimeError('Consumer did not become ready with the requested value')
except BaseException:
    path.write_bytes(original)
    subprocess.run(['systemctl','restart','skitrak-consumer'],check=False)
    raise
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('name',choices=['ACK_BATCH_SIZE','PREFETCH_COUNT'])
    parser.add_argument('value',type=int)
    args=parser.parse_args()
    if not 1<=args.value<=1024:parser.error('value must be 1..1024')
    host,alias=HOSTS['consumer']
    key=Path(__file__).resolve().parents[1]/'cs6650-lab1-key.pem'
    command=['ssh','-T','-i',str(key),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10']
    if alias:command+=['-o','HostKeyAlias='+alias]
    command+=['ec2-user@'+host,'sudo','-n','python3','-',args.name,str(args.value)]
    subprocess.run(command,input=REMOTE,text=True,check=True)

if __name__=='__main__':
    main()
