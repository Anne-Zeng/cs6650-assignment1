#!/usr/bin/env python3
"""Fresh EC2 consumer/broker restart verification; restores all touched services.

Use only between benchmarks. No persistent data is deleted or replaced.
Two direct ingest SSH tunnels exercise cross-node replay and local subscriptions.
"""
import argparse
import collections
import datetime
import json
import subprocess
import time
import uuid
from pathlib import Path
from urllib.error import HTTPError
from ec2_monitor import HOSTS, ROOT
from verify_ec2_failover import ssh, wait_for
from verify_pipeline import WebSocket, http_json, load_lifts


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--day',type=int,default=339)
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    if not 1<=args.day<=366:parser.error('day must be1..366')
    consumer='http://127.0.0.1:18081'
    urls=['http://127.0.0.1:19081','http://127.0.0.1:19082']
    tunnels=[];sockets=[];touched=set();events=[];expected=collections.defaultdict(int)
    lifts=load_lifts(ROOT/'deployment/config/lift_vertical.json')
    output=Path(args.output);output.parent.mkdir(parents=True,exist_ok=True)
    result={'passed':False,'deployment':'Existing EC2 consumer and RabbitMQ; direct SSH tunnels to ingest1/2',
            'startUTC':datetime.datetime.now(datetime.timezone.utc).isoformat(),'dayID':args.day,'resortID':3,'stages':[]}
    def get(url):return http_json(url)[1]
    def action(role,verb):
        assert role in ['consumer','rabbitmq'] and verb in ['stop','start']
        return ssh(role,'sudo -n systemctl '+verb+' skitrak-'+role)
    def submit(e,index):
        try:
            return http_json(urls[index]+f'/skiers/3/seasons/2026/days/{args.day}/skiers/{e["skier"]}',
                             {'time':e['time'],'liftID':e['lift']},{'Idempotency-Key':e['id']})[0]
        except HTTPError as error:return error.code
    def new(skier,lift):
        e={'id':str(uuid.uuid4()),'skier':skier,'lift':lift,'time':len(events)+1}
        events.append(e);expected[str(skier)]+=lifts[lift];return e
    def verify(label,count):
        def correct():
            s=get(consumer+f'/leaderboards/3?dayID={args.day}')
            return s if s['processed']==count else None
        s=wait_for('Aggregation '+label,correct)
        top=[{'rank':i+1,'skierID':k,'vertical':v} for i,(k,v) in enumerate(sorted(expected.items(),key=lambda pair:(-pair[1],int(pair[0])))[:20])]
        assert s['top']==top,'Authoritative Top-N differs from official lift totals'
        for ws in sockets:
            observed=ws.wait(lambda x:x['processed']==count and x['version']>=s['version'],timeout=40)
            assert observed['top']==top,'Recovered WebSocket standings differ'
        result['stages'].append({'stage':label,'uniqueEvents':count,'snapshot':s})
        return s
    try:
        assert get(consumer+'/drain').get('drained'),'Pipeline must be drained'
        for index,role in enumerate(['ingest1','ingest2']):
            host,alias=HOSTS[role]
            command=['ssh','-T','-N','-i',str(ROOT/'cs6650-lab1-key.pem'),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10','-o','ExitOnForwardFailure=yes']
            if alias:command+=['-o','HostKeyAlias='+alias]
            command+=['-L',f'127.0.0.1:{19081+index}:127.0.0.1:8080','ec2-user@'+host]
            tunnels.append(subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL))
        time.sleep(1)
        assert all(p.poll() is None for p in tunnels),'Direct-ingest tunnel failed'
        s=get(consumer+f'/leaderboards/3?dayID={args.day}')
        assert s['processed']==0 and not s['top'],'Choose an unused resort/day'
        for url in urls:
            ws=WebSocket(url.replace('http://','ws://')+f'/leaderboard/3?dayID={args.day}')
            ws.wait(lambda x:x['processed']==0);sockets.append(ws)
        for i,(skier,lift) in enumerate([(11,21),(22,1),(11,40)]):assert submit(new(skier,lift),i%2)==201
        verify('initial',3)
        touched.add('consumer');print('Stopping consumer with durable data retained',flush=True);action('consumer','stop')
        for i in range(20):assert submit(new(100+i%5,i%40+1),i%2)==201
        for i,e in enumerate(events[:3]):assert submit(e,(i+1)%2)==201
        action('consumer','start')
        wait_for('Consumer readiness',lambda:get(consumer+'/metrics')['healthy'])
        verify('consumer restart, queued backlog and duplicate replay',23)
        touched.add('rabbitmq');print('Stopping broker; testing failed publish and reconnect',flush=True);action('rabbitmq','stop')
        wait_for('Producer detects broker outage',lambda:all(not get(url+'/metrics')['producerReady'] for url in urls))
        pending={'id':str(uuid.uuid4()),'skier':77,'lift':21,'time':99}
        code=submit(pending,0);result['brokerDownPublishStatus']=code;assert code==503
        action('rabbitmq','start')
        for url in urls:
            wait_for('Producer and backplane reconnect',lambda url=url:get(url+'/health')['producerReady'] and get(url+'/health')['backplaneReady'])
        wait_for('Consumer reconnect',lambda:get(consumer+'/metrics')['healthy'])
        events.append(pending);expected['77']+=lifts[21]
        assert submit(pending,1)==201
        assert submit(pending,0)==201
        assert submit(new(77,40),0)==201
        verify('broker restart and cross-ingest replay',25)
        result['functionalChecksPassed']=True
    except BaseException as error:
        result['failure']=type(error).__name__+': '+str(error)
        raise
    finally:
        for ws in sockets:ws.close()
        failures=[]
        for role in ['rabbitmq','consumer']:
            if role in touched:
                try:action(role,'start')
                except Exception as error:failures.append(role+': '+type(error).__name__)
        if not failures:
            try:
                wait_for('Restored pipeline drain',lambda:get(consumer+'/drain').get('drained'))
                for role in [k for k in HOSTS if k.startswith('ingest')]:
                    wait_for('Restored '+role,lambda role=role:json.loads(ssh(role,'curl -fsS --max-time 10 http://127.0.0.1:8080/health'))['status']=='UP')
            except Exception as error:failures.append('Readiness: '+type(error).__name__)
        for p in tunnels:
            p.terminate()
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:p.kill();p.wait()
        result['restoreErrors']=failures
        result['endUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        result['passed']=bool(result.get('functionalChecksPassed') and not failures)
        output.write_text(json.dumps(result,indent=2)+'\n')
        print(json.dumps({'passed':result['passed'],'evidence':str(output),'restoreErrors':failures},indent=2),flush=True)
        if failures:raise SystemExit(1)


if __name__=='__main__':main()
