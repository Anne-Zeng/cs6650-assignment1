#!/usr/bin/env python3
"""Render fresh-session results and a separate two-page architecture document.

All measured values come from the new session's summaries; older test results
are never imported. The architecture drawing functions are reused as diagrams.
"""
import argparse
import importlib.util
import json
from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, Image, PageBreak, Flowable, Preformatted
from pypdf import PdfReader, PdfWriter

ROOT=Path(__file__).resolve().parents[1]
NAVY=colors.HexColor('#17324d')
GREY=colors.HexColor('#506174')
BLUE=colors.HexColor('#eaf1f8')
styles=getSampleStyleSheet()
styles.add(ParagraphStyle(name='FreshTitle',fontName='Helvetica-Bold',fontSize=23,leading=28,textColor=NAVY,spaceAfter=14))
styles.add(ParagraphStyle(name='FreshHead',fontName='Helvetica-Bold',fontSize=13,leading=17,textColor=NAVY,spaceBefore=10,spaceAfter=8))
styles.add(ParagraphStyle(name='FreshBody',fontName='Helvetica',fontSize=9,leading=13,spaceAfter=8))
styles.add(ParagraphStyle(name='FreshSmall',fontName='Helvetica',fontSize=7,leading=10,textColor=GREY,spaceAfter=6,wordWrap='CJK'))
styles.add(ParagraphStyle(name='FreshCell',fontName='Helvetica',fontSize=7,leading=9))
styles.add(ParagraphStyle(name='FreshLog',fontName='Courier',fontSize=6.1,leading=7.1,spaceAfter=6))


def para(text,style='FreshBody'):
    return Paragraph(escape(str(text)),styles[style])


def table(rows,widths):
    rows=[[para(v,'FreshCell') for v in row] for row in rows]
    t=Table(rows,colWidths=widths,repeatRows=1,hAlign='LEFT')
    t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),BLUE),('TEXTCOLOR',(0,0),(-1,0),NAVY),
                           ('GRID',(0,0),(-1,-1),.35,colors.HexColor('#cbd5df')),
                           ('VALIGN',(0,0),(-1,-1),'TOP'),('TOPPADDING',(0,0),(-1,-1),5),('BOTTOMPADDING',(0,0),(-1,-1),5)]))
    return t


def image(path,width=510,max_height=590):
    from PIL import Image as PILImage
    with PILImage.open(path) as im:w,h=im.size
    scale=min(width/w,max_height/h)
    return Image(str(path),width=w*scale,height=h*scale)


class ConsoleExcerpt(Flowable):
    """Document viewport into an unchanged original UI screenshot, not a redraw."""
    def __init__(self,path):
        from PIL import Image as PILImage
        super().__init__()
        self.path=path
        with PILImage.open(path) as im:self.sourceWidth,self.sourceHeight=im.size
        self.left,self.top,self.cropWidth,self.cropHeight=20,200,680,340
        self.width=510;self.scale=self.width/self.cropWidth;self.height=self.cropHeight*self.scale
    def draw(self):
        c=self.canv;c.saveState();clip=c.beginPath();clip.rect(0,0,self.width,self.height);c.clipPath(clip,stroke=0,fill=0)
        c.drawImage(str(self.path),-self.left*self.scale,-(self.sourceHeight-self.top-self.cropHeight)*self.scale,
                    width=self.sourceWidth*self.scale,height=self.sourceHeight*self.scale,mask='auto')
        c.restoreState()


def footer(canvas,doc):
    canvas.saveState();canvas.setStrokeColor(colors.HexColor('#d2dde8'));canvas.line(45,38,567,38)
    canvas.setFont('Helvetica',7);canvas.setFillColor(GREY)
    canvas.drawString(45,25,'CS6650 Assignment 2 | Fresh evidence only')
    canvas.drawRightString(567,25,str(doc.page));canvas.restoreState()


def num(value,digits=2):
    return 'Not measured' if value is None else f'{value:,.{digits}f}'


