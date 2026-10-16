# HTTP transport migration — Apache HttpClient 5

## Scope

Both clients now use Apache HttpClient **5.6.4** for HTTP/1.1 swipe POSTs.
The existing Java `java.net.http.HttpClient` remains responsible for WebSockets.
Event generation, queues, phase sizes, application retry policy, raw push records,
and the Part 2 HTTP CSV/statistics pipeline remain in place.

Files changed in each client:

- `pom.xml`: Apache HttpClient 5 plus test-only JUnit and test runner.
- `HttpTransport.java`: shared pool, socket-connect counters, pool snapshots,
  sampled pool peaks, request attempt/retry counts, response consumption, cleanup.
- `SwipeSender.java`: sends via HttpTransport; still owns the five-retry limit.
- `LoadClient.java`: owns and closes transport; derives WebSocket address from
  one HTTP base URL; accepts optional program arguments for small/full runs.
- `SequentialClient.java`: measures RTT with the same Apache transport.
- `src/test/java/HttpTransportTest.java`: local deterministic transport tests.

No server deployment or EC2 performance run was performed during migration.
The server does not need a new WAR solely because the client HTTP library changed.
Previously requested server validation/session fixes are a separate deployment.

## Definitions

Pool state is a snapshot: leased, available, pending, and max. It is printed
before the pool closes. Peak values are sampled at 50 ms intervals and after
requests complete and may miss brief peaks.

Cumulative successful TCP connections are counted in Apache's
`onAfterSocketConnect` hook, after successful socket establishment. Pool leasing
and reuse do not increase this count. The pre-connect hook counts TCP connect
attempts, excluding failures that occur earlier (e.g. DNS or pool acquisition).
After workers stop, attempts minus successes is the count of socket-connect
attempts without success, not the total HTTP error count.

Application retry count is increased only for a request attempt after the first
attempt of a logical swipe. Automatic Apache retries and redirects are disabled.
An HTTP status of 503 therefore causes only the application's configured retries.

The limited HTTP recovery measure is **successful new TCP connections established
while executing an application retry**. It is explicitly not the total number of
replacement connections or proven fault-recovery connections. For example, a
first connect refusal followed by a successful retry is included; a server close
followed by a new connection on the next event is not included in this subset but
is included in total successful connections. A retry can reuse a connection and
therefore need not increase this measure. No metric subtracts thread count from
connection count. WebSocket reconnect counts remain separate.

## Capacity and timeouts

A strict pool is shared by warmup and main workers. Total and per-route capacities
are `max(32, mainThreads)`. Separate timeouts are 5 s for a pool lease, 5 s for
connect, and 10 s for response/socket reads. These are not a hard end-to-end request
deadline. HttpTransport explicitly supports the assignment's `http://` endpoints.

The response handler consumes the response body before returning, so per-attempt
latency includes pool wait, connection setup if needed, request and full response
processing. Backoff is outside the measured attempt. All network error attempts
remain status 0 in the Part 2 CSV. The client closes the pool with try-with-resources.

## Validation completed

Executed `mvn -q -f client-part1/pom.xml package` and the corresponding Part 2
command on the local Mac (JDK 22, Java 11 compiler target).
Both completed successfully: **8 tests each, 0 failures, 0 errors**.
Subsequently rebuilt both clients with Amazon Corretto **11.0.32.1**, including
all eight tests per client: both passed with zero failures/errors. Surefire XML
confirms java.version=11.0.32.1. The server also built successfully with Java 11.
The sandbox required permission for Maven downloads and loopback test sockets;
these environment errors were resolved by running the approved test commands.

Tests cover:

1. Ten successful POSTs with nonempty response bodies reuse one TCP connection;
   response bodies are consumed and the lease is released.
2. A persistent HTTP 503 produces exactly six server requests, five application
   retries, one final failed swipe, and one TCP connection (no nested retry loop).
3. A 503 with Connection: close followed by 201 creates two TCP connections,
   one during a retry, and one successful logical event.
4. A refused connection increments connect attempts, not successful connections.
5. An abruptly dropped response is not retried by Apache automatically.
6. A capacity-two pool leases two connections and queues a third request, then
   completes all requests using two connections with no outstanding leases.
7. A response read timeout releases its pool lease.
8. Pool-acquisition timeout does not open an extra socket and the active request
   can finish and release its connection normally.

Reports: `client-part1/target/surefire-reports/HttpTransportTest.txt` and
`client-part2/target/surefire-reports/HttpTransportTest.txt` (generated build files).

## Next measurements

Reload Maven in IntelliJ. First run SequentialClient with program argument:

```text
http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet
```

Then run LoadClient with program arguments:

```text
http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet 5000 32 10
```

Only after the small joint HTTP/WebSocket run passes, run the full workload:

```text
http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet 500000 128 1000
```

Use new output filenames containing `apache5`. All existing EC2 logs, charts,
screenshots, and CSVs are pre-migration JDK baselines, not Apache results.
The earlier best thread count is a starting point, not a proven Apache optimum.
The fresh request RTT includes startup overhead as well as TCP connect cost;
report it separately from pooled RTT. Existing push clock-skew limitations remain.

## API references

- [Apache connection operator hooks](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/DefaultHttpClientConnectionOperator.html)
- [Apache pool builder](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManagerBuilder.html)

The hook integration uses an Apache implementation extension point annotated
Internal; the dependency is pinned and the tests should be rerun on upgrades.

## Java 11 compatibility verification

Both modules were subsequently clean-compiled using Amazon Corretto 11 javac
and tested using its Java 11.0.32.1 JVM (not just a Java 11 bytecode target).
Each module passed all 8 tests and packaged successfully. Surefire XML records
java.home as amazon-corretto-11.jdk and java.version as 11.0.32.1.
Maven itself was launched using the terminal default JDK; compiler and test JVM
were explicitly selected with maven.compiler.executable and the jvm property.
Existing EC2 client performance baselines used JDK 22. Use Java 11 for the next
Apache baseline and record both the transport and runtime change in comparisons.
