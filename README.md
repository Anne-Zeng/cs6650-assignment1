# CS6650 Assignment 1 — SkiTrak

A Tomcat-based swipe-ingest server and multithreaded Java load clients.

## Project structure

- `server/`: HTTP ingest, health check, and synthetic WebSocket leaderboard,
  packaged in one WAR.
- `client-part1/`: load generation, retries, and WebSocket subscriptions.
- `client-part2/`: extended client with CSV output and performance statistics.
- `results/ec2/`: EC2 test logs and throughput chart.
- `results/screenshots/`: deployment and execution screenshots.
- `lab2/`: earlier lab work.

## Environment

- Server: Amazon Linux 2023, Java 11, Tomcat 9.0.121, EC2 in us-west-2.
- Earlier client performance tests: macOS, JDK 22.
- Apache migration: also compiled and tested on Amazon Corretto 11.0.32.1;
  use Java 11 for subsequent course runs.
- Maven compiler target: Java 11.
- Python and matplotlib are used to generate throughput charts.

## Build

Run `mvn clean package` separately in:

- `server`
- `client-part1`
- `client-part2`

The server build produces `server/target/ski-servlet.war`.

## Deploy the server

Copy `ski-servlet.war` to the EC2 Tomcat deployment directory:

`/opt/tomcat9/webapps/ski-servlet.war`

Start Tomcat if it is not running:

```bash
/opt/tomcat9/bin/startup.sh
```

Check deployment logs:

```bash
tail -40 /opt/tomcat9/logs/catalina.out
```

Verify the health endpoint:

```bash
curl -i http://localhost:8080/ski-servlet/health
```

Expected response: HTTP 200 with `{"status":"UP"}`.

## Endpoints

All endpoints are hosted by the same WAR under `/ski-servlet`.

- `POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}`
  validates a swipe and returns HTTP 201 on success.
- `GET /health` returns server status.
- WebSocket `/leaderboard/{resortID}` pushes synthetic rankings approximately
  every 500 milliseconds.

The server does not persist swipes or compute real rankings.

## Run the clients

Use Java 11. Open `client-part1` or `client-part2` as a Maven project
in IntelliJ and reload Maven.

In Run → Edit Configurations → LoadClient:

- Select Java 11 as the runtime.
- Set the working directory to the selected client module.
- Enter the following in Program arguments:

```text
http://<EC2_PUBLIC_IP>:8080/ski-servlet 500000 128 1000 10
```

Replace `<EC2_PUBLIC_IP>` with the instance's current public IP.
Keep Tomcat running on EC2 throughout the test.

Arguments, in order:

| Argument | Meaning | Default |
|---|---|---|
| HTTP base URL | Server address including context path | Address in source; pass explicitly |
| Total events | Includes warmup and main phases | 500000 |
| Main threads | Sender threads after warmup | 128 |
| Warmup events per thread | Events sent by each of 32 warmup threads | 1000 |
| Resort count | Generate events and subscribe to resorts 1 through this number | 10 |

For the full assignment run, warmup sends 32 × 1000 = 32000 events.
After all warmup workers finish, the main phase sends the remaining
468000 events. Warmup and main times are reported separately.

For a small configuration check with two resorts:

```text
http://<EC2_PUBLIC_IP>:8080/ski-servlet 5000 32 10 2
```

This sends 320 warmup events and 4680 main-phase events.
It is a functional check, not the full assignment performance run.

### Event generation and connections

One dedicated generator thread fills a thread-safe queue before senders
start. Senders therefore never wait for event generation. Generation time
is reported separately. This approach stores all events in memory, so
queue memory grows with the event count.

HTTP requests use Apache HttpClient 5.6.4. All sender threads and both
phases share one connection pool, with total and per-route capacity
`max(32, mainThreads)`.

Pool-acquisition timeout is 5 seconds, TCP-connect timeout is 5 seconds,
and response/socket-read timeout is 10 seconds. These are separate
timeouts, not one overall request deadline.

Automatic library retries are disabled. The application allows one
initial attempt and up to five retries, with backoff delays of
100, 200, 400, 800, and 1600 milliseconds. Final success and failure
counts refer to logical events.

WebSocket subscriptions use Java's HttpClient. The WebSocket base URL
is derived from the HTTP base URL. One persistent subscription is opened
per configured resort before ingest begins.

Dropped subscriptions reconnect with exponential backoff capped at
16 seconds. Subscriptions are closed when the run finishes.
HTTP and WebSocket connection statistics are reported separately.

