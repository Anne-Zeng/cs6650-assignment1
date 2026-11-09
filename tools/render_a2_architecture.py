#!/usr/bin/env python3
"""Render the implemented architecture as a two-page course handout (ReportLab)."""
from pathlib import Path
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import letter
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.graphics.shapes import Drawing, Rect, String, Line, Polygon
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf/assignment2-architecture.pdf'
NAVY = colors.HexColor('#17324d')
BLUE = colors.HexColor('#e9f2fa')
TEAL = colors.HexColor('#dcf1ed')
GREY = colors.HexColor('#536578')


def arrow(drawing, x1, y1, x2, y2, color=NAVY):
    drawing.add(Line(x1, y1, x2, y2, strokeColor=color, strokeWidth=1))
    import math
    theta = math.atan2(y2-y1, x2-x1)
    a = theta+2.65; b = theta-2.65
    drawing.add(Polygon([x2,y2,x2+5*math.cos(a),y2+5*math.sin(a),
                         x2+5*math.cos(b),y2+5*math.sin(b)], fillColor=color, strokeColor=color))


def box(drawing, x, y, width, height, lines, fill=BLUE):
    drawing.add(Rect(x,y,width,height,rx=5,ry=5,fillColor=fill,strokeColor=colors.HexColor('#adc2d4')))
    for index, text in enumerate(lines):
        drawing.add(String(x+width/2, y+height/2+(len(lines)-1)*5-index*10,
                           text, textAnchor='middle',fontName='Helvetica',fontSize=8,fillColor=NAVY))


def architecture():
    d=Drawing(504,175)
    top=128; low=58
    for x,w,lines in [(0,62,['Load client']),(82,52,['AWS ALB']),
                      (154,104,['Ingest + WebSocket','EC2 x 1 / 2 / 4']),
                      (278,100,['skitrak.exchange','topic / resort.N']),
                      (398,106,['resort.1 ... .10','durable queues'])]:
        box(d,x,top,w,35,lines)
    for x1,x2 in [(62,82),(134,154),(258,278),(378,398)]: arrow(d,x1,145,x2,145)
    box(d,0,low,90,35,['Subscribers','local sockets'],TEAL)
    box(d,110,low,110,35,['Per-server queue','only local resorts'],TEAL)
    box(d,240,low,110,35,['leaderboard.topic','routing key lb.N'],TEAL)
    box(d,370,low,134,35,['Consumer EC2','10 ordered resort lanes'],TEAL)
    arrow(d,451,128,451,93)
    for x1,x2 in [(370,350),(240,220),(110,90)]: arrow(d,x1,75,x2,75)
    box(d,370,0,134,32,['SQLite WAL / persistent EBS','inbox + totals + outbox'])
    arrow(d,437,58,437,32)
    d.add(String(0,24,'Each WebSocket server binds only lb.N keys with local subscribers.',
                 fontSize=8,fillColor=GREY))
    d.add(String(0,10,'RabbitMQ and consumer run on separate EC2 instances.',fontSize=8,fillColor=GREY))
    return d


def sequence():
    d=Drawing(504,210)
    labels=['Client','Ingest','RabbitMQ','Consumer','SQLite','WS node']
    xs=[28,113,201,296,391,476]
    for x,label in zip(xs,labels):
        d.add(String(x,198,label,textAnchor='middle',fontSize=8,fillColor=NAVY,fontName='Helvetica-Bold'))
        d.add(Line(x,18,x,187,strokeColor=colors.HexColor('#bccad5'),strokeDashArray=[2,3]))
    flows=[(0,1,'POST + stable event UUID'),(1,2,'Persistent mandatory publish'),
           (2,1,'Confirm; then HTTP 201'),(2,3,'Ordered delivery / manual ACK'),
           (3,4,'Batch: inbox + totals + changed snapshot/outbox'),(4,3,'Durable batch commit'),
           (3,2,'Changed snapshot / lb.N; confirm'),(3,4,'Remove confirmed outbox prefix'),
           (3,2,'ACK original swipe'),(2,5,'Route to matching server queue'),
           (5,0,'Real versioned leaderboard over WebSocket')]
    for i,(source,target,label) in enumerate(flows):
        y=180-i*15
        arrow(d,xs[source],y,xs[target],y)
        mid=(xs[source]+xs[target])/2
        d.add(String(mid,y+3,label,textAnchor='middle',fontSize=6.5,fillColor=NAVY))
    return d


