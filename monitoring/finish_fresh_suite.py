#!/usr/bin/env python3
"""Finish the authorized fresh suite once the four-ingest scaling phase ends.

Runs serially, stops on errors, and never changes ALB registrations or deletes
persistent state. Rebuilds the new report only; old reports stay untouched.
"""
import datetime
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from ec2_monitor import ROOT
from verify_pipeline import http_json

PYTHON='/private/tmp/skitrak-evidence-venv/bin/python'
ALB='http://skitrak-alb-1400622109.us-west-2.elb.amazonaws.com'


def main():
    session=Path(json.loads((ROOT/'results/a2-fresh/active.json').read_text())['root'])
    status={'pid':os.getpid(),'state':'waiting for four-ingest scaling','updatedUTC':None}
    child=None
    workers_changed=False
    def save():
        status['updatedUTC']=datetime.datetime.now(datetime.timezone.utc).isoformat()
        temp=session/'controller-status.tmp';temp.write_text(json.dumps(status,indent=2)+'\n');temp.replace(session/'controller-status.json')
    def interrupted(signum,frame):
        if child is not None and child.poll() is None:child.send_signal(signal.SIGINT)
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM,interrupted)
    signal.signal(signal.SIGINT,interrupted)
    def run(label,command,log=None):
        nonlocal child
        status['state']=label;status['command']=command;save();print(label,flush=True)
        stream=log.open('w') if log else None
        try:
            child=subprocess.Popen(command,cwd=ROOT,stdout=stream,stderr=subprocess.STDOUT if stream else None)
            code=child.wait()
            if code:raise RuntimeError(label+' exited '+str(code))
        finally:
            if child is not None and child.poll() is None:
                child.send_signal(signal.SIGINT)
                try:child.wait(timeout=90)
                except subprocess.TimeoutExpired:child.kill();child.wait()
            child=None
            if stream:stream.close()
    try:
        save()
        deadline=time.monotonic()+2400
        while time.monotonic()<deadline:
            manifests=[json.loads(p.read_text()) for p in session.glob('phase-4ingest-workers10/*/run.json')]
            scaling=[m for m in manifests if m['events']==500000 and m.get('category','scaling')=='scaling']
            if any(m.get('failure') for m in scaling):raise RuntimeError('Four-ingest scaling contains a failed attempt; inspect before continuing')
            complete={m['threads'] for m in scaling if m.get('evidenceCollectorsPassed') and m.get('clientExitCode')==0}
            if complete=={64,128,256,512}:break
            time.sleep(3)
        else:raise RuntimeError('Four-ingest phase did not finish within40minutes')
        run('Four-ingest1M stress test',[sys.executable,str(ROOT/'monitoring/run_fresh_phase.py'),'--ingests','4','--events','1000000','--threads','512'])
        for n in [5,10,15,20]:
            workers_changed=True
            run('Set fixed Consumer workers='+str(n),[sys.executable,str(ROOT/'monitoring/set_consumer_threads.py'),str(n)])
            run('Consumer sweep workers='+str(n),[sys.executable,str(ROOT/'monitoring/run_fresh_phase.py'),'--ingests','4','--events','500000','--threads','128','--consumer-threads',str(n),'--category','consumer-sweep'])
        run('Restore fixed Consumer workers=10',[sys.executable,str(ROOT/'monitoring/set_consumer_threads.py'),'10'])
        workers_changed=False
        verification=session/'verification';verification.mkdir(exist_ok=True)
        run('Fresh deterministic ALB pipeline verification',[sys.executable,str(ROOT/'monitoring/verify_pipeline.py'),'--ingest-url',ALB,'--consumer-url','http://127.0.0.1:18081','--lift-file',str(ROOT/'deployment/config/lift_vertical.json'),'--day','340','--output',str(verification/'pipeline.json')],verification/'pipeline.log')
        run('Fresh EC2 broker and Consumer restart verification',[sys.executable,str(ROOT/'monitoring/verify_ec2_recovery.py'),'--day','339','--output',str(verification/'ec2-recovery.json')],verification/'ec2-recovery.log')
        run('Fresh live WebSocket failover verification',[sys.executable,str(ROOT/'monitoring/verify_ec2_failover.py'),'--day','338','--output',str(verification/'websocket-failover.json')],verification/'websocket-failover.log')
        run('Fresh ALB distribution and cookie verification',[sys.executable,str(ROOT/'monitoring/verify_alb.py'),ALB,'--requests','200','--expected-targets','4','--cookie-file',str(verification/'alb-cookies.txt'),'--output',str(verification/'alb.json')],verification/'alb.log')
        final=http_json('http://127.0.0.1:18081/drain')[1]
        (session/'final-consumer-state.json').write_text(json.dumps(final,indent=2)+'\n')
        assert final.get('drained'),'Final pipeline must be drained'
        run('Build fresh report and all-run plots',[PYTHON,str(ROOT/'monitoring/build_fresh_report.py')])
        run('Render fresh report PDFs',[PYTHON,str(ROOT/'monitoring/render_fresh_pdf.py')])
        status['state']='suite complete; PDF visual QA and console-evidence review remain';status.pop('command',None);save()
    except BaseException as error:
        status['state']='stopped on error';status['error']=type(error).__name__+': '+str(error);save();raise
    finally:
        if workers_changed:
            subprocess.run([sys.executable,str(ROOT/'monitoring/set_consumer_threads.py'),'10'],cwd=ROOT,check=False)


if __name__=='__main__':main()
