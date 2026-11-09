#!/usr/bin/env python3
"""Read-only Linux probe, executed over SSH stdin; secrets never leave the host."""
import base64,json,re,socket,sys,time
from pathlib import Path
from urllib.request import Request,urlopen

def fetch(url, auth=None):
    request=Request(url)
    if auth: request.add_header('Authorization','Basic '+base64.b64encode(auth.encode()).decode())
    with urlopen(request,timeout=4) as response: return json.load(response)

def host():
    cpu=[int(v) for v in Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]
    memory={line.split(':')[0]:int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith(('MemTotal:','MemAvailable:','SwapTotal:','SwapFree:'))}
    network={line.split(':')[0].strip():{'rx':int(line.split(':')[1].split()[0]),'tx':int(line.split(':')[1].split()[8])} for line in Path('/proc/net/dev').read_text().splitlines()[2:] if ':' in line and line.split(':')[0].strip()!='lo'}
    disks={}
    for line in Path('/proc/diskstats').read_text().splitlines():
        fields=line.split()
        if re.fullmatch(r'nvme\d+n\d+|xvd[a-z]+|sd[a-z]+',fields[2]):
            disks[fields[2]]={key:int(fields[index]) for key,index in [('reads',3),('readSectors',5),('readMs',6),('writes',7),('writeSectors',9),('writeMs',10),('inProgress',11),('ioMs',12),('weightedIoMs',13)]}
    return {'cpuTicks':cpu,'memoryKiB':memory,'network':network,'diskCounters':disks}

def main():
    role,interval,duration=sys.argv[1],float(sys.argv[2]),float(sys.argv[3])
    if role not in ('rabbitmq','consumer','ingest1','ingest2','ingest3','ingest4'):raise ValueError('Unknown role')
    auth=None
    if role=='rabbitmq':
        env={}
        for line in Path('/etc/skitrak/rabbitmq.env').read_text().splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key,value=line.split('=',1);env[key.strip()]=value.strip()
        auth=env['RABBITMQ_DEFAULT_USER']+':'+env['RABBITMQ_DEFAULT_PASS']
    deadline=time.monotonic()+duration
    while time.monotonic()<deadline:
        start=time.monotonic();sample={'timestamp':time.time(),'role':role,'hostname':socket.gethostname()}
        try:sample['host']=host()
        except Exception:sample['hostError']='Host counters unavailable'
        try:
            if role=='rabbitmq':
                queues=fetch('http://127.0.0.1:15672/api/queues',auth)
                raw=[q for q in queues if q['name'] in {'resort.'+str(i) for i in range(1,11)} and q.get('vhost')=='/']
                if len(raw)!=10:raise ValueError('Expected ten raw queues')
                sample['queues']=[{'name':q['name'],'ready':q.get('messages_ready',0),'unacked':q.get('messages_unacknowledged',0),**{key:q.get('message_stats',{}).get(metric+'_details',{}).get('rate',0) for key,metric in [('publishRate','publish'),('deliverRate','deliver_get'),('ackRate','ack')]}} for q in raw]
                sample['deadReady']=sum(q.get('messages_ready',0) for q in queues if q['name'].endswith('.dead') and q.get('vhost')=='/')
                nodes=fetch('http://127.0.0.1:15672/api/nodes',auth)
                sample['brokerNodes']=[{key:n.get(key) for key in ['name','mem_used','mem_limit','mem_alarm','disk_free','disk_free_alarm','fd_used','fd_total','running']} for n in nodes]
            else:sample['app']=fetch('http://127.0.0.1:'+('8081' if role=='consumer' else '8080')+'/metrics')
        except Exception as error:
            # Log only the error type: never credentials, requests or environment contents.
            sample['appError']=type(error).__name__
        print(json.dumps(sample),flush=True)
        time.sleep(max(0,interval-(time.monotonic()-start)))

if __name__=='__main__':main()
