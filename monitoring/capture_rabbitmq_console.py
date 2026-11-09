#!/usr/bin/env python3
"""Capture the actual RabbitMQ UI via a private SSH tunnel and ephemeral browser.

Credentials stay in process memory. No credentials or browser profiles are saved.
Create stop-capture in the output directory to take a final capture and exit.
"""
import argparse
import datetime
import json
import subprocess
import time
from pathlib import Path
from playwright.sync_api import sync_playwright
from ec2_monitor import HOSTS, ROOT


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True)
    parser.add_argument('--interval',type=int,default=45)
    parser.add_argument('--port',type=int,default=15673)
    args=parser.parse_args()
    out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
    host,alias=HOSTS['rabbitmq']
    options=['ssh','-T','-i',str(ROOT/'cs6650-lab1-key.pem'),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=10']
    if alias:options+=['-o','HostKeyAlias='+alias]
    result=subprocess.run(options+['ec2-user@'+host,'sudo -n cat /etc/skitrak/rabbitmq.env'],capture_output=True,text=True,check=True,timeout=20)
    env=dict(line.split('=',1) for line in result.stdout.splitlines() if '=' in line and not line.lstrip().startswith('#'))
    # A dedicated foreground tunnel is owned and terminated by this process.
    tunnel=subprocess.Popen(options+['-N','-o','ExitOnForwardFailure=yes','-L',f'127.0.0.1:{args.port}:127.0.0.1:15672','ec2-user@'+host],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    captures=[]
    try:
        time.sleep(1)
        if tunnel.poll() is not None:raise RuntimeError('RabbitMQ screenshot tunnel failed')
        with sync_playwright() as pw:
            browser=pw.chromium.launch(executable_path='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',headless=True)
            try:
                context=browser.new_context(viewport={'width':1500,'height':1100},timezone_id='UTC')
                page=context.new_page()
                page.goto(f'http://127.0.0.1:{args.port}/',wait_until='networkidle',timeout=30000)
                page.locator('input[name="username"]').fill(env['RABBITMQ_DEFAULT_USER'])
                page.locator('input[name="password"]').fill(env['RABBITMQ_DEFAULT_PASS'])
                page.locator('input[type="submit"]').click()
                page.get_by_text('Queued messages',exact=False).first.wait_for(timeout=20000)
                # RabbitMQ exposes chart range controls through actual UI links.
                range_links=page.get_by_text('last minute',exact=True)
                if range_links.count():
                    range_links.first.click()
                    choice=page.get_by_text('Last ten minutes',exact=False)
                    if not choice.count():choice=page.get_by_text('Ten minutes',exact=False)
                    if choice.count():choice.first.click()
                    else:page.keyboard.press('Escape')
                    close=page.get_by_text('Close',exact=True)
                    if close.count():close.first.click()
                    page.wait_for_timeout(6000)
                (out/'capture-ready.json').write_text(json.dumps({'ready':True,'timezone':'UTC','source':'Actual RabbitMQ Management Overview UI'}))
                started=time.monotonic();next_capture=0
                while time.monotonic()-started<1800:
                    stop=(out/'stop-capture').exists()
                    if stop or time.monotonic()>=next_capture:
                        if stop:
                            # Wait for broker statistics and UI caches after final drain.
                            page.wait_for_timeout(6000)
                            page.reload(wait_until='networkidle',timeout=20000)
                            page.get_by_text('Queued messages',exact=False).first.wait_for(timeout=10000)
                        page.keyboard.press('Escape')
                        stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
                        filename='rabbitmq-'+stamp+('-final' if stop else '')+'.png'
                        page.screenshot(path=str(out/filename),full_page=True)
                        captures.append({'file':filename,'capturedUTC':stamp,'afterClientExit':stop})
                        (out/'captures.json').write_text(json.dumps(captures,indent=2)+'\n')
                        next_capture=time.monotonic()+args.interval
                    if stop:break
                    page.wait_for_timeout(500)
            finally:browser.close()
    finally:
        tunnel.terminate()
        try:tunnel.wait(timeout=10)
        except subprocess.TimeoutExpired:tunnel.kill();tunnel.wait()


if __name__=='__main__':main()