def main():
    OUT.parent.mkdir(parents=True,exist_ok=True)
    styles=getSampleStyleSheet()
    styles.add(ParagraphStyle(name='A2Title',fontName='Helvetica-Bold',fontSize=22,leading=25,textColor=NAVY,spaceAfter=8))
    styles.add(ParagraphStyle(name='A2Sub',fontSize=9,leading=12,textColor=GREY,spaceAfter=14))
    styles.add(ParagraphStyle(name='A2Head',fontName='Helvetica-Bold',fontSize=11,leading=14,textColor=NAVY,spaceBefore=11,spaceAfter=5))
    styles.add(ParagraphStyle(name='A2Body',fontSize=9,leading=12.3,textColor=colors.HexColor('#26384a'),spaceAfter=7,alignment=TA_LEFT))
    story=[]
    def p(text): story.append(Paragraph(text,styles['A2Body']))
    def h(text): story.append(Paragraph(text,styles['A2Head']))
    story.append(Paragraph('SkiTrak | Assignment 2',styles['A2Title']))
    story.append(Paragraph('Architecture and delivery logic  |  Java 11 / Tomcat 9  |  10 resorts / Top-20',styles['A2Sub']))
    story.append(architecture())
    h('Queue topology and connection locality')
    p('The ingest layer accepts swipes independently of where WebSocket sessions live. A separate RabbitMQ EC2 routes raw events through <b>skitrak.exchange</b> to ten durable resort queues. A separate consumer EC2 computes standings and publishes full snapshots to <b>leaderboard.topic</b>, using exact keys <b>lb.1 ... lb.10</b>. Each WebSocket server owns an exclusive generated queue and binds only resorts with local subscribers.')
    p('The topic backplane allows ingest/WebSocket nodes to scale independently of aggregation. Co-location would save a hop but couple resort routing, state ownership and node capacity. Selective topic bindings save uninterested traffic, at the cost of managing subscription transitions and bootstrap snapshots.')
    h('Subscription lifecycle and ordering')
    p('First subscription: install the resort binding, then fetch the authoritative snapshot. Bootstrap responses and live updates reconcile by persistent <b>(resort, day, version)</b>. Last unsubscribe removes the binding; stale bootstrap responses are ignored. Broker reconnect recreates the private queue, restores active bindings and refreshes snapshots. Periodic refresh repairs transient notification loss. Each session has one asynchronous send in flight and a bounded queue; slow sessions reconnect for a fresh snapshot.')
    h('Consumer threading and Top-N')
    p('One FIFO serial lane owns each resort. A shared executor yields between batches for fair scheduling. Automatic scaling is supported; fixed worker counts isolate the 5/10/15/20 sweep. Ten queues cap useful aggregation parallelism at ten. The instructor-endorsed sweep is <b>5/10/15/20</b>, with expected flattening beyond ten useful lanes. Retry stays within the same lane; invalid/conflicting events pause that partition rather than let later events overtake it.')
    p('All-skier totals plus a bounded ordered set maintain Top-20 incrementally. Scores sort descending with numeric skier-ID ties. A changed displayed score also triggers a broadcast, even when rank order is unchanged.')
    story.append(PageBreak())
    story.append(Paragraph('Delivery, recovery and verification',styles['A2Title']))
    story.append(Paragraph('Swipe-to-leaderboard sequence and operational guarantees',styles['A2Sub']))
    story.append(sequence())
    h('Reliable publication and durable aggregation')
    p('A client generates one UUID per logical swipe and preserves it through retries. Ingest validates the request, looks up the supplied official lift vertical, borrows a pre-created Channel exclusively, and returns 201 only after mandatory routing and publisher confirmation. One producer connection backs the bounded pool; one recovery owner replaces a failed connection and its channels. Old leases cannot re-enter a replacement pool. Unknown outcomes return 503 and retain the same event ID on retry.')
    p('Each resort uses SQLite WAL with FULL synchronization. Up to 64 events commit atomically per batch, with a 10ms partial-batch flush trigger. Inbox and totals always persist; snapshot/version and outbox change only when Top-N changes. All changed snapshots retain order; grouped publisher confirms precede source ACK (batch 16, prefetch 128). A confirmed outbox prefix is then deleted; a crash can repeat a snapshot without recounting the swipe. No matching notification queue is normal when nobody subscribes. Raw queues default to 24h TTL, capacity 100000 and reject-publish overflow; the operating depth target remains below 1000. Expired/dead-lettered events are not successful aggregation.')
    h('ALB and process recovery')
    p('ALB: round robin, generated-cookie stickiness=300s, <b>/health</b> at 30s intervals, 5s timeout, healthy=2, unhealthy=3, idle timeout=120s. ROOT WAR makes the health path literal. WebSocket Upgrade pins a live socket to its accepted node; node failure requires client reconnect and snapshot bootstrap. Docker/systemd restart processes. Broker and consumer access use private-network rules; management stays behind loopback/SSH tunneling.')
    h('Evidence and design boundaries')
    p('Completed: 61 local regression tests; real RabbitMQ duplicate/restart tests; EC2 500K runs at 1/2/4 ingests; four-node 1M stress; client 64/128/256/512 and consumer 5/10/15/20 sweeps. Functional tests verify official-table totals and real WebSocket snapshots. ALB probes show even distribution and cookie stickiness. A Python harness reconnects from failed ingest-3 to ingest-4 in 2.981s, then restores all four targets.')
    p('Four ingests / 128 client threads / 20 fixed workers: 500K completed, zero retries, sampled raw-queue total peak 253, mean consumer lag 25.61ms. At 512 threads / 1M: ready peak 417048, mean lag 52.68s; high-load targets were missed. Earlier EBS IOPS checks show storage pressure, not an exclusive cause. Lag means and sampled peaks are not per-event guarantees. State survives process restart on retained EBS; host/disk-loss HA is outside this single-broker/consumer design.')
    def footer(canvas,doc):
        canvas.setStrokeColor(colors.HexColor('#dbe4ec'));canvas.line(45,39,567,39)
        canvas.setFont('Helvetica',8);canvas.setFillColor(GREY)
        canvas.drawString(45,25,'CS6650 | Topic backplane with subscriber-aware routing')
        canvas.drawRightString(567,25,str(doc.page))
    SimpleDocTemplate(str(OUT),pagesize=letter,leftMargin=45,rightMargin=45,
                      topMargin=39,bottomMargin=48,title='SkiTrak Assignment 2 Architecture',
                      author='SkiTrak project').build(story,onFirstPage=footer,onLaterPages=footer)
    count=len(PdfReader(OUT).pages)
    if count != 2: raise RuntimeError(f'Expected exactly two architecture pages; got {count}')
    print(f'Created two-page architecture: {OUT}')


if __name__ == '__main__': main()
