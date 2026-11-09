#!/usr/bin/env python3
"""Repeat the two-ingest channel-pool comparison without replacing the original formal evidence."""
import concurrent.futures
import datetime
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from ec2_monitor import ROOT, HOSTS
from verify_ec2_failover import ssh
from verify_pipeline import http_json


def main():
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    session=ROOT/'results/a2-pool-repeat'/stamp
    session.mkdir(parents=True,exist_ok=False)
    (session.parent/'latest.json').write_text(json.dumps({'root':str(session)},indent=2)+'\n')
    print('Session: '+str(session),flush=True)
    status={'pid':os.getpid(),'state':'preflight'}
    child=tunnel=None
    restore_needed=False
    def save(state):
        status.update(state=state,updatedUTC=datetime.datetime.now(datetime.timezone.utc).isoformat())
        (session/'controller-status.json').write_text(json.dumps(status,indent=2)+'\n')
        print(state,flush=True)
    def interrupt(sig,frame):
        if child is not None and child.poll() is None:child.send_signal(signal.SIGINT)
        raise KeyboardInterrupt()
    signal.signal(signal.SIGINT,interrupt);signal.signal(signal.SIGTERM,interrupt)
    def run(label,cmd):
        nonlocal child
        save(label)
        child=subprocess.Popen(cmd,cwd=ROOT)
        try:
            code=child.wait()
            if code:raise RuntimeError(label+' failed with exit '+str(code))
        finally:
            if child.poll() is None:
                child.send_signal(signal.SIGINT)
                try:child.wait(timeout=35)
                except subprocess.TimeoutExpired:child.kill();child.wait()
            child=None
    try:
        save('Checking clocks and resource baseline')
        def check(role):
            return role,ssh(role,'date -u; chronyc tracking; uptime; df -h /; free -m')
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            baseline=dict(pool.map(check,['ingest1','ingest2','consumer','rabbitmq']))
        (session/'preflight-hosts.json').write_text(json.dumps(baseline,indent=2)+'\n')
        # Own this tunnel and terminate it after the sweep; never kill unrelated sessions.
        host,alias=HOSTS['consumer']
        command=['ssh','-T','-N','-i',str(ROOT/'cs6650-lab1-key.pem'),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','HostKeyAlias='+alias,'-o','ConnectTimeout=10','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=15','-L','127.0.0.1:18081:127.0.0.1:8081','ec2-user@'+host]
        tunnel=subprocess.Popen(command,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        time.sleep(1)
        if tunnel.poll() is not None:raise RuntimeError('Cannot establish dedicated Consumer metrics tunnel; check port 18081 before retrying')
        initial=http_json('http://127.0.0.1:18081/drain')[1]
        assert initial['drained'],'Pipeline not drained'
        assert initial['configuredThreads']==10,'Expected initial fixed workers=10'
        (session/'initial-consumer-state.json').write_text(json.dumps(initial,indent=2)+'\n')
        for n in [32,64]:
            restore_needed=True
            run('Setting channel pool='+str(n),['bash',str(ROOT/'deployment/set-ingest-pool.sh'),str(n)])
            run('Running 2 ingests / 500K / 512 client threads / pool '+str(n),
                [sys.executable,str(ROOT/'monitoring/run_fresh_phase.py'),'--ingests','2','--events','500000','--threads','512','--consumer-threads','10','--channel-pool-size',str(n),'--category','channel-pool','--session',str(session)])
        restore_needed=False
        final=http_json('http://127.0.0.1:18081/drain')[1]
        assert final['drained'] and final['processed']-initial['processed']==1000000,'Final 1M count reconciliation failed'
        (session/'final-consumer-state.json').write_text(json.dumps(final,indent=2)+'\n')
        save('complete; both pool runs reconciled; channel pools restored to 64')
    except BaseException as error:
        status['error']=type(error).__name__+': '+str(error);save('stopped on error');raise
    finally:
        if restore_needed:
            subprocess.run(['bash',str(ROOT/'deployment/set-ingest-pool.sh'),'64'],cwd=ROOT,check=False)
        if tunnel is not None and tunnel.poll() is None:
            tunnel.terminate()
            try:tunnel.wait(timeout=10)
            except subprocess.TimeoutExpired:tunnel.kill();tunnel.wait()

if __name__=='__main__':main()