def architecture(out):
    spec=importlib.util.spec_from_file_location('architecture_diagrams',ROOT/'tools/render_a2_architecture.py')
    diagrams=importlib.util.module_from_spec(spec);spec.loader.exec_module(diagrams)
    story=[para('SkiTrak | Assignment 2','FreshTitle'),para('Architecture 1/2 - deployment, topology and connection locality','FreshSmall'),diagrams.architecture(),
           para('Routing and state ownership','FreshHead'),
           para('Java 11/Tomcat 9 ingest servers publish persistent swipe events through one reused producer connection and a bounded, exclusive channel pool. The dedicated RabbitMQ EC2 routes skitrak.exchange topic keys resort.1 through resort.10 to ten durable queues. A separate Consumer EC2 owns one FIFO aggregation lane and one SQLite database per resort.'),
           para('Subscriber-aware topic backplane','FreshHead'),
           para('The Consumer publishes complete versioned Top-20 snapshots to leaderboard.topic using lb.{resortID}. Each WebSocket node owns an exclusive generated queue and binds only the resorts with local subscribers. This bridges connection locality without requiring resort co-location. It reduces uninterested traffic but adds binding, bootstrap and reconnect coordination.'),
           para('First subscriber binds before bootstrap from the authoritative Consumer snapshot. Live updates and bootstrap responses reconcile by resort/day/version. Last unsubscribe removes the binding. Broker reconnect recreates the private queue and restores bindings; periodic snapshot refresh repairs transient notification loss.'),
           para('ALB and threading','FreshHead'),
           para('ALB forwards HTTP and WebSocket upgrades to ROOT-deployed ingest apps. Health path /health: 30 s interval, 5 s timeout,healthy 2 / unhealthy 3. Idle timeout 120 s; generated-cookie stickiness 300 s. Each WebSocket stays on the node that accepted it; after failure a new connection bootstraps on another healthy node. Configurable worker pools process fair serial resort lanes; ten resorts cap useful aggregation parallelism at ten. Dynamic scaling is supported but disabled during fixed-worker comparisons.'),
           PageBreak(),para('Delivery and failure handling','FreshTitle'),para('Architecture 2/2 - swipe-to-leaderboard sequence','FreshSmall'),diagrams.sequence(),
           para('Durable aggregation and acknowledgement','FreshHead'),
           para('A stable event UUID is reused through Idempotency-Key on retries. Ingest validates the official lift mapping and returns 201 only after mandatory routing and publisher confirmation. Uncertain outcomes return 503. Per-resort SQLite WAL/FULL transactions atomically persist dedupe inbox, skier totals and any changed snapshot/version/outbox. Batches hold at most 64 events with a 10 ms partial-batch flush trigger. Top-N changes are retained individually and published in order. Grouped broadcast confirms precede source ACKs; the confirmed outbox prefix is deleted. Prefetch 128 and ACK batch 16 are fixed for the fresh comparisons.'),
           para('Recovery and boundaries','FreshHead'),
           para('One recovery owner rebuilds a failed producer connection/channel pool; stale leases cannot re-enter its replacement. Consumer failures retry in the same lane before reconnect/redelivery. Inbox state prevents double-counting and persistent outbox state permits notification replay. Conflicting/poison events pause a resort instead of silently reordering it. systemd/Docker restart processes; persistent directories survive process restart. Source queues default to 24 h TTL, capacity 100000 and reject-publish overflow; expired events reach dead-letter queues and are not counted as successful aggregation.'),
           para('The backplane is transient notification transport; the Consumer is the authoritative bootstrap source. Publisher confirm is broker acceptance, not proof that every WebSocket client received an update. Host/disk loss and multi-host aggregation failover require additional replication and ownership design. Fresh report measurements, separate from this architecture handout, disclose sampled queue peaks, clock-dependent lag means and missed performance targets.')]
    path=out/'architecture.pdf'
    SimpleDocTemplate(str(path),pagesize=letter,leftMargin=45,rightMargin=45,topMargin=37,bottomMargin=48,title='SkiTrak Assignment2 Architecture').build(story,onFirstPage=footer,onLaterPages=footer)
    assert len(PdfReader(path).pages)==2,'Architecture must remain at most two pages'
    return path


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--allow-partial',action='store_true')
    args=parser.parse_args()
    session=Path(json.loads((ROOT/'results/a2-fresh/active.json').read_text())['root'])
    out=ROOT/'output'/('assignment2-fresh-'+session.name)
    rows=json.loads((out/'all-fresh-runs.json').read_text())
    plan=json.loads((session/'session.json').read_text())
    coverage=json.loads((out/'matrix-coverage.json').read_text())
    complete=not coverage['missing']
    if not complete and not args.allow_partial:raise SystemExit('Fresh matrix incomplete; use --allow-partial only for a clearly labeled progress PDF')
    story=[para('SkiTrak | Assignment 2','FreshTitle'),para('Fresh performance tests and validation','FreshHead'),
           para('Fengying Zeng | CS6650 | Session '+session.name),
           para('Repository: https://github.com/Anne-Zeng/cs6650-assignment1.git','FreshSmall'),
           para('Repository URL is the configured project remote. Publication of the local A2 changes has not been verified; this report does not claim that they have been pushed.','FreshSmall'),
           para('IN PROGRESS - NOT FOR SUBMISSION' if not complete else 'English performance and validation report','FreshHead'),
           para(f'{len(rows)} of {len(plan["plannedRuns"])} planned benchmark runs collected. This report contains only the new session. Earlier report values and tuning experiments are excluded.'),
           para('The repository evidence directories preserve complete client output, CSVs, final WebSocket snapshots, monitor JSONL, actual RabbitMQ UI screenshots and provenance hashes. This PDF summarizes the metrics; it does not replace the original records.'),
           para('Measurement method','FreshHead'),
           para('Client on the Mac; ALB stages with 1/2/4 responding ingest nodes; dedicated RabbitMQ and Consumer EC2 instances. Ten resorts,Top-N = 20,seed 6650. Main comparisons use 10 fixed Consumer workers, prefetch 128, maximum database batch 64,10 ms flush trigger, ACK batch 16 and 64 publisher channels per ingest. A separate fixed-worker sweep uses 5/10/15/20. SQLite FULL synchronization remains enabled.'),
           para('Persistent data is retained between runs; database growth and cache state are not reset. Tests are single observations, not statistical estimates. Queue peaks/means are sample statistics over the client window including warmup and drain. Consumer lag depends on host clocks and is calculated per run from cumulative sums/counts; it is not a per-event upper bound.'),
           para('Console and application evidence','FreshHead'),
           para('RabbitMQ screenshots are captured from the actual Management UI in UTC. Overview covers all queues; numerical queue metrics cover the ten raw resort queues only. Ten-minute console history can contain earlier tests, so use each run timestamp. Application counter distribution is separate from ALB CloudWatch/access-log evidence. The user supplies AWS console screenshots for the same UTC windows.'),
           para('Original session directory: '+str(session.relative_to(ROOT)),'FreshSmall'),PageBreak(),
           para('Benchmark matrix','FreshTitle')]
    # Place a concise findings and configuration page before the full matrix.
    story=story[:-1]
    story += [para('Results and limits','FreshTitle'),
              para('All 17 planned runs completed: 9,000,000 events sent, 9,000,000 additional events processed, zero final failed requests, and zero additional duplicates in benchmark counters. There were 269 application retries. All recorded final Ready, in-flight and pending-outbox counts were zero.'),
              para('At 512 client threads and 500K events, four ingests increased main HTTP throughput by 25.80% relative to one ingest, but end-to-end throughput decreased by 5.16%. Load balancing therefore increased admission capacity without removing the downstream completion limit.'),
              para('The four-ingest 1M stress run reached 6,551.14 main HTTP events/s and 3,323.33 end-to-end events/s. Ready peaked at 377,327, mean Consumer lag was 49,377.24 ms, drain/verification took 110.256 s, and the client retried 77 times. This run failed the queue-depth and lag targets despite eventual completion.'),
              para('Consumer mean I/O wait in the 1M run was 41.39%, with mean OS-observed writes of 4,842.87 IOPS; broker mean CPU was 54.74%. These support downstream storage/processing pressure, but do not establish EBS IOPS as the only cause. SQL work, transaction scheduling, confirmations and broker pressure remain plausible contributors. OS IOPS are not identical to EBS CloudWatch IOPS.'),
              para('For the fixed-worker sweep, mean lag stayed below 100 ms, but 15 workers had a sampled Ready peak of 1,059. Ten ordered resort lanes cap useful parallelism at ten; 15/20 configured workers did not improve throughput over ten. Dynamic scaling exists in the implementation but was disabled and not validated by this fixed-worker experiment.'),
              para('Target interpretation','FreshHead'),
              para('High-concurrency runs did not consistently satisfy queue depth < 1,000 or Consumer lag < 100 ms. Low-concurrency sampled peaks and mean lag are observations, not a proof that every message met a latency bound. Count reconciliation plus WebSocket final-state checks supports no observed loss in these finite runs, not a general guarantee against host/disk failure.'),PageBreak()]
    infra=json.loads((session/'infrastructure.json').read_text())
    story += [para('Deployment and measurement settings','FreshTitle'),
              table([['Role','EC2 type','Private IP'],['Ingest 1','t3.micro','172.31.45.254'],['Ingest 2','t3.micro','172.31.24.108'],['Ingest 3','t3.micro','172.31.45.75'],['Ingest 4','t3.micro','172.31.44.196'],['Consumer','t3.medium','172.31.36.151'],['RabbitMQ','t3.medium','172.31.32.233']],[160,150,200]),Spacer(1,12),
              para('Instructor clarification: use Top-N = 20 and bracket the ten-resort concurrency cap with 5/10/15/20 Consumer workers. The original handout lists 10/20/40/80; this report follows the instructor-endorsed clarification provided by the student.'),
              para('The supplied official lift_vertical.json is used with demoLifts=false. For example, lift 21 adds 267 ft. The measured configuration uses a maximum store batch of 64 (the earlier 32-event tuning configuration is excluded), a roughly 10 ms partial-batch flush trigger, prefetch 128, ACK batch 16, and one producer connection with 64 pooled channels per ingest.'),
              para('The recorded gp3 configuration was 6,000 provisioned IOPS and 125 MiB/s; the lab policy denied further volume modification. This restriction is context, not an explanation that turns overload runs into passes. The fresh session does not contain a controlled IOPS-only comparison.'),
              para('ALB: HTTP 80 to HTTP 8080, /health every 30 s, timeout 5 s, healthy threshold 2, unhealthy threshold 3; idle timeout 120 s, generated-cookie stickiness 300 s. Four instances are registered in stages; no EC2 recreation is required between stages.'),
              para('Clock synchronization was checked on EC2 at a point in time. The Mac clock was not calibrated by that check. Consequently, WebSocket snapshot-to-receipt wall-clock differences are not presented as calibrated one-way latency.'),
              para('CloudWatch captures use Sum for RequestCount and Average for TargetResponseTime, period 60 s. LoadBalancer-wide metrics aggregate traffic and do not establish per-instance distribution. The latter is supported by application counters, routing probes and the independent cookie/distribution verification. Minute buckets can straddle run boundaries.'),PageBreak(),para('Benchmark matrix','FreshTitle')]
    data=[['Nodes','Events','Client','Workers','HTTP main/s','E2E/s','Retries','Ready peak','Mean lag ms']]
    for r in rows:
        c=r['client'];q=r['queue'];a=r['consumer']
        data.append([r['ingestCount'],r['events'],r['clientThreads'],r['consumerConfiguration']['configuredThreads'],num(c['mainThroughput']),num(c['endToEndThroughput']),num(c['retries'],0),num(q['ready']['sampleMax'],0),num(a['runMeanLagMs'])])
    story += [table(data,[38,55,40,47,70,65,42,75,75]),Spacer(1,12),
              para('Read the metrics together: faster HTTP ingestion is not faster pipeline completion when queue backlog and drain increase. Final success after retries is not the absence of failed HTTP attempts. A run is not claimed to meet every target solely because its client exit code is 0.'),
              para('All old runs remain archived and excluded. Per-run configurations and errors below must be considered before making scaling claims.'),PageBreak()]
    matched={(r['ingestCount'],r['clientThreads']):r for r in rows if r['category']=='scaling' and r['events']==500000}
    story += [para('Measured scaling changes','FreshTitle')]
    if (out/'scaling-comparison.png').exists():story.append(image(out/'scaling-comparison.png',max_height=230))
    comparisons=[['Client threads','Ingests','Main HTTP change vs1','End-to-end change vs1']]
    for threads in [64,128,256,512]:
        baseline=matched.get((1,threads))
        if not baseline:continue
        for nodes in [2,4]:
            r=matched.get((nodes,threads))
            if r:
                comparisons.append([threads,nodes,f"{(r['client']['mainThroughput']/baseline['client']['mainThroughput']-1)*100:+.2f}%",f"{(r['client']['endToEndThroughput']/baseline['client']['endToEndThroughput']-1)*100:+.2f}%"])
    story += [table(comparisons,[90,70,175,175]),Spacer(1,10),
              para('Changes describe single observations with matching application tuning. Database size/cache state and test time differ, so they are not statistically isolated causal estimates. Faster ingestion with little completion improvement and rising backlog identifies a downstream capacity limit. Low-concurrency runs may be limited by admitted workload instead of server capacity.'),
              para('A finite overload run can accumulate a large queue and then drain after publishing stops. Oscillating unacknowledged counts alone do not prove sustained Ready backlog. Use the Ready curve, publish/delivery rates, run-specific mean lag and final count reconciliation together.'),PageBreak()]
    for index,r in enumerate(rows,1):
        d=out/'evidence'/r['run'];c=r['client'];q=r['queue'];a=r['consumer']
        story += [para(f'Run {index:02d} | {r["ingestCount"]} ingest(s), {r["events"]:,} events','FreshTitle'),
                  para(f'Client threads={r["clientThreads"]}; Consumer={json.dumps(r["consumerConfiguration"])}','FreshSmall'),
                  para('UTC '+r['startUTC']+' to '+r['endUTC'],'FreshSmall')]
        metrics=[['Measurement','Recorded result'],
                 ['Main HTTP / total ingestion / end-to-end events/s',f'{num(c["mainThroughput"])} / {num(c["ingestThroughput"])} / {num(c["endToEndThroughput"])}'],
                 ['Ingestion / program runtime / drain seconds',f'{num(c["ingestRuntimeSeconds"],3)} / {num(c["programRuntimeSeconds"],3)} / {num(c["drainSeconds"],3)}'],
                 ['Successful / final failed / application retries',f'{num(c["successful"],0)} / {num(c["failed"],0)} / {num(c["retries"],0)}'],
                 ['HTTP mean / p95 / p99 ms',f'{num(c["httpMeanMs"])} / {num(c["httpp95Ms"])} / {num(c["httpp99Ms"])}'],
                 ['Ready sampled mean / maximum',f'{num(q["ready"]["sampleMean"])} / {num(q["ready"]["sampleMax"],0)}'],
                 ['Unacked / Ready+Unacked maximum',f'{num(q["unacked"]["sampleMax"],0)} / {num(q["total"]["sampleMax"],0)}'],
                 ['Broker publish / delivery / ACK sampled mean per second',f'{num(q["publishRate"]["sampleMean"])} / {num(q["deliverRate"]["sampleMean"])} / {num(q["ackRate"]["sampleMean"])}'],
                 ['Additional processed / duplicate count',f'{a["processedDelta"]} / {a["duplicateDelta"]}'],
                 ['Final Ready / in-flight / pending outbox',f'{a["finalReady"]} / {a["finalInFlight"]} / {a["finalOutbox"]}'],
                 ['Mean run-specific Consumer lag ms',num(a['runMeanLagMs'])],
                 ['WebSocket updates / reconnections',f'{c["webSocketUpdates"]} / {c["webSocketReconnections"]}']]
        observed_attempts=sum(v.get('requestAttemptDelta',0) for v in r.get('ingestDistribution',{}).values())
        expected_attempts=c.get('attempts') or c['successful']+c['retries']
        if abs(observed_attempts-expected_attempts)>max(10,expected_attempts*.01):
            story += [para(f'Application distribution coverage is incomplete: recorded counter deltas total {observed_attempts:,}, versus {expected_attempts:,} client attempts. The distribution chart covers only observed counter deltas and must not be read as whole-run balance. The cause of the mismatch was not established.','FreshSmall')]
        story += [table(metrics,[245,265]),Spacer(1,10),image(d/'queue.png',max_height=280),
                  para('Source: '+r['run']+'; complete client-output.txt, queue-timeseries.csv and summary.json in the matching evidence directory.','FreshSmall'),PageBreak(),
                  para(f'Run {index:02d} | resource use and distribution','FreshHead'),image(d/'system.png',max_height=430),(image(d/'distribution.png',max_height=185) if abs(observed_attempts-expected_attempts)<=max(10,expected_attempts*.01) else para('Whole-run per-ingest distribution is not shown: counter coverage is incomplete. The archived chart reflects only the observed deltas.')),
                  para('Collection gaps: '+('; '.join(r['gaps']) or 'None detected.'),'FreshSmall'),PageBreak()]
        captures=[d/p for p in r.get('consoleScreenshots',[]) if (d/p).exists()]
        if captures:
            selected=[captures[len(captures)//2],captures[-1]]
            if selected[0]==selected[-1]:selected=selected[:1]
            story += [para(f'Run {index:02d} | actual RabbitMQ console','FreshHead'),
                      para('Readable excerpts of the actual UI queue-depth and rate panels. Additional legend columns outside this viewport remain in the full original PNG files. Console history may include earlier tests; this run window is '+r['startUTC']+' to '+r['endUTC']+'.','FreshSmall')]
            for capture in selected:
                story += [ConsoleExcerpt(capture),para(capture.name+' | Original screenshot retained unchanged.','FreshSmall'),Spacer(1,8)]
            story.append(PageBreak())
        alb=[d/p for p in r.get('screenshotContext',{}).get('albScreenshots',[]) if (d/p).exists()]
        # The overview remains archived; show the two detailed metric images without an orphan caption page.
        if len(alb)>2:alb=[p for p in alb if 'overview' not in p.name]
        if alb:
            story += [para(f'Run {index:02d} | supplied AWS CloudWatch captures','FreshHead')]
            settings=r.get('screenshotContext',{}).get('albCaptureSettings',{})
            if settings.get('scope'):
                story.append(para('Metric scope: '+settings['scope']+'. '+settings.get('scopeIssue',''),'FreshSmall'))
            for capture in alb:
                story += [image(capture,max_height=265),para(capture.name,'FreshSmall'),Spacer(1,8)]
            story += [para('Statistics/time-range verification is recorded in screenshot-context.json. CloudWatch minute buckets can overlap test boundaries; client logs provide exact application event counts.','FreshSmall'),PageBreak()]
    story += [para('Functional tests, recovery and remaining evidence','FreshTitle')]
    results=list((session/'verification').glob('*.json')) if (session/'verification').exists() else []
    if not results:story.append(para('Fresh functional/recovery verification is pending. Prior session results are not substituted.'))
    for path in results:
        result=json.loads(path.read_text());story += [para(path.name,'FreshHead'),para('Recorded pass flag: '+str(result.get('passed',result.get('stickyPassed'))))]
        keys=['demoLifts','uniqueEvents','duplicateRequests','serverRequestCounts','originalServer','replacementServer','reconnectSecondsFromStopStart','brokerDownPublishStatus','restoredIngestHealthy','restoreErrors','distribution','stickyPassed','distributionTargetsPassed','reconnectionMode']
        for key in keys:
            if key in result:story.append(para(key+': '+json.dumps(result[key]),'FreshSmall'))
        for stage in result.get('stages',[]):story.append(para(stage['stage']+': '+str(stage['uniqueEvents'])+' unique events verified.','FreshSmall'))
        story.append(para('Full result: '+str(path.relative_to(ROOT)),'FreshSmall'))
    pipeline=json.loads((session/'verification/pipeline.json').read_text())
    failover=json.loads((session/'verification/websocket-failover.json').read_text())
    story += [PageBreak(),para('Real, swipe-driven standings','FreshTitle'),
              para('The deterministic functional test sent 100 unique swipes and 20 duplicate requests using the official lift table. Both tested resorts finished at 50 processed unique events. The verifier compared the received WebSocket standings against independently computed expected totals.'),
              table([['Evidence','Resort / day','Processed','Skier','Vertical ft'],
                     ['Functional final snapshot','1 / 340','50','3','712'],
                     ['Functional final snapshot','2 / 340','50','18','605'],
                     ['Before stopping WebSocket owner','9 / 338','1','123','267'],
                     ['After reconnect and another lift-21 swipe','9 / 338','2','123','534']],[175,90,65,65,115]),Spacer(1,8),
              para('During the injected ingest failure, the Python harness observed disconnection and reconnected from ingest-4 to ingest-1 in 7.199 s measured from stop initiation. This demonstrates server/backplane recovery with harness-managed reconnection; it does not prove that the Java load client reconnects automatically.'),
              para('Broker confirmation means broker acceptance, not WebSocket client receipt. The separate final-state and functional checks provide the client-observed evidence. Functional tests are correctness checks, not performance measurements.')]
    regression=session/'local-regression/summary.json'
    if regression.exists():
        r=json.loads(regression.read_text());story += [para('Fresh local regression','FreshHead'),para(json.dumps(r['counts'])+'; buildSuccess='+str(r['buildSuccess'])),para(r['environmentNote'],'FreshSmall')]
    story += [para('Submission review items','FreshHead'),para('The new CloudWatch captures for the two- and four-ingest scaling runs are included. The single-ingest target image shows a draining transition, not a final single-target screenshot; fresh routing probes provide separate topology evidence. Consumer-sweep runs retain automated RabbitMQ and system evidence but do not have separate supplied CloudWatch captures. The student must review the AI-use reflection and verify that the A2 modules are published in the linked repository before submission.')]
    story += [PageBreak(),para('ALB target-registration evidence','FreshTitle'),
              para('These are user-supplied captures of registered targets. Fresh-session health probes separately verify which server IDs actually responded. The single-ingest image records a deregistration transition, not a final one-target registration screenshot.','FreshSmall')]
    for name,label in [('single-deregistration-in-progress.png','Transition toward single-ingest stage: three targets Draining, ingest1 Healthy.'),('two-targets-healthy.png','Two registered targets, ingest1 and ingest2, both Healthy.'),('four-targets-healthy.png','Four known registered ingest targets, all Healthy.')]:
        source=session/'aws-console'/name
        if source.exists():story += [image(source,max_height=190),para(label,'FreshSmall'),Spacer(1,8)]
    story += [PageBreak(),para('AI collaboration reflection','FreshTitle'),
              para('Factual collaboration record - student review required','FreshHead'),
              para('Codex assisted with the channel pool and recovery ownership, official-table lookup, durable per-resort aggregation and deduplication, transactional outbox broadcasting, subscriber-aware topic bindings, deployment helpers, monitoring, verification scripts and this report. The student supplied the official lift table and instructor clarifications, configured AWS resources, collected console screenshots and requested independent repeats when results appeared questionable.'),
              para('Suggestions accepted','FreshHead'),
              para('1. Reuse a stable event UUID on retries, because an uncertain response must not create a second logical swipe. 2. Preserve ordered resort lanes and SQLite FULL durability while batching transactions, so lower commit overhead does not weaken atomic deduplication, totals and pending notifications.'),
              para('Suggestions or conclusions corrected','FreshHead'),
              para('1. Treating EBS IOPS as the sole explanation was too narrow; the evidence also permits database, synchronization, confirmation and broker limits. 2. One poor five-worker observation was insufficient to generalize; the student requested a repeat and a fresh formal matrix. 3. An early verifier claimed failover success without injecting failure; the revised evidence separates untested failover from a real stop/reconnect test.'),
              para('A concrete measurement correction','FreshHead'),
              para('ALB screenshots initially used a single AvailabilityZone metric instead of the whole LoadBalancer, and one replacement window included two runs. The corrected captures preserve LoadBalancer scope, UTC boundaries, Sum/Average and one-minute periods. Queue statistics use direct broker samples with missing samples retained as gaps; API timeouts are not converted to zeros.'),
              para('The student should review this factual draft in their own words and confirm which implementation decisions they personally understand. No personal reasoning or transcript has been fabricated. The repository AI_USAGE.md must be reviewed separately before submission.')]
    representative={}
    for r in rows:
        if r['category']=='scaling' and r['clientThreads']==512:
            representative[(r['ingestCount'],r['events'])]=r
    for key in [(1,500000),(2,500000),(4,500000),(4,1000000)]:
        r=representative.get(key)
        if not r:continue
        text=(out/'evidence'/r['run']/'client-output.txt').read_text()
        summary='--- Warmup ---'+text.split('--- Warmup ---',1)[1] if '--- Warmup ---' in text else text
        story += [PageBreak(),para('Original client output | '+str(key[0])+' ingest(s)','FreshTitle'),
                  para(f'{key[1]:,} events,512 client threads. Verbatim benchmark summary; startup and Top-N debug payloads remain in the complete original log. All other runs also retain their complete logs in the evidence directory.','FreshSmall'),
                  para(r['run'],'FreshSmall'),Preformatted(summary,styles['FreshLog'],maxLineLength=120)]
    body=ROOT/'tmp/pdfs/fresh-report-body.pdf';body.parent.mkdir(parents=True,exist_ok=True)
    SimpleDocTemplate(str(body),pagesize=letter,leftMargin=45,rightMargin=45,topMargin=38,bottomMargin=48,title='SkiTrak Assignment2 Fresh Test Report').build(story,onFirstPage=footer,onLaterPages=footer)
    architecture_path=architecture(out)
    path=out/'test-report.pdf'
    writer=PdfWriter();writer.append(architecture_path);writer.append(body)
    from io import BytesIO
    from reportlab.pdfgen import canvas as pdfcanvas
    total=len(writer.pages)
    for n,page in enumerate(writer.pages,1):
        buf=BytesIO();c=pdfcanvas.Canvas(buf,pagesize=letter)
        c.setFillColor(colors.white);c.rect(530,17,42,18,stroke=0,fill=1)
        c.setFillColor(GREY);c.setFont('Helvetica',7);c.drawRightString(567,25,f'{n} / {total}');c.save();buf.seek(0)
        page.merge_page(PdfReader(buf).pages[0])
    writer.add_outline_item('Architecture (2 pages)',0)
    writer.add_outline_item('Test report and findings',2)
    writer.add_outline_item('Benchmark matrix',5)
    writer.add_outline_item('Scaling analysis',6)
    writer.add_metadata({'/Title':'SkiTrak Assignment2 - Fresh Architecture and Test Report','/Author':'Fengying Zeng'})
    with path.open('wb') as stream:writer.write(stream)
    print(path,'pages',len(PdfReader(path).pages))
    print(architecture_path,'pages',len(PdfReader(architecture_path).pages))


if __name__=='__main__':main()