## Performance measurement

Part 2 writes timestamped CSV files into its `results/` directory.

CSV columns:

```text
timestamp,requestType,latency,statusCode,resortID,responseTimestamp
```

- Each row represents one application-level HTTP attempt.
- Latency is in milliseconds, measured with `System.nanoTime()`.
- Status code `0` means no HTTP response was obtained.
- HTTP latency statistics include warmup and main-phase attempts.
- Retry backoff is excluded from individual attempt latency.
- Overall ingest throughput is successful events divided by ingest duration.
- CSV export occurs after the ingest timing interval.

WebSocket push latency is calculated as client receipt time minus the
message's server timestamp. It includes cross-machine clock offset.
Negative values were retained and must not be interpreted as negative
network transit times.

## Generate the throughput chart

Run these commands from the repository root. A Python environment with
matplotlib is required.

Create the environment and install matplotlib if not already available:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install matplotlib
```

Generate the chart from the recorded Apache/Java 11 Part 2 run:

```bash
.venv/bin/python client-part2/plot_throughput.py \
  client-part2/results/swipes-1792110301816.csv
```

The output is:

```text
client-part2/results/swipes-1792110301816-throughput.png
```

The chart counts successful HTTP responses in 10-second buckets, using
response completion timestamps. It includes warmup and main phases.
The final partial bucket uses its actual duration.

This reads existing CSV data and does not send requests to the server.

## EC2 results — Apache HttpClient with Java 11

These runs used Amazon Corretto 11 and Apache HttpClient 5.6.4.
The client ran on macOS and sent requests to Tomcat 9 on EC2.
Each run used 32 warmup threads, 1000 events per warmup thread,
128 main-phase threads, and 10 WebSocket subscriptions.

Both clients completed all 500000 events with zero final failures
and zero application retries.

| Metric | Client Part 1 | Client Part 2 |
|---|---:|---:|
| Warmup successful events | 32000 | 32000 |
| Warmup runtime (s) | 34.639 | 35.287 |
| Main successful events | 468000 | 468000 |
| Main runtime (s) | 128.402 | 135.665 |
| Total ingest runtime (s) | 163.041 | 170.952 |
| Overall throughput (events/s) | 3066.71 | 2924.80 |
| Main-phase throughput (events/s) | 3644.80 | 3449.67 |
| TCP connections established | 5059 | 5058 |
| Application retries | 0 | 0 |
| WebSocket updates | 3260 | 3430 |
| WebSocket reconnections | 0 | 0 |
| Total program runtime (s) | 165.222 | 173.847 |

Overall throughput includes warmup. Main-phase throughput is calculated
as 468000 divided by main-phase runtime.

The TCP connection count is cumulative across the run; it is not the
number of simultaneous connections or the number of failures.
The configured HTTP pool capacity was 128.

### HTTP latency — Client Part 2

These statistics include all 500000 HTTP attempts across warmup and main.

| Metric | Latency (ms) |
|---|---:|
| Mean | 36.939 |
| Median | 31.651 |
| p95 | 77.010 |
| p99 | 122.782 |
| Minimum | 11.612 |
| Maximum | 586.850 |

HTTP elapsed time is measured with System.nanoTime(), so it does not
depend on clock synchronization between the client and server.

### WebSocket push latency and clock offset

Push latency is calculated as client receipt time minus serverTimestamp.

| Metric | Client Part 1 (ms) | Client Part 2 (ms) |
|---|---:|---:|
| Mean | -72.903 | -90.872 |
| Median | -76.475 | -97.819 |
| p95 | -57.196 | -37.446 |
| p99 | 6.322 | -10.490 |

These measurements use clocks on two different machines. The negative
values demonstrate that clock offset affects the results. They were
retained without clamping or correction and must not be interpreted as
negative network transit time.

The raw measurements satisfy receiveTime minus serverTimestamp, but
they cannot establish accurate absolute one-way network latency.

### Little's Law prediction and comparison

The sequential Apache client measured:

- Fresh-client request, including connection and startup overhead:
  295.246 ms.
- Mean pooled request latency over 100 measured requests: 27.444 ms.
- Measured sequential throughput: 36.44 events/s.

The fresh request includes more than TCP handshake time, so its entire
duration is not treated as a pure connection-establishment cost.

For synchronous senders with one outstanding request per thread,
Little's Law gives the planning estimate:

throughput ≈ concurrent requests / mean response time

Using the pooled latency:

128 / 0.027444 ≈ 4664 events/s

The sequential program reported 4664.03 events/s using its unrounded
measurement.

Client Part 1 achieved approximately 3644.80 events/s during the main
phase, about 78.1% of the prediction. Client Part 2 achieved approximately
3449.67 events/s, about 74.0%.

The comparison uses main-phase throughput because the prediction assumes
128 concurrent senders, whereas warmup uses only 32.

This prediction assumes that the sequential response time remains
representative under concurrency. In practice, contention, scheduling,
network variability, connection replacement, and client processing can
increase request-cycle time. Part 2 also records per-attempt metrics.
These are possible contributors; the runs do not isolate their
individual costs.

128 threads was selected based on earlier JDK HttpClient comparisons.
The Apache version has not yet been tested across a full thread-count
sweep, so 128 is not claimed to be its proven optimal configuration.

### Evidence and version scope

Formal performance logs:

- results/ec2/apache5-java11-part1-128threads.txt
- results/ec2/apache5-java11-part2-128threads.txt

Client Part 2 raw data:

- client-part2/results/swipes-1792110301816.csv
- client-part2/results/pushes-1792110301816.csv

Client Part 1 raw push data:

- client-part1/results/pushes-1792111343814.csv

The full performance runs preceded the final resort-count configuration
change and the final server redeployment. Subsequent 5000-event runs
verified the configurable resort count and the Part 1 connection-time
summary. After redeployment, health, selected POST boundary cases, and
WebSocket synthetic pushes were verified separately.

The full performance runs have not been repeated against the final
redeployed WAR. They are retained as measured results of the earlier
deployment, rather than presented as a new benchmark of that WAR.

## EC2 results — pre-migration JDK HttpClient baseline

All runs below completed 500,000 events successfully using the earlier JDK
HttpClient transport. They are NOT measurements of the Apache version. Keep
these logs/CSVs as baseline evidence; remeasure before reporting Apache results.

| Client | Main threads | Ingest runtime (s) | Overall throughput (swipes/s) |
|---|---:|---:|---:|
| Part 2 | 32 | 487.270 | 1026.12 |
| Part 2 | 64 | 303.201 | 1649.07 |
| Part 2 | 128 | 192.272 | 2600.48 |
| Part 1 | 128 | 176.745 | 2828.94 |

128 main-phase threads achieved the highest throughput among the tested
configurations. These were separate runs; network and instance conditions
may vary.

## HTTP metric definitions

- Pool state: current leased (in use), available (idle), pending (waiting for a
  lease), and configured maximum. Printed before closing the pool.
- Sampled pool peaks: maxima observed every 50 ms and at attempt completion;
  short-lived peaks may be missed. These are not cumulative counts.
- TCP connect attempts: calls reaching the connection operator's socket-connect
  hook. DNS failures and pool-acquisition timeouts need not reach this hook.
- TCP connections established: incremented only AFTER socket connection succeeds.
  Reusing a pooled connection does not increment this counter.
- TCP attempts without success: attempts minus successes, evaluated after workers
  finish. This is not a count of all HTTP request failures.
- Application request retries: attempts after the first for each logical event.
- TCP connections established during application retries: new successful TCP opens
  whose request context is marked as an application retry. This is the limited
  HTTP recovery/reconnection measure used here. It is not all replacement
  connections, and a retry can reuse a connection. A failed first TCP connect
  followed by a successful retry is included. Pool expansion and server-requested
  closes can create connections outside this subset; causes are not inferred.
- WebSocket reconnections: successful connections after the first per subscriber,
  reported separately. HTTP metrics exclude WebSockets.

Both clients export raw push timestamps. Part 2 additionally exports per-attempt
HTTP samples. Existing negative push time differences include clock offset.

## Migration validation and new baseline

Run `mvn test` in each client module. Local fixture tests cover response-body
consumption and connection reuse, server-initiated close plus retry, failed TCP
connects, an abruptly dropped response, exact application retry limits, response
read timeout, and concurrent pool leasing/pending requests.

Run `SequentialClient` with the HTTP base URL as its sole program argument to
remeasure RTT using the same Apache transport as LoadClient. It reports one
fresh-client request (including setup), then 20 warmup requests and 100 measured
requests, and prints predictions for 32/64/128 senders. The fresh request includes
other startup overhead; it is not a pure TCP handshake measurement.

See [HTTP transport migration](results/HTTP_TRANSPORT_MIGRATION.md) for the scope
and validation record. The Apache/Java 11 remote baseline and full load-test results are recorded
above, together with the scope of subsequent configuration and deployment
checks.
