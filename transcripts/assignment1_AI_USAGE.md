# AI Usage Reflection — CS6650 Assignment 1

I used OpenAI Codex for step-by-step explanations, code generation and edits,
debugging, test design, and documentation. The AI provided substantial help;
I did not write all of the implementation independently. I ran the clients,
deployed the server, shared actual outputs, and asked questions when the
implementation or explanation did not make sense to me.

## Where I used AI

- Understanding the server/client split, HTTP requests, WebSocket subscriptions,
  event generation, queues, and sender threads.
- Implementing and debugging connection pooling, retries, reconnect handling,
  latency recording, CSV export, and throughput charts.
- Interpreting Maven and EC2 logs and drafting the README and design document.
- After grading feedback, implementing task-local metrics buffers and a
  repeated comparison of `shared`, `buffered`, and `off` recording modes.

## Suggestions I accepted

1. **A shared Apache HTTP pool with separate Java WebSocket connections.**
   I accepted this because HTTP requests benefit from connection reuse, while
   leaderboard subscriptions need persistent connections and reconnect logic.
   We kept TCP connection counts, application retries, and WebSocket
   reconnections as separate metrics.
2. **Task-local metrics buffers after grading feedback.** I accepted this to
   reduce concurrent writes to a shared statistics queue and per-attempt
   object allocation. We retained the old recorder as a comparison mode,
   checked record integrity and retries with tests, and ran repeated experiments.

## Suggestions or explanations I questioned or corrected

1. **A claimed arithmetic error.** The AI first calculated Part 1 main
   throughput as 3,644.80 events/s, then incorrectly said this value needed
   correction. A later recalculation confirmed that `468000 / 128.402` is
   approximately 3,644.80. The original value was retained.
2. **The Word-based PDF workflow.** The AI initially guided me to copy the
   design into Word or Google Docs and draw the diagram there. I asked whether
   the assignment required Word. It did not, so we chose to keep Markdown as
   the source and render the Mermaid diagram for PDF preparation instead.

## What I learned and had to reason through

I questioned why a client JAR was introduced when the assignment required one
server WAR. The clarification was that the single WAR contains all server
endpoints, while the load clients are separate Java programs running on my Mac.
I also raised the Java 11 requirement when local output showed Java 22.

The performance discussion required care. The three recording modes and three
repetitions per mode were our experimental choices, not assignment requirements.
The observed buffered throughput median was 1.35% higher than shared, while
median whole-program GC pause time was approximately 91.4% lower. This does
not mean the application became 91.4% faster. The throughput ranges overlapped,
some runs retried requests, and the experiment did not isolate every cause of
the gap from the theoretical prediction.

> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> cd /Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet
> mvn clean package
> [INFO] Scanning for projects...
> [INFO]
> [INFO] ----------------------< cs6650.lab2:ski-servlet >-----------------------
> [INFO] Building ski-servlet 1.0-SNAPSHOT
> [INFO]   from pom.xml
> [INFO] --------------------------------[ war ]---------------------------------
> [INFO]
> [INFO] --- clean:3.2.0:clean (default-clean) @ ski-servlet ---
> [INFO] Deleting /Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet/target
> [INFO]
> [INFO] --- resources:3.3.1:resources (default-resources) @ ski-servlet ---
> [INFO] Copying 0 resource from src/main/resources to target/classes
> [INFO]
> [INFO] --- compiler:3.11.0:compile (default-compile) @ ski-servlet ---
> [INFO] Changes detected - recompiling the module! :source
> [INFO] Compiling 7 source files with javac [debug release 11] to target/classes
> [INFO]
> [INFO] --- resources:3.3.1:testResources (default-testResources) @ ski-servlet ---
> [INFO] skip non existing resourceDirectory /Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet/src/test/resources
> [INFO]
> [INFO] --- compiler:3.11.0:testCompile (default-testCompile) @ ski-servlet ---
> [INFO] Changes detected - recompiling the module! :dependency
> [INFO]
> [INFO] --- surefire:3.2.5:test (default-test) @ ski-servlet ---
> [INFO]
> [INFO] --- war:3.3.2:war (default-war) @ ski-servlet ---
> [INFO] Packaging webapp
> [INFO] Assembling webapp [ski-servlet] in [/Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet/target/ski-servlet]
> [INFO] Processing war project
> [INFO] Copying webapp resources [/Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet/web]
> [INFO] Building war: /Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet/target/ski-servlet.war
> [INFO] ------------------------------------------------------------------------
> [INFO] BUILD SUCCESS
> [INFO] ------------------------------------------------------------------------
> [INFO] Total time:  3.057 s
> [INFO] Finished at: 2026-10-14T22:50:56-07:00
> [INFO] -----

**The build succeeded!** Your Java code compiled and Maven created `target/ski-servlet.war`. Next, we need to verify the server’s actual HTTP responses.

**1. Deploy the new WAR** to your Tomcat 9 `webapps` directory using the same process as Lab 2. Wait for Tomcat to deploy it.

**2. Test a valid swipe.** If Tomcat runs locally on port 8080:

```bash
curl -i -X POST \
  'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":21}'
```

Expected: **`201 Created`**.

Here, `/ski-servlet` comes from the WAR filename, and `/skiers/...` identifies your servlet endpoint.

**3. Test an invalid lift ID:**

```bash
curl -i -X POST \
  'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":41}'
```

Expected: **`400 Bad Request`**.

**4. Test an invalid URL ID:**

```bash
curl -i -X POST \
  'http://localhost:8080/ski-servlet/skiers/0/seasons/2026/days/1/skiers/123' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":21}'
```

Expected: **`400 Bad Request`**.

If Tomcat runs on EC2, replace `localhost` with your instance’s public address. Paste the results, and we’ll check them before adding `/health`.

> how do i know If Tomcat runs on EC2? i just follow your step and run the tomcat in intellij

:codex-annotation{index="1"} If you started Tomcat through IntelliJ on your Mac, **Tomcat is running locally on your Mac**. Use `localhost` in the test commands.

EC2 means a separate computer hosted by AWS. You would have explicitly created an AWS instance, connected to it, installed Tomcat there, and uploaded your WAR. We haven’t done that together yet.

For now:

1. In IntelliJ, stop and restart your Tomcat run configuration so it deploys your updated application.
2. Check its console for the HTTP port, usually `8080`.
3. Run the first `curl` command from my previous message in your Mac terminal.

The application address should look like:

```text
http://localhost:8080/ski-servlet/...
```

**Check the application context in IntelliJ:** open **Run → Edit Configurations → your Tomcat configuration → Deployment → Application context**. If it shows something other than `/ski-servlet`, use that value in the URL.

We’ll test locally first, then walk through EC2 deployment later.

> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -i -X POST \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}'
> HTTP/1.1 201
> Content-Type: application/json;charset=UTF-8
> Content-Length: 92
> Date: Thu, 15 Oct 2026 06:09:39 GMT
>
> {"message":"Lift ride recorded: skier 123, resort 1, season 2026, day 1, time 217, lift 21"}%
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -i -X POST \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":41}'
> HTTP/1.1 400
> Content-Type: application/json;charset=UTF-8
> Content-Length: 31
> Date: Thu, 15 Oct 2026 06:09:51 GMT
> Connection: close
>
> {"message":"invalid JSON body"}%
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -i -X POST \
>   'http://localhost:8080/ski-servlet/skiers/0/seasons/2026/days/1/skiers/123' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}'
> HTTP/1.1 400
> Content-Type: application/json;charset=UTF-8
> Content-Length: 25
> Date: Thu, 15 Oct 2026 06:10:00 GMT
> Connection: close
>
> {"message":"invalid url"}%

**All three tests passed:** valid swipe → `201`, invalid lift ID → `400`, and invalid resort ID → `400`.

Next, let’s add **`GET /health`**. This lets a caller check that your application is running.

**1. Create `HealthServlet.java`** under `src/main/java`, alongside `SkierServlet.java`:

```java
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/health")
public class HealthServlet extends HttpServlet {

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_OK);
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write("{\"status\":\"UP\"}");
  }
}
```

Here’s what each part does:

- `@WebServlet("/health")` maps requests for `/health` to this servlet.
- `doGet()` handles HTTP GET requests.
- `SC_OK` sets status `200`.
- The response body is a fixed JSON message; no database checks are needed.

**2. Rebuild:**

```bash
mvn clean package
```

Then restart Tomcat in IntelliJ to deploy the updated application.

**3. Test:**

```bash
curl -i http://localhost:8080/ski-servlet/health
```

Expected status: **`200`**, with:

```json
{"status":"UP"}
```

If you get `404`, paste the result and your `web/WEB-INF/web.xml`; its configuration may affect annotation discovery.

> okat next step

Next, add the **WebSocket leaderboard**. I’m assuming your `/health` test returned `200`.

Unlike a swipe POST, which gets one response, a WebSocket stays connected so the server can send repeated updates.

**1. Create `LeaderboardEndpoint.java`** in `src/main/java`, alongside your servlets:

```java
import com.google.gson.Gson;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

  private static final Gson GSON = new Gson();

  private static final Map<String, Set<Session>> SUBSCRIBERS =
      new ConcurrentHashMap<>();

  @OnOpen
  public void onOpen(Session session,
                     @PathParam("resortID") String resortID) {
    SUBSCRIBERS.computeIfAbsent(
        resortID, key -> ConcurrentHashMap.newKeySet()
    ).add(session);
  }

  @OnClose
  public void onClose(Session session,
                      @PathParam("resortID") String resortID) {
    removeSession(session, resortID);
  }

  @OnError
  public void onError(Session session, Throwable error,
                      @PathParam("resortID") String resortID) {
    error.printStackTrace();
    if (session != null) {
      removeSession(session, resortID);
    }
  }

  private static void removeSession(Session session, String resortID) {
    SUBSCRIBERS.computeIfPresent(resortID, (key, sessions) -> {
      sessions.remove(session);
      return sessions.isEmpty() ? null : sessions;
    });
  }

  public static void pushAll() {
    SUBSCRIBERS.forEach((resortID, sessions) -> {
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("resortID", resortID);
      message.put("serverTimestamp", Instant.now().toString());
      message.put("top", Arrays.asList(
          skier(1, 40213, 44120),
          skier(2, 11872, 43880)
      ));

      String json = GSON.toJson(message);

      for (Session session : sessions) {
        if (session.isOpen()) {
          try {
            session.getAsyncRemote().sendText(json);
          } catch (RuntimeException error) {
            error.printStackTrace();
          }
        }
      }
    });
  }

  private static Map<String, Object> skier(
      int rank, int skierID, int vertical) {
    Map<String, Object> skier = new LinkedHashMap<>();
    skier.put("rank", rank);
    skier.put("skierID", skierID);
    skier.put("vertical", vertical);
    return skier;
  }
}
```

The important pieces are:

- `@ServerEndpoint` defines the WebSocket URL.
- `@OnOpen` adds a connection to its resort’s subscriber set.
- `@OnClose` removes it.
- `pushAll()` creates a fake leaderboard and sends it to connected subscribers.
- The concurrent map and sets support connections opening and closing while broadcasts happen.

**2. Create `LeaderboardScheduler.java`** in the same folder:

```java
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.util.concurrent.*;

@WebListener
public class LeaderboardScheduler implements ServletContextListener {

  private ScheduledExecutorService scheduler;

  @Override
  public void contextInitialized(ServletContextEvent event) {
    scheduler = Executors.newSingleThreadScheduledExecutor();

    scheduler.scheduleAtFixedRate(() -> {
      try {
        LeaderboardEndpoint.pushAll();
      } catch (RuntimeException error) {
        error.printStackTrace();
      }
    }, 500, 500, TimeUnit.MILLISECONDS);
  }

  @Override
  public void contextDestroyed(ServletContextEvent event) {
    scheduler.shutdownNow();
  }
}
```

This starts the timer when the application starts and stops it when the application shuts down. Keeping that lifecycle explicit avoids leaving a timer thread running after redeployment.

**3. Rebuild and restart Tomcat:**

```bash
mvn clean package
```

**4. Test in Postman:**

1. Create a **WebSocket request**.
2. Enter:
   ```text
   ws://localhost:8080/ski-servlet/leaderboard/7
   ```
3. Click **Connect**.

You should receive JSON approximately every **500 milliseconds**, with `"resortID":"7"`, a changing `serverTimestamp`, and two fake leaderboard entries.

The rankings stay fixed here; that satisfies the synthetic leaderboard requirement. **You don’t need to send swipes to trigger these messages.**

> next step

Next, we’ll **measure a single swipe’s round-trip time**. The assignment asks you to predict throughput before building the multithreaded client.

I’m assuming your WebSocket test is receiving leaderboard messages every 500 ms.

**1. Keep Tomcat running in IntelliJ.**

**2. Run this in your terminal:**

```bash
curl -s -o /dev/null \
  -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":21}' \
  'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
```

This sends the same valid POST, but prints timing instead of the response body:

- **Connection time:** time to establish the TCP connection.
- **Total time:** time until the response finishes arriving, including connection setup.
- **Status:** should be `201`.

Run it **five times**. The first request can be slower because the application is warming up. Each invocation opens a fresh connection; later, our Java client will reuse connections.

**3. Understand the throughput prediction.**

For synchronous sender threads, each with one request in flight:

```text
Predicted throughput ≈ number of sender threads / average request time in seconds
```

For example, if a request takes **20 ms**:

```text
20 ms = 0.020 seconds

1 thread:   1 / 0.020 =    50 swipes/second
32 threads: 32 / 0.020 = 1,600 swipes/second
```

This comes from Little’s Law: `L = λW`, where `L` is average requests in flight, `λ` is throughput, and `W` is average response time. It’s an initial estimate: response time may increase under load.

**Local measurements are practice measurements.** We’ll repeat this against EC2 and use the Java client’s reused connections for a more representative prediction.

Paste your five timing results, and we’ll calculate your initial estimate before creating the client.

> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -s -o /dev/null \
>   -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}' \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
> Status: 201
> Connection time: 0.000311s
> Total time: 0.047113s
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -s -o /dev/null \
>   -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}' \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
> Status: 201
> Connection time: 0.000301s
> Total time: 0.030849s
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -s -o /dev/null \
>   -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}' \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
> Status: 201
> Connection time: 0.000219s
> Total time: 0.010310s
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -s -o /dev/null \
>   -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}' \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
> Status: 201
> Connection time: 0.000258s
> Total time: 0.002976s
> (base) ~/Desktop/CS6650distributedsystem/lab2/ski-servlet
> curl -s -o /dev/null \
>   -w 'Status: %{http_code}\nConnection time: %{time_connect}s\nTotal time: %{time_total}s\n' \
>   -H 'Content-Type: application/json' \
>   -d '{"time":217,"liftID":21}' \
>   'http://localhost:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123'
> Status: 201
> Connection time: 0.000293s
> Total time: 0.003726s

All five requests succeeded. Your response times decreased substantially as the application warmed up:

| Request | Total time |
|---|---:|
| 1 | 47.113 ms |
| 2 | 30.849 ms |
| 3 | 10.310 ms |
| 4 | 2.976 ms |
| 5 | 3.726 ms |

The **average across all five is 18.995 ms**. Using that for our initial prediction:

```text
Throughput ≈ threads / response time in seconds

32 / 0.018995 ≈ 1,685 swipes/second
```

The last two average **3.351 ms**, which would predict about **9,549 swipes/second** with 32 threads. That difference shows why five requests are too few for a reliable prediction. Save these as preliminary observations; we’ll measure more requests with connection reuse later.

Next, **create the separate client project** so client dependencies and server deployment stay easy to manage.

**1. Create these directories from your terminal:**

```bash
mkdir -p /Users/anne/Desktop/CS6650distributedsystem/client-part1/src/main/java
```

**2. In IntelliJ, create `pom.xml` inside `client-part1`:**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">

  <modelVersion>4.0.0</modelVersion>

  <groupId>cs6650.assignment1</groupId>
  <artifactId>client-part1</artifactId>
  <version>1.0-SNAPSHOT</version>

  <properties>
    <maven.compiler.release>11</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.10.1</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
      </plugin>
    </plugins>
  </build>
</project>
```

We’ll use Java 11’s built-in HTTP and WebSocket clients. Gson will parse the leaderboard JSON.

**3. Load the project in IntelliJ.**

Right-click the new `pom.xml` and select **Add as Maven Project** if it hasn’t been detected automatically.

Our next small piece will be a **sequential Java client using one shared `HttpClient`**. We’ll verify connection reuse and timing before adding the generator and 32 sender threads.

> why not built it into lab2 folder

:codex-annotation{index="1"} You **can build it inside `lab2`**. I suggested the repository root because the assignment’s submission structure asks for:

```text
CS6650distributedsystem/
├── server/
├── client-part1/
├── client-part2/
└── results/
```

Your existing server is currently in `lab2/ski-servlet`; we can copy it into `/server` later while preserving your lab work.

The important distinction is to make the client a **separate Maven project from `ski-servlet`**:

- The server produces a **WAR** that Tomcat runs.
- The client runs as a **standalone Java program** that sends requests to Tomcat.

Putting `client-part1` under `lab2` would work technically, but you’d need to move it later to match the requested submission layout. Creating it at the root now saves that step.

> is this the same as lab2

:codex-annotation{index="1"} It uses the **same Maven structure**, but it’s configured for the client rather than the Lab 2 server.

| Setting | Lab 2 server | New client |
|---|---|---|
| `groupId` | `cs6650.lab2` | `cs6650.assignment1` |
| `artifactId` | `ski-servlet` | `client-part1` |
| Packaging | `war` | `jar` (Maven’s default) |
| Dependencies | Servlet API, WebSocket API, Tyrus, Gson | Gson |
| Runtime | Runs inside Tomcat | Runs directly as a Java program |
| Build plugins | Compiler and WAR plugins | Compiler plugin |

Both use **Java 11** and **Gson**.

The client doesn’t need servlet dependencies because it **sends HTTP requests**; the server needs them because it **receives and handles HTTP requests**.

Create a **new `pom.xml` in `client-part1`** using the provided code. Keep your existing Lab 2 `pom.xml` as it is.

> but i remember it said this in assignment 1:\
> You will build **one deployable server** (a single WAR deployed to Tomcat) that exposes an HTTP ingest endpoint, a health endpoint, and a WebSocket leaderboard endpoint. All three run in the same servlet container you set up in Lab 1. why you said new client Packaging jar(Maven’s default)

You’re right about the **single WAR requirement**. It applies to the **server’s three endpoints**, which all stay together:

```text
Server: ski-servlet.war → runs in Tomcat
├── POST /skiers/...       (ingest)
├── GET /health            (health check)
└── /leaderboard/{resortID} (WebSocket)
```

The assignment also requires a **separate load client**. That is the program that sends 500,000 swipes and subscribes to leaderboards:

```text
Client: standalone Java program
   ├── HTTP POST ───────→ Tomcat’s ingest endpoint
   └── WebSocket ───────→ Tomcat’s leaderboard endpoint
```

The client’s Maven project defaults to **JAR packaging** because it runs as a regular Java program, with a `main()` method. You can run it directly from IntelliJ during development; it doesn’t go into Tomcat.

So we still have **exactly one deployable server WAR**, plus the separate client required by Part 2. My earlier explanation should have made that distinction clearer.

> 我看不懂 所以我们是根据assignment1的要求去做的对吗you will build **one deployable server** (a single WAR deployed to Tomcat) that exposes an HTTP ingest endpoint, a health endpoint, and a WebSocket leaderboard endpoint. All three run in the same servlet container you set up in Lab 1. why you said new client Packaging jar(Maven’s default)

对，我们是在按照 Assignment 1 的要求做。之前我没有解释清楚，抱歉。

你引用的这段话说的是 **server（服务器）**：

> 一个 WAR，部署到一个 Tomcat，里面包含三个 endpoint。

我们已经写的这三个功能，全部放在 `ski-servlet` 项目里：

```text
ski-servlet → 打包成一个 WAR → 在 Tomcat 中运行
├── SkierServlet          接收滑雪记录
├── HealthServlet         返回服务器状态
└── LeaderboardEndpoint   推送排行榜
```

**这就符合你引用的要求。没有把三个 endpoint 拆成不同服务器。**

但是 Assignment 1 的 **Part 2 还要求你写 client（客户端）**。它负责向上面的服务器发送 500,000 条记录，并接收排行榜：

```text
client-part1                       ski-servlet
客户端                              服务器

发送滑雪记录 ───── HTTP ──────────→ 接收记录
接收排行榜   ←──── WebSocket ────── 推送排行榜

在 IntelliJ 中运行 main()           在 Tomcat 中运行
```

所以两种打包方式分别是：

| 项目 | 打包方式 | 原因 |
|---|---|---|
| `ski-servlet` 服务器 | **WAR** | 部署到 Tomcat |
| `client-part1` 客户端 | **JAR** | 普通 Java 程序，不部署到 Tomcat |

**“一个 WAR”限制的是服务器。它并不是要求把客户端也放进 WAR。**

你现在的 `ski-servlet` 继续保持 WAR，不需要改。我们新建 `client-part1`，是为了完成作业 Part 2。

> 你刚刚说2. In IntelliJ, create pom.xml inside client-part1: 我需要先stop tomcat9-lab2并且打开这个文件夹吗，因为我们目前还在ski-servlet project folder

:codex-annotation{index="1"} **不用停止 Tomcat。** 可以让它继续运行，因为稍后客户端需要连接这个服务器。

你现在 IntelliJ 打开的是 `ski-servlet` 项目。最容易的做法是：**在新的 IntelliJ 窗口打开 `client-part1`，保留原窗口运行 Tomcat。**

按下面操作：

1. 如果还没创建客户端文件夹，在终端运行：

   ```bash
   mkdir -p /Users/anne/Desktop/CS6650distributedsystem/client-part1/src/main/java
   ```

2. 在 IntelliJ 选择 **File → Open**。

3. 选择这个文件夹：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/client-part1
   ```

4. 如果询问打开方式，选择 **New Window（新窗口）**。

5. 在新窗口左侧，右键 `client-part1` → **New → File** → 输入 `pom.xml`，粘贴之前提供的客户端 XML，然后保存。

6. 如果 IntelliJ 没有自动识别 Maven，右键 `pom.xml` → **Add as Maven Project**。

完成后，你会有两个窗口：

| IntelliJ 窗口 | 用途 |
|---|---|
| 原来的 `ski-servlet` | 保持 Tomcat 运行服务器 |
| 新的 `client-part1` | 编写、运行客户端 |

**客户端的 `pom.xml` 要新建在 `client-part1` 中，不要覆盖 `ski-servlet/pom.xml`。**

> next step

:codex-annotation{index="1"} 下一步：写一个**依次发送请求的 Java 客户端**，先确认 Java 可以连接服务器，并测量请求耗时。之后再增加多线程。

保持原窗口的 **Tomcat 运行**，在 **`client-part1` 窗口**操作。

**1. 创建 Java 类**

右键 `src/main/java` → **New → Java Class** → 输入：

```text
SequentialClient
```

**2. 粘贴代码**

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class SequentialClient {

  public static void main(String[] args) throws Exception {
    String url =
        "http://localhost:8080/ski-servlet"
        + "/skiers/1/seasons/2026/days/1/skiers/123";

    // 只创建一个客户端，让后面的请求能够复用连接。
    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(
            "{\"time\":217,\"liftID\":21}"
        ))
        .build();

    // 先预热，不计入后面的测量。
    for (int i = 0; i < 20; i++) {
      HttpResponse<Void> response =
          client.send(request, HttpResponse.BodyHandlers.discarding());

      if (response.statusCode() != 201) {
        throw new IllegalStateException(
            "Warmup failed: HTTP " + response.statusCode()
        );
      }
    }

    int requestCount = 100;
    int successful = 0;
    long totalLatencyNanos = 0;

    long runStart = System.nanoTime();

    for (int i = 0; i < requestCount; i++) {
      long start = System.nanoTime();

      HttpResponse<Void> response =
          client.send(request, HttpResponse.BodyHandlers.discarding());

      long latency = System.nanoTime() - start;
      totalLatencyNanos += latency;

      if (response.statusCode() == 201) {
        successful++;
      }
    }

    double elapsedSeconds =
        (System.nanoTime() - runStart) / 1_000_000_000.0;

    double averageLatencyMs =
        totalLatencyNanos / 1_000_000.0 / requestCount;

    System.out.printf("Successful: %d%n", successful);
    System.out.printf("Failed: %d%n", requestCount - successful);
    System.out.printf("Average latency: %.3f ms%n", averageLatencyMs);
    System.out.printf("Measured throughput: %.2f swipes/sec%n",
        successful / elapsedSeconds);

    if (successful == requestCount) {
      double predictedThroughput =
          32 / (averageLatencyMs / 1000.0);

      System.out.printf(
          "Initial prediction for 32 senders: %.2f swipes/sec%n",
          predictedThroughput
      );
    }
  }
}
```

**3. 运行**

点击 `main()` 左边的绿色 ▶，选择 **Run 'SequentialClient.main()'**。

这段代码的重点是：

- **一个共享的 `HttpClient`**：支持连接复用，避免每次都新建客户端。
- **`client.send()`**：等待当前响应完成，再发送下一条，所以目前是顺序执行。
- **`System.nanoTime()`**：测量经过的时间，适合计算延迟。
- **`discarding()`**：消费并丢弃响应体，我们这里只需要状态码。

这里的 **20 次预热只是这个小实验的预热**。正式负载客户端仍然要按作业要求，使用 **32 个线程，每个发送 1,000 次**。

这段程序允许连接复用，但输出本身不能证明实际复用了多少条连接。先把运行结果贴给我，我们再计算预测值，然后加入生成器和发送线程。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=52343:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SequentialClient
> Successful: 100
> Failed: 0
> Average latency: 5.989 ms
> Measured throughput: 166.93 swipes/sec
> Initial prediction for 32 senders: 5342.88 swipes/sec
>
> Process finished with exit code 0

运行成功！**100 次请求全部返回成功，没有失败。**

你的结果表示：

```text
平均每条请求耗时：5.989 ms = 0.005989 秒

单线程吞吐量 ≈ 1 / 0.005989
             ≈ 166.97 条/秒

32 个发送线程的初步预测 ≈ 32 / 0.005989
                       ≈ 5,343 条/秒
```

实际单线程吞吐量是 **166.93 条/秒**，与计算结果接近。32 线程的结果还需要实验验证，因为并发增加后，响应时间可能变化。把这些数据保存下来，之后写 Little’s Law 分析会用到。

下一步先创建**滑雪事件的数据类**，之后生成器负责创建它，发送线程负责发送它。

在 `client-part1/src/main/java` 下新建 **`SwipeEvent.java`**：

```java
public class SwipeEvent {

  private final int resortID;
  private final int seasonID;
  private final int dayID;
  private final int skierID;
  private final int time;
  private final int liftID;

  public SwipeEvent(int resortID, int seasonID, int dayID,
                    int skierID, int time, int liftID) {
    this.resortID = resortID;
    this.seasonID = seasonID;
    this.dayID = dayID;
    this.skierID = skierID;
    this.time = time;
    this.liftID = liftID;
  }

  public String path() {
    return "/skiers/" + resortID
        + "/seasons/" + seasonID
        + "/days/" + dayID
        + "/skiers/" + skierID;
  }

  public String body() {
    return "{\"time\":" + time
        + ",\"liftID\":" + liftID + "}";
  }

  public int getResortID() {
    return resortID;
  }
}
```

例如：

```java
SwipeEvent event = new SwipeEvent(1, 2026, 1, 123, 217, 21);
```

它产生：

```text
path(): /skiers/1/seasons/2026/days/1/skiers/123
body(): {"time":217,"liftID":21}
```

这里的 `final` 表示字段赋值后不能再修改。这样，一个事件在线程之间传递时，内容不会被其他线程改变。

**这个类没有 `main()`，不需要单独运行。** 创建好后，我们下一步写专门生成事件的线程。

> 好

接下来写 **事件生成器 `SwipeGenerator`**。它负责随机生成滑雪记录，然后放进队列，发送线程之后从队列取记录。

```text
生成器线程 → 队列 → 发送线程 → Tomcat
```

为了先满足作业中“发送线程不能等待生成事件”的要求，我们先采用容易验证的方式：**由一个独立线程提前生成全部事件，再启动发送线程**。之后测性能时，再评估内存和启动耗时。

**1. 在 `client-part1/src/main/java` 新建 `SwipeGenerator.java`：**

```java
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

public class SwipeGenerator implements Runnable {

  private final BlockingQueue<SwipeEvent> queue;
  private final int eventCount;
  private final int resortCount;
  private final int seasonID;
  private final int dayID;

  public SwipeGenerator(BlockingQueue<SwipeEvent> queue,
                        int eventCount,
                        int resortCount,
                        int seasonID,
                        int dayID) {
    this.queue = queue;
    this.eventCount = eventCount;
    this.resortCount = resortCount;
    this.seasonID = seasonID;
    this.dayID = dayID;
  }

  @Override
  public void run() {
    ThreadLocalRandom random = ThreadLocalRandom.current();

    for (int i = 0; i < eventCount; i++) {
      SwipeEvent event = new SwipeEvent(
          random.nextInt(1, resortCount + 1),
          seasonID,
          dayID,
          random.nextInt(1, 100001),
          random.nextInt(1, 361),
          random.nextInt(1, 41)
      );

      queue.add(event);
    }
  }
}
```

`nextInt(start, end)` **包含起点，不包含终点**。因此：

```java
random.nextInt(1, 41)
```

生成的就是 `1–40`。

这里用 `queue.add()`，稍后的队列容量要足够容纳全部事件。

**2. 新建 `GeneratorDemo.java`，先做一个小验证：**

```java
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class GeneratorDemo {

  public static void main(String[] args) throws InterruptedException {
    int eventCount = 10;

    BlockingQueue<SwipeEvent> queue =
        new ArrayBlockingQueue<>(eventCount);

    Thread generator = new Thread(
        new SwipeGenerator(queue, eventCount, 10, 2026, 1),
        "swipe-generator"
    );

    generator.start();
    generator.join();

    System.out.println("Generated events: " + queue.size());

    SwipeEvent event;
    while ((event = queue.poll()) != null) {
      System.out.println(event.path() + " " + event.body());
    }
  }
}
```

这两个方法的区别很重要：

- `start()`：启动生成器线程。
- `join()`：让当前的 `main` 线程等待生成器完成。

**3. 运行 `GeneratorDemo.main()`。**

应该看到：

```text
Generated events: 10
```

然后是 10 条随机记录。这个验证只生成数据，**不会向 Tomcat 发送请求**。

正式运行时，我们把数量改成 `500000`，并分别记录生成耗时和负载发送耗时，让报告中的计时范围清楚。

> next step

下一步写 **发送任务 `SwipeSender`**：从队列取事件，通过共享的 `HttpClient` 发给 Tomcat。

先用 **4 个线程发送 100 条**，确认多线程流程正确；正式的 32 线程预热随后再加。

**1. 在 `client-part1/src/main/java` 新建 `SwipeSender.java`：**

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class SwipeSender implements Runnable {

  private final BlockingQueue<SwipeEvent> queue;
  private final HttpClient client;
  private final String baseUrl;
  private final AtomicInteger successful;
  private final AtomicInteger failed;

  public SwipeSender(BlockingQueue<SwipeEvent> queue,
                     HttpClient client,
                     String baseUrl,
                     AtomicInteger successful,
                     AtomicInteger failed) {
    this.queue = queue;
    this.client = client;
    this.baseUrl = baseUrl;
    this.successful = successful;
    this.failed = failed;
  }

  @Override
  public void run() {
    SwipeEvent event;

    while ((event = queue.poll()) != null) {
      try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + event.path()))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(event.body()))
            .build();

        HttpResponse<Void> response = client.send(
            request, HttpResponse.BodyHandlers.discarding()
        );

        if (response.statusCode() == 201) {
          successful.incrementAndGet();
        } else {
          failed.incrementAndGet();
        }

      } catch (InterruptedException e) {
        failed.incrementAndGet();
        Thread.currentThread().interrupt();
        return;

      } catch (Exception e) {
        failed.incrementAndGet();
        System.err.println("Send failed: " + e.getMessage());
      }
    }
  }
}
```

这里有两个关键点：

- **`queue.poll()`**：立即取出并移除一条事件；队列为空时返回 `null`，发送任务结束。因为我们提前生成了全部事件，所以空队列表示没有剩余事件。
- **`AtomicInteger`**：多个线程同时增加计数时，避免丢失更新。普通的 `count++` 不能保证这一点。

目前先不加重试，方便检查基本流程；之后会补上作业要求的指数退避重试。

**2. 新建 `LoadClient.java`：**

```java
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadClient {

  public static void main(String[] args) throws Exception {
    int eventCount = 100;
    int threadCount = 4;

    String baseUrl = "http://localhost:8080/ski-servlet";

    BlockingQueue<SwipeEvent> queue =
        new ArrayBlockingQueue<>(eventCount);

    // 先由一个独立线程生成全部事件。
    Thread generator = new Thread(
        new SwipeGenerator(queue, eventCount, 10, 2026, 1),
        "swipe-generator"
    );

    generator.start();
    generator.join();

    if (queue.size() != eventCount) {
      throw new IllegalStateException("Event generation incomplete");
    }

    // 所有发送任务共享这一个 HTTP 客户端。
    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    AtomicInteger successful = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();

    ExecutorService executor =
        Executors.newFixedThreadPool(threadCount);

    long start = System.nanoTime();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(new SwipeSender(
          queue, client, baseUrl, successful, failed
      ));
    }

    executor.shutdown();

    if (!executor.awaitTermination(2, TimeUnit.MINUTES)) {
      executor.shutdownNow();
      throw new IllegalStateException("Load run timed out");
    }

    double elapsedSeconds =
        (System.nanoTime() - start) / 1_000_000_000.0;

    System.out.println("Successful: " + successful.get());
    System.out.println("Failed: " + failed.get());
    System.out.println("Remaining in queue: " + queue.size());
    System.out.printf("Runtime: %.3f seconds%n", elapsedSeconds);
    System.out.printf("Throughput: %.2f swipes/sec%n",
        successful.get() / elapsedSeconds);
  }
}
```

`newFixedThreadPool(4)` 创建最多运行 4 个任务的线程池。`shutdown()` 停止接收新任务，已经提交的任务继续执行；`awaitTermination()` 等待它们结束。

**3. 保持 Tomcat 运行，运行 `LoadClient.main()`。**

预期看到：

```text
Successful: 100
Failed: 0
Remaining in queue: 0
```

这次运行用于验证线程和队列是否正确配合。100 条请求太少，暂时不要用它判断最终性能。把结果贴给我，接下来我们加入正式的 **32 × 1,000 预热阶段**。

> Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=52493:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar LoadClient
> Successful: 100
> Failed: 0
> Remaining in queue: 0
> Runtime: 0.328 seconds
> Throughput: 305.21 swipes/sec
>
> Process finished with exit code 0

结果正确：**100 条全部成功，队列清空，4 个发送线程的基本流程跑通了。** 这次包含客户端初次建立连接的开销，而且请求很少，所以先不拿 `305.21` 与之前的理论值比较。

下一步加入正式的**预热阶段**。注意：作业要求的是 **每个线程发送 1,000 条**，不能只让 32 个线程自由竞争 32,000 条，否则每个线程分到的数量可能不同。

先修改 `SwipeSender`，让它支持“最多发送指定数量”。

**1. 在 `SwipeSender` 中添加字段：**

```java
private final int maxEvents;
```

**2. 给构造方法增加最后一个参数，并保存它：**

```java
public SwipeSender(BlockingQueue<SwipeEvent> queue,
                   HttpClient client,
                   String baseUrl,
                   AtomicInteger successful,
                   AtomicInteger failed,
                   int maxEvents) {
  this.queue = queue;
  this.client = client;
  this.baseUrl = baseUrl;
  this.successful = successful;
  this.failed = failed;
  this.maxEvents = maxEvents;
}
```

**3. 将原来的循环：**

```java
while ((event = queue.poll()) != null) {
```

替换成：

```java
for (int sent = 0; sent < maxEvents; sent++) {
  event = queue.poll();

  if (event == null) {
    return;
  }
```

循环内部的发送和异常处理代码保持原样。

这样，预热任务传入 `1000`，每个任务就处理 1,000 条事件。

**4. 将 `LoadClient.java` 替换为：**

```java
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadClient {

  public static void main(String[] args) throws Exception {
    int eventCount = 500_000;
    int warmupThreads = 32;
    int eventsPerWarmupThread = 1_000;
    int mainThreads = 32;

    String baseUrl = "http://localhost:8080/ski-servlet";

    BlockingQueue<SwipeEvent> queue =
        new ArrayBlockingQueue<>(eventCount);

    long generationStart = System.nanoTime();

    Thread generator = new Thread(
        new SwipeGenerator(queue, eventCount, 10, 2026, 1),
        "swipe-generator"
    );

    generator.start();
    generator.join();

    if (queue.size() != eventCount) {
      throw new IllegalStateException("Event generation incomplete");
    }

    double generationSeconds = secondsSince(generationStart);

    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    AtomicInteger successful = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();

    long ingestStart = System.nanoTime();

    // 预热：32 个任务，每个处理 1,000 条事件。
    long warmupStart = System.nanoTime();

    runPhase(
        warmupThreads, eventsPerWarmupThread,
        queue, client, baseUrl, successful, failed
    );

    double warmupSeconds = secondsSince(warmupStart);
    int warmupSuccessful = successful.get();
    int warmupFailed = failed.get();

    // 主阶段：处理剩余事件，直到队列为空。
    long mainStart = System.nanoTime();

    runPhase(
        mainThreads, Integer.MAX_VALUE,
        queue, client, baseUrl, successful, failed
    );

    double mainSeconds = secondsSince(mainStart);
    double ingestSeconds = secondsSince(ingestStart);

    System.out.printf("Generation time: %.3f seconds%n",
        generationSeconds);

    System.out.println("\n--- Warmup ---");
    System.out.println("Successful: " + warmupSuccessful);
    System.out.println("Failed: " + warmupFailed);
    System.out.printf("Runtime: %.3f seconds%n", warmupSeconds);

    System.out.println("\n--- Main ---");
    System.out.println("Successful: "
        + (successful.get() - warmupSuccessful));
    System.out.println("Failed: "
        + (failed.get() - warmupFailed));
    System.out.printf("Runtime: %.3f seconds%n", mainSeconds);

    System.out.println("\n--- Total ingest ---");
    System.out.println("Successful: " + successful.get());
    System.out.println("Failed: " + failed.get());
    System.out.println("Remaining in queue: " + queue.size());
    System.out.printf("Runtime: %.3f seconds%n", ingestSeconds);
    System.out.printf("Throughput: %.2f swipes/sec%n",
        successful.get() / ingestSeconds);
  }

  private static void runPhase(
      int threads,
      int maxEventsPerTask,
      BlockingQueue<SwipeEvent> queue,
      HttpClient client,
      String baseUrl,
      AtomicInteger successful,
      AtomicInteger failed
  ) throws InterruptedException {

    ExecutorService executor =
        Executors.newFixedThreadPool(threads);

    try {
      for (int i = 0; i < threads; i++) {
        executor.submit(new SwipeSender(
            queue, client, baseUrl,
            successful, failed, maxEventsPerTask
        ));
      }

      executor.shutdown();

      if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
        throw new IllegalStateException("Phase timed out");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static double secondsSince(long start) {
    return (System.nanoTime() - start) / 1_000_000_000.0;
  }
}
```

这里两个阶段**共享同一个 `HttpClient`**，因此主阶段能够继续复用连接；两个阶段使用各自的发送线程池。

**运行前先用小规模检查：**

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;
```

如果全部成功，预期：

```text
Warmup successful: 320
Main successful: 4680
Total successful: 5000
Remaining in queue: 0
```

先运行这个小规模版本并贴出结果。验证通过后，再恢复 **500,000 总量和每线程 1,000 条预热**。当前版本还缺重试和 WebSocket 订阅，我们会继续补齐。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=52606:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar LoadClient
> Generation time: 0.013 seconds
>
> --- Warmup ---
> Successful: 320
> Failed: 0
> Runtime: 0.479 seconds
>
> --- Main ---
> Successful: 4680
> Failed: 0
> Runtime: 1.710 seconds
>
> --- Total ingest ---
> Successful: 5000
> Failed: 0
> Remaining in queue: 0
> Runtime: 2.189 seconds
> Throughput: 2283.76 swipes/sec
>
> Process finished with exit code 0

结果正确，两个阶段的数量都符合预期：

- 预热：`32 × 10 = 320` 条。
- 主阶段：`5000 − 320 = 4680` 条。
- 总计：5,000 条全部成功，队列清空。

下一步加入作业要求的 **失败重试和指数退避**。

**1. 将 `SwipeSender.java` 中的 `run()` 替换为下面代码，并添加 `sendWithRetries()` 方法。** 字段和构造方法保持不变。

```java
@Override
public void run() {
  for (int sent = 0; sent < maxEvents; sent++) {
    SwipeEvent event = queue.poll();

    if (event == null) {
      return;
    }

    try {
      if (sendWithRetries(event)) {
        successful.incrementAndGet();
      } else {
        failed.incrementAndGet();
      }
    } catch (InterruptedException e) {
      failed.incrementAndGet();
      Thread.currentThread().interrupt();
      return;
    }
  }
}

private boolean sendWithRetries(SwipeEvent event)
    throws InterruptedException {

  HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + event.path()))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(event.body()))
      .build();

  // 首次发送 + 最多 5 次重试 = 最多 6 次尝试。
  for (int attempt = 0; attempt <= 5; attempt++) {
    try {
      HttpResponse<Void> response = client.send(
          request, HttpResponse.BodyHandlers.discarding()
      );

      if (response.statusCode() == 201) {
        return true;
      }
    } catch (java.io.IOException e) {
      // 网络错误也进入重试流程。
    }

    if (attempt < 5) {
      long delayMs = 100L << attempt;
      Thread.sleep(delayMs);
    }
  }

  return false;
}
```

**指数退避**就是每次失败后等待更久：

| 重试 | 等待时间 |
|---|---:|
| 第 1 次 | 100 ms |
| 第 2 次 | 200 ms |
| 第 3 次 | 400 ms |
| 第 4 次 | 800 ms |
| 第 5 次 | 1,600 ms |

这里按作业的“最多重试 5 次”理解为：**首次发送之外，再尝试最多 5 次**。

计数也很重要：同一条事件即使尝试了多次，最终也只计为 **一次成功或一次失败**。网络超时时，服务器可能已经收到事件，因此重试可能产生重复请求；当前 dummy server 不保存数据，但后续真实系统需要处理这个问题。

**2. 保留当前 5,000 条配置，再运行 `LoadClient`。**

Tomcat 正常运行时，预期依然是：

```text
Successful: 5000
Failed: 0
Remaining in queue: 0
```

**3. 验证重试失败的情况。**

暂时把 `LoadClient` 中的配置改为：

```java
int eventCount = 1;
int warmupThreads = 1;
int eventsPerWarmupThread = 1;
int mainThreads = 1;
```

把地址暂时改为一个**确认没有服务监听的端口**，例如本机未使用的 `9999`：

```java
String baseUrl = "http://localhost:9999/ski-servlet";
```

运行后，预期最终是：

```text
Successful: 0
Failed: 1
Remaining in queue: 0
```

这验证了失败路径能够结束并计数。完成后恢复原来的 **8080 地址和 5,000 条配置**。下一步我们再加入 WebSocket 订阅。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=53285:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar LoadClient
> Generation time: 0.001 seconds
>
> --- Warmup ---
> Successful: 0
> Failed: 1
> Runtime: 3.236 seconds
>
> --- Main ---
> Successful: 0
> Failed: 0
> Runtime: 0.000 seconds
>
> --- Total ingest ---
> Successful: 0
> Failed: 1
> Remaining in queue: 0
> Runtime: 3.236 seconds
> Throughput: 0.00 swipes/sec
>
> Process finished with exit code 0

这个结果符合预期：**唯一一条事件最终失败，并且只计数一次。**

`3.236 秒`也与退避等待时间基本一致：

```text
100 + 200 + 400 + 800 + 1600 = 3100 ms
```

其余时间用于连接尝试和程序执行。不过，仅凭这份输出还不能直接确认尝试了几次。

我们加一行日志，让重试过程看得见。在 `sendWithRetries()` 中，紧接在这行：

```java
for (int attempt = 0; attempt <= 5; attempt++) {
```

后面添加：

```java
System.out.printf(
    "Skier request attempt %d/6%n", attempt + 1
);
```

保持当前 **1 条事件、9999 端口**的配置，再运行一次，应该看到：

```text
Skier request attempt 1/6
Skier request attempt 2/6
Skier request attempt 3/6
Skier request attempt 4/6
Skier request attempt 5/6
Skier request attempt 6/6
```

确认后，**删除这行日志**，避免正式负载测试时大量打印影响性能。然后恢复：

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;

String baseUrl = "http://localhost:8080/ski-servlet";
```

保持 Tomcat 运行，再确认恢复后是 **5,000 成功、0 失败**。这样成功和失败两条路径都检查过了，接下来就可以加入 WebSocket 订阅。

> next step

下一步加入 **WebSocket 订阅**：客户端连接排行榜，在发送滑雪记录期间持续接收更新。

我们先单独验证一个订阅连接，下一步再加入断线重连并接入 `LoadClient`。

**1. 在 `client-part1/src/main/java` 创建 `LeaderboardSubscriber.java`：**

```java
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

public class LeaderboardSubscriber implements WebSocket.Listener {

  private final int resortID;
  private final AtomicInteger updates = new AtomicInteger();
  private final StringBuilder messageBuffer = new StringBuilder();

  public LeaderboardSubscriber(int resortID) {
    this.resortID = resortID;
  }

  @Override
  public void onOpen(WebSocket webSocket) {
    System.out.println("Connected to resort " + resortID);
    webSocket.request(1);
  }

  @Override
  public CompletionStage<?> onText(
      WebSocket webSocket,
      CharSequence data,
      boolean last) {

    // 一条消息可能被拆成多个片段。
    messageBuffer.append(data);

    if (last) {
      Instant receivedAt = Instant.now();
      String message = messageBuffer.toString();
      messageBuffer.setLength(0);

      try {
        JsonObject json =
            JsonParser.parseString(message).getAsJsonObject();

        Instant serverTimestamp = Instant.parse(
            json.get("serverTimestamp").getAsString()
        );

        int count = updates.incrementAndGet();

        // 这里只打印前三条，避免频繁打印影响性能。
        if (count <= 3) {
          System.out.println("Resort " + resortID
              + ", update " + count
              + ", server time: " + serverTimestamp
              + ", received at: " + receivedAt);
        }
      } catch (RuntimeException e) {
        System.err.println(
            "Invalid leaderboard message: " + e.getMessage()
        );
      }
    }

    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(
      WebSocket webSocket,
      int statusCode,
      String reason) {

    System.out.println("Resort " + resortID
        + " connection closed: " + statusCode);

    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    System.err.println("Resort " + resortID
        + " WebSocket error: " + error.getMessage());
  }

  public int getUpdates() {
    return updates.get();
  }
}
```

这里最重要的是：

- `onOpen()`：连接成功时执行。
- `onText()`：收到文本消息时执行；`last` 表示这条消息是否收完整。
- `request(1)`：允许客户端继续接收下一个消息回调。
- `AtomicInteger`：接收回调更新计数，主线程也可以安全读取。

**2. 创建 `SubscriberDemo.java`：**

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class SubscriberDemo {

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    LeaderboardSubscriber subscriber =
        new LeaderboardSubscriber(7);

    long start = System.nanoTime();

    WebSocket socket = client.newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(
            URI.create(
                "ws://localhost:8080/ski-servlet/leaderboard/7"
            ),
            subscriber
        )
        .get(10, TimeUnit.SECONDS);

    double connectionMs =
        (System.nanoTime() - start) / 1_000_000.0;

    System.out.printf(
        "Connection establishment: %.3f ms%n", connectionMs
    );

    try {
      // 保持连接 5 秒，观察服务器推送。
      Thread.sleep(5_000);

      System.out.println(
          "Updates received: " + subscriber.getUpdates()
      );
    } finally {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Demo finished")
            .get(3, TimeUnit.SECONDS);
      } finally {
        socket.abort();
      }
    }
  }
}
```

**3. 保持 Tomcat 运行，运行 `SubscriberDemo.main()`。**

预期看到：

```text
Connected to resort 7
Connection establishment: ... ms
Resort 7, update 1, ...
Resort 7, update 2, ...
Resort 7, update 3, ...
Updates received: 大约 10
```

服务器每 500 ms 推送一次，所以 5 秒内大约收到 10 条，数量可能略有差异。**不需要运行 `LoadClient` 也能收到这些消息**，因为目前排行榜由服务器定时生成。

把运行结果贴给我，随后我们加入断线重连，并让订阅连接覆盖整个负载测试。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=53950:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SubscriberDemo
> Connected to resort 7
> Connection establishment: 116.580 ms
> Resort 7, update 1, server time: 2026-10-15T09:43:58.541944Z, received at: 2026-10-15T09:43:58.545250Z
> Resort 7, update 2, server time: 2026-10-15T09:43:59.045259Z, received at: 2026-10-15T09:43:59.046750Z
> Resort 7, update 3, server time: 2026-10-15T09:43:59.542572Z, received at: 2026-10-15T09:43:59.544391Z
> Updates received: 10
>
> Process finished with exit code 0

成功！**5 秒收到 10 条更新**，符合服务器每 500 ms 推送一次的设置。连接建立用了 **116.580 ms**，这与收到消息后的推送延迟是两个不同指标。

下一步给订阅添加**断线重连**。我们用一个管理类负责连接，保留 `LeaderboardSubscriber` 负责处理消息。

**1. 修改 `LeaderboardSubscriber`。**

在字段区添加：

```java
private final Runnable onDisconnect;
```

把构造方法替换为：

```java
public LeaderboardSubscriber(int resortID) {
  this(resortID, () -> {});
}

public LeaderboardSubscriber(int resortID, Runnable onDisconnect) {
  this.resortID = resortID;
  this.onDisconnect = onDisconnect;
}
```

把 `onClose()` 和 `onError()` 替换为：

```java
@Override
public CompletionStage<?> onClose(
    WebSocket webSocket, int statusCode, String reason) {
  onDisconnect.run();
  return null;
}

@Override
public void onError(WebSocket webSocket, Throwable error) {
  onDisconnect.run();
}
```

其他代码保持原样。

**2. 新建 `LeaderboardConnection.java`：**

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class LeaderboardConnection implements AutoCloseable {

  private final HttpClient client;
  private final URI uri;
  private final int resortID;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();

  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicInteger completedUpdates = new AtomicInteger();

  // 以下状态由 synchronized 方法保护。
  private boolean stopped;
  private boolean connecting;
  private boolean retryScheduled;
  private int consecutiveFailures;
  private WebSocket socket;
  private LeaderboardSubscriber currentSubscriber;

  public LeaderboardConnection(
      HttpClient client, String wsBaseUrl, int resortID) {
    this.client = client;
    this.resortID = resortID;
    this.uri = URI.create(wsBaseUrl + "/leaderboard/" + resortID);
  }

  public synchronized void start() {
    if (stopped || connecting || retryScheduled || socket != null) {
      return;
    }

    connect();
  }

  private synchronized void connect() {
    if (stopped || connecting || socket != null) {
      return;
    }

    connecting = true;

    LeaderboardSubscriber subscriber =
        new LeaderboardSubscriber(
            resortID, () -> disconnected()
        );

    currentSubscriber = subscriber;

    long start = System.nanoTime();

    client.newWebSocketBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .buildAsync(uri, subscriber)
        .whenComplete((connectedSocket, error) ->
            connectionFinished(connectedSocket, error, start));
  }

  private synchronized void connectionFinished(
      WebSocket connectedSocket, Throwable error, long start) {

    connecting = false;

    if (stopped) {
      if (connectedSocket != null) {
        connectedSocket.abort();
      }
      return;
    }

    if (error != null) {
      consecutiveFailures++;
      scheduleRetry();
      return;
    }

    socket = connectedSocket;
    connections.incrementAndGet();
    consecutiveFailures = 0;

    System.out.printf(
        "Resort %d connected in %.3f ms%n",
        resortID, (System.nanoTime() - start) / 1_000_000.0
    );

    // 连接可能在握手完成后立即关闭。
    if (socket.isInputClosed() || socket.isOutputClosed()) {
      disconnected();
    }
  }

  private synchronized void disconnected() {
    if (stopped) {
      return;
    }

    if (socket != null) {
      socket.abort();
      socket = null;
    }

    // 握手期间的失败由 connectionFinished 处理。
    if (!connecting) {
      scheduleRetry();
    }
  }

  private synchronized void scheduleRetry() {
    if (stopped || retryScheduled) {
      return;
    }

    retryScheduled = true;

    long delaySeconds =
        Math.min(16L, 1L << Math.min(consecutiveFailures, 4));

    System.out.println(
        "Resort " + resortID
            + " reconnecting in " + delaySeconds + " seconds"
    );

    scheduler.schedule(() -> {
      synchronized (LeaderboardConnection.this) {
        retryScheduled = false;

        if (stopped) {
          return;
        }

        if (currentSubscriber != null) {
          completedUpdates.addAndGet(currentSubscriber.getUpdates());
          currentSubscriber = null;
        }

        connect();
      }
    }, delaySeconds, TimeUnit.SECONDS);
  }

  public synchronized int getUpdates() {
    return completedUpdates.get()
        + (currentSubscriber == null
            ? 0 : currentSubscriber.getUpdates());
  }

  public int getConnections() {
    return connections.get();
  }

  public int getReconnections() {
    return Math.max(0, connections.get() - 1);
  }

  @Override
  public synchronized void close() {
    stopped = true;
    scheduler.shutdownNow();

    if (socket != null) {
      socket.abort();
      socket = null;
    }
  }
}
```

这个类负责三件事：

- 连接失败或中途断开后，等待一段时间再连接。
- 防止同一个订阅同时安排多个重连任务。
- 测试结束调用 `close()` 后，停止重连并释放连接。

**3. 将 `SubscriberDemo.java` 替换为：**

```java
import java.net.http.HttpClient;
import java.time.Duration;

public class SubscriberDemo {

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    try (LeaderboardConnection connection =
             new LeaderboardConnection(
                 client, "ws://localhost:8080/ski-servlet", 7)) {

      connection.start();

      System.out.println(
          "观察 60 秒：连接成功后停止 Tomcat，再重新启动 Tomcat。"
      );

      Thread.sleep(60_000);

      System.out.println("Updates: " + connection.getUpdates());
      System.out.println("Connections: " + connection.getConnections());
      System.out.println(
          "Reconnections: " + connection.getReconnections()
      );
    }
  }
}
```

运行后，在这 **60 秒内**：

1. 等待出现 `Connected to resort 7`。
2. 在服务器窗口停止 Tomcat。
3. 看到客户端提示重连后，重新启动 Tomcat。

预期：Tomcat 恢复后，客户端自动重新连接并继续收到排行榜，最后 **`Reconnections` 至少为 1**。这次通过后，我们再把订阅接入 `LoadClient`。

> 什么意思说清楚！运行ublic class SubscriberDemo {\
> \
> &#x20; public static void main(String[] args) throws Exception {\
> &#x20;   HttpClient client = HttpClient.*newBuilder*()吗？在运行这个之后停止tomcat吗？

:codex-annotation{index="1"} **对，先运行 `SubscriberDemo`，看到连接成功后，再停止 Tomcat。** 这是故意模拟服务器断线，检查客户端能不能自动重连。

按这个顺序操作：

1. **服务器窗口：**确认 `Tomcat9-lab2` 正在运行。

2. **客户端窗口：**打开 `SubscriberDemo.java`，点击 `main()` 左边的绿色 ▶，选择 **Run 'SubscriberDemo.main()'**。运行的是整个程序，不是单独运行 `HttpClient.newBuilder()` 那一行。

3. 在客户端输出中，等待看到：

   ```text
   Connected to resort 7
   ```

4. **切到服务器窗口：**点击 Tomcat 的红色 ■ 停止按钮。**不要停止 `SubscriberDemo`。**

5. **切回客户端窗口：**观察是否出现类似：

   ```text
   Resort 7 reconnecting in ... seconds
   ```

6. **切到服务器窗口：**点击绿色 ▶，重新启动 `Tomcat9-lab2`。

7. **回到客户端窗口：**等待再次出现：

   ```text
   Connected to resort 7
   ```

   这表示自动重连成功。

`SubscriberDemo` 启动后总共运行 **60 秒**，然后打印统计并退出。如果操作时来不及，可以先把：

```java
Thread.sleep(60_000);
```

改成：

```java
Thread.sleep(180_000);
```

这样你有 **3 分钟**完成停止和重启操作。

> 人家都说了要等60秒 你为什么又不说清楚！！！ 现在还是connected：/Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=54175:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SubscriberDemo
> 观察 60 秒：连接成功后停止 Tomcat，再重新启动 Tomcat。
> Connected to resort 7
> Resort 7 connected in 114.569 ms
> Resort 7, update 1, server time: 2026-10-15T10:07:29.458677Z, received at: 2026-10-15T10:07:29.473806Z
> Resort 7, update 2, server time: 2026-10-15T10:07:29.957314Z, received at: 2026-10-15T10:07:29.957972Z
> Resort 7, update 3, server time: 2026-10-15T10:07:30.461326Z, received at: 2026-10-15T10:07:30.462109Z
> Updates: 120
> Connections: 1
> Reconnections: 0
>
> Process finished with exit code 0

抱歉，我之前“观察 60 秒”的提示容易让你理解成先等 60 秒。**实际意思是：程序只运行 60 秒，你需要在它结束之前停止并重启 Tomcat。**

你贴的结果显示程序**已经结束**：

```text
Updates: 120
Connections: 1
Reconnections: 0
Process finished with exit code 0
```

这说明收到了 120 条消息，成功连接过一次，没有成功重连。前面的 `Connected` 是历史日志，不是当前连接状态。

:codex-annotation{index="1"} 现在重新做一次，按下面顺序，**不用先等 60 秒**：

1. 确认 Tomcat 正在运行。
2. 重新运行 `SubscriberDemo`。
3. **一看到 `Connected to resort 7`，马上切到服务器窗口，停止 Tomcat。**
4. 保持 `SubscriberDemo` 运行，等它打印 `reconnecting`。
5. 马上重新启动 Tomcat。
6. 回到客户端窗口，观察是否再次打印 `Connected to resort 7`。
7. **这时才等程序自动结束**，检查 `Reconnections` 是否至少为 `1`。

为避免操作来不及，先把等待时间改成 3 分钟：

```java
Thread.sleep(180_000);
```

同时把提示文字改成：

```java
System.out.println(
    "连接成功后立即停止 Tomcat，再重启；程序将在 3 分钟后结束。"
);
```

如果你已经停止了 Tomcat，但客户端没有出现 `reconnecting`，直接告诉我这个现象，我们检查断线检测，不需要反复重做。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=54242:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SubscriberDemo
> 观察 60 秒：连接成功后停止 Tomcat，再重新启动 Tomcat。
> Connected to resort 7
> Resort 7 connected in 198.384 ms
> Resort 7, update 1, server time: 2026-10-15T10:12:20.380210Z, received at: 2026-10-15T10:12:20.425044Z
> Resort 7, update 2, server time: 2026-10-15T10:12:20.882288Z, received at: 2026-10-15T10:12:20.883272Z
> Resort 7, update 3, server time: 2026-10-15T10:12:21.381248Z, received at: 2026-10-15T10:12:21.382331Z
> Resort 7 reconnecting in 1 seconds
> Resort 7 reconnecting in 2 seconds
> Resort 7 reconnecting in 4 seconds
> Resort 7 reconnecting in 8 seconds
> Resort 7 reconnecting in 16 seconds
> Connected to resort 7
> Resort 7 connected in 52.882 ms
> Resort 7, update 1, server time: 2026-10-15T10:12:52.765600Z, received at: 2026-10-15T10:12:52.783099Z
> Resort 7, update 2, server time: 2026-10-15T10:12:53.265452Z, received at: 2026-10-15T10:12:53.267386Z
> Resort 7, update 3, server time: 2026-10-15T10:12:53.765477Z, received at: 2026-10-15T10:12:53.767523Z

**这次自动重连成功了！**

日志显示：

1. 首次连接成功，收到了排行榜。
2. Tomcat 停止后，客户端分别等待 `1、2、4、8、16` 秒尝试重连。
3. Tomcat 恢复后，再次出现：

   ```text
   Connected to resort 7
   Resort 7 connected in 52.882 ms
   ```

4. 随后继续收到排行榜，说明重连后的连接也能正常接收消息。

**现在不用再停止 Tomcat，等 `SubscriberDemo` 自动结束即可。** 最终预期：

```text
Connections: 2
Reconnections: 1
```

重连后日志里的 `update 1` 重新计数是正常的：每次连接创建新的消息监听器，管理类会累计各次连接收到的总数。

下一步，我们把这个订阅连接加入 `LoadClient`，让客户端在发送滑雪记录的同时接收排行榜。

> 下一步

现在把 **WebSocket 订阅接入 `LoadClient`**，让两条路径同时运行：

```text
32 个发送线程 ── HTTP POST ──→ Tomcat
排行榜订阅     ←─ WebSocket ── Tomcat
```

**本次只修改 `LoadClient.java`。保持 Tomcat 运行，不要停止它。** 继续使用 5,000 条的小规模配置。

### 1. 添加 import

在文件顶部添加：

```java
import java.util.ArrayList;
import java.util.List;
```

### 2. 替换发送和打印部分

找到这一行：

```java
long ingestStart = System.nanoTime();
```

从这一行开始，到 `main()` 方法结束前的所有代码，替换为下面内容。**保留后面的 `runPhase()` 和 `secondsSince()` 方法。**

```java
List<LeaderboardConnection> subscribers = new ArrayList<>();

try {
  // 每个 resort 建立一个订阅，共 10 个。
  for (int resortID = 1; resortID <= 10; resortID++) {
    LeaderboardConnection connection =
        new LeaderboardConnection(
            client,
            "ws://localhost:8080/ski-servlet",
            resortID
        );

    subscribers.add(connection);
    connection.start();
  }

  // 等待所有订阅至少成功建立过一次连接，最多等待 15 秒。
  long deadline =
      System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

  while (subscribers.stream()
      .anyMatch(connection -> connection.getConnections() == 0)) {

    if (System.nanoTime() >= deadline) {
      throw new IllegalStateException(
          "Some WebSocket subscriptions did not connect within 15 seconds"
      );
    }

    Thread.sleep(50);
  }

  long ingestStart = System.nanoTime();

  // 预热阶段。
  long warmupStart = System.nanoTime();

  runPhase(
      warmupThreads, eventsPerWarmupThread,
      queue, client, baseUrl, successful, failed
  );

  double warmupSeconds = secondsSince(warmupStart);
  int warmupSuccessful = successful.get();
  int warmupFailed = failed.get();

  // 主阶段。
  long mainStart = System.nanoTime();

  runPhase(
      mainThreads, Integer.MAX_VALUE,
      queue, client, baseUrl, successful, failed
  );

  double mainSeconds = secondsSince(mainStart);
  double ingestSeconds = secondsSince(ingestStart);

  System.out.printf("Generation time: %.3f seconds%n",
      generationSeconds);

  System.out.println("\n--- Warmup ---");
  System.out.println("Successful: " + warmupSuccessful);
  System.out.println("Failed: " + warmupFailed);
  System.out.printf("Runtime: %.3f seconds%n", warmupSeconds);

  System.out.println("\n--- Main ---");
  System.out.println("Successful: "
      + (successful.get() - warmupSuccessful));
  System.out.println("Failed: "
      + (failed.get() - warmupFailed));
  System.out.printf("Runtime: %.3f seconds%n", mainSeconds);

  System.out.println("\n--- Total ingest ---");
  System.out.println("Successful: " + successful.get());
  System.out.println("Failed: " + failed.get());
  System.out.println("Remaining in queue: " + queue.size());
  System.out.printf("Runtime: %.3f seconds%n", ingestSeconds);
  System.out.printf("Throughput: %.2f swipes/sec%n",
      successful.get() / ingestSeconds);

} finally {
  // 无论正常结束还是发生异常，都关闭订阅并停止重连。
  for (LeaderboardConnection connection : subscribers) {
    connection.close();
  }
}

System.out.println("\n--- WebSocket subscriptions ---");

int totalUpdates = 0;

for (int i = 0; i < subscribers.size(); i++) {
  LeaderboardConnection connection = subscribers.get(i);
  int updates = connection.getUpdates();
  totalUpdates += updates;

  System.out.printf(
      "Resort %d: updates=%d, connections=%d, reconnections=%d%n",
      i + 1,
      updates,
      connection.getConnections(),
      connection.getReconnections()
  );
}

System.out.println("Total leaderboard updates: " + totalUpdates);
```

这里：

- `connection.start()`：开始连接该 resort 的排行榜。
- 等待循环：等所有订阅首次连接成功后，再开始发送负载；这段等待不计入 ingest runtime。
- `finally`：发送结束后关闭订阅，避免后台继续重连。
- 当前更新计数覆盖**订阅建立到关闭的整个期间**，包括发送开始前的短暂等待。

### 3. 运行 `LoadClient.main()`

**运行的是 `LoadClient`，这次不需要运行 `SubscriberDemo`。**

预期结果：

```text
--- Total ingest ---
Successful: 5000
Failed: 0
Remaining in queue: 0

--- WebSocket subscriptions ---
Resort 1: updates=..., connections=1, reconnections=0
...
Resort 10: updates=..., connections=1, reconnections=0
Total leaderboard updates: ...
```

各 resort 的更新数量可能略有不同。把结果贴给我，我们确认 HTTP 和 WebSocket 能同时工作后，再进入正式的 500,000 条测试。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/42dc75e9-364d-4741-895d-3a904e3d9992/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看你贴的运行结果，确认 HTTP 发送和 WebSocket 订阅是否都正常。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/42dc75e9-364d-4741-895d-3a904e3d9992/Pasted text.txt`
>
> </details>
</details>

**这次 HTTP 和 WebSocket 同时运行成功了。**

| 检查项 | 结果 |
|---|---|
| 预热 | 320 条成功 |
| 主阶段 | 4,680 条成功 |
| 总请求 | 5,000 成功，0 失败 |
| 剩余队列 | 0 |
| WebSocket | 10 个 resort 都连接成功 |
| 排行榜更新 | 每个收到 7 条，共 70 条 |
| 重连 | 0，正常运行时没有断线 |

不过，你还保留了之前检查重试用的日志，输出中有大量：

```text
Skier request attempt 1/6
```

**这表示“首次尝试”，不是发生了重试。** 大量打印会影响性能，因此现在先清理日志，再运行 500,000 条。

**1. 在客户端 `SwipeSender.java` 中删除：**

```java
System.out.printf(
    "Skier request attempt %d/6%n", attempt + 1
);
```

**2. 在服务器 `SkierServlet.java` 的 `doPost()` 中，删除或注释每次请求都打印的这一行：**

```java
logger.info("POST request received: " + req.getPathInfo());
```

服务器每处理一次请求都打印，也会拖慢负载测试。

**3. 因为修改了服务器代码，在服务器项目重新打包：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/lab2/ski-servlet
mvn clean package
```

然后在 IntelliJ **重启 Tomcat，确保部署更新后的服务器**。

**4. 在客户端 `LoadClient.java` 中恢复作业规定的数量：**

```java
int eventCount = 500_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 1_000;
int mainThreads = 32;
```

**5. 确认 Tomcat 启动完成后，运行 `LoadClient.main()`。这次让 Tomcat 一直运行，不要中途停止。**

预期数量：

```text
Warmup: 32,000
Main:   468,000
Total:  500,000
```

运行结束后，把最后的汇总输出贴给我即可。这是**本地完整负载测试**；后面还要补充详细指标、EC2 部署和性能分析，才能完成作业。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/68b66419-8cd3-4b38-9525-e389f7aecf1c/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看这次 500,000 条测试的汇总结果。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -100 '/Users/anne/.codex/attachments/68b66419-8cd3-4b38-9525-e389f7aecf1c/Pasted text.txt'`
>
> </details>
</details>

**这次本地 500,000 条完整负载测试成功了。**

| 项目 | 结果 |
|---|---:|
| 预热 | 32,000 成功，耗时 5.931 秒 |
| 主阶段 | 468,000 成功，耗时 25.362 秒 |
| 总成功数 | **500,000** |
| 最终失败数 | **0** |
| 剩余队列 | **0** |
| 总发送耗时 | **31.293 秒** |
| 整体吞吐量 | **15,978.15 条/秒** |
| WebSocket 更新 | 每个 resort 62 条，共 **620 条** |
| WebSocket 重连 | 0 |

这说明发送任务处理完了所有事件，而且在发送期间，10 个排行榜订阅也正常收到了消息。注意：`Failed: 0` 表示没有**重试后仍然失败**的事件，不能据此判断是否发生过 HTTP 重试。

实际吞吐量高于之前预测的约 **5,343 条/秒**，也不矛盾。那个预测使用早期测出的 **5.989 ms**；这次运行时间更长，而且清理了逐请求日志，运行条件已经变化。我们需要重新测量相同条件下的延迟，才能做可靠比较。

**先保存这次控制台汇总的截图，标注“本地测试”。** 它还不能替代作业要求的 EC2 测试结果。

接下来进入 **`client-part2`：详细性能分析**，逐步补齐：

1. 每次 HTTP 尝试的时间、延迟、状态码和 resort，保存成 CSV。
2. HTTP 延迟的平均值、中位数、p95、p99、最小值和最大值。
3. 每个 resort 的吞吐量。
4. WebSocket 推送延迟及其统计。
5. 每 10 秒一个区间的吞吐量折线图。

目前的连接统计是 **WebSocket 连接统计**；HTTP 连接统计、完整程序运行时间也还需要补充。下一步先创建 `client-part2`，保留现在已经跑通的 `client-part1`。

> next step

下一步先创建 **`client-part2` 项目**，作为详细性能分析版本。我们复制已经跑通的客户端，再逐步加入 CSV 和统计。

**这一步不需要运行负载测试。Tomcat 可以继续运行。**

**1. 在 Mac 终端执行：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem
mkdir -p client-part2
cp client-part1/pom.xml client-part2/pom.xml
cp -R client-part1/src client-part2/
```

这里复制源代码和 Maven 配置，不复制编译结果 `target` 或 IntelliJ 配置。

完成后的结构应该是：

```text
CS6650distributedsystem/
├── lab2/
│   └── ski-servlet/      ← 服务器
├── client-part1/         ← 已跑通的基本客户端
└── client-part2/         ← 接下来添加详细指标
    ├── pom.xml
    └── src/main/java/
```

**2. 在 IntelliJ 打开新项目。**

选择 **File → Open**，打开：

```text
/Users/anne/Desktop/CS6650distributedsystem/client-part2
```

选择 **New Window**。之后的修改都在这个新窗口中进行。

**3. 修改新项目的 `pom.xml`。**

把：

```xml
<artifactId>client-part1</artifactId>
```

改成：

```xml
<artifactId>client-part2</artifactId>
```

保存，然后点击 Maven 的刷新按钮。如果没有识别成 Maven 项目，右键 `pom.xml` → **Add as Maven Project**。

**4. 验证复制后的项目可以编译。**

在终端运行：

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
mvn clean package
```

看到 **`BUILD SUCCESS`** 就完成了。这条命令只编译和打包，不会发送 500,000 条请求。

下一步我们会在这个项目里记录每次 HTTP 请求的耗时，并生成作业要求的 CSV：

```text
timestamp,requestType,latency,statusCode,resortID
```

其中延迟用 `System.nanoTime()` 计算，时间戳用于后面绘制吞吐量随时间变化的图。

> okay next

<details><summary>2 previous messages</summary>

> 我先看一下 `client-part2` 里的发送代码，再告诉你准确在哪几处加入 CSV 记录。
>
> <details><summary>Ran a command</summary>
>
> - Ran `cat client-part2/src/main/java/SwipeSender.java client-part2/src/main/java/LoadClient.java`
>
> </details>
</details>

现在加入 **HTTP 请求的 CSV 记录**。这一步只修改 **`client-part2`**。

我们采用：**发送时把测量结果存入内存，发送结束后统一写文件**。这样避免每个发送线程频繁写磁盘。

**1. 在 `client-part2/src/main/java` 新建 `SwipeMetrics.java`：**

```java
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SwipeMetrics {

  private final ConcurrentLinkedQueue<Sample> samples =
      new ConcurrentLinkedQueue<>();

  public void record(Instant sentAt,
                     Instant receivedAt,
                     long latencyNanos,
                     int statusCode,
                     int resortID) {
    samples.add(new Sample(
        sentAt, receivedAt, latencyNanos, statusCode, resortID
    ));
  }

  public void writeCsv(Path path) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());

    try (BufferedWriter writer =
             Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {

      writer.write(
          "timestamp,requestType,latency,statusCode,resortID,responseTimestamp"
      );
      writer.newLine();

      for (Sample sample : samples) {
        writer.write(
            sample.sentAt + ",SWIPE,"
                + (sample.latencyNanos / 1_000_000.0) + ","
                + sample.statusCode + ","
                + sample.resortID + ","
                + sample.receivedAt
        );
        writer.newLine();
      }
    }

    System.out.println("HTTP attempts recorded: " + samples.size());
    System.out.println("CSV saved to: " + path.toAbsolutePath());
  }

  private static class Sample {
    final Instant sentAt;
    final Instant receivedAt;
    final long latencyNanos;
    final int statusCode;
    final int resortID;

    Sample(Instant sentAt, Instant receivedAt,
           long latencyNanos, int statusCode, int resortID) {
      this.sentAt = sentAt;
      this.receivedAt = receivedAt;
      this.latencyNanos = latencyNanos;
      this.statusCode = statusCode;
      this.resortID = resortID;
    }
  }
}
```

前五列对应作业要求。额外的 `responseTimestamp` 保存响应完成时间，之后可以按**完成时间**统计每 10 秒的吞吐量。

`ConcurrentLinkedQueue` 允许多个发送线程安全地添加记录。这种实现会占用与请求尝试数量成比例的内存。

**2. 修改 `SwipeSender.java`。**

添加 import：

```java
import java.time.Instant;
```

添加字段：

```java
private final SwipeMetrics metrics;
```

在构造方法最后增加 `SwipeMetrics metrics` 参数：

```java
public SwipeSender(BlockingQueue<SwipeEvent> queue,
                   HttpClient client,
                   String baseUrl,
                   AtomicInteger successful,
                   AtomicInteger failed,
                   int maxEvents,
                   SwipeMetrics metrics) {
  this.queue = queue;
  this.client = client;
  this.baseUrl = baseUrl;
  this.successful = successful;
  this.failed = failed;
  this.maxEvents = maxEvents;
  this.metrics = metrics;
}
```

然后将整个 `sendWithRetries()` 方法替换为：

```java
private boolean sendWithRetries(SwipeEvent event)
    throws InterruptedException {

  HttpRequest request = HttpRequest.newBuilder()
      .uri(URI.create(baseUrl + event.path()))
      .timeout(Duration.ofSeconds(10))
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(event.body()))
      .build();

  for (int attempt = 0; attempt <= 5; attempt++) {
    Instant sentAt = Instant.now();
    long start = System.nanoTime();

    // 0 表示没有收到 HTTP 响应，不是真实 HTTP 状态码。
    int statusCode = 0;

    try {
      HttpResponse<Void> response = client.send(
          request, HttpResponse.BodyHandlers.discarding()
      );

      statusCode = response.statusCode();

    } catch (java.io.IOException e) {
      // 记录此次网络失败，然后按原逻辑重试。
    } finally {
      long latencyNanos = System.nanoTime() - start;
      Instant receivedAt = Instant.now();

      metrics.record(
          sentAt,
          receivedAt,
          latencyNanos,
          statusCode,
          event.getResortID()
      );
    }

    if (statusCode == 201) {
      return true;
    }

    if (attempt < 5) {
      Thread.sleep(100L << attempt);
    }
  }

  return false;
}
```

每次尝试记录一行，包括重试。**退避等待时间不算入单次请求延迟。** 没有收到响应时，`responseTimestamp` 表示该尝试结束的时间。

**3. 修改 `LoadClient.java`，把同一个指标对象传给所有发送任务。**

添加 import：

```java
import java.nio.file.Path;
```

在这两行后面：

```java
AtomicInteger successful = new AtomicInteger();
AtomicInteger failed = new AtomicInteger();
```

添加：

```java
SwipeMetrics metrics = new SwipeMetrics();
```

两个 `runPhase()` 调用都增加最后一个参数 `metrics`：

```java
runPhase(
    warmupThreads, eventsPerWarmupThread,
    queue, client, baseUrl, successful, failed, metrics
);
```

```java
runPhase(
    mainThreads, Integer.MAX_VALUE,
    queue, client, baseUrl, successful, failed, metrics
);
```

`runPhase()` 方法声明也增加这个参数：

```java
private static void runPhase(
    int threads,
    int maxEventsPerTask,
    BlockingQueue<SwipeEvent> queue,
    HttpClient client,
    String baseUrl,
    AtomicInteger successful,
    AtomicInteger failed,
    SwipeMetrics metrics
) throws InterruptedException {
```

方法内部创建发送任务的位置改为：

```java
executor.submit(new SwipeSender(
    queue, client, baseUrl,
    successful, failed, maxEventsPerTask, metrics
));
```

最后，在 `main()` 中这行后面：

```java
System.out.println("Total leaderboard updates: " + totalUpdates);
```

添加：

```java
metrics.writeCsv(Path.of(
    "results", "swipes-" + System.currentTimeMillis() + ".csv"
));
```

文件名包含时间，避免每次运行覆盖上一次结果。文件写入发生在发送计时结束之后。

**4. 先用 5,000 条验证。**

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;
```

保持 Tomcat 运行，在 **`client-part2` 窗口运行 `LoadClient.main()`**。

如果全部首次发送成功，最后会看到：

```text
HTTP attempts recorded: 5000
CSV saved to: .../results/swipes-....csv
```

CSV 应该有 **5,001 行：1 行标题，加 5,000 条记录**。如果发生重试，记录数会更多。把最后的汇总和 CSV 前几行贴给我，我们再加入平均值和百分位数统计。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/e5fa62f6-e65a-40d7-8024-e6b8285fc9b6/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查运行汇总和 CSV 是否成功生成。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/e5fa62f6-e65a-40d7-8024-e6b8285fc9b6/Pasted text.txt`
>
> </details>
</details>

**这次成功了，而且你运行的是完整的 500,000 条测试。**

| 检查项 | 结果 |
|---|---:|
| 预热成功 | 32,000 |
| 主阶段成功 | 468,000 |
| 最终失败 | 0 |
| HTTP 尝试记录 | 500,000 |
| 发送耗时 | 28.921 秒 |
| 吞吐量 | 17,288.61 条/秒 |
| 排行榜更新 | 580 条 |

成功事件数和 HTTP 尝试记录数都是 **500,000**，说明按照当前计数逻辑，这次没有发生应用层重试。

程序报告 CSV 已保存到：

[打开 swipes-1792061437464.csv](./client-part2/results/swipes-1792061437464.csv)

下一步加入 **HTTP 延迟统计**。在 `client-part2` 的 **`SwipeMetrics.java`** 中，添加下面两个方法，放在 `private static class Sample` **前面**：

```java
public void printLatencySummary() {
  double[] latencies = samples.stream()
      .mapToDouble(sample -> sample.latencyNanos / 1_000_000.0)
      .sorted()
      .toArray();

  if (latencies.length == 0) {
    System.out.println("No HTTP latency samples.");
    return;
  }

  double sum = 0;
  for (double latency : latencies) {
    sum += latency;
  }

  int n = latencies.length;

  double median = n % 2 == 0
      ? (latencies[n / 2 - 1] + latencies[n / 2]) / 2.0
      : latencies[n / 2];

  System.out.println("\n--- HTTP attempt latency ---");
  System.out.println("Samples: " + n);
  System.out.printf("Mean: %.3f ms%n", sum / n);
  System.out.printf("Median: %.3f ms%n", median);
  System.out.printf("p95: %.3f ms%n", percentile(latencies, 0.95));
  System.out.printf("p99: %.3f ms%n", percentile(latencies, 0.99));
  System.out.printf("Min: %.3f ms%n", latencies[0]);
  System.out.printf("Max: %.3f ms%n", latencies[n - 1]);
}

private static double percentile(double[] sorted, double fraction) {
  int index = (int) Math.ceil(fraction * sorted.length) - 1;
  return sorted[index];
}
```

这些指标的含义：

- **Mean**：平均耗时。
- **Median**：排序后处于中间的耗时。
- **p95**：至少 95% 的样本耗时不超过这个值。
- **p99**：至少 99% 的样本耗时不超过这个值。
- **Min / Max**：最短、最长耗时。

这里使用 **nearest-rank** 方法计算百分位数，统计范围是**预热和主阶段的所有 HTTP 尝试，包括失败尝试，不包含重试之间的等待时间**。

然后在 **`LoadClient.java`** 中，找到：

```java
metrics.writeCsv(Path.of(
    "results", "swipes-" + System.currentTimeMillis() + ".csv"
));
```

在它后面添加：

```java
metrics.printLatencySummary();
```

保持 Tomcat 运行，再运行 **`client-part2` 的 `LoadClient.main()`**。运行结束后，把新增的 `HTTP attempt latency` 统计贴给我。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/474226fe-a9cd-4295-bed3-e87c1135e5a7/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看这次新增的延迟统计结果。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -65 '/Users/anne/.codex/attachments/474226fe-a9cd-4295-bed3-e87c1135e5a7/Pasted text.txt'`
>
> </details>
</details>

**延迟统计已经正常输出，500,000 条请求也全部成功。**

| 指标 | 结果 | 含义 |
|---|---:|---|
| Mean | 1.854 ms | 平均请求耗时 |
| Median | 1.484 ms | 一半请求的耗时不超过此值 |
| p95 | 3.305 ms | 至少 95% 的请求不超过此值 |
| p99 | 6.849 ms | 至少 99% 的请求不超过此值 |
| Min | 0.126 ms | 最快请求 |
| Max | 154.358 ms | 最慢请求 |

最大值明显高于 p99，说明存在少量较慢请求；仅凭这些统计还不能确定原因。

下一步补上作业要求的 **每个 resort 的吞吐量**。

**1. 在 `client-part2` 的 `SwipeMetrics.java` 中添加方法，放在 `private static class Sample` 前面：**

```java
public void printResortThroughput(
    double ingestSeconds, int resortCount) {

  long[] successes = new long[resortCount + 1];

  for (Sample sample : samples) {
    if (sample.statusCode == 201
        && sample.resortID >= 1
        && sample.resortID <= resortCount) {
      successes[sample.resortID]++;
    }
  }

  System.out.println("\n--- Throughput per resort ---");

  for (int resortID = 1; resortID <= resortCount; resortID++) {
    System.out.printf(
        "Resort %d: successful=%d, throughput=%.2f swipes/sec%n",
        resortID,
        successes[resortID],
        successes[resortID] / ingestSeconds
    );
  }
}
```

计算方式是：

```text
某个 resort 的吞吐量
= 该 resort 成功的请求数 ÷ 整个 ingest 阶段耗时
```

所有 resort 使用**同一个时间范围**，因此它们的吞吐量相加，应接近整体吞吐量。

**2. 在 `LoadClient.java` 中，找到打印整体吞吐量的代码：**

```java
System.out.printf("Throughput: %.2f swipes/sec%n",
    successful.get() / ingestSeconds);
```

**紧接着添加：**

```java
metrics.printResortThroughput(ingestSeconds, 10);
```

要放在这里，因为 `ingestSeconds` 在当前 `try` 代码块内定义，在这里可以直接使用。

**3. 保持 Tomcat 运行，再运行 `client-part2` 的 `LoadClient.main()`。**

新增输出会类似：

```text
--- Throughput per resort ---
Resort 1: successful=..., throughput=... swipes/sec
...
Resort 10: successful=..., throughput=... swipes/sec
```

由于 resort 是随机生成的，每个成功数应在 **50,000 左右**，不要求完全相等；总和应为 **500,000**。之后我们再加入 WebSocket 推送延迟统计。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/72de1749-54f4-45ae-9c87-118ce3229f88/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看各 resort 的吞吐量和这次运行的汇总。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -85 '/Users/anne/.codex/attachments/72de1749-54f4-45ae-9c87-118ce3229f88/Pasted text.txt'`
>
> </details>
</details>

**各 resort 的吞吐量统计正常。** 10 个 resort 的成功数相加为 **500,000**，每个约 50,000 条，符合随机生成的预期。

本次整体吞吐量是 **17,673.02 条/秒**，HTTP 平均延迟 **1.799 ms**，排行榜共收到 **570 条**。

下一步加入 **WebSocket 推送延迟**。先创建统计类，再接入订阅，避免一次修改太多。

在 **`client-part2/src/main/java`** 新建 **`PushMetrics.java`**：

```java
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PushMetrics {

  private final ConcurrentLinkedQueue<Double> latencies =
      new ConcurrentLinkedQueue<>();

  public void record(Instant serverTimestamp, Instant receivedAt) {
    double latencyMs =
        Duration.between(serverTimestamp, receivedAt).toNanos()
            / 1_000_000.0;

    latencies.add(latencyMs);
  }

  public void printSummary() {
    double[] sorted = latencies.stream()
        .mapToDouble(Double::doubleValue)
        .sorted()
        .toArray();

    if (sorted.length == 0) {
      System.out.println("No push latency samples.");
      return;
    }

    double sum = 0;
    for (double value : sorted) {
      sum += value;
    }

    int n = sorted.length;

    double median = n % 2 == 0
        ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        : sorted[n / 2];

    System.out.println("\n--- WebSocket push latency ---");
    System.out.println("Samples: " + n);
    System.out.printf("Mean: %.3f ms%n", sum / n);
    System.out.printf("Median: %.3f ms%n", median);
    System.out.printf("p95: %.3f ms%n",
        sorted[(int) Math.ceil(n * 0.95) - 1]);
    System.out.printf("p99: %.3f ms%n",
        sorted[(int) Math.ceil(n * 0.99) - 1]);
  }
}
```

计算公式是：

```text
推送延迟 = 客户端收到完整消息的时间 − 消息中的 serverTimestamp
```

这里用 `Instant`，因为需要比较服务器和客户端的时间戳。以后服务器部署到 EC2，两台机器的时钟差也会包含在结果中，需要在报告里说明；**不要把负值强行改成 0**。

这个类没有 `main()`，暂时不需要运行。下一步把同一个 `PushMetrics` 对象传给所有订阅，让重连前后的消息都记录到同一个统计对象里。

> next

<details><summary>2 previous messages</summary>

> 我先确认两个 WebSocket 类的构造方法，再把推送延迟统计接进去，保证重连后的数据也会累计。
>
> <details><summary>Ran a command</summary>
>
> - Ran `cat client-part2/src/main/java/LeaderboardSubscriber.java client-part2/src/main/java/LeaderboardConnection.java`
>
> </details>
</details>

现在把 `PushMetrics` 接入订阅。**只修改 `client-part2`，共三个文件。**

数据传递关系是：

```text
LoadClient 创建一个 PushMetrics
    → 传给 10 个 LeaderboardConnection
    → 每次连接或重连，都传给 LeaderboardSubscriber
    → 收到消息时记录延迟
```

**1. 修改 `LeaderboardSubscriber.java`。**

在字段区添加：

```java
private final PushMetrics pushMetrics;
```

把原来的两个构造方法替换为下面三个：

```java
public LeaderboardSubscriber(int resortID) {
  this(resortID, () -> {}, new PushMetrics());
}

public LeaderboardSubscriber(int resortID, Runnable onDisconnect) {
  this(resortID, onDisconnect, new PushMetrics());
}

public LeaderboardSubscriber(
    int resortID,
    Runnable onDisconnect,
    PushMetrics pushMetrics) {
  this.resortID = resortID;
  this.onDisconnect = onDisconnect;
  this.pushMetrics = pushMetrics;
}
```

前两个构造方法保留了之前示例程序的调用方式；第三个允许传入共享的统计对象。

在 `onText()` 中找到：

```java
Instant serverTimestamp = Instant.parse(
    json.get("serverTimestamp").getAsString()
);
```

紧接着添加：

```java
pushMetrics.record(serverTimestamp, receivedAt);
```

这样，每收到一条完整且能解析的排行榜消息，就记录一次推送延迟。

**2. 修改 `LeaderboardConnection.java`。**

在字段区添加：

```java
private final PushMetrics pushMetrics;
```

把原来的构造方法替换为：

```java
public LeaderboardConnection(
    HttpClient client, String wsBaseUrl, int resortID) {
  this(client, wsBaseUrl, resortID, new PushMetrics());
}

public LeaderboardConnection(
    HttpClient client,
    String wsBaseUrl,
    int resortID,
    PushMetrics pushMetrics) {
  this.client = client;
  this.resortID = resortID;
  this.uri = URI.create(wsBaseUrl + "/leaderboard/" + resortID);
  this.pushMetrics = pushMetrics;
}
```

然后在 `connect()` 中找到：

```java
LeaderboardSubscriber subscriber =
    new LeaderboardSubscriber(
        resortID, () -> disconnected()
    );
```

替换为：

```java
LeaderboardSubscriber subscriber =
    new LeaderboardSubscriber(
        resortID, () -> disconnected(), pushMetrics
    );
```

**重连时继续传同一个 `pushMetrics`，因此之前记录的延迟不会丢失。**

**3. 修改 `LoadClient.java`。**

找到：

```java
SwipeMetrics metrics = new SwipeMetrics();
```

在后面添加：

```java
PushMetrics pushMetrics = new PushMetrics();
```

找到创建订阅的位置，增加最后一个参数：

```java
LeaderboardConnection connection =
    new LeaderboardConnection(
        client,
        "ws://localhost:8080/ski-servlet",
        resortID,
        pushMetrics
    );
```

最后，在：

```java
metrics.printLatencySummary();
```

后面添加：

```java
pushMetrics.printSummary();
```

**4. 运行验证。**

保持 Tomcat 运行，在 **`client-part2` 窗口运行 `LoadClient.main()`**。本次不需要手动停止或重启 Tomcat。

结束后应该新增：

```text
--- WebSocket push latency ---
Samples: ...
Mean: ... ms
Median: ... ms
p95: ... ms
p99: ... ms
```

这里的样本来自 **10 个订阅的全部消息，包括建立订阅后、发送负载前的短暂等待期间**。把新增的统计结果贴给我，我们检查推送延迟是否成功记录。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/34524135-bc1e-4c5b-bfc5-6f498590e1ad/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看新增的 WebSocket 延迟统计和消息数量。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -75 '/Users/anne/.codex/attachments/34524135-bc1e-4c5b-bfc5-6f498590e1ad/Pasted text.txt'`
>
> </details>
</details>

**WebSocket 推送延迟已经成功记录。** 收到的消息总数和延迟样本数都是 **560**，这次每条消息都有对应的延迟记录。

| 指标 | HTTP 请求延迟 | WebSocket 推送延迟 |
|---|---:|---:|
| 样本数 | 500,000 | 560 |
| 平均值 | 1.787 ms | 4.690 ms |
| 中位数 | 1.457 ms | 1.267 ms |
| p95 | 3.107 ms | 20.523 ms |
| p99 | 6.222 ms | 113.178 ms |

WebSocket 的中位数只有 **1.267 ms**，但 p99 达到 **113.178 ms**，说明大多数推送很快，少量推送明显更慢。仅凭这些数字，不能确定是线程调度、GC，还是其他因素造成的。

两列测量含义不同：

- **HTTP**：客户端发出请求到完整响应返回的耗时。
- **WebSocket**：服务器消息时间戳到客户端收到完整消息的时间差。

另外，输出有一个很小的计时问题：预热 `3.674` 秒加主阶段 `24.417` 秒略大于总时间 `28.090` 秒。这与代码对相邻时间点分别取值有关；后面整理计时边界时一起修正。

**目前本地功能已验证：500,000 条全部成功、10 个订阅正常、CSV 和两类延迟统计都已生成。** 下一步是用已有 CSV 制作作业要求的 **每 10 秒吞吐量折线图**，不需要为了画图再跑一次负载测试。

> okay

现在用已经生成的 CSV 画图，**不用运行 `LoadClient`，也不需要操作 Tomcat**。

**1. 在 `client-part2` 项目根目录创建 `plot_throughput.py`。**

放在与 `pom.xml` 同一层，粘贴：

```python
import csv
import math
import sys
from datetime import datetime
from pathlib import Path

import matplotlib.pyplot as plt

def parse_time(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00"))

csv_path = Path(sys.argv[1])

first_send = None
last_completion = None
successful_completions = []

with csv_path.open(newline="", encoding="utf-8") as file:
    for row in csv.DictReader(file):
        sent = parse_time(row["timestamp"])
        completed = parse_time(row["responseTimestamp"])

        if first_send is None or sent < first_send:
            first_send = sent

        if last_completion is None or completed > last_completion:
            last_completion = completed

        if int(row["statusCode"]) == 201:
            successful_completions.append(completed)

if first_send is None:
    raise SystemExit("CSV contains no samples.")

duration = (last_completion - first_send).total_seconds()

if duration <= 0:
    raise SystemExit("Measurement duration must be positive.")

bucket_seconds = 10
bucket_count = max(1, math.ceil(duration / bucket_seconds))
counts = [0] * bucket_count

for completed in successful_completions:
    elapsed = (completed - first_send).total_seconds()
    index = min(int(elapsed // bucket_seconds), bucket_count - 1)
    counts[index] += 1

midpoints = []
throughputs = []

for index, count in enumerate(counts):
    start = index * bucket_seconds
    end = min(start + bucket_seconds, duration)
    actual_seconds = end - start
    throughput = count / actual_seconds

    midpoints.append((start + end) / 2)
    throughputs.append(throughput)

    print(
        f"{start:.2f}–{end:.2f}s: "
        f"{count} successful swipes, "
        f"{throughput:.2f} swipes/sec"
    )

fig, ax = plt.subplots(figsize=(9, 5))

ax.plot(midpoints, throughputs, marker="o")
ax.set_xlabel("Seconds since first HTTP attempt")
ax.set_ylabel("Successful swipes / second")
ax.set_title("Local ingest throughput — 10-second buckets")
ax.set_xlim(0, duration)
ax.set_ylim(bottom=0)
ax.grid(True, alpha=0.3)

fig.text(
    0.5, 0.02,
    "Includes warmup. Final partial bucket uses its actual duration.",
    ha="center", fontsize=9
)

fig.tight_layout(rect=(0, 0.05, 1, 1))

output_path = csv_path.with_name(csv_path.stem + "-throughput.png")
fig.savefig(output_path, dpi=200)
plt.close(fig)

print(f"Total successful swipes: {sum(counts)}")
print(f"Chart saved to: {output_path.resolve()}")
```

这里按**成功响应完成的时间**分组：

```text
0–10 秒：这段时间完成多少条 ÷ 10
10–20 秒：这段时间完成多少条 ÷ 10
20–结束：这段时间完成多少条 ÷ 实际剩余秒数
```

最后一段不足 10 秒时，用实际时长计算，避免把最后一段吞吐量算低。图包含预热阶段；计时范围从 CSV 中最早发送到最后一次尝试完成，因此与控制台计时可能略有差别。

**2. 在 Mac 终端逐行执行：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
```

```bash
python3 -m venv .venv
```

```bash
source .venv/bin/activate
```

```bash
python -m pip install matplotlib
```

这是创建绘图用的 Python 环境并安装绘图库，只需设置一次。

**3. 使用刚才那次测试的 CSV 生成图：**

```bash
python plot_throughput.py results/swipes-1792063619206.csv
```

运行后，会在 `results` 文件夹生成：

```text
swipes-1792063619206-throughput.png
```

这次测试约 28 秒，因此图上大约只有 **3 个数据点**，符合每 10 秒一个区间的要求。终端最后的 `Total successful swipes` 应为 **500000**。

把生成的图或终端输出贴给我，我们检查分组结果。

> User context
>
> Images:
> - `./client-part2/results/swipes-1792063619206-throughput.png`

**图已经成功生成，展示方式符合每 10 秒分组的要求。**

三个点分别表示：

| 时间区间 | 图中的平均吞吐量（约） |
|---|---:|
| 0–10 秒 | 13,600 条/秒 |
| 10–20 秒 | 18,800 条/秒 |
| 20–28 秒 | 21,700 条/秒 |

**点画在区间中间**：例如横坐标 5 秒的点代表整个 **0–10 秒**的平均吞吐量，不是第 5 秒的瞬时速度。最后一段不足 10 秒，已经按实际时长计算。

这次运行中吞吐量逐段提高。第一段包含预热，连接建立和 JVM 预热可能影响表现，但仅凭这张图不能确定各因素的贡献，也不能说明吞吐量会一直上升。

报告里可以写：

> 本地测试中，以 10 秒为区间统计成功完成的请求。吞吐量从首个区间的约 13,600 次/秒提高至最后一个区间的约 21,700 次/秒。统计包含预热阶段，最后不足 10 秒的区间按实际持续时间计算。

保留这张图作为**本地测试结果**。接下来先整理计时和连接统计，再进行 EC2 部署与远程测试；远程运行后还需要生成对应的图。

> ok

下一步先修正**计时范围**。只修改 **`client-part2` 的 `LoadClient.java`**。

我们要分别报告：

- **生成时间**：生成 500,000 条事件。
- **预热时间、主阶段时间**：两个发送阶段。
- **总发送时间**：预热开始到主阶段结束。
- **完整运行时间**：包含初始化、生成、订阅建立、发送、关闭订阅和 CSV 导出。

**1. 在 `main()` 的第一行添加：**

```java
long programStart = System.nanoTime();
```

**2. 找到预热阶段的开始位置。**

把：

```java
long ingestStart = System.nanoTime();

// 预热阶段。
long warmupStart = System.nanoTime();
```

替换为：

```java
// 预热开始，也是整个发送阶段的开始。
long ingestStart = System.nanoTime();
```

预热的 `runPhase(...)` 调用保持原样。

**3. 在预热 `runPhase(...)` 调用结束后，替换计时部分。**

把：

```java
double warmupSeconds = secondsSince(warmupStart);
int warmupSuccessful = successful.get();
int warmupFailed = failed.get();

// 主阶段。
long mainStart = System.nanoTime();
```

替换为：

```java
// 共享一个阶段分界点，避免计时范围重叠。
long mainStart = System.nanoTime();

double warmupSeconds =
    (mainStart - ingestStart) / 1_000_000_000.0;

int warmupSuccessful = successful.get();
int warmupFailed = failed.get();
```

**4. 在主阶段 `runPhase(...)` 调用后，替换：**

```java
double mainSeconds = secondsSince(mainStart);
double ingestSeconds = secondsSince(ingestStart);
```

改成：

```java
long ingestEnd = System.nanoTime();

double mainSeconds =
    (ingestEnd - mainStart) / 1_000_000_000.0;

double ingestSeconds =
    (ingestEnd - ingestStart) / 1_000_000_000.0;
```

这样，两个阶段在计算上满足：

```text
预热时间 + 主阶段时间 = 总发送时间
```

打印时保留三位小数，仍可能有 `0.001` 秒的舍入差异，这是正常的。

**5. 在 `main()` 最后，紧接着：**

```java
pushMetrics.printSummary();
```

添加：

```java
System.out.printf(
    "%nTotal program runtime: %.3f seconds%n",
    secondsSince(programStart)
);
```

原来的吞吐量计算继续使用：

```java
successful.get() / ingestSeconds
```

因为它衡量的是**发送期间每秒成功完成多少条**。完整运行时间单独报告，便于看清初始化和导出开销。

**现在先编译，不需要再跑 500,000 条：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
mvn clean package
```

看到 `BUILD SUCCESS` 后，我们再补连接统计，最后一起运行验证。

> ok

接下来把 **WebSocket 连接建立时间加入最终汇总**。目前它只在连接成功时打印，最后的统计里没有保留。

**这一步统计的是 WebSocket 连接。HTTP 连接池的实际连接数量需要另外测量，不能用发送线程数代替。**

只修改 **`client-part2`** 的两个文件。

**1. 修改 `LeaderboardConnection.java`。**

在字段区添加：

```java
private final java.util.List<Double> connectionTimesMs =
    new java.util.ArrayList<>();
```

在 `connectionFinished()` 中找到：

```java
System.out.printf(
    "Resort %d connected in %.3f ms%n",
    resortID, (System.nanoTime() - start) / 1_000_000.0
);
```

替换为：

```java
double connectionMs =
    (System.nanoTime() - start) / 1_000_000.0;

connectionTimesMs.add(connectionMs);

System.out.printf(
    "Resort %d connected in %.3f ms%n",
    resortID, connectionMs
);
```

这样，每次**成功连接，包括成功重连**，都会保存一次建立连接的耗时。失败连接的等待时间不包含在这里。

然后在 `getReconnections()` 方法后面添加：

```java
public synchronized double getAverageConnectionMs() {
  if (connectionTimesMs.isEmpty()) {
    return Double.NaN;
  }

  double total = 0;
  for (double time : connectionTimesMs) {
    total += time;
  }

  return total / connectionTimesMs.size();
}
```

`connectionFinished()` 和这个读取方法都使用 `synchronized`，保护共享列表的访问。

**2. 修改 `LoadClient.java`。**

找到最后打印每个 resort 订阅结果的代码：

```java
System.out.printf(
    "Resort %d: updates=%d, connections=%d, reconnections=%d%n",
    i + 1,
    updates,
    connection.getConnections(),
    connection.getReconnections()
);
```

替换为：

```java
System.out.printf(
    "Resort %d: updates=%d, connections=%d, "
        + "reconnections=%d, avgConnectMs=%.3f%n",
    i + 1,
    updates,
    connection.getConnections(),
    connection.getReconnections(),
    connection.getAverageConnectionMs()
);
```

最终输出就会包含：

```text
Resort 1: updates=..., connections=1, reconnections=0, avgConnectMs=...
```

如果只有一次成功连接，平均值就是那次连接的耗时；有重连时，就是所有成功连接耗时的平均值。

**修改后先编译：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
mvn clean package
```

这一步也不需要运行负载。下一步我们处理 HTTP 连接统计，之后再一起验证。

> next， and how much we left to do till finish this assignment

<details><summary>2 previous messages</summary>

> 本地的核心功能已经跑通；剩下主要是 HTTP 连接统计、EC2 部署、远程性能测试和提交材料。我先核对 Java HTTP 客户端的连接日志设置，给你一个能实际观察连接复用的下一步。
>
> <details><summary>Searched the web</summary>
>
> Searched the web for `site.docs.oracle.com jdk.httpclient.HttpClient.log channel errors requests Java 22`
>
> </details>
</details>

**本地主要功能已经完成，但距离提交还剩 4 个阶段。** 前面让你每加一个小功能就跑一次 500,000 条，步骤太碎了；后面我们把相关修改合并验证。

| 剩余阶段 | 要完成的内容 |
|---|---|
| 1. 收尾检查 | HTTP 连接统计、线程异常处理、重连和计时检查；将必要修正同步到两个客户端 |
| 2. EC2 部署 | 整理 `/server`，部署一个 WAR，验证三个 endpoint |
| 3. 远程性能测试 | 重新测 RTT 和 Little’s Law 预测，比较不同线程数，保存完整测试和图表 |
| 4. 提交材料 | README、最多两页设计文档、截图、结果分析、PDF、Git 仓库链接；AI 使用反思是加分项 |

**代码主体已经写出来了，剩余工作主要在云端测试与报告。** EC2 环境是否已经配置好，会明显影响剩余工作量，因此现在不适合给一个准确完成百分比。

下一步先做一个**小规模 HTTP 连接诊断**，观察共享 `HttpClient` 的连接行为。Java 提供 `channel` 连接日志设置。[Oracle Java 22 文档](https://docs.oracle.com/en/java/javase/22/core/java-networking.html)

**1. 在 `client-part2/LoadClient.java` 暂时改成：**

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;
```

**2. 在客户端 IntelliJ 窗口中配置日志：**

1. 选择 **Run → Edit Configurations**。
2. 左侧选择 **LoadClient**。
3. 如果没有 **VM options**，点击 **Modify options → Add VM options**。
4. 在 **VM options** 中填入：

   ```text
   -Djdk.httpclient.HttpClient.log=channel
   ```

5. 点击 **Apply → OK**。

注意填入 **VM options**，不是 **Program arguments**。

**3. 保持 Tomcat 运行，运行 `client-part2` 的 `LoadClient`。**

这次会打印连接相关日志。它也可能包含 WebSocket 使用的连接，因此**不能直接把所有连接日志行相加作为 HTTP 连接数**；我们要先看日志格式，再区分连接建立、重复引用和关闭。

**4. 把这次控制台输出贴给我。**

诊断结束后，删除刚才添加的 VM option，避免日志影响之后的性能测试。这次的吞吐量不作为正式性能结果。

这一步是收集连接统计的依据，还没有完成最终的自动汇总。我们看过实际日志后，再确定统计方式，不会把“32 个线程”误当成“32 条实际连接”。

> User context
>
> Attachments:
> - nels.SocketChannel[connected local=/127.0.0.1:64401 remote=localhost/127.0.0.1:…: `~/.codex/attachments/1ac5a78b-bef6-4491-a8af-41ff8f1eefe9/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查连接日志，区分实际连接和重复打印的记录。
>
> <details><summary>Ran 3 commands</summary>
>
> - Ran `head -45 '/Users/anne/.codex/attachments/1ac5a78b-bef6-4491-a8af-41ff8f1eefe9/Pasted text.txt'`
> - Ran `tail -65 '/Users/anne/.codex/attachments/1ac5a78b-bef6-4491-a8af-41ff8f1eefe9/Pasted text.txt'`
> - Ran `python3 - <<'PY'
> from pathlib import Path
> import re,collections
> s=Path('/Users/anne/.codex/attachments/1ac5a78b-bef6-4491-a8af-41ff8f1eefe9/Pasted text.txt').read_text()
> p=collections.Counter(re.findall(r'local=/127\.0\.0\.1:(\d+)',s))
> print('Lines:',len(s.splitlines()))
> print('Distinct observed local ports:',len(p))
> print('Most frequent:',p.most_common(3))
> print('Connection creation lines:',len(re.findall(r'(?i)channel:.*(?:connect|open)',s)))
> PY`
>
> </details>
</details>

**这次运行正常，连接日志也提供了有用的信息。**

- 5,000 条请求全部成功。
- 10 个 WebSocket 订阅都连接成功，没有重连。
- 连接建立耗时和完整运行时间已经出现在汇总里。
- 我在这份日志中找到 **42 个不同的本地端口**；同一个端口反复出现，说明日志行数不能当成连接数。

42 个端口与“约 32 条 HTTP 连接 + 10 条 WebSocket 连接”的情况一致，**但目前不能把这个推测当成精确统计**：你贴的文件从半行开始，缺少开头，而且并发日志会交错，不能根据相邻行准确关联请求和连接。

另外，这次开启了大量诊断日志，**不要把这次的吞吐量和延迟用于正式性能比较**。

现在先做两件事：

1. 在 **Run → Edit Configurations → LoadClient → VM options** 中删除：

   ```text
   -Djdk.httpclient.HttpClient.log=channel
   ```

2. 保留当前 5,000 条的小规模配置，暂时不用再运行。

**我需要修正一下前面的安排：`channel` 日志适合检查连接行为，但不适合直接作为程序最终输出的连接计数。** 我们不继续靠手工数日志来完成这一项，也不会声称 HTTP 连接统计已经完成。

接下来做一次集中代码检查，重点处理发送线程异常是否会被隐藏、WebSocket 重连时的状态竞争，以及确定可靠的 HTTP 连接统计方式。完成这些收尾后就进入 EC2 部署，避免继续零碎地重复运行测试。

> next

<details><summary>2 previous messages</summary>

> 我会集中检查两个客户端的线程、重连和统计代码，先定位需要修正的地方，再按顺序带你修改。
>
> <details><summary>Explored a list, ran a command</summary>
>
> <details><summary>Explored a list</summary>
>
> - Listed files
>
> </details>
>
> - Ran `cat client-part2/src/main/java/LeaderboardConnection.java client-part2/src/main/java/LoadClient.java`
>
> </details>
</details>

检查发现一个需要修正的问题：**发送线程发生未捕获异常时，当前主程序可能不知道，仍继续打印结果。**

原因是我们调用了：

```java
executor.submit(...)
```

却没有读取它返回的 `Future`。`Future` 保存任务的完成结果；调用 `get()` 才能把任务中的异常传回主线程。

这次先修正这个问题，**不需要跑完整负载测试**。

**1. 打开 `client-part2` 的 `LoadClient.java`。**

把整个 `runPhase()` 方法替换为：

```java
private static void runPhase(
    int threads,
    int maxEventsPerTask,
    BlockingQueue<SwipeEvent> queue,
    HttpClient client,
    String baseUrl,
    AtomicInteger successful,
    AtomicInteger failed,
    SwipeMetrics metrics
) throws InterruptedException {

  ExecutorService executor =
      Executors.newFixedThreadPool(threads);

  List<Future<?>> tasks = new ArrayList<>();

  int completedBefore = successful.get() + failed.get();
  int queuedBefore = queue.size();

  int expected = (int) Math.min(
      (long) queuedBefore,
      (long) threads * maxEventsPerTask
  );

  try {
    for (int i = 0; i < threads; i++) {
      tasks.add(executor.submit(new SwipeSender(
          queue, client, baseUrl,
          successful, failed, maxEventsPerTask, metrics
      )));
    }

    executor.shutdown();

    if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
      throw new IllegalStateException("Phase timed out");
    }

    // 线程结束不一定代表成功，需要检查任务结果。
    for (Future<?> task : tasks) {
      try {
        task.get();
      } catch (ExecutionException e) {
        throw new IllegalStateException(
            "Sender task failed", e.getCause()
        );
      }
    }

    int completed =
        successful.get() + failed.get() - completedBefore;

    if (completed != expected
        || queue.size() != queuedBefore - expected) {
      throw new IllegalStateException(
          "Incomplete phase: expected=" + expected
              + ", completed=" + completed
              + ", remaining=" + queue.size()
      );
    }

  } finally {
    executor.shutdownNow();
  }
}
```

新增了两层检查：

- **任务异常检查**：发送任务意外抛出异常时，明确报告错误。
- **数量检查**：本阶段的成功数加最终失败数，必须等于应处理的事件数；队列也必须减少相同数量。

例如正式预热阶段：

```text
本阶段应处理：32 × 1,000 = 32,000
本阶段成功数 + 最终失败数：必须是 32,000
```

**2. 同步修正 `client-part1` 的 `LoadClient.java`。**

使用同一段方法，但删除两处 `metrics`：

方法参数最后改为：

```java
AtomicInteger successful,
AtomicInteger failed
) throws InterruptedException {
```

创建发送任务时改为：

```java
tasks.add(executor.submit(new SwipeSender(
    queue, client, baseUrl,
    successful, failed, maxEventsPerTask
)));
```

两个项目已有的 `java.util.concurrent.*`、`List` 和 `ArrayList` 导入足够使用。

**3. 分别编译两个项目：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part1
mvn clean package
```

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
mvn clean package
```

两个都显示 `BUILD SUCCESS` 即可。这一步解决发送任务异常被隐藏的问题；下一步修正 WebSocket 的旧连接回调可能影响新连接的问题。

> next

:codex-annotation{index="1"} 上一步的线程异常修正需要应用到两个客户端。接下来这个 **WebSocket 重连修正也要在 `client-part1` 和 `client-part2` 中各做一次**。

问题是：旧连接的断开通知可能来得比较晚。如果此时新连接已经建立，旧通知可能把新连接关闭。解决方法是给每次连接一个编号，只处理当前编号的通知。

**1. 在两个项目的 `LeaderboardConnection.java` 字段区添加：**

```java
private long connectionGeneration;
private boolean disconnectedDuringConnect;
```

**2. 在 `connect()` 方法中，找到：**

```java
connecting = true;
```

紧接着添加：

```java
long generation = ++connectionGeneration;
disconnectedDuringConnect = false;
```

然后修改创建监听器的代码。

`client-part1` 使用：

```java
LeaderboardSubscriber subscriber =
    new LeaderboardSubscriber(
        resortID, () -> disconnected(generation)
    );
```

`client-part2` 使用：

```java
LeaderboardSubscriber subscriber =
    new LeaderboardSubscriber(
        resortID, () -> disconnected(generation), pushMetrics
    );
```

两者唯一的区别是 Part 2 额外传入指标对象。

**3. 两个项目都修改 `whenComplete()`：**

```java
.whenComplete((connectedSocket, error) ->
    connectionFinished(
        connectedSocket, error, start, generation
    ));
```

再修改 `connectionFinished()` 的方法声明，并在方法开头加入检查：

```java
private synchronized void connectionFinished(
    WebSocket connectedSocket,
    Throwable error,
    long start,
    long generation) {

  if (generation != connectionGeneration) {
    if (connectedSocket != null) {
      connectedSocket.abort();
    }
    return;
  }

  connecting = false;

  // 后面保留原来的 stopped、error 和成功连接处理代码。
```

这里的注释是说明位置，**不要删除后面的原有代码**。

在这个方法末尾，把：

```java
if (socket.isInputClosed() || socket.isOutputClosed()) {
  disconnected();
}
```

替换为：

```java
if (disconnectedDuringConnect
    || socket.isInputClosed()
    || socket.isOutputClosed()) {
  disconnected(generation);
}
```

这样也能处理握手完成前已经收到断开通知的情况。

**4. 把整个 `disconnected()` 方法替换为：**

```java
private synchronized void disconnected(long generation) {
  if (stopped || generation != connectionGeneration) {
    return;
  }

  if (connecting) {
    disconnectedDuringConnect = true;
    return;
  }

  WebSocket oldSocket = socket;
  socket = null;

  if (oldSocket != null) {
    oldSocket.abort();
  }

  scheduleRetry();
}
```

先将 `socket` 清空，再关闭旧连接，避免关闭操作触发回调时再次使用它。

**5. 在 `close()` 中，紧接着：**

```java
stopped = true;
```

添加：

```java
connectionGeneration++;
```

这会使关闭前所有尚未到达的回调编号失效。

**修改完后，分别编译两个项目：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part1
mvn clean package
```

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
mvn clean package
```

这次先检查编译，不用运行 500,000 条。现在每次连接的回调都携带自己的编号，旧连接的通知就不会误操作新连接。

> both build success

两个项目都编译通过了。**接下来验证刚修改过的重连逻辑，不跑 500,000 条。**

按下面顺序操作：

1. **保持 Tomcat 运行。**

2. 在 **`client-part2`** 窗口打开 `SubscriberDemo.java`，确认观察时间是：

   ```java
   Thread.sleep(180_000);
   ```

   这表示程序持续运行 3 分钟，给你时间操作。

3. 运行 **`SubscriberDemo.main()`**。

4. **看到 `Connected to resort 7` 后，立即去服务器窗口停止 Tomcat。不要停止客户端，也不用先等 3 分钟。**

5. 客户端出现 `reconnecting` 后，重新启动 Tomcat。

6. 等待客户端再次显示 `Connected to resort 7`，并继续收到排行榜消息。

7. 让客户端自动结束，检查：

   ```text
   Connections: 2
   Reconnections: 1
   ```

如果期间有额外断线，数字可能更高；关键是服务器恢复后，订阅能恢复接收。

这验证正常的断线恢复流程；旧回调竞争的问题主要靠刚加入的连接编号检查防护。把最后的汇总贴给我，之后我们进入 **整理 `/server` 和 EC2 部署**。

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=64622:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part2/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SubscriberDemo
> 观察 60 秒：连接成功后停止 Tomcat，再重新启动 Tomcat。
> Connected to resort 7
> Resort 7 connected in 176.156 ms
> Resort 7, update 1, server time: 2026-10-15T12:35:57.270225Z, received at: 2026-10-15T12:35:57.309189Z
> Resort 7, update 2, server time: 2026-10-15T12:35:57.771417Z, received at: 2026-10-15T12:35:57.772263Z
> Resort 7, update 3, server time: 2026-10-15T12:35:58.274414Z, received at: 2026-10-15T12:35:58.276405Z
> Resort 7 reconnecting in 1 seconds
> Resort 7 reconnecting in 2 seconds
> Resort 7 reconnecting in 4 seconds
> Resort 7 reconnecting in 8 seconds
> Resort 7 reconnecting in 16 seconds
> Resort 7 reconnecting in 16 seconds
> Connected to resort 7
> Resort 7 connected in 85.549 ms
> Resort 7, update 1, server time: 2026-10-15T12:36:53.424381Z, received at: 2026-10-15T12:36:53.471124Z
> Resort 7, update 2, server time: 2026-10-15T12:36:53.919479Z, received at: 2026-10-15T12:36:53.920545Z
> Resort 7, update 3, server time: 2026-10-15T12:36:54.423168Z, received at: 2026-10-15T12:36:54.425352Z
> Updates: 266
> Connections: 2
> Reconnections: 1
>
> Process finished with exit code 0

**重连测试通过：`Connections: 2`、`Reconnections: 1`，恢复连接后也继续收到了排行榜。** 日志里的“观察 60 秒”只是提示文字；如果你已把等待时间改成 180 秒，记得同步修改提示，避免混淆。

现在整理作业要求的 **`/server` 文件夹**，为 EC2 部署准备 WAR。

**1. 在终端执行：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem
mkdir -p server
cp lab2/ski-servlet/pom.xml server/pom.xml
cp -R lab2/ski-servlet/src server/
cp -R lab2/ski-servlet/web server/
```

复制后结构是：

```text
CS6650distributedsystem/
├── lab2/ski-servlet/    ← 原来的 Lab 2 项目
├── server/             ← Assignment 1 服务器
├── client-part1/
└── client-part2/
```

**2. 在 IntelliJ 选择 File → Open，打开 `server`，选择 New Window。**

打开新项目的 `pom.xml`，将：

```xml
<groupId>cs6650.lab2</groupId>
```

改成：

```xml
<groupId>cs6650.assignment1</groupId>
```

继续保留：

```xml
<packaging>war</packaging>
```

以及：

```xml
<finalName>ski-servlet</finalName>
```

这样输出仍然叫 `ski-servlet.war`，后续请求地址中的 `/ski-servlet` 不需要改变。

**3. 编译新服务器项目：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/server
mvn clean package
```

预期生成：

```text
server/target/ski-servlet.war
```

**目前不需要在新窗口再启动一个 Tomcat。** 这一步只是整理和打包，之后会把这个 WAR 部署到 EC2。

另外，为了接下来给你正确的部署步骤：**你在 Lab 1 是否已经创建过 EC2 实例，并用 SSH 登录过？** 如果有，我们优先沿用；如果没有，就从创建实例开始。

> 有

那我们优先使用你 Lab 1 的 EC2 实例。

**现在先确认实例和登录信息，不需要创建新实例。**

1. 打开 AWS Console → **EC2 → Instances**。
2. 确认右上角区域是 **Oregon / `us-west-2`**，这是作业指定的区域。
3. 找到 Lab 1 的实例，查看状态。如果是 `Stopped`，选中它 → **Instance state → Start instance**，等它变成 `Running`。
4. 复制当前的 **Public IPv4 address**。实例停止后再启动，公网 IP 可能改变。
5. 在 Mac 终端使用 Lab 1 的 SSH 命令连接，替换成当前 IP。

例如，你以前使用 Ubuntu 时，命令通常是：

```bash
ssh -i /你的密钥路径/lab1.pem ubuntu@当前公网IP
```

**请沿用你之前成功使用的用户名和密钥路径**，不要直接复制示例里的占位文字。

登录成功后，在 **EC2 的终端**执行：

```bash
java -version
```

```bash
pwd
```

```bash
ls
```

把这三个命令的输出，以及你之前的 Tomcat 安装路径（如果记得）贴给我。**不要贴 `.pem` 私钥文件的内容。** 我们接着确认 Tomcat 9 的位置，再上传刚生成的 WAR。

> \
> **Instance ID**
> i-0d12f2707bcc779d5
> **Public IPv4 address**
> 54.201.46.55 | [open address ](https://54.201.46.55/)
> **Private IPv4 addresses**
> 172.31.45.254
> **IPv6 address**
> –
> **Instance state**
> Running
> **Public DNS 密钥是这个吗cs6650-lab1-key.pem（在CS6650distributedsystem folder里）**

对，**`cs6650-lab1-key.pem` 是 SSH 私钥文件的名称**；`Public DNS` 是 EC2 的网络地址，两者不是同一项。是否与这台实例匹配，可以在 EC2 实例详情的 **Key pair name** 中确认，应该显示 `cs6650-lab1-key`。

现在在 **Mac 终端**执行：

```bash
cd /Users/anne/Desktop/CS6650distributedsystem
chmod 400 cs6650-lab1-key.pem
```

`chmod 400` 将私钥设置为只有你可以读取。

如果 Lab 1 使用的是 **Ubuntu**，接着执行：

```bash
ssh -i cs6650-lab1-key.pem ubuntu@54.201.46.55
```

如果使用的是 **Amazon Linux**，则执行：

```bash
ssh -i cs6650-lab1-key.pem ec2-user@54.201.46.55
```

**选与你实例系统对应的一条即可。** 登录成功后，在 EC2 终端运行：

```bash
java -version
pwd
ls
```

把输出贴给我。如果 SSH 报错，贴报错文字即可。这个 `.pem` 文件不要上传到 Git 仓库，也不要粘贴其中的内容。

> 这是lab1的配置
>
> User context
>
> Attachments:
> - # CS6650 Lab 1 - AWS EC2 Setup with Tomcat ## Overview This lab guides you thro…: `~/.codex/attachments/f7dc48a2-7139-47d7-a1cb-03d43a96bc4d/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先看 Lab 1 的系统和 Tomcat 安装步骤，确认你应该使用哪条 SSH 命令。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/f7dc48a2-7139-47d7-a1cb-03d43a96bc4d/Pasted text.txt`
>
> </details>
</details>

看过了。**Lab 1 指定 Amazon Linux 2023，所以 SSH 用户名用 `ec2-user`。** 按讲义安装的话，Tomcat 路径是 `/opt/tomcat9`，我们登录后确认一下。

**1. 在 Mac 终端运行：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem
chmod 400 cs6650-lab1-key.pem
ssh -i cs6650-lab1-key.pem ec2-user@54.201.46.55
```

登录成功后，提示符会类似：

```text
[ec2-user@ip-172-31-45-254 ~]$
```

这表示你现在操作的是 **EC2 上的 Linux**。

**2. 登录后，在同一个终端逐条运行：**

检查 Java：

```bash
java -version
```

检查 Tomcat 安装版本：

```bash
/opt/tomcat9/bin/version.sh
```

检查 8080 端口是否已经有服务运行：

```bash
sudo ss -tlnp 'sport = :8080'
```

**把这三个命令的输出贴给我。** 先不用重新安装或启动 Tomcat；确认当前状态后，我们再上传 `server/target/ski-servlet.war`。

> Last login: Wed Oct 14 21:21:18 on ttys003
> (base) anne@sousousous-MacBook-Air \~ % cd /Users/anne/Desktop/CS6650distributedsystem
> chmod 400 cs6650-lab1-key.pem
> (base) anne@sousousous-MacBook-Air CS6650distributedsystem % cd /Users/anne/Desktop/CS6650distributedsystem
> chmod 400 cs6650-lab1-key.pem
> ssh -i cs6650-lab1-key.pem ec2-user@54.201.46.55
> The authenticity of host '54.201.46.55 (54.201.46.55)' can't be established.
> ED25519 key fingerprint is: SHA256:yYKXvhU+EX38y4SHHCG/0EPxvq6VQFbgxMDbrWQkLW0
> This host key is known by the following other names/addresses:
>     \~/.ssh/known_hosts:14: 18.236.196.250
>     \~/.ssh/known_hosts:16: 54.149.165.21
>     \~/.ssh/known_hosts:17: 16.145.97.122
>     \~/.ssh/known_hosts:18: 35.90.90.200
> Are you sure you want to continue connecting (yes/no/[fingerprint])? yes
> Warning: Permanently added '54.201.46.55' (ED25519) to the list of known hosts.
> \*\* WARNING: connection is not using a post-quantum key exchange algorithm.
> \*\* This session may be vulnerable to "store now, decrypt later" attacks.
> \*\* The server may need to be upgraded. See [https://openssh.com/pq.html](https://openssh.com/pq.html)
>
> A newer release of "Amazon Linux" is available.
>   Version 2023.12.20260914:
>   Version 2023.12.20260917:
>   Version 2023.12.20260918:
>   Version 2023.12.20260928:
>   Version 2023.12.20260930:
>   Version 2023.12.20261012:
>   Version 2023.12.20261013:
> Run "/usr/bin/dnf check-release-update" for full release and version update info
>    ,     #\_
>    \~\\\_  ####\_        Amazon Linux 2023
>   \~\~  \\\_#####\\
>   \~\~     \\###|
>   \~\~       \\#/ \_\_\_   [https://aws.amazon.com/linux/amazon-linux-2023](https://aws.amazon.com/linux/amazon-linux-2023)
>    \~\~       V\~' '->
>     \~\~\~         /
>       \~\~.\_.   \_/
>          \_/ \_/
>        \_/m/'
> Last login: Thu Oct 15 00:49:06 2026 from 24.22.139.124
> [ec2-user@ip-172-31-45-254 \~]$ java -version
> openjdk version "11.0.32.1" 2026-08-18 LTS
> OpenJDK Runtime Environment Corretto-11.0.32.10.1 (build 11.0.32.1+10-LTS)
> OpenJDK 64-Bit Server VM Corretto-11.0.32.10.1 (build 11.0.32.1+10-LTS, mixed mode)
> [ec2-user@ip-172-31-45-254 \~]$ /opt/tomcat9/bin/version.sh
> Using CATALINA_BASE:   /opt/tomcat9
> Using CATALINA_HOME:   /opt/tomcat9
> Using CATALINA_TMPDIR: /opt/tomcat9/temp
> Using JRE_HOME:        /usr
> Using CLASSPATH:       /opt/tomcat9/bin/bootstrap.jar:/opt/tomcat9/bin/tomcat-juli.jar
> Using CATALINA_OPTS:
> NOTE: Picked up JDK_JAVA_OPTIONS:  --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED
> Server version: Apache Tomcat/9.0.121
> Server built:   Aug 12 2026 19:38:16 UTC
> Server number:  9.0.121.0
> OS Name:        Linux
> OS Version:     6.18.44-99.149.amzn2023.x86_64
> Architecture:   amd64
> JVM Version:    11.0.32.1+10-LTS
> JVM Vendor:     Amazon.com Inc.
> APR loaded:     false
>
> Third-party libraries:
>   ecj-4.20.jar: 3.26.0.v20210609-0549
> [ec2-user@ip-172-31-45-254 \~]$ sudo ss -tlnp 'sport = :8080'
> State   Recv-Q   Send-Q     Local Address:Port     Peer Address:Port  Process
> [ec2-user@ip-172-31-45-254 \~]$

环境符合要求：**Java 11、Tomcat 9 都已安装。8080 没有监听进程，说明目前没有服务在这个端口运行。**

现在上传 WAR 并启动 EC2 上的 Tomcat。注意区分两个终端。

**1. 保留当前 EC2 终端，在 Mac 新开一个终端窗口。**

在新的 **Mac 终端**执行：

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/server
mvn clean package
```

看到 `BUILD SUCCESS` 后，上传 WAR 到 EC2 用户目录：

```bash
scp -i /Users/anne/Desktop/CS6650distributedsystem/cs6650-lab1-key.pem \
  target/ski-servlet.war \
  ec2-user@54.201.46.55:/home/ec2-user/ski-servlet.war
```

**2. 回到原来的 EC2 终端。**

提示符应是：

```text
[ec2-user@ip-172-31-45-254 ~]$
```

依次执行：

```bash
cp /home/ec2-user/ski-servlet.war /opt/tomcat9/webapps/ski-servlet.war
```

```bash
/opt/tomcat9/bin/startup.sh
```

这会部署当前作业版本；如果已有同名 WAR，会替换它。

**3. 等几秒，在 EC2 终端检查启动和部署情况：**

```bash
sudo ss -tlnp 'sport = :8080'
```

```bash
tail -40 /opt/tomcat9/logs/catalina.out
```

再测试健康检查：

```bash
curl -i http://localhost:8080/ski-servlet/health
```

预期返回：

```text
HTTP/1.1 200
```

```json
{"status":"UP"}
```

**这里的 `localhost` 指 EC2 本身，因为命令是在 EC2 终端运行的。** 把这三条检查命令的输出贴给我；确认部署成功后，我们再从你的 Mac 访问公网地址。

> User context
>
> Attachments:
> - [ec2-user@ip-172-31-45-254 ~]$ java -version openjdk version "11.0.32.1" 2026-0…: `~/.codex/attachments/7f2270e8-90c1-4e92-bb9e-e0f40304527b/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看 EC2 的启动日志和健康检查结果。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/7f2270e8-90c1-4e92-bb9e-e0f40304527b/Pasted text.txt`
>
> </details>
</details>

**EC2 部署成功了。** 日志确认：

- Tomcat 正在监听 **8080**。
- `ski-servlet.war` 部署完成。
- `/health` 返回 **200** 和 `{"status":"UP"}`。

下一步确认**你的 Mac 可以通过公网访问 EC2**。

**请切到 Mac 终端窗口**，提示符应类似：

```text
(base) anne@... %
```

不要在 `[ec2-user@...]$` 的 SSH 窗口执行下面的测试。

**1. 测试公网健康检查：**

```bash
curl --connect-timeout 5 --max-time 15 -i \
  http://54.201.46.55:8080/ski-servlet/health
```

预期：**200**。

**2. 测试有效滑雪记录：**

```bash
curl --connect-timeout 5 --max-time 15 -i \
  -X POST \
  'http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":21}'
```

预期：**201**。

**3. 测试无效记录：**

```bash
curl --connect-timeout 5 --max-time 15 -i \
  -X POST \
  'http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123' \
  -H 'Content-Type: application/json' \
  -d '{"time":217,"liftID":41}'
```

预期：**400**。

把这三个结果贴给我。如果第一条连接超时，先停在这里，贴出报错；我们检查安全组的 8080 入站规则。通过后，再验证远程 WebSocket 并测量远程 RTT。

> (base) anne@sousousous-MacBook-Air server % curl --connect-timeout 5 --max-time 15 -i \\
>   [http://54.201.46.55:8080/ski-servlet/health](http://54.201.46.55:8080/ski-servlet/health)
> HTTP/1.1 200
> **Content-Type**: application/json;charset=UTF-8
> **Content-Length**: 15
> **Date**: Thu, 15 Oct 2026 13:15:06 GMT
>
> {"status":"UP"}**%**                                                                (base) anne@sousousous-MacBook-Air server % curl --connect-timeout 5 --max-time 15 -i \\
>   -X POST \\
>   '[http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123](http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123)' \\
>   -H 'Content-Type: application/json' \\
>   -d '{"time":217,"liftID":21}'
> HTTP/1.1 201
> **Content-Type**: application/json;charset=UTF-8
> **Content-Length**: 92
> **Date**: Thu, 15 Oct 2026 13:15:12 GMT
>
> {"message":"Lift ride recorded: skier 123, resort 1, season 2026, day 1, time 217, lift 21"}**%**                                                                   (base) anne@sousousous-MacBook-Air server % curl --connect-timeout 5 --max-time 15 -i \\
>   -X POST \\
>   '[http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123](http://54.201.46.55:8080/ski-servlet/skiers/1/seasons/2026/days/1/skiers/123)' \\
>   -H 'Content-Type: application/json' \\
>   -d '{"time":217,"liftID":41}'
> HTTP/1.1 400
> **Content-Type**: application/json;charset=UTF-8
> **Content-Length**: 31
> **Date**: Thu, 15 Oct 2026 13:15:24 GMT
> **Connection**: close
>
> {"message":"invalid JSON body"}**%**                                                (base) anne@sousousous-MacBook-Air server %

**公网 HTTP 测试全部通过：健康检查 200、有效记录 201、无效记录 400。** 你的 Mac 已能正常访问 EC2。

接下来验证远程 WebSocket，并测远程请求延迟。**这次保持 EC2 的 Tomcat 运行，不要停止它。**

**1. 在 `client-part2` 的 `SubscriberDemo.java` 中修改地址。**

把：

```java
"ws://localhost:8080/ski-servlet"
```

改成：

```java
"ws://54.201.46.55:8080/ski-servlet"
```

把等待时间改为：

```java
Thread.sleep(10_000);
```

提示文字改成：

```java
System.out.println("接收 EC2 排行榜 10 秒，然后结束。");
```

运行 **`SubscriberDemo.main()`**。预期：

```text
Connected to resort 7
...
Updates: 大约 20
Connections: 1
Reconnections: 0
```

**保存这段输出的截图**，作为远程 WebSocket 测试证据。

**2. 在 `client-part2` 的 `SequentialClient.java` 中修改 URL：**

```java
String url =
    "http://54.201.46.55:8080/ski-servlet"
    + "/skiers/1/seasons/2026/days/1/skiers/123";
```

其余代码保持原样：20 次预热，随后测量 100 次请求。

等 `SubscriberDemo` 结束后，运行 **`SequentialClient.main()`**，记录：

```text
Successful: ...
Failed: ...
Average latency: ... ms
Measured throughput: ... swipes/sec
Initial prediction for 32 senders: ... swipes/sec
```

这次测的是 **Mac → 网络 → EC2 → Mac** 的请求耗时，适合用来预测后续远程负载测试的吞吐量。

把这两个程序的结果贴给我。**暂时不用运行 `LoadClient` 的 500,000 条测试。**

> /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=49363:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part2/target/classes:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SequentialClient
> Successful: 100
> Failed: 0
> Average latency: 27.006 ms
> Measured throughput: 37.03 swipes/sec
> Initial prediction for 32 senders: 1184.94 swipes/sec
>
> Process finished with exit code 0
>
> User context
>
> Images:
> - `~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/wxid_0ajsuian648e22_7738/temp/RWTemp/2026-10/9e20f478899dc29eb19741386f9343c8/90be34db1d323ddb1211007e64b1431e.png`

**远程 HTTP 和 WebSocket 都通过了。**

- HTTP：100 条全部成功，平均 RTT **27.006 ms**。
- WebSocket：10 秒收到 **20 条**更新，连接成功一次，无重连。

32 个发送线程的初步预测是：

```text
吞吐量 ≈ 32 / 0.027006 ≈ 1,185 条/秒
500,000 条预计耗时 ≈ 422 秒 ≈ 7 分钟
```

这是假设并发后响应时间不变的估计，实际结果需要测试。

**截图里还有一个重要现象：部分客户端接收时间早于服务器发送时间。** 例如第一条消息约为：

```text
客户端接收时间 − 服务器时间 ≈ −86.6 ms
```

这说明两台机器的时钟存在偏差，当前时间差不能直接当成真实网络延迟。HTTP 使用 `System.nanoTime()` 测量同一台机器上的耗时，不受这个跨机器时钟差影响。

在正式远程负载测试前，先检查时间同步：

**1. 在 EC2 的 SSH 终端执行：**

```bash
timedatectl status
```

```bash
chronyc tracking
```

如果第二条提示找不到命令，把提示贴给我即可。

**2. 在 Mac 上打开：**

**系统设置 → 通用 → 日期与时间**

确认 **“自动设置时间和日期”** 已开启。如果原来关闭，请开启。

先把 EC2 两条命令的输出贴给我。我们保留负延迟原始值，不把它改成零；确认时钟同步状态后，再进行远程负载测试，并在报告中说明时钟偏差的影响。

> {"status":"UP"}[ec2-user@ip-172-31-45-254 ~]$ timedatectl status
>                Local time: Thu 2026-10-15 13:28:30 UTC
>            Universal time: Thu 2026-10-15 13:28:30 UTC
>                  RTC time: Thu 2026-10-15 13:28:29
>                 Time zone: n/a (UTC, +0000)
> System clock synchronized: yes
>               NTP service: active
>           RTC in local TZ: no
> [ec2-user@ip-172-31-45-254 ~]$ chronyc tracking
> Reference ID    : A9FEA97B (169.254.169.123)
> Stratum         : 4
> Ref time (UTC)  : Thu Oct 15 13:28:26 2026
> System time     : 0.000001355 seconds fast of NTP time
> Last offset     : +0.000001589 seconds
> RMS offset      : 0.000011079 seconds
> Frequency       : 1.996 ppm slow
> Residual freq   : +0.005 ppm
> Skew            : 0.096 ppm
> Root delay      : 0.000457902 seconds
> Root dispersion : 0.000387028 seconds
> Update interval : 16.1 seconds
> Leap status     : Normal

**EC2 的时间同步正常。** 输出显示：

- `System clock synchronized: yes`
- `NTP service: active`
- 相对时间源的偏差约 **1.355 微秒**
- `Leap status: Normal`

这些结果说明 EC2 当前同步状态良好，但不能证明 Mac 与 EC2 的时钟完全一致。请确认 Mac 的**自动设置时间和日期**已开启；推送延迟仍保留原始值，并在报告中说明跨机器时钟偏差。

现在进行 **5,000 条远程联合测试**，确认负载和订阅一起工作。

**1. 在 `client-part2/LoadClient.java` 中修改两个地址。**

HTTP 地址：

```java
String baseUrl = "http://54.201.46.55:8080/ski-servlet";
```

创建 `LeaderboardConnection` 时的 WebSocket 地址：

```java
"ws://54.201.46.55:8080/ski-servlet"
```

**两个都要修改**，否则可能出现 HTTP 发到 EC2、排行榜却连到本机的情况。

**2. 保留小规模配置：**

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;
```

确认运行配置中已经删除：

```text
-Djdk.httpclient.HttpClient.log=channel
```

**3. 在 Mac 的 IntelliJ 中运行 `client-part2` 的 `LoadClient.main()`。**

保持 EC2 Tomcat 运行，等客户端自动结束。本机 IntelliJ 的 Tomcat 不参与这次测试。

预期：

```text
Warmup successful: 320
Main successful: 4680
Total successful: 5000
Failed: 0
Remaining in queue: 0
```

把最后的汇总贴给我，包括 **HTTP 和 WebSocket 延迟统计**。通过这次联合测试后，再恢复 500,000 条配置进行正式远程测试。

> 之前这些截图我没保存怎么办Screenshot of Part 1 output (basic metrics, warmup reported separately)
>
> - Screenshot of Part 2 output (ingest + push-latency statistics)
> - Throughput-over-time chart
> - Evidence of EC2 deployment (EC2 console screenshot)

**没关系，可以重新运行后截图，不需要重做代码。** 之前主要是本地测试，我们接下来本来就要保存正式的 **EC2 远程测试结果**。

这里的 **Part 1 output 和 Part 2 output 指两个客户端版本的输出**。

| 提交材料 | 接下来怎么补 |
|---|---|
| Part 1 输出截图 | 用 `client-part1` 向 EC2 发送完整 500,000 条，结束后截图，包含独立的 Warmup 汇总 |
| Part 2 输出截图 | 用 `client-part2` 向 EC2 发送完整 500,000 条，截图包含 HTTP 和 WebSocket 延迟统计；一屏放不下可分两张 |
| 吞吐量折线图 | 用正式远程测试生成的 CSV 重新画图；已有本地图也可以保留 |
| EC2 部署截图 | 现在就能补：在 EC2 Console 选中实例，显示实例 ID、Running 状态、公网 IP 和区域 |

**先创建保存材料的文件夹。** 在 Mac 终端执行：

```bash
mkdir -p /Users/anne/Desktop/CS6650distributedsystem/results/screenshots
mkdir -p /Users/anne/Desktop/CS6650distributedsystem/results/ec2
```

Mac 按 **Shift + Command + 4** 可以选区域截图，再把截图移到 `results/screenshots`。建议命名：

```text
ec2-console.png
client-part1-ec2.png
client-part2-ec2-ingest.png
client-part2-ec2-push.png
websocket-ec2.png
```

你刚才发来的 **WebSocket 收到 20 条更新的截图**也可以保存，作业还要求这项证据。

**现在先补 EC2 Console 截图，然后继续刚才的 5,000 条远程联合测试。** 测试通过后，我们再分别运行两个客户端的正式测试；到时我会明确提醒你保存哪些输出。已有 CSV 不依赖截图，仍然可以用于分析和重新画图。

> 好

现在继续 **5,000 条远程联合测试**。

在 **`client-part2` 的 `LoadClient.java`** 中确认：

```java
int eventCount = 5_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 10;
int mainThreads = 32;

String baseUrl = "http://54.201.46.55:8080/ski-servlet";
```

创建 `LeaderboardConnection` 的地方，地址也必须是：

```java
"ws://54.201.46.55:8080/ski-servlet"
```

然后：

1. 保持 **EC2 的 Tomcat 运行**。
2. 在 Mac 的 IntelliJ 中运行 **`client-part2 → LoadClient.main()`**。
3. 等程序自动结束，把从 `--- Warmup ---` 开始到最后的统计贴给我。

这次是小规模检查，**暂时不用作为正式提交截图**。通过后，我们再运行完整 500,000 条并保存证据。

> User context
>
> Attachments:
> - import java.net.URI; import java.net.http.HttpClient; import java.net.http.WebS…: `~/.codex/attachments/0a2a9754-831a-4ffb-b8c1-ea94f4383cce/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我看一下你贴的代码，指出地址具体在哪个文件里修改。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/0a2a9754-831a-4ffb-b8c1-ea94f4383cce/Pasted text.txt`
>
> </details>
</details>

:codex-annotation{index="1"} 你贴的是 **`LeaderboardConnection.java`**。地址是在 **`LoadClient.java` 创建这个对象时传进去的**，所以这次不用改你贴的文件。

**打开 `client-part2/src/main/java/LoadClient.java`**，按 **Command + F** 搜索：

```text
new LeaderboardConnection
```

找到这段，把地址改成下面这样：

```java
LeaderboardConnection connection =
    new LeaderboardConnection(
        client,
        "ws://54.201.46.55:8080/ski-servlet",
        resortID,
        pushMetrics
    );
```

你贴的类中，这一行：

```java
this.uri = URI.create(wsBaseUrl + "/leaderboard/" + resortID);
```

会自动把传入的地址拼成，例如：

```text
ws://54.201.46.55:8080/ski-servlet/leaderboard/7
```

所以**传入的地址只写到 `/ski-servlet`，不要额外加 `/leaderboard/7`**。

同一个 `LoadClient.java` 中，还要确认 HTTP 地址是：

```java
String baseUrl = "http://54.201.46.55:8080/ski-servlet";
```

保存后，保持 5,000 条配置，运行 **`LoadClient.main()`**。

> --- Warmup ---
> Successful: 320
> Failed: 0
> Runtime: 0.542 seconds
>
> --- Main ---
> Successful: 4680
> Failed: 0
> Runtime: 5.414 seconds
>
> --- Total ingest ---
> Successful: 5000
> Failed: 0
> Remaining in queue: 0
> Runtime: 5.956 seconds
> Throughput: 839.45 swipes/sec
>
> --- Throughput per resort ---
> Resort 1: successful=540, throughput=90.66 swipes/sec
> Resort 2: successful=511, throughput=85.79 swipes/sec
> Resort 3: successful=477, throughput=80.08 swipes/sec
> Resort 4: successful=515, throughput=86.46 swipes/sec
> Resort 5: successful=523, throughput=87.81 swipes/sec
> Resort 6: successful=499, throughput=83.78 swipes/sec
> Resort 7: successful=494, throughput=82.94 swipes/sec
> Resort 8: successful=502, throughput=84.28 swipes/sec
> Resort 9: successful=477, throughput=80.08 swipes/sec
> Resort 10: successful=462, throughput=77.56 swipes/sec
>
> --- WebSocket subscriptions ---
> Resort 1: updates=12, connections=1, reconnections=0, avgConnectMs=189.565
> Resort 2: updates=12, connections=1, reconnections=0, avgConnectMs=156.622
> Resort 3: updates=12, connections=1, reconnections=0, avgConnectMs=155.274
> Resort 4: updates=12, connections=1, reconnections=0, avgConnectMs=154.416
> Resort 5: updates=12, connections=1, reconnections=0, avgConnectMs=172.764
> Resort 6: updates=12, connections=1, reconnections=0, avgConnectMs=172.502
> Resort 7: updates=12, connections=1, reconnections=0, avgConnectMs=171.350
> Resort 8: updates=12, connections=1, reconnections=0, avgConnectMs=149.947
> Resort 9: updates=12, connections=1, reconnections=0, avgConnectMs=167.498
> Resort 10: updates=12, connections=1, reconnections=0, avgConnectMs=167.514
> Total leaderboard updates: 120
> HTTP attempts recorded: 5000
> CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/swipes-1792071572091.csv
>
> --- HTTP attempt latency ---
> Samples: 5000
> Mean: 37.662 ms
> Median: 30.608 ms
> p95: 107.829 ms
> p99: 137.647 ms
> Min: 16.586 ms
> Max: 202.355 ms
>
> --- WebSocket push latency ---
> Samples: 120
> Mean: -60.997 ms
> Median: -76.985 ms
> p95: 10.403 ms
> p99: 11.514 ms
>
> Total program runtime: 6.682 seconds
>
> Process finished with exit code 0

**5,000 条远程联合测试通过了。** 所有请求成功，10 个订阅都收到更新，没有重连。

实际吞吐量 **839.45 条/秒**，低于之前预测的约 **1,185 条/秒**。这次并发运行的平均 HTTP 延迟增加到了 **37.662 ms**：

```text
32 / 0.037662 ≈ 850 条/秒
```

与实测接近。这是对结果的一致性检查；报告里仍应保留之前基于单线程 RTT 的预测，并解释并发后的延迟变化。

**WebSocket 平均值为负，表示跨机器时钟偏差仍在影响测量。** 保留原始值，在报告中说明它是包含时钟偏差的时间差，不能解释为真实的负网络延迟。

现在运行 **`client-part2` 的完整远程测试**，并保存输出。

**1. 修改 `LoadClient.java` 配置：**

```java
int eventCount = 500_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 1_000;
int mainThreads = 32;
```

HTTP 和 WebSocket 地址继续使用 EC2 的 `54.201.46.55`。

**2. 运行前设置自动保存控制台输出。**

在 IntelliJ 中：

**Run → Edit Configurations → LoadClient → Modify options → Save console output to file**

如果该选项在 **Logs** 区域，展开那里。输出文件填写：

```text
/Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part2-32threads.txt
```

确保之前创建的 `results/ec2` 文件夹存在。这样即使忘了截图，也保留完整文字结果。

**3. 运行 `client-part2 → LoadClient.main()`。**

- 保持 EC2 Tomcat 运行。
- 不要同时运行其他负载客户端。
- 保持 Mac 唤醒、网络连接稳定。

按刚才的吞吐量粗估需要 **约 10 分钟**，实际可能变化。

**4. 结束后保存截图：**

- 预热、主阶段、整体吞吐量。
- HTTP 延迟统计、WebSocket 更新数量及推送延迟统计。

预期成功数量是：

```text
Warmup: 32,000
Main:   468,000
Total:  500,000
```

这次是 **32 线程的远程基线测试**。保存 CSV 和输出后，我们再比较线程配置，并生成远程吞吐量图。

> User context
>
> Attachments:
> - Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:/…: `~/.codex/attachments/264fe454-380c-49a4-ad30-4e7f736b893a/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查这次完整远程测试的请求数量、吞吐量和订阅统计。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -90 '/Users/anne/.codex/attachments/264fe454-380c-49a4-ad30-4e7f736b893a/Pasted text.txt'`
>
> </details>
</details>

**32 线程的完整 EC2 测试成功了：500,000 条全部成功，没有应用层重试。**

| 指标 | 结果 |
|---|---:|
| 预热 | 32,000 条，35.868 秒 |
| 主阶段 | 468,000 条，451.402 秒 |
| 总发送时间 | **487.270 秒，约 8 分 7 秒** |
| 整体吞吐量 | **1,026.12 条/秒** |
| HTTP 平均延迟 | 31.146 ms |
| HTTP p95 / p99 | 38.715 / 107.686 ms |
| WebSocket 更新 | 9,740 条 |
| WebSocket 重连 | 0 |

实测达到之前预测 **1,184.94 条/秒的约 86.6%**。并发后的平均延迟从 27.006 ms 增加到 31.146 ms，与吞吐量低于预测的情况一致。

WebSocket 时间差仍为负，继续保留原始数据，并在报告中明确：**受跨机器时钟偏差影响，不能将这些值解释为真实单向网络延迟。**

**现在先保存这次结果：**

1. 截图保存预热、主阶段和整体汇总。
2. 截图保存 HTTP 和 WebSocket 延迟统计。
3. 保留控制台输出文件及这次 CSV：
   [swipes-1792072227367.csv](./client-part2/results/swipes-1792072227367.csv)

下一步做 **64 个主阶段线程的对照测试**。在 `client-part2/LoadClient.java` 中只改：

```java
int mainThreads = 64;
```

其他配置保持：

```java
int eventCount = 500_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 1_000;
```

**预热仍然必须是 32 个线程，每个 1,000 条。**

把 IntelliJ 保存控制台输出的文件名改为：

```text
/Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part2-64threads.txt
```

然后运行 `LoadClient.main()`，保持 EC2 和网络稳定，不同时运行其他负载程序。

这次比较重点是 **主阶段吞吐量和延迟**。32 线程主阶段的基线约为：

```text
468000 / 451.402 ≈ 1,037 条/秒
```

64 线程未必翻倍；我们用结果判断增加并发是否有帮助。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/546608f7-81d3-4eb1-9c3e-ebe27306050f/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先读取 64 线程测试结果，和之前的 32 线程基线比较。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -75 '/Users/anne/.codex/attachments/546608f7-81d3-4eb1-9c3e-ebe27306050f/Pasted text.txt'`
>
> </details>
</details>

**64 线程测试成功，500,000 条全部成功，主阶段明显加快了。**

| 指标 | 32 个主阶段线程 | 64 个主阶段线程 |
|---|---:|---:|
| 主阶段耗时 | 451.402 秒 | **267.709 秒** |
| 主阶段吞吐量 | 约 1,037 条/秒 | **约 1,748 条/秒** |
| 整体吞吐量 | 1,026.12 条/秒 | **1,649.07 条/秒** |
| HTTP 平均延迟¹ | 31.146 ms | 36.499 ms |
| HTTP p99¹ | 107.686 ms | 125.149 ms |
| 最终失败数 | 0 | 0 |

¹延迟统计包含预热和主阶段。

主阶段吞吐量提高约 **68.6%**，同时请求延迟有所增加。说明增加并发有效，但没有达到线性翻倍。两次测试在不同时间运行，网络和实例状态也可能影响结果，因此这是目前的实测比较。

WebSocket 共收到 **6,060 条更新**，没有重连。数量比上次少，是因为这次运行时间更短；负时间差仍按时钟偏差问题记录。

**保存这次输出和 CSV：**

[swipes-1792089896727.csv](./client-part2/results/swipes-1792089896727.csv)

下一步再测试 **128 个主阶段线程**，看看吞吐量是否继续提升。在 `client-part2/LoadClient.java` 中只改：

```java
int mainThreads = 128;
```

其余保持：

```java
int eventCount = 500_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 1_000;
```

把控制台保存文件改为：

```text
/Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part2-128threads.txt
```

然后运行 **`LoadClient.main()`**。保持 EC2 运行，不同时启动其他负载测试。

**完成这次后，我们先从 32、64、128 三组中选表现最好的配置，继续 Part 1 正式运行、远程图表和报告，不无限增加测试轮次。** HTTP 连接精确统计仍是待收尾项，不能用线程数替代。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/cbc66f66-ec67-4a3a-97a6-b9191cd7926e/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看 128 线程的结果，再从三组测试中选出后续使用的配置。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -75 '/Users/anne/.codex/attachments/cbc66f66-ec67-4a3a-97a6-b9191cd7926e/Pasted text.txt'`
>
> </details>
</details>

**128 线程是目前三组中最快的配置，500,000 条全部成功。** 我们选它作为后续使用的配置，不再继续增加线程测试。

| 主阶段线程数 | 主阶段吞吐量 | 整体吞吐量 | 总发送时间 |
|---|---:|---:|---:|
| 32 | 约 1,037 条/秒 | 1,026.12 条/秒 | 487.270 秒 |
| 64 | 约 1,748 条/秒 | 1,649.07 条/秒 | 303.201 秒 |
| **128** | **约 3,041 条/秒** | **2,600.48 条/秒** | **192.272 秒** |

128 线程主阶段吞吐量比 32 线程约提高 **193%**，但 HTTP 平均延迟也从 **31.146 ms** 增至 **41.806 ms**。报告可以说明：增加并发提高了总体处理速度，同时增加了单次请求延迟。这是**已测试配置中的最佳结果**，不能据此声称是全局最优配置。

本次 WebSocket 收到 **3,850 条更新**，没有重连。推送时间差仍受时钟偏差影响。

**先保存这次 Part 2 输出截图和 CSV：**

[swipes-1792091917631.csv](./client-part2/results/swipes-1792091917631.csv)

下一步补齐 **Part 1 的正式远程运行截图**。

**1. 打开 `client-part1` 的 `LoadClient.java`。注意这次切换到 Part 1 项目。**

设置：

```java
int eventCount = 500_000;
int warmupThreads = 32;
int eventsPerWarmupThread = 1_000;
int mainThreads = 128;
```

确认 HTTP 地址：

```java
String baseUrl = "http://54.201.46.55:8080/ski-servlet";
```

创建 `LeaderboardConnection` 时的地址也改为：

```java
"ws://54.201.46.55:8080/ski-servlet"
```

**2. 在 Part 1 的运行配置中，将控制台输出保存到：**

```text
/Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part1-128threads.txt
```

确认没有启用 `-Djdk.httpclient.HttpClient.log=channel`。

**3. 保持 EC2 Tomcat 运行，运行 `client-part1` 的 `LoadClient.main()`。**

结束后截图保存：

- Warmup：32,000 条及独立耗时。
- Main：468,000 条及耗时。
- 总成功数、失败数、运行时间和吞吐量。
- WebSocket 更新和连接统计。

**这次输出才是提交清单中的 “Part 1 output”。** 完成后，我们用已有 Part 2 CSV 生成远程图表，再整理报告；HTTP 连接精确统计仍需收尾，不能把它标记为已完成。

> 怎么做这一步

:codex-annotation{index="1"} 在 **`client-part1` 的 IntelliJ 窗口**里操作：

1. 点击顶部菜单 **Run → Edit Configurations…**。
2. 在左侧选择 **LoadClient**。确认右侧 **Main class** 是 `LoadClient`，使用的模块是 `client-part1`。
3. 找到 **Save console output to file**：
   - 如果没看到，点击 **Modify options**，勾选 **Save console output to file**。
   - 有些版本放在 **Logs** 标签页里。
4. 在对应的文件路径框粘贴：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part1-128threads.txt
   ```

5. 检查 **VM options**。如果里面有这一项，只删除这一项：

   ```text
   -Djdk.httpclient.HttpClient.log=channel
   ```

   如果没有添加过，就不用改。

6. 点击 **Apply → OK**。
7. 运行 **LoadClient**。输出会显示在控制台，同时保存到指定文件。

如果不确定目标文件夹是否存在，可以先在 **Mac 终端**运行：

```bash
mkdir -p /Users/anne/Desktop/CS6650distributedsystem/results/ec2
```

如果你在配置窗口找不到保存选项，把 **Edit Configurations** 窗口截图发给我，我帮你指出位置。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/jdk-22.jdk/Contents/Home/bin/java -javaagent:…: `~/.codex/attachments/23f1d576-dc45-4131-9462-6bd300e99c64/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查 Part 1 的完整远程测试结果。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -60 '/Users/anne/.codex/attachments/23f1d576-dc45-4131-9462-6bd300e99c64/Pasted text.txt'`
>
> </details>
</details>

**Part 1 的完整远程测试通过了，可以保存为提交截图。**

| 指标 | 结果 |
|---|---:|
| 预热 | 32,000 成功，37.082 秒 |
| 主阶段 | 468,000 成功，139.663 秒 |
| 总成功 / 失败 | **500,000 / 0** |
| 总发送耗时 | **176.745 秒** |
| 整体吞吐量 | **2,828.94 条/秒** |
| 排行榜更新 | 3,530 条 |
| WebSocket 重连 | 0 |

请截图保存从 `--- Warmup ---` 到 WebSocket 汇总的内容，一屏放不下可以分两张。Part 1 比 Part 2 快，但两次运行环境可能变化，不能把全部差异归因于指标采集开销。

下一步生成 **Part 2 的远程吞吐量图**，无需重新运行负载。

**1. 在 `client-part2/plot_throughput.py` 中，将图标题：**

```python
ax.set_title("Local ingest throughput — 10-second buckets")
```

改为：

```python
ax.set_title("EC2 ingest throughput — 128 main threads")
```

**2. 在 Mac 终端运行：**

```bash
cd /Users/anne/Desktop/CS6650distributedsystem/client-part2
source .venv/bin/activate
python plot_throughput.py results/swipes-1792091917631.csv
```

这使用的是之前 **Part 2、128 线程**的 CSV。生成文件：

```text
results/swipes-1792091917631-throughput.png
```

**3. 复制图到仓库根目录的提交结果文件夹：**

```bash
cp results/swipes-1792091917631-throughput.png \
  ../results/ec2/client-part2-128threads-throughput.png
```

把生成的图贴给我。随后整理 README、设计文档和提交材料；HTTP 连接统计等尚未补齐的项目也会保留在最终检查清单中。

> 是这个图片吗swipes-1792091917631-throughput.png

> User context
>
> Images:
> - `./client-part2/results/swipes-1792091917631-throughput.png`

**对，就是这张图。** 它对应 Part 2 的 **EC2、128 个主阶段线程**测试，可以用于提交。

图中可以看到：

- 前约 **38 秒**是 32 线程预热，吞吐量较低。
- 切换到 **128 线程**后，吞吐量明显提高。
- 后半段约为 **3,200–3,650 条/秒**。后半段进一步提升的原因，仅凭这张图无法确定。

每个点代表一个时间区间的平均吞吐量；最后不足 10 秒的区间已按实际时长计算。

如果还没复制到提交结果目录，在 Mac 终端执行：

```bash
cp /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/swipes-1792091917631-throughput.png \
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/client-part2-128threads-throughput.png
```


> why use Apache HttpClient instead of `java.net.http.HttpClient`

**主要是为了更容易统计 HTTP 连接池和实际建连次数，并不是因为 `java.net.http.HttpClient` 不符合要求。** 作业明确允许两者；你目前的 JDK 客户端已经支持连接复用，也完成了性能测试。

区别是：

| | `java.net.http.HttpClient` | Apache HttpClient |
|---|---|---|
| 发送 HTTP、复用连接 | 支持 | 支持 |
| 查看连接池状态 | 公共 API 没有直接提供这些计数 | 提供连接池统计接口 |
| 自定义建连计数 | 比较困难，需要额外诊断手段 | 可以在 socket factory 中计数 |
| 改动成本 | 保持现有实现 | 需要改发送代码、重新验证和测性能 |

** Apache 的优势是可观测性，不代表它一定更快，也不能自动解决“重连次数如何定义”的问题。

结合你现在已经完成的工作，我建议：

- **保留 `java.net.http.HttpClient` 作为最终实现。**
- 使用独立诊断来检查 HTTP 连接行为，并清楚说明统计方法和限制。
- 不把线程数、请求重试数冒充 TCP 连接数。
- 如果课程要求必须由客户端精确打印 HTTP 建连/重连计数，再考虑有明确计数设计的替换方案。

**暂时不要继续修改 `SwipeSender`。** 如果已经添加 Apache 依赖和 `CountingSocketFactory.java`，它们尚未接入运行，不影响现有程序，可以删除。

目前准确的状态仍是：**HTTP 发送与复用已实现；精确连接统计尚未完成。** 我们应先把这个要求的统计口径确定好，而不是为补一个指标贸然重写已经验证的发送路径。

> **这份作业使用 Apache HttpClient 5 处理 HTTP 发送，继续用 Java 的 WebSocket 客户端接收排行榜。**

> 按这个方案做。我会把两个客户端的 HTTP 发送切换到 Apache HttpClient 5，保留生成器、线程阶段、CSV 和 Java WebSocket。先核对建连计数的接口，再修改代码并验证连接复用、重试和失败统计；原来的性能结果保留为迁移前基线。
>
> <details><summary>Ran 3 commands, searched the web</summary>
>
> - Ran `cat client-part2/pom.xml client-part2/src/main/java/SwipeSender.java client-part1/src/main/java/SwipeSender.java; cat client-part2/src/main/java/LoadClient.java; rg --files -g AGENTS.md -g '*Socket*' -g '!**/.venv/**'`
>
> Searched the web for `site.hc.apache.org httpclient 5.5 DefaultHttpClientConnectionOperator connect ConnectionSocketFactory connection listener PoolingHttpClientConnectionManagerBuilder ...`
>
> Searched the web for `https://hc.apache.org/httpcomponents-client-5.5.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManagerBuilder.html`
>
> - Ran `ls /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5; command -v mvn; java -version`
>
> - Ran `ls /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4; javap -classpath /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator org.apache.hc.client5.http.io.DetachedSocketFactory; cat client-part2/src/main/java/SequentialClient.java; cat client-part1/src/main/java/LoadClient.java`
>
> </details>
>
> 我会分别输出连接池状态、成功建连次数和应用层重试次数。HTTP 的“重连”单独标为“应用重试期间成功新建的连接”，注明这是一个有限口径，不包含所有空闲超时或扩容产生的新连接，也不会用总连接数减线程数来计算。
>
> <details><summary>Ran 6 commands</summary>
>
> - Ran `ls /Users/anne/.m2/repository/junit/junit; ls /Users/anne/.m2/repository/org/apache/maven/plugins/maven-surefire-plugin; javap -classpath /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar org.apache.hc.client5.http.config.RequestConfig\$Builder org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy`
> - Ran `mkdir -p /tmp/skitrak-before-hc5
> cp -R client-part1/src /tmp/skitrak-before-hc5/part1-src
> cp -R client-part2/src /tmp/skitrak-before-hc5/part2-src
> cp client-part1/pom.xml /tmp/skitrak-before-hc5/part1-pom.xml
> cp client-part2/pom.xml /tmp/skitrak-before-hc5/part2-pom.xml`
> - Ran `python3 - <<'PY'
> from pathlib import Path
> for part in ('client-part1','client-part2'):
>  p=Path(part)/'pom.xml'
>  s=p.read_text().replace('  <dependencies>', '''  <dependencies>
>     <dependency>
>       <groupId>org.apache.httpcomponents.client5</groupId>
>       <artifactId>httpclient5</artifactId>
>       <version>5.6.4</version>
>     </dependency>
>     <dependency>
>       <groupId>junit</groupId>
>       <artifactId>junit</artifactId>
>       <version>4.12</version>
>       <scope>test</scope>
>     </dependency>''')
>  s=s.replace('    <plugins>', '''    <plugins>
>       <plugin>
>         <groupId>org.apache.maven.plugins</groupId>
>         <artifactId>maven-surefire-plugin</artifactId>
>         <version>3.2.5</version>
>       </plugin>''')
>  p.write_text(s)
> PY`
> - Ran `cat > /tmp/skitrak-before-hc5/HttpTransport.java <<'EOF'
    > import java.io.IOException;
    > import java.net.URI;
    > import java.util.concurrent.*;
    > import java.util.concurrent.atomic.AtomicInteger;
    > import java.util.concurrent.atomic.AtomicLong;
    > import org.apache.hc.client5.http.DnsResolver;
    > import org.apache.hc.client5.http.SchemePortResolver;
    > import org.apache.hc.client5.http.classic.methods.HttpPost;
    > import org.apache.hc.client5.http.config.ConnectionConfig;
    > import org.apache.hc.client5.http.config.RequestConfig;
    > import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
    > import org.apache.hc.client5.http.impl.classic.HttpClients;
    > import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
    > import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
    > import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
    > import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
    > import org.apache.hc.client5.http.protocol.HttpClientContext;
    > import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
    > import org.apache.hc.core5.http.ContentType;
    > import org.apache.hc.core5.http.HttpHost;
    > import org.apache.hc.core5.http.config.RegistryBuilder;
    > import org.apache.hc.core5.http.io.entity.EntityUtils;
    > import org.apache.hc.core5.http.io.entity.StringEntity;
    > import org.apache.hc.core5.http.protocol.HttpContext;
    > import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
    > import org.apache.hc.core5.pool.PoolStats;
    > import org.apache.hc.core5.util.Timeout;
>
> /** One shared HTTP/1.1 pool. Java HttpClient remains responsible for WebSockets. */
> public final class HttpTransport implements AutoCloseable {
>   private static final String RETRY = "skitrak.applicationRetry";
>   private final AtomicLong connectAttempts = new AtomicLong();
>   private final AtomicLong connectionsEstablished = new AtomicLong();
>   private final AtomicLong connectionsOnRetry = new AtomicLong();
>   private final AtomicLong requestAttempts = new AtomicLong();
>   private final AtomicLong requestRetries = new AtomicLong();
>   private final AtomicInteger peakLeased = new AtomicInteger();
>   private final AtomicInteger peakAvailable = new AtomicInteger();
>   private final AtomicInteger peakPending = new AtomicInteger();
>   private final PoolingHttpClientConnectionManager pool;
>   private final CloseableHttpClient client;
>   private final ScheduledExecutorService sampler;
>
>   public HttpTransport(int capacity) {
>     this(capacity, 5000, 5000, 10000);
>   }
>
>   // Separate timeouts: pool acquisition, TCP establishment, response/socket reads.
>   public HttpTransport(int capacity, int leaseMs, int connectMs, int responseMs) {
>     if (capacity < 1 || leaseMs < 1 || connectMs < 1 || responseMs < 1) {
>       throw new IllegalArgumentException("Capacity and timeouts must be positive");
>     }
>     pool = new PoolingHttpClientConnectionManagerBuilder() {
>       @Override
>       protected HttpClientConnectionOperator createConnectionOperator(
>           SchemePortResolver resolver, DnsResolver dns, TlsSocketStrategy tls) {
>         return new DefaultHttpClientConnectionOperator(resolver, dns,
>             RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build()) {
>           @Override
>           protected void onBeforeSocketConnect(HttpContext context, HttpHost host) {
>             connectAttempts.incrementAndGet();
>           }
>           @Override
>           protected void onAfterSocketConnect(HttpContext context, HttpHost host) {
>             // Called only after an actual TCP socket.connect succeeds, not on pool reuse.
>             connectionsEstablished.incrementAndGet();
>             if (Boolean.TRUE.equals(context.getAttribute(RETRY))) {
>               connectionsOnRetry.incrementAndGet();
>             }
>           }
>         };
>       }
>     }.setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
>         .setMaxConnTotal(capacity)
>         .setMaxConnPerRoute(capacity)
>         .setDefaultConnectionConfig(ConnectionConfig.custom()
>             .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
>             .setSocketTimeout(Timeout.ofMilliseconds(responseMs))
>             .build())
>         .build();
>
>     client = HttpClients.custom().setConnectionManager(pool)
>         .disableAutomaticRetries()
>         .disableRedirectHandling()
>         .disableCookieManagement()
>         .setDefaultRequestConfig(RequestConfig.custom()
>             .setConnectionRequestTimeout(Timeout.ofMilliseconds(leaseMs))
>             .setResponseTimeout(Timeout.ofMilliseconds(responseMs))
>             .setProtocolUpgradeEnabled(false)
>             .build())
>         .build();
>     sampler = Executors.newSingleThreadScheduledExecutor(task -> {
>       Thread thread = new Thread(task, "http-pool-sampler");
>       thread.setDaemon(true);
>       return thread;
>     });
>     sampler.scheduleAtFixedRate(this::samplePool, 0, 50, TimeUnit.MILLISECONDS);
>   }
>
>   /** Exactly one application attempt; retries belong to SwipeSender. */
>   public int post(String url, String body, boolean retry) throws IOException {
>     if (!"http".equalsIgnoreCase(URI.create(url).getScheme())) {
>       throw new IllegalArgumentException("This assignment transport expects an http:// URL");
>     }
>     HttpPost request = new HttpPost(url);
>     request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
>     HttpClientContext context = HttpClientContext.create();
>     context.setAttribute(RETRY, retry);
>     requestAttempts.incrementAndGet();
>     if (retry) {
>       requestRetries.incrementAndGet();
>     }
>     try {
>       return client.execute(request, context, response -> {
>         int status = response.getCode();
>         // Fully consume response before returning the connection to the pool.
>         EntityUtils.consume(response.getEntity());
>         return status;
>       });
>     } finally {
>       samplePool();
>     }
>   }
>
>   private void samplePool() {
>     PoolStats stats = pool.getTotalStats();
>     peakLeased.accumulateAndGet(stats.getLeased(), Math::max);
>     peakAvailable.accumulateAndGet(stats.getAvailable(), Math::max);
>     peakPending.accumulateAndGet(stats.getPending(), Math::max);
>   }
>
>   public long getConnectionsEstablished() { return connectionsEstablished.get(); }
>   public long getConnectAttempts() { return connectAttempts.get(); }
>   public long getConnectionsOnRetry() { return connectionsOnRetry.get(); }
>   public long getRequestAttempts() { return requestAttempts.get(); }
>   public long getRequestRetries() { return requestRetries.get(); }
>   public PoolStats getPoolStats() { return pool.getTotalStats(); }
>
>   // Print after all senders finish, before closing the pool.
>   public void printSummary() {
>     samplePool();
>     PoolStats stats = pool.getTotalStats();
>     System.out.println("\n--- HTTP transport: Apache HttpClient 5.6.4 ---");
>     System.out.println("Application request attempts: " + getRequestAttempts());
>     System.out.println("Application request retries: " + getRequestRetries());
>     System.out.println("TCP connect attempts: " + getConnectAttempts());
>     System.out.println("TCP connections established: " + getConnectionsEstablished());
>     System.out.println("TCP connect attempts without success: "
>         + (getConnectAttempts() - getConnectionsEstablished()));
>     System.out.println("TCP connections established during application retries: "
>         + getConnectionsOnRetry());
>     System.out.println("HTTP reconnection definition: new TCP opens during application retries; "
>         + "not all replacement/idle-expiry connections.");
>     System.out.printf("Pool before close: leased=%d, available=%d, pending=%d, max=%d%n",
>         stats.getLeased(), stats.getAvailable(), stats.getPending(), stats.getMax());
>     System.out.printf("Sampled pool peaks (50 ms + attempt completion): "
>         + "leased=%d, available=%d, pending=%d%n",
>         peakLeased.get(), peakAvailable.get(), peakPending.get());
>   }
>
>   @Override
>   public void close() throws IOException {
>     sampler.shutdownNow();
>     client.close();
>   }
> }
> EOF
> cp /tmp/skitrak-before-hc5/HttpTransport.java client-part1/src/main/java/HttpTransport.java
> cp /tmp/skitrak-before-hc5/HttpTransport.java client-part2/src/main/java/HttpTransport.java`
> - Ran `python3 - <<'PY'
    > from pathlib import Path
    > for part in ('client-part1','client-part2'):
    >  p=Path(part)/'src/main/java/SwipeSender.java'; s=p.read_text()
    >  for imp in ('java.net.URI','java.net.http.HttpClient','java.net.http.HttpRequest','java.net.http.HttpResponse','java.time.Duration'):
    >   s=s.replace('import '+imp+';\n','')
    >  s=s.replace('HttpClient client','HttpTransport client')
    >  a=s.index('    HttpRequest request ='); b=s.index('    for (int attempt',a)
    >  s=s[:a]+s[b:]
    >  s=s.replace('    for (int attempt = 0; attempt <= 5; attempt++) {','''    for (int attempt = 0; attempt <= 5; attempt++) {
    >       if (Thread.currentThread().isInterrupted()) {
    >         throw new InterruptedException("Sender interrupted");
    >       }''')
    >  a=s.index('        HttpResponse<Void> response ='); b=s.index('\n',s.index('        );',a))+1
    >  s=s[:a]+'''        int responseCode = client.post(
    >             baseUrl + event.path(), event.body(), attempt > 0);
    > '''+s[b:]
    >  s=s.replace('response.statusCode()', 'responseCode')
    >  p.write_text(s)
    >  p=Path(part)/'src/main/java/LoadClient.java'; s=p.read_text()
    >  s=s.replace('HttpClient client = HttpClient.newBuilder()', 'HttpClient webSocketClient = HttpClient.newBuilder()')
    >  s=s.replace('                client,','                webSocketClient,')
    >  s=s.replace('    try {\n      //', '    try (HttpTransport client = new HttpTransport(Math.max(warmupThreads, mainThreads))) {\n      //',1)
    >  # Pool summary while pool still open.
    >  pos=s.index('    } finally {')
    >  s=s[:pos]+'      client.printSummary();\n'+s[pos:]
    >  s=s.replace('      HttpClient client,', '      HttpTransport client,')
    >  # Optional command-line arguments; no-argument default preserves existing workload.
    >  a=s.index('    int eventCount ='); b=s.index('    BlockingQueue<SwipeEvent>',a)
    >  s=s[:a]+'''    String baseUrl = args.length > 0 ? args[0] : "http://54.201.46.55:8080/ski-servlet";
    >     int eventCount = args.length > 1 ? Integer.parseInt(args[1]) : 500_000;
    >     int mainThreads = args.length > 2 ? Integer.parseInt(args[2]) : 128;
    >     int warmupThreads = 32;
    >     int eventsPerWarmupThread = args.length > 3 ? Integer.parseInt(args[3]) : 1_000;
    >     if (!baseUrl.startsWith("http://") || mainThreads < 1
    >         || eventsPerWarmupThread < 1
    >         || eventCount < (long) warmupThreads * eventsPerWarmupThread) {
    >       throw new IllegalArgumentException(
    >           "Usage: LoadClient [http-base-url] [events] [main-threads] [warmup-per-thread]; "
    >               + "events must cover 32 warmup workers");
    >     }
    >     String wsBaseUrl = "ws://" + baseUrl.substring("http://".length());
    >     System.out.printf("HTTP transport: Apache HttpClient 5.6.4; events=%d, mainThreads=%d%n",
    >         eventCount, mainThreads);
>
> '''+s[b:]
>  s=s.replace('"ws://54.201.46.55:8080/ski-servlet",','wsBaseUrl,')
>  p.write_text(s)
> PY
> mvn -o -q -f client-part2/pom.xml -DskipTests package`
> - Ran `cat > /tmp/skitrak-before-hc5/SequentialClient.java <<'EOF'
> /** Same HTTP transport as LoadClient: measure a fresh request, then pooled RTT. */
> public class SequentialClient {
>   public static void main(String[] args) throws Exception {
>     String baseUrl = args.length > 0 ? args[0] : "http://54.201.46.55:8080/ski-servlet";
>     String url = baseUrl + "/skiers/1/seasons/2026/days/1/skiers/123";
>     String body = "{\"time\":217,\"liftID\":21}";
>     try (HttpTransport client = new HttpTransport(1)) {
>       long firstStart = System.nanoTime();
>       int firstStatus = client.post(url, body, false);
>       System.out.printf("Fresh-client request (includes connection setup): %.3f ms%n",
>           (System.nanoTime() - firstStart) / 1_000_000.0);
>       if (firstStatus != 201) throw new IllegalStateException("First request: HTTP " + firstStatus);
>       for (int i = 0; i < 20; i++) {
>         if (client.post(url, body, false) != 201) {
>           throw new IllegalStateException("Sequential warmup failed");
>         }
>       }
>       int successful = 0;
>       int count = 100;
>       long latencyNanos = 0;
>       long started = System.nanoTime();
>       for (int i = 0; i < count; i++) {
>         long requestStart = System.nanoTime();
>         int status = client.post(url, body, false);
>         latencyNanos += System.nanoTime() - requestStart;
>         if (status == 201) successful++;
>       }
>       double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
>       double meanMs = latencyNanos / 1_000_000.0 / count;
>       System.out.println("Successful: " + successful);
>       System.out.println("Failed: " + (count - successful));
>       System.out.printf("Average pooled latency: %.3f ms%n", meanMs);
>       System.out.printf("Measured throughput: %.2f swipes/sec%n", successful / seconds);
>       if (successful == count) {
>         for (int threads : new int[] {32, 64, 128}) {
>           System.out.printf("Initial prediction for %d senders: %.2f swipes/sec%n",
>               threads, threads / (meanMs / 1000.0));
>         }
>       }
>       client.printSummary();
>     }
>   }
> }
> EOF
> cp /tmp/skitrak-before-hc5/SequentialClient.java client-part1/src/main/java/SequentialClient.java
> cp /tmp/skitrak-before-hc5/SequentialClient.java client-part2/src/main/java/SequentialClient.java`
>
> </details>
>
> 两个客户端已接入共享连接池，并关闭 Apache 的自动重试。`SequentialClient` 也改用了同一套 HTTP 传输实现，之后测出的 RTT 才能用于新版预测。现在正在检查：连接是否复用、响应是否完整消费并释放、503 是否只按我们的代码重试，以及并发时池容量是否正确。
>
> <details><summary>Explored 2 files, a search, ran 5 commands</summary>
>
> - Ran `cat > /tmp/skitrak-before-hc5/HttpTransportTest.java <<'EOF'
    > import com.sun.net.httpserver.HttpExchange;
    > import com.sun.net.httpserver.HttpServer;
    > import java.io.IOException;
    > import java.net.InetSocketAddress;
    > import java.net.ServerSocket;
    > import java.net.Socket;
    > import java.util.concurrent.*;
    > import java.util.concurrent.atomic.AtomicInteger;
    > import org.junit.Test;
    > import static org.junit.Assert.*;
>
> public class HttpTransportTest {
>   private static void reply(HttpExchange exchange, int code, boolean close) throws IOException {
>     exchange.getRequestBody().readAllBytes();
>     if (close) exchange.getResponseHeaders().set("Connection", "close");
>     exchange.sendResponseHeaders(code, -1);
>     exchange.close();
>   }
>   private static String url(HttpServer server) {
>     return "http://127.0.0.1:" + server.getAddress().getPort();
>   }
>   private static HttpServer server() throws IOException {
>     return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
>   }
>   private static SwipeSender sender(HttpTransport transport, String url,
>       AtomicInteger success, AtomicInteger failed) {
>     BlockingQueue<SwipeEvent> queue = new ArrayBlockingQueue<>(1);
>     queue.add(new SwipeEvent(1, 2026, 1, 123, 217, 21));
>     return new SwipeSender(queue, transport, url, success, failed, 1 METRICS);
>   }
>
>   @Test public void sequentialRequestsReuseOneConnection() throws Exception {
>     HttpServer server = server();
>     AtomicInteger received = new AtomicInteger();
>     server.createContext("/", ex -> {
>       received.incrementAndGet();
>       byte[] data = "acknowledged".getBytes(java.nio.charset.StandardCharsets.UTF_8);
>       ex.getRequestBody().readAllBytes();
>       ex.sendResponseHeaders(201, data.length);
>       ex.getResponseBody().write(data);
>       ex.close();
>     });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(4)) {
>       for (int i = 0; i < 10; i++) assertEquals(201, transport.post(url(server), "{}", false));
>       assertEquals(10, received.get());
>       assertEquals(1, transport.getConnectionsEstablished());
>       assertEquals(0, transport.getPoolStats().getLeased());
>       assertEquals(1, transport.getPoolStats().getAvailable());
>       assertEquals(0, transport.getRequestRetries());
>     } finally { server.stop(0); }
>   }
>
>   @Test public void failedHttpStatusesHaveExactlyFiveApplicationRetries() throws Exception {
>     HttpServer server = server();
>     AtomicInteger received = new AtomicInteger();
>     server.createContext("/", ex -> { received.incrementAndGet(); reply(ex, 503, false); });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(1)) {
>       AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
>       sender(transport, url(server), success, failed).run();
>       assertEquals(6, received.get());
>       assertEquals(6, transport.getRequestAttempts());
>       assertEquals(5, transport.getRequestRetries());
>       assertEquals(0, success.get());
>       assertEquals(1, failed.get());
>       assertEquals(1, transport.getConnectionsEstablished());
>       assertEquals(0, transport.getConnectionsOnRetry());
>       assertEquals(0, transport.getPoolStats().getLeased());
>     } finally { server.stop(0); }
>   }
>
>   @Test public void serverCloseThenRetryCountsNewSocket() throws Exception {
>     HttpServer server = server();
>     AtomicInteger received = new AtomicInteger();
>     server.createContext("/", ex -> {
>       boolean first = received.incrementAndGet() == 1;
>       reply(ex, first ? 503 : 201, first);
>     });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(1)) {
>       AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
>       sender(transport, url(server), success, failed).run();
>       assertEquals(1, success.get());
>       assertEquals(0, failed.get());
>       assertEquals(2, transport.getConnectionsEstablished());
>       assertEquals(1, transport.getConnectionsOnRetry());
>       assertEquals(1, transport.getRequestRetries());
>     } finally { server.stop(0); }
>   }
>
>   @Test public void failedConnectDoesNotCountAsEstablished() throws Exception {
>     int port;
>     try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
>     try (HttpTransport transport = new HttpTransport(1, 300, 300, 300)) {
>       try {
>         transport.post("http://127.0.0.1:" + port, "{}", false);
>         fail("Expected connection refusal");
>       } catch (IOException expected) { }
>       assertEquals(1, transport.getRequestAttempts());
>       assertEquals(1, transport.getConnectAttempts());
>       assertEquals(0, transport.getConnectionsEstablished());
>       assertEquals(0, transport.getPoolStats().getLeased());
>     }
>   }
>
>   @Test public void closedResponseIsNotAutomaticallyRetried() throws Exception {
>     try (ServerSocket server = new ServerSocket(0)) {
>       ExecutorService acceptor = Executors.newSingleThreadExecutor();
>       Future<?> accepted = acceptor.submit(() -> {
>         try (Socket socket = server.accept()) {
>           socket.getInputStream().read();
>           // Drop the connection without sending a response.
>         } catch (IOException e) { throw new RuntimeException(e); }
>       });
>       try (HttpTransport transport = new HttpTransport(1, 500, 500, 500)) {
>         try {
>           transport.post("http://127.0.0.1:" + server.getLocalPort(), "{}", false);
>           fail("Expected dropped response");
>         } catch (IOException expected) { }
>         accepted.get(2, TimeUnit.SECONDS);
>         assertEquals(1, transport.getConnectAttempts());
>         assertEquals(1, transport.getConnectionsEstablished());
>         assertEquals(0, transport.getRequestRetries());
>         assertEquals(0, transport.getPoolStats().getLeased());
>       } finally { acceptor.shutdownNow(); }
>     }
>   }
>
>   @Test public void poolLimitCreatesPendingRequestAndReleasesAll() throws Exception {
>     HttpServer server = server();
>     ExecutorService handlers = Executors.newCachedThreadPool();
>     ExecutorService callers = Executors.newFixedThreadPool(3);
>     CountDownLatch twoHandlers = new CountDownLatch(2);
>     CountDownLatch release = new CountDownLatch(1);
>     server.setExecutor(handlers);
>     server.createContext("/", ex -> {
>       twoHandlers.countDown();
>       try { release.await(3, TimeUnit.SECONDS); }
>       catch (InterruptedException e) { Thread.currentThread().interrupt(); }
>       reply(ex, 201, false);
>     });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(2, 3000, 1000, 3000)) {
>       Future<Integer> a = callers.submit(() -> transport.post(url(server), "{}", false));
>       Future<Integer> b = callers.submit(() -> transport.post(url(server), "{}", false));
>       assertTrue(twoHandlers.await(2, TimeUnit.SECONDS));
>       Future<Integer> c = callers.submit(() -> transport.post(url(server), "{}", false));
>       long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
>       while (transport.getPoolStats().getPending() == 0 && System.nanoTime() < deadline) {
>         Thread.sleep(5);
>       }
>       assertEquals(2, transport.getPoolStats().getLeased());
>       assertEquals(1, transport.getPoolStats().getPending());
>       assertEquals(2, transport.getConnectionsEstablished());
>       release.countDown();
>       assertEquals(201, (int) a.get(3, TimeUnit.SECONDS));
>       assertEquals(201, (int) b.get(3, TimeUnit.SECONDS));
>       assertEquals(201, (int) c.get(3, TimeUnit.SECONDS));
>       assertEquals(2, transport.getConnectionsEstablished());
>       assertEquals(0, transport.getPoolStats().getLeased());
>       assertEquals(0, transport.getPoolStats().getPending());
>     } finally {
>       release.countDown(); server.stop(0); callers.shutdownNow(); handlers.shutdownNow();
>     }
>   }
>
>   @Test public void responseReadTimeoutReleasesConnection() throws Exception {
>     HttpServer server = server();
>     CountDownLatch release = new CountDownLatch(1);
>     server.createContext("/", ex -> {
>       try { release.await(2, TimeUnit.SECONDS); }
>       catch (InterruptedException e) { Thread.currentThread().interrupt(); }
>       ex.close();
>     });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(1, 500, 500, 100)) {
>       try {
>         transport.post(url(server), "{}", false);
>         fail("Expected read timeout");
>       } catch (java.net.SocketTimeoutException expected) { }
>       assertEquals(1, transport.getConnectionsEstablished());
>       assertEquals(0, transport.getPoolStats().getLeased());
>     } finally { release.countDown(); server.stop(0); }
>   }
> }
> EOF
> python3 - <<'PY'
> from pathlib import Path
> s=Path('/tmp/skitrak-before-hc5/HttpTransportTest.java').read_text()
> for part in ('client-part1','client-part2'):
>  p=Path(part)/'src/test/java/HttpTransportTest.java'
>  p.parent.mkdir(parents=True,exist_ok=True)
>  p.write_text(s.replace(' METRICS', ', new SwipeMetrics()' if part=='client-part2' else ''))
> PY
> mvn -o -q -f client-part2/pom.xml test`
>
> - Ran `mvn -o -q -f client-part1/pom.xml test`
>
> - Ran `mvn -q -f client-part2/pom.xml test`
>
> <details><summary>Explored 2 files, a search</summary>
>
> - Read `./README.md`
> - Searched for `HttpTransport|HttpClient|runPhase|printSummary|wsBaseUrl` in `LoadClient.java`
> - Read `./client-part1/src/main/java/SwipeSender.java`
>
> </details>
>
> - Ran `mvn -q -f client-part2/pom.xml test`
>
> - Ran ````python3 - <<'PY'
    > from pathlib import Path
    > p=Path('README.md'); s=p.read_text()
    > a=s.index('1. Open the desired client'); b=s.index('The warmup sends',a)
    > s=s[:a]+'''1. Open the desired client folder as a Maven project in IntelliJ and reload Maven.
> 2. Set its working directory to the client project directory.
> 3. Run `LoadClient.main()` with Program arguments (not VM options):
>
> ```text
> http://<EC2_PUBLIC_IP>:8080/ski-servlet 500000 128 1000
> ```
>
> Arguments are HTTP base URL, total events, main threads, and events per warmup
> thread. The warmup always uses 32 workers. For a small verification run use:
>
> ```text
> http://<EC2_PUBLIC_IP>:8080/ski-servlet 5000 32 10
> ```
>
> The WebSocket URL is derived from the HTTP base URL. With no arguments, defaults
> are 500000 events, 128 main threads, 1000 events per warmup thread, and the
> previous EC2 IP in the source (which may have changed).
>
> HTTP transport is Apache HttpClient 5.6.4; Java HttpClient handles WebSockets.
> One strict pool is shared across HTTP senders and both phases; total and per-route
> capacity are `max(32, mainThreads)`. Pool-acquisition timeout is 5 seconds,
> TCP-connect timeout 5 seconds, and response/socket-read timeout 10 seconds.
> These are separate timeouts, not a single end-to-end deadline. Automatic library
> retries and redirects are disabled. Responses are fully consumed before releasing
> connections. The HTTP pool is closed on normal completion or exceptions.
>
> ''' + s[b:]
> s=s.replace('## EC2 results','## EC2 results — pre-migration JDK HttpClient baseline')
> s=s.replace('All runs below completed 500,000 events successfully.', 'All runs below completed 500,000 events successfully using the earlier JDK\nHttpClient transport. They are NOT measurements of the Apache version. Keep\nthese logs/CSVs as baseline evidence; remeasure before reporting Apache results.')
> s=s[:s.index('## Measurement limitations')]+'''## HTTP metric definitions
>
> - Pool state: current leased (in use), available (idle), pending (waiting for a
    >   lease), and configured maximum. Printed before closing the pool.
> - Sampled pool peaks: maxima observed every 50 ms and at attempt completion;
    >   short-lived peaks may be missed. These are not cumulative counts.
> - TCP connect attempts: calls reaching the connection operator's socket-connect
    >   hook. DNS failures and pool-acquisition timeouts need not reach this hook.
> - TCP connections established: incremented only AFTER socket connection succeeds.
    >   Reusing a pooled connection does not increment this counter.
> - TCP attempts without success: attempts minus successes, evaluated after workers
    >   finish. This is not a count of all HTTP request failures.
> - Application request retries: attempts after the first for each logical event.
> - TCP connections established during application retries: new successful TCP opens
    >   whose request context is marked as an application retry. This is the limited
    >   HTTP recovery/reconnection measure used here. It is not all replacement
    >   connections, and a retry can reuse a connection. A failed first TCP connect
    >   followed by a successful retry is included. Pool expansion and server-requested
    >   closes can create connections outside this subset; causes are not inferred.
> - WebSocket reconnections: successful connections after the first per subscriber,
    >   reported separately. HTTP metrics exclude WebSockets.
>
> Both clients export raw push timestamps. Part 2 additionally exports per-attempt
> HTTP samples. Existing negative push time differences include clock offset.
>
> ## Migration validation and new baseline
>
> Run `mvn test` in each client module. Local fixture tests cover response-body
> consumption and connection reuse, server-initiated close plus retry, failed TCP
> connects, an abruptly dropped response, exact application retry limits, response
> read timeout, and concurrent pool leasing/pending requests.
>
> Run `SequentialClient` with the HTTP base URL as its sole program argument to
> remeasure RTT using the same Apache transport as LoadClient. It reports one
> fresh-client request (including setup), then 20 warmup requests and 100 measured
> requests, and prints predictions for 32/64/128 senders. The fresh request includes
> other startup overhead; it is not a pure TCP handshake measurement.
>
> See [HTTP transport migration](results/HTTP_TRANSPORT_MIGRATION.md) for the scope
> and validation record. New remote baseline and load-test evidence are pending.
> '''
> p.write_text(s)
> PY````
>
> </details>
>
> Part 2 的本机验证已通过：10 次顺序请求只建立了 1 条连接；连续 503 恰好触发 6 次尝试、5 次重试；服务器关闭连接后，重试成功建立了新连接；连接拒绝没有被计为成功建连。并发测试也观察到池容量为 2 时，第三个请求会等待。接下来验证 Part 1，并补上连接池等待超时测试。
>
> <details><summary>Ran 6 commands</summary>
>
> - Ran `python3 - <<'PY'
> from pathlib import Path
> extra='''
>   @Test public void leaseTimeoutDoesNotOpenAnExtraSocket() throws Exception {
>     HttpServer server = server();
>     CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
>     ExecutorService caller = Executors.newSingleThreadExecutor();
>     server.createContext("/", ex -> {
>       entered.countDown();
>       try { release.await(3, TimeUnit.SECONDS); }
>       catch (InterruptedException e) { Thread.currentThread().interrupt(); }
>       reply(ex, 201, false);
>     });
>     server.start();
>     try (HttpTransport transport = new HttpTransport(1, 100, 500, 3000)) {
>       Future<Integer> first = caller.submit(() -> transport.post(url(server), "{}", false));
>       assertTrue(entered.await(2, TimeUnit.SECONDS));
>       try {
>         transport.post(url(server), "{}", false);
>         fail("Expected connection pool timeout");
>       } catch (org.apache.hc.core5.http.ConnectionRequestTimeoutException expected) { }
>       assertEquals(1, transport.getConnectAttempts());
>       assertEquals(1, transport.getConnectionsEstablished());
>       assertEquals(2, transport.getRequestAttempts());
>       release.countDown();
>       assertEquals(201, (int) first.get(2, TimeUnit.SECONDS));
>       assertEquals(0, transport.getPoolStats().getLeased());
>     } finally { release.countDown(); server.stop(0); caller.shutdownNow(); }
>   }
> '''
> for part in ('client-part1','client-part2'):
>  p=Path(part)/'src/test/java/HttpTransportTest.java'; s=p.read_text(); p.write_text(s.rstrip()[:-1]+extra+'}\n')
> PY
> mvn -q -f client-part1/pom.xml package`
> - Ran `ls /Users/anne/.m2/repository/org/slf4j/slf4j-nop; rg -n 'Tests run' client-part1/target/surefire-reports/*.txt client-part2/target/surefire-reports/*.txt`
> - Ran `mvn -q -f client-part2/pom.xml package`
> - Ran `mvn -q -f client-part1/pom.xml package`
> - Ran `mvn -q -f client-part2/pom.xml package`
> - Ran `rg -n 'Tests run' client-part1/target/surefire-reports/*.txt client-part2/target/surefire-reports/*.txt; ls -l client-part1/target/client-part1-1.0-SNAPSHOT.jar client-part2/target/client-part2-1.0-SNAPSHOT.jar`
>
> </details>
>
> 两个客户端都已打包成功，各自的 8 项测试全部通过，包括连接复用、重试上限、断连恢复、连接拒绝、池容量限制、获取连接超时和读取超时。旧的 EC2 日志、CSV 和截图没有改动；README 已将它们标为 JDK 版本基线。新版的远程 RTT 和性能仍需要重新测量。
>
> <details><summary>Ran a command</summary>
>
> - Ran ````cat > results/HTTP_TRANSPORT_MIGRATION.md <<'EOF'
> # HTTP transport migration — Apache HttpClient 5
>
> ## Scope
>
> Both clients now use Apache HttpClient **5.6.4** for HTTP/1.1 swipe POSTs.
> The existing Java `java.net.http.HttpClient` remains responsible for WebSockets.
> Event generation, queues, phase sizes, application retry policy, raw push records,
> and the Part 2 HTTP CSV/statistics pipeline remain in place.
>
> Files changed in each client:
>
> - `pom.xml`: Apache HttpClient 5 plus test-only JUnit and test runner.
> - `HttpTransport.java`: shared pool, socket-connect counters, pool snapshots,
    >   sampled pool peaks, request attempt/retry counts, response consumption, cleanup.
> - `SwipeSender.java`: sends via HttpTransport; still owns the five-retry limit.
> - `LoadClient.java`: owns and closes transport; derives WebSocket address from
    >   one HTTP base URL; accepts optional program arguments for small/full runs.
> - `SequentialClient.java`: measures RTT with the same Apache transport.
> - `src/test/java/HttpTransportTest.java`: local deterministic transport tests.
>
> No server deployment or EC2 performance run was performed during migration.
> The server does not need a new WAR solely because the client HTTP library changed.
> Previously requested server validation/session fixes are a separate deployment.
>
> ## Definitions
>
> Pool state is a snapshot: leased, available, pending, and max. It is printed
> before the pool closes. Peak values are sampled at 50 ms intervals and after
> requests complete and may miss brief peaks.
>
> Cumulative successful TCP connections are counted in Apache's
> `onAfterSocketConnect` hook, after successful socket establishment. Pool leasing
> and reuse do not increase this count. The pre-connect hook counts TCP connect
> attempts, excluding failures that occur earlier (e.g. DNS or pool acquisition).
> After workers stop, attempts minus successes is the count of socket-connect
> attempts without success, not the total HTTP error count.
>
> Application retry count is increased only for a request attempt after the first
> attempt of a logical swipe. Automatic Apache retries and redirects are disabled.
> An HTTP status of 503 therefore causes only the application's configured retries.
>
> The limited HTTP recovery measure is **successful new TCP connections established
> while executing an application retry**. It is explicitly not the total number of
> replacement connections or proven fault-recovery connections. For example, a
> first connect refusal followed by a successful retry is included; a server close
> followed by a new connection on the next event is not included in this subset but
> is included in total successful connections. A retry can reuse a connection and
> therefore need not increase this measure. No metric subtracts thread count from
> connection count. WebSocket reconnect counts remain separate.
>
> ## Capacity and timeouts
>
> A strict pool is shared by warmup and main workers. Total and per-route capacities
> are `max(32, mainThreads)`. Separate timeouts are 5 s for a pool lease, 5 s for
> connect, and 10 s for response/socket reads. These are not a hard end-to-end request
> deadline. HttpTransport explicitly supports the assignment's `http://` endpoints.
>
> The response handler consumes the response body before returning, so per-attempt
> latency includes pool wait, connection setup if needed, request and full response
> processing. Backoff is outside the measured attempt. All network error attempts
> remain status 0 in the Part 2 CSV. The client closes the pool with try-with-resources.
>
> ## Validation completed
>
> Executed `mvn -q -f client-part1/pom.xml package` and the corresponding Part 2
> command on the local Mac (JDK 22, Java 11 compiler target).
> Both completed successfully: **8 tests each, 0 failures, 0 errors**.
> The sandbox required permission for Maven downloads and loopback test sockets;
> these environment errors were resolved by running the approved test commands.
>
> Tests cover:
>
> 1. Ten successful POSTs with nonempty response bodies reuse one TCP connection;
     >    response bodies are consumed and the lease is released.
> 2. A persistent HTTP 503 produces exactly six server requests, five application
     >    retries, one final failed swipe, and one TCP connection (no nested retry loop).
> 3. A 503 with Connection: close followed by 201 creates two TCP connections,
     >    one during a retry, and one successful logical event.
> 4. A refused connection increments connect attempts, not successful connections.
> 5. An abruptly dropped response is not retried by Apache automatically.
> 6. A capacity-two pool leases two connections and queues a third request, then
     >    completes all requests using two connections with no outstanding leases.
> 7. A response read timeout releases its pool lease.
> 8. Pool-acquisition timeout does not open an extra socket and the active request
     >    can finish and release its connection normally.
>
> Reports: `client-part1/target/surefire-reports/HttpTransportTest.txt` and
> `client-part2/target/surefire-reports/HttpTransportTest.txt` (generated build files).
>
> ## Next measurements
>
> Reload Maven in IntelliJ. First run SequentialClient with program argument:
>
> ```text
> http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet
> ```
>
> Then run LoadClient with program arguments:
>
> ```text
> http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet 5000 32 10
> ```
>
> Only after the small joint HTTP/WebSocket run passes, run the full workload:
>
> ```text
> http://<CURRENT_EC2_PUBLIC_IP>:8080/ski-servlet 500000 128 1000
> ```
>
> Use new output filenames containing `apache5`. All existing EC2 logs, charts,
> screenshots, and CSVs are pre-migration JDK baselines, not Apache results.
> The earlier best thread count is a starting point, not a proven Apache optimum.
> The fresh request RTT includes startup overhead as well as TCP connect cost;
> report it separately from pooled RTT. Existing push clock-skew limitations remain.
>
> ## API references
>
> - [Apache connection operator hooks](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/DefaultHttpClientConnectionOperator.html)
> - [Apache pool builder](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/PoolingHttpClientConnectionManagerBuilder.html)
>
> The hook integration uses an Apache implementation extension point annotated
> Internal; the dependency is pinned and the tests should be rerun on upgrades.
> EOF
> cmp client-part1/src/main/java/HttpTransport.java client-part2/src/main/java/HttpTransport.java
> rg -n 'httpclient5|5.6.4|Tests run' client-part1/pom.xml client-part2/pom.xml client-part1/target/surefire-reports/HttpTransportTest.txt client-part2/target/surefire-reports/HttpTransportTest.txt````
>
> </details>
</details>

**已按这个方案完成两个客户端的代码替换，你不用再手动复制代码。**

- HTTP：Apache HttpClient **5.6.4**，所有发送线程共享连接池。
- WebSocket：继续使用 Java 客户端。
- Apache 自动重试已关闭，仍由 `SwipeSender` 控制最多 5 次重试。
- 已加入实际成功建连次数、请求重试次数、连接池状态和采样峰值。
- 两个项目都打包成功，**各 8 项测试全部通过**。

连接计数使用 Apache 的实际建连回调；复用连接不会增加成功建连次数。[Apache 接口说明](https://hc.apache.org/httpcomponents-client-5.6.x/current/httpclient5/apidocs/org/apache/hc/client5/http/impl/io/DefaultHttpClientConnectionOperator.html)

HTTP“重连”明确记录为：**应用重试期间成功新建的 TCP 连接**。它不是所有连接替换，也不等于请求重试次数。定义和验证结果已写入 [迁移说明](./results/HTTP_TRANSPORT_MIGRATION.md)。

> 我记得lab1 和lab2不是说过使用java11吗，这个会不会有影响呢$`ls /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5; command -v mvn; java -version`
> `5.3.1 5.4.2 5.6.4 /opt/homebrew/opt/maven/bin/mvn java version "22.0.1" 2024-04-16 Java(TM) SE Runtime Environment (build 22.0.1+8-16) Java HotSpot(TM) 64-Bit Server VM (build 22.0.1+8-16, mixed mode, sharing)`

<details><summary>2 previous messages</summary>

> 我先确认 Maven 的编译目标、生成的字节码版本，以及你本机是否安装了 JDK 11，区分“用哪个 JDK 运行”和“代码兼容哪个 Java 版本”。
>
> <details><summary>Explored 2 searches, ran 5 commands</summary>
>
> - Ran `/usr/libexec/java_home -V; rg -n 'maven.compiler|java.version' server/pom.xml client-part1/pom.xml client-part2/pom.xml; javap -verbose client-part2/target/classes/LoadClient.class | rg 'major version'; javap -verbose -classpath /Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar org.apache.hc.client5.http.impl.classic.CloseableHttpClient | rg 'major version'`
>
> - Ran `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -f client-part1/pom.xml clean package`
>
> - Ran `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -o -q -f server/pom.xml clean package`
>
> - Ran `JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home mvn -q -f client-part2/pom.xml clean package`
>
> <details><summary>Explored 2 searches</summary>
>
> - Searched for `Tests run` in `HttpTransportTest.txt`
> - Searched for `java.version" value="[^"]+` in `TEST-HttpTransportTest.xml`
>
> </details>
>
> - Ran `python3 - <<'PY'
> from pathlib import Path
> p=Path('results/HTTP_TRANSPORT_MIGRATION.md')
> s=p.read_text().replace('Both completed successfully: **8 tests each, 0 failures, 0 errors**.', 'Both completed successfully: **8 tests each, 0 failures, 0 errors**.\nSubsequently rebuilt both clients with Amazon Corretto **11.0.32.1**, including\nall eight tests per client: both passed with zero failures/errors. Surefire XML\nconfirms java.version=11.0.32.1. The server also built successfully with Java 11.')
> p.write_text(s)
> p=Path('README.md'); s=p.read_text().replace('- Client tests: macOS, JDK 22.', '- Historical EC2 client benchmarks: macOS, JDK 22.\n- Current Apache clients: build and 8 tests per module also verified on Corretto 11.0.32.1.\n- Use JDK 11 for the next remote measurements to match the course environment.')
> p.write_text(s)
> PY`
>
> </details>
</details>

**你记得对，后续统一使用 Java 11 更符合课程环境。之前本机默认使用 Java 22，我应该更早帮你统一。**

不过，**不需要重写代码，也不用放弃 Apache HttpClient 5**。我刚刚实际验证了：

| 项目 | 使用 Java 11 的结果 |
|---|---|
| `server` | 编译打包成功 |
| `client-part1` | 编译成功，8 项测试全部通过 |
| `client-part2` | 编译成功，8 项测试全部通过 |

你本机已经安装 **Amazon Corretto 11.0.32.1**。三个项目也都设置了：

```xml
<maven.compiler.release>11</maven.compiler.release>
```

这控制编译兼容性，但**不会自动把 IntelliJ 的运行 JDK 从 22 改为 11**。

现在统一设置：

**1. 在 IntelliJ 的两个客户端项目窗口分别操作：**

- **File → Project Structure → Project**
- 将 **SDK** 选择为 **Amazon Corretto 11**
- **Language level** 选择 **SDK default**

**2. 检查运行配置：**

- **Run → Edit Configurations**
- 分别选择 `LoadClient` 和 `SequentialClient`
- 将 **JRE / Java runtime** 设置为 **Project SDK（11）**或直接选 **Corretto 11**

如果需要手动指定 JDK 路径：

```text
/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home
```

**3. Mac 终端中切换 Maven 使用的 Java：**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
export PATH="$JAVA_HOME/bin:$PATH"
java -version
mvn -version
```

两者都应显示 Java 11。这个设置只影响当前终端窗口。

**以前的 Java 22 测试保留为历史基线，如实标注环境。** 下一次新版 RTT 和正式性能测试使用 Java 11；EC2 已经是 Java 11，不用改。


> (.venv) (base) anne@sousousous-MacBook-Air CS6650distributedsystem % curl --connect-timeout 5 --max-time 15 -i \\
>   [http://54.201.46.55:8080/ski-servlet/health](http://54.201.46.55:8080/ski-servlet/health)
> curl: (28) Failed to connect to 54.201.46.55 port 8080 after 5006 ms: Timeout was reached
> (.venv) (base) anne@sousousous-MacBook-Air CS6650distributedsystem %

这是**连接 EC2 的 8080 端口超时**，还没进入你的 Java 程序。先暂停运行 `SequentialClient`，按顺序排查。

**1. 在 AWS Console 检查实例。**

找到实例 `i-0d12f2707bcc779d5`，确认：

- 状态是 **Running**。
- 当前 **Public IPv4 address** 仍是 `54.201.46.55`。

如果 IP 已变化，后面的命令和客户端参数都使用新 IP。

**2. 在 Mac 终端尝试 SSH：**

```bash
ssh -o ConnectTimeout=10 \
  -i /Users/anne/Desktop/CS6650distributedsystem/cs6650-lab1-key.pem \
  ec2-user@54.201.46.55
```

**3. 如果 SSH 登录成功，在 EC2 终端执行：**

```bash
sudo ss -tlnp 'sport = :8080'
```

```bash
curl --max-time 5 -i http://localhost:8080/ski-servlet/health
```

根据结果处理：

| 结果 | 下一步 |
|---|---|
| 8080 没有监听进程 | 执行 `/opt/tomcat9/bin/startup.sh`，再测健康检查 |
| EC2 内部返回 200，但 Mac 超时 | 检查安全组 **TCP 8080** 的来源是否包含你当前公网 IP |
| SSH 也超时 | 先检查当前 IP、实例状态，以及安全组 **TCP 22** 的来源 |

如果你换了网络，原来的安全组 **My IP** 可能已经不适用，需要更新为当前 IP。

**先告诉我当前实例状态、公网 IP，以及 SSH 能否登录。** 不需要重新上传 WAR。

> (.venv) (base) anne@sousousous-MacBook-Air CS6650distributedsystem % ssh -o ConnectTimeout=10 -i /Users/anne/Desktop/CS6650distributedsystem/cs6650-lab1-key.pem ec2-user@34.220.189.189
> The authenticity of host '34.220.189.189 (34.220.189.189)' can't be established.
> ED25519 key fingerprint is: SHA256:yYKXvhU+EX38y4SHHCG/0EPxvq6VQFbgxMDbrWQkLW0
> This host key is known by the following other names/addresses:
>     \~/.ssh/known_hosts:14: 18.236.196.250
>     \~/.ssh/known_hosts:16: 54.149.165.21
>     \~/.ssh/known_hosts:17: 16.145.97.122
>     \~/.ssh/known_hosts:18: 35.90.90.200
>     \~/.ssh/known_hosts:19: 54.201.46.55
> Are you sure you want to continue connecting (yes/no/[fingerprint])? yes
> Warning: Permanently added '34.220.189.189' (ED25519) to the list of known hosts.
> \*\* WARNING: connection is not using a post-quantum key exchange algorithm.
> \*\* This session may be vulnerable to "store now, decrypt later" attacks.
> \*\* The server may need to be upgraded. See [https://openssh.com/pq.html](https://openssh.com/pq.html)
>
> A newer release of "Amazon Linux" is available.
>   Version 2023.12.20260914:
>   Version 2023.12.20260917:
>   Version 2023.12.20260918:
>   Version 2023.12.20260928:
>   Version 2023.12.20260930:
>   Version 2023.12.20261012:
>   Version 2023.12.20261013:
>   Version 2023.12.20261014:
> Run "/usr/bin/dnf check-release-update" for full release and version update info
>    ,     #\_
>    \~\\\_  ####\_        Amazon Linux 2023
>   \~\~  \\\_#####\\
>   \~\~     \\###|
>   \~\~       \\#/ \_\_\_   [https://aws.amazon.com/linux/amazon-linux-2023](https://aws.amazon.com/linux/amazon-linux-2023)
>    \~\~       V\~' '->
>     \~\~\~         /
>       \~\~.\_.   \_/
>          \_/ \_/
>        \_/m/'
> Last login: Thu Oct 15 13:08:33 2026 from 24.22.139.124
> [ec2-user@ip-172-31-45-254 \~]$ sudo ss -tlnp 'sport = :8080'
> State   Recv-Q   Send-Q     Local Address:Port     Peer Address:Port  Process
> [ec2-user@ip-172-31-45-254 \~]$ curl --max-time 5 -i [http://localhost:8080/ski-servlet/health](http://localhost:8080/ski-servlet/health)
> curl: (7) Failed to connect to localhost port 8080 after 0 ms: Could not connect to server
> [ec2-user@ip-172-31-45-254 \~]$

现在明确了两点：**EC2 公网 IP 已变为 `34.220.189.189`，而且 Tomcat 当前没有运行。**

**1. 在当前 EC2 终端执行：**

```bash
/opt/tomcat9/bin/startup.sh
```

等几秒，再执行：

```bash
curl --max-time 5 -i http://localhost:8080/ski-servlet/health
```

预期返回 **200** 和 `{"status":"UP"}`。如果没有成功，查看日志并贴给我：

```bash
tail -40 /opt/tomcat9/logs/catalina.out
```

**2. 如果 EC2 内部检查成功，在 Mac 新开一个终端执行：**

```bash
curl --connect-timeout 5 --max-time 15 -i \
  http://34.220.189.189:8080/ski-servlet/health
```

**3. 如果 Mac 也返回 200，再修改 IntelliJ：**

在 **`client-part2` → Run → Edit Configurations → SequentialClient** 中：

- **JRE**：Corretto 11
- **Program arguments**：

  ```text
  http://34.220.189.189:8080/ski-servlet
  ```

然后运行 **`SequentialClient.main()`**，把输出贴给我。这次不需要重新上传 WAR。

> /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=58943:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part2/target/classes:/Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5/5.4.3/httpcore5-5.4.3.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5-h2/5.4.3/httpcore5-h2-5.4.3.jar:/Users/anne/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar SequentialClient http://34.220.189.189:8080/ski-servlet
> SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
> SLF4J: Defaulting to no-operation (NOP) logger implementation
> SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
> Fresh-client request (includes connection setup): 295.246 ms
> Successful: 100
> Failed: 0
> Average pooled latency: 27.444 ms
> Measured throughput: 36.44 swipes/sec
> Initial prediction for 32 senders: 1166.01 swipes/sec
> Initial prediction for 64 senders: 2332.02 swipes/sec
> Initial prediction for 128 senders: 4664.03 swipes/sec
>
> --- HTTP transport: Apache HttpClient 5.6.4 ---
> Application request attempts: 121
> Application request retries: 0
> TCP connect attempts: 2
> TCP connections established: 2
> TCP connect attempts without success: 0
> TCP connections established during application retries: 0
> HTTP reconnection definition: new TCP opens during application retries; not all replacement/idle-expiry connections.
> Pool before close: leased=0, available=1, pending=0, max=1
> Sampled pool peaks (50 ms + attempt completion): leased=1, available=1, pending=0
>
> Process finished with exit code 0

**新版 Apache HttpClient + Java 11 的远程 RTT 测试通过了。** 第一行确认运行的是 Corretto 11。

| 指标 | 结果 |
|---|---:|
| 首次请求耗时，含初始化和建连 | 295.246 ms |
| 复用连接后的平均 RTT | **27.444 ms** |
| 100 次测量请求 | 全部成功 |
| 32 线程初步预测 | 1,166 条/秒 |
| 128 线程初步预测 | 4,664 条/秒 |

连接统计也合理：

- **121 次尝试**＝1 次首次请求＋20 次预热＋100 次测量。
- **累计建立 2 条 TCP 连接，池容量为 1**并不矛盾：容量限制同时保留的连接数，不限制整个运行期间累计建连次数。
- 为什么替换过一次连接，目前没有足够证据确定，不能直接认定发生了故障。
- 最后 `leased=0、pending=0`，说明没有请求继续占用或等待连接。
- SLF4J 提示表示没有配置日志输出实现，**不是请求失败**；我们的指标计数不依赖它。

保存这份输出作为 **Apache + Java 11 的预测基准**。

下一步运行 **5,000 条联合测试**。在 `client-part2` 中：

1. 打开 **Run → Edit Configurations → LoadClient**。
2. **JRE** 选择 Corretto 11。
3. **Program arguments** 填入：

   ```text
   http://34.220.189.189:8080/ski-servlet 5000 32 10
   ```

   四个参数分别是：服务器地址、总事件数、主阶段线程数、每个预热线程发送数。WebSocket 地址会自动派生，不用另改代码。

4. 将控制台输出保存为：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-smoke.txt
   ```

5. 保持 EC2 Tomcat 运行，运行 **`LoadClient.main()`**。

预期 **320 条预热＋4,680 条主阶段，全部成功**。把最终汇总贴给我，重点看新增 HTTP 连接统计、WebSocket 更新数，以及两份 CSV 是否生成。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java…: `~/.codex/attachments/6c102e0e-ffd8-45da-b5a3-2e2035e8883d/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查这次小规模联合测试的连接统计、请求结果和 CSV 输出。
>
> <details><summary>Ran a command</summary>
>
> - Ran `tail -95 '/Users/anne/.codex/attachments/6c102e0e-ffd8-45da-b5a3-2e2035e8883d/Pasted text.txt'`
>
> </details>
</details>

**新版小规模联合测试通过了。**

- 5,000 条全部成功，应用层重试 **0 次**。
- HTTP 和推送两份 CSV 都已生成。
- 10 个 WebSocket 订阅各收到 14 条消息，共 **140 条**，没有重连。
- 结束时 `leased=0、pending=0`，没有未释放的 HTTP 连接租用或等待请求。

**累计建立 64 条 TCP 连接、池容量 32，并不矛盾。** 前者是整个运行期间的累计次数，后者是同时允许的池容量。这里说明运行期间发生了连接替换，但仅凭汇总不能确定原因。`pending` 的采样峰值为 0，也只能说明采样时没观察到等待。

现在可以进行 **新版 Part 2 的完整远程测试**。

在 **`client-part2 → Run → Edit Configurations → LoadClient`** 中设置：

- **JRE**：Corretto 11。
- **Program arguments**：

  ```text
  http://34.220.189.189:8080/ski-servlet 500000 128 1000
  ```

- **保存控制台输出到**：

  ```text
  /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part2-128threads.txt
  ```

保持 EC2 Tomcat 运行，然后运行 **`LoadClient.main()`**。参数会产生：

```text
预热：32 × 1,000 = 32,000 条
主阶段：128 个线程处理剩余 468,000 条
总量：500,000 条
```

这次输出用于替换最终报告中的新版性能结果；旧 JDK 版本结果继续保留作比较。完成后保存汇总截图，并把结果贴给我。推送延迟仍保留原始负值，在报告中说明时钟偏差。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java…: `~/.codex/attachments/9c3c580c-090b-4984-9104-ceb3fb50a439/Pasted text.txt`

<details><summary>3 previous messages</summary>

> 我先查看新版完整测试的吞吐量、连接统计和最终结果。
>
> <details><summary>Ran a command, searched the web</summary>
>
> - Ran `tail -90 '/Users/anne/.codex/attachments/9c3c580c-090b-4984-9104-ceb3fb50a439/Pasted text.txt'`
>
> Searched the web for `site.tomcat.apache.org/tomcat-9.0-doc/config/http.html maxKeepAliveRequests default 100`
>
> </details>
>
> Context compaction
>
> Source: automatic
> Status: completed
</details>

**这次 Part 2 的完整测试成功跑完了：500,000 条全部成功，失败和重试都是 0。** 但推送延迟仍受两台电脑的时钟偏差影响，需要在报告中说明。

| 指标 | 本次结果 |
|---|---:|
| Warmup：32,000 条 | 35.287 秒 |
| Main：468,000 条 | 135.665 秒 |
| 总吞吐量（含 warmup） | 2,924.80 条/秒 |
| Main 阶段吞吐量 | 约 3,449.67 条/秒 |
| HTTP 平均延迟 / p99 | 36.939 / 122.782 ms |
| WebSocket 更新 | 3,430 条 |
| WebSocket 重连 | 0 |

128 个发送线程的预测值是 **4,664.03 条/秒**，Main 实测约达到预测值的 **74%**。报告应使用 Main 阶段与这个预测比较，因为 warmup 只有 32 个线程。

**5,058 次 TCP 建连不等于 5,058 次故障重连。** 平均每条连接处理约 99 个请求，与 Tomcat 默认每个持久连接最多处理 100 个请求的设置相符；这是合理解释，但还需要检查 EC2 的实际配置才能确认。[Tomcat 官方说明](https://tomcat.apache.org/tomcat-9.0-doc/config/http.html)

推送延迟平均值为 **−90.872 ms**，应保留原始结果，并说明存在时钟偏差，不能把它解释成真实的单向网络延迟。

**下一步：补跑新版 Part 1 的正式结果。**

1. **保持 EC2 上的 Tomcat 运行。** 等当前 Part 2 已结束后再开始。
2. 在 IntelliJ 打开 **client-part1**，进入 **Run → Edit Configurations → LoadClient**。
3. 确认 JRE 使用 **Java 11**，在 **Program arguments** 填入：

   ```text
   http://34.220.189.189:8080/ski-servlet 500000 128 1000
   ```

4. 将控制台输出保存到：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part1-128threads.txt
   ```

5. 运行 `LoadClient`，等出现 `Process finished with exit code 0`，把结果贴给我。

这次 Part 2 的 CSV 要保留：`swipes-1792110301816.csv`。后续最终吞吐量图应使用这份新版数据生成。

> User context
>
> Attachments:
> - /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java…: `~/.codex/attachments/acd40e97-bf67-41b1-bdc8-868cdaaeb240/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先查看这次 Part 1 的完整输出，确认结果后告诉你下一步。
>
> <details><summary>Explored 2 files</summary>
>
> - Read `~/.codex/attachments/acd40e97-bf67-41b1-bdc8-868cdaaeb240/Pasted text.txt`
> - Read `./client-part2/plot_throughput.py`
>
> </details>
</details>

**这次 Part 1 完整测试也通过了。** Java 11 + Apache HttpClient 的两个客户端，现在都有 EC2 上的完整运行结果。

| 指标 | Part 1 本次结果 |
|---|---:|
| Warmup | 32,000 条成功，34.639 秒 |
| Main | 468,000 条成功，128.402 秒 |
| 总成功 / 失败 | 500,000 / 0 |
| 总吞吐量（含 warmup） | 3,066.71 条/秒 |
| HTTP 重试 | 0 |
| WebSocket 更新 | 3,260 条 |
| WebSocket 重连 | 0 |

输出已保存到 `results/ec2/apache5-java11-part1-128threads.txt`。推送延迟仍有负值，报告需要说明跨机器时钟偏差，不能当作真实网络延迟。

**下一步：用新版 Part 2 数据生成最终吞吐量图。**

在 **Mac 的终端**中运行，直接复制下面两行：

```bash
cd /Users/anne/Desktop/CS6650distributedsystem
.venv/bin/python client-part2/plot_throughput.py client-part2/results/swipes-1792110301816.csv
```

这一步读取已有 CSV，**不需要重新运行负载测试，也不需要操作 Tomcat**。

生成的图片是：

[swipes-1792110301816-throughput.png](./client-part2/results/swipes-1792110301816-throughput.png)

同时趁 IntelliJ 控制台还在，截图保存本次 Part 1 的 **Warmup、Main、Total ingest** 输出。文字日志和截图都保留，后面整理报告时使用。

> 这次part1（ Warmup ---
> Successful: 32000
> Failed: 0
> Runtime: 34.639 seconds
>
> --- Main ---
> Successful: 468000
> Failed: 0
> Runtime: 128.402 seconds
>
> --- Total ingest ---
> Successful: 500000
> Failed: 0
> Remaining in queue: 0
> Runtime: 163.041 seconds
> Throughput: 3066.71 swipes/sec
>
> --- HTTP transport: Apache HttpClient 5.6.4 ---
> Application request attempts: 500000
> Application request retries: 0
> TCP connect attempts: 5059
> TCP connections established: 5059
> TCP connect attempts without success: 0
> TCP connections established during application retries: 0
> HTTP reconnection definition: new TCP opens during application retries; not all replacement/idle-expiry connections.
> Pool before close: leased=0, available=128, pending=0, max=128
> Sampled pool peaks (50 ms + attempt completion): leased=128, available=128, pending=0
>
> --- WebSocket subscriptions ---
> Resort 1: updates=326, connections=1, reconnections=0
> Resort 2: updates=326, connections=1, reconnections=0
> Resort 3: updates=326, connections=1, reconnections=0
> Resort 4: updates=326, connections=1, reconnections=0
> Resort 5: updates=326, connections=1, reconnections=0
> Resort 6: updates=326, connections=1, reconnections=0
> Resort 7: updates=326, connections=1, reconnections=0
> Resort 8: updates=326, connections=1, reconnections=0
> Resort 9: updates=326, connections=1, reconnections=0
> Resort 10: updates=326, connections=1, reconnections=0
> Total leaderboard updates: 3260
> Push CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part1/results/pushes-1792111343814.csv
>
> --- WebSocket push latency ---
> Samples: 3260
> Mean: -72.903 ms
> Median: -76.475 ms
> p95: -57.196 ms
> p99: 6.322 ms
>
> Total program runtime: 165.222 seconds
>
> Process finished with exit code 0）和part2 （
> --- Warmup ---
> Successful: 32000
> Failed: 0
> Runtime: 35.287 seconds
>
> --- Main ---
> Successful: 468000
> Failed: 0
> Runtime: 135.665 seconds
>
> --- Total ingest ---
> Successful: 500000
> Failed: 0
> Remaining in queue: 0
> Runtime: 170.952 seconds
> Throughput: 2924.80 swipes/sec
>
> --- Throughput per resort ---
> Resort 1: successful=49725, throughput=290.87 swipes/sec
> Resort 2: successful=50055, throughput=292.80 swipes/sec
> Resort 3: successful=50683, throughput=296.48 swipes/sec
> Resort 4: successful=49982, throughput=292.37 swipes/sec
> Resort 5: successful=49903, throughput=291.91 swipes/sec
> Resort 6: successful=49893, throughput=291.85 swipes/sec
> Resort 7: successful=50016, throughput=292.57 swipes/sec
> Resort 8: successful=50009, throughput=292.53 swipes/sec
> Resort 9: successful=49715, throughput=290.81 swipes/sec
> Resort 10: successful=50019, throughput=292.59 swipes/sec
>
> --- HTTP transport: Apache HttpClient 5.6.4 ---
> Application request attempts: 500000
> Application request retries: 0
> TCP connect attempts: 5058
> TCP connections established: 5058
> TCP connect attempts without success: 0
> TCP connections established during application retries: 0
> HTTP reconnection definition: new TCP opens during application retries; not all replacement/idle-expiry connections.
> Pool before close: leased=0, available=128, pending=0, max=128
> Sampled pool peaks (50 ms + attempt completion): leased=128, available=128, pending=0
>
> --- WebSocket subscriptions ---
> Resort 1: updates=343, connections=1, reconnections=0, avgConnectMs=771.638
> Resort 2: updates=343, connections=1, reconnections=0, avgConnectMs=717.143
> Resort 3: updates=343, connections=1, reconnections=0, avgConnectMs=714.754
> Resort 4: updates=343, connections=1, reconnections=0, avgConnectMs=561.056
> Resort 5: updates=343, connections=1, reconnections=0, avgConnectMs=702.232
> Resort 6: updates=343, connections=1, reconnections=0, avgConnectMs=702.481
> Resort 7: updates=343, connections=1, reconnections=0, avgConnectMs=692.326
> Resort 8: updates=343, connections=1, reconnections=0, avgConnectMs=702.452
> Resort 9: updates=343, connections=1, reconnections=0, avgConnectMs=701.330
> Resort 10: updates=343, connections=1, reconnections=0, avgConnectMs=696.424
> Total leaderboard updates: 3430
> HTTP attempts recorded: 500000
> CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/swipes-1792110301816.csv
> Push CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/pushes-1792110301816.csv
>
> --- HTTP attempt latency ---
> Samples: 500000
> Mean: 36.939 ms
> Median: 31.651 ms
> p95: 77.010 ms
> p99: 122.782 ms
> Min: 11.612 ms
> Max: 586.850 ms
>
> --- WebSocket push latency ---
> Samples: 3430
> Mean: -90.872 ms
> Median: -97.819 ms
> p95: -37.446 ms
> p99: -10.490 ms
>
> Total program runtime: 173.847 seconds
>
> Process finished with exit code 0）的这些截图都要保存吗 怎么命名呢

**对，这两次运行的结果都要保存。可以分成几张截图，不用把全部输出挤进一张。** 同一张截图里保持文字清晰，分段截图时可以稍微重叠，避免漏掉内容。

统一保存到这个文件夹：

```text
/Users/anne/Desktop/CS6650distributedsystem/results/ec2/
```

**client-part1：保存 3 张截图**

| 文件名 | 截图内容 |
|---|---|
| `apache5-java11-part1-128threads-summary.png` | Warmup、Main、Total ingest |
| `apache5-java11-part1-128threads-http.png` | HTTP transport 全部连接与连接池统计 |
| `apache5-java11-part1-128threads-websocket.png` | WebSocket subscriptions、push latency、Total program runtime、exit code |

**client-part2：保存 4 张截图**

| 文件名 | 截图内容 |
|---|---|
| `apache5-java11-part2-128threads-summary.png` | Warmup、Main、Total ingest、Throughput per resort |
| `apache5-java11-part2-128threads-http.png` | HTTP transport 全部连接与连接池统计 |
| `apache5-java11-part2-128threads-websocket.png` | WebSocket subscriptions、Total leaderboard updates |
| `apache5-java11-part2-128threads-latency.png` | HTTP attempt latency、WebSocket push latency、Total program runtime、exit code |

在 Mac 上按 **⌘⇧4**，框选控制台需要的区域；然后把截图移到上述文件夹并重命名。如果一张放不下，可拆成 `-1.png`、`-2.png`。

另外，新版 Part 2 的吞吐量图可以复制到同一个文件夹，命名为：

```text
apache5-java11-part2-128threads-throughput.png
```

**保留负延迟的原始截图，不修改数值。** 报告里解释时钟偏差。完整 `.txt` 日志和原始 CSV 也继续保留，截图用于报告展示，原始文件用于核查数据。


> User context
>
> Attachments:
> - (base) anne@sousousous-MacBook-Air CS6650distributedsystem % cd /Users/anne/Des…: `~/.codex/attachments/600fd7a7-7a69-40cf-938b-11ad7213ffe8/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查两个客户端的编译和测试结果。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/600fd7a7-7a69-40cf-938b-11ad7213ffe8/Pasted text.txt`
>
> </details>
</details>

**两份都编译成功了，各自的 8 项测试也全部通过。** 下一步验证第五个参数能否真正把 resort 数量改成 2。

先测试 **client-part2**：

1. 保持 **EC2 的 Tomcat 运行**。
2. 在 IntelliJ 打开 **client-part2 → Run → Edit Configurations → LoadClient**。
3. 确认 JRE 是 **Java 11**，把 **Program arguments** 改为：

   ```text
   http://34.220.189.189:8080/ski-servlet 5000 32 10 2
   ```

   参数依次表示：服务器地址、5,000 条事件、32 个主阶段线程、每个 warmup 线程发送 10 条、**2 个 resort**。如果 EC2 IP 已改变，替换成当前地址。

4. 将 **Save console output to file** 改为下面的新路径，避免覆盖正式测试日志：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part2-2resorts-smoke.txt
   ```

5. 点击 **Apply → OK**，运行 `LoadClient`，等待结束。

预期结果：

- Warmup：成功 **320** 条。
- Main：成功 **4,680** 条。
- Total：成功 **5,000** 条，失败 **0**，队列剩余 **0**。
- `Throughput per resort` 只显示 **Resort 1、2**。
- `WebSocket subscriptions` 只显示 **Resort 1、2**，两者都有收到更新。

把这次输出贴给我。确认 Part 2 的配置生效后，再验证 Part 1。

> /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=54638:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part2/target/classes:/Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5/5.4.3/httpcore5-5.4.3.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5-h2/5.4.3/httpcore5-h2-5.4.3.jar:/Users/anne/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar LoadClient http://34.220.189.189:8080/ski-servlet 5000 32 10 2
> Console output is saving to: /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part2-2resorts-smoke.txt
> HTTP transport: Apache HttpClient 5.6.4; events=5000, mainThreads=32
> SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
> SLF4J: Defaulting to no-operation (NOP) logger implementation
> SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
> Connected to resort 2
> Connected to resort 1
> Resort 1 connected in 272.009 ms
> Resort 2 connected in 207.161 ms
> Resort 2, update 1, server time: 2026-10-16T02:43:14.529064Z, received at: 2026-10-16T02:43:14.499857Z
> Resort 1, update 1, server time: 2026-10-16T02:43:14.528969Z, received at: 2026-10-16T02:43:14.552308Z
> Resort 1, update 2, server time: 2026-10-16T02:43:15.028933Z, received at: 2026-10-16T02:43:14.998418Z
> Resort 2, update 2, server time: 2026-10-16T02:43:15.029022Z, received at: 2026-10-16T02:43:14.999407Z
> Resort 2, update 3, server time: 2026-10-16T02:43:15.528978Z, received at: 2026-10-16T02:43:15.499127Z
> Resort 1, update 3, server time: 2026-10-16T02:43:15.528917Z, received at: 2026-10-16T02:43:15.499558Z
> Generation time: 0.008 seconds
>
> --- Warmup ---
> Successful: 320
> Failed: 0
> Runtime: 0.614 seconds
>
> --- Main ---
> Successful: 4680
> Failed: 0
> Runtime: 5.072 seconds
>
> --- Total ingest ---
> Successful: 5000
> Failed: 0
> Remaining in queue: 0
> Runtime: 5.686 seconds
> Throughput: 879.36 swipes/sec
>
> --- Throughput per resort ---
> Resort 1: successful=2558, throughput=449.88 swipes/sec
> Resort 2: successful=2442, throughput=429.48 swipes/sec
>
> --- HTTP transport: Apache HttpClient 5.6.4 ---
> Application request attempts: 5000
> Application request retries: 0
> TCP connect attempts: 64
> TCP connections established: 64
> TCP connect attempts without success: 0
> TCP connections established during application retries: 0
> HTTP reconnection definition: new TCP opens during application retries; not all replacement/idle-expiry connections.
> Pool before close: leased=0, available=32, pending=0, max=32
> Sampled pool peaks (50 ms + attempt completion): leased=32, available=32, pending=0
>
> --- WebSocket subscriptions ---
> Resort 1: updates=12, connections=1, reconnections=0, avgConnectMs=272.009
> Resort 2: updates=12, connections=1, reconnections=0, avgConnectMs=207.161
> Total leaderboard updates: 24
> HTTP attempts recorded: 5000
> CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/swipes-1792118600180.csv
> Push CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part2/results/pushes-1792118600180.csv
>
> --- HTTP attempt latency ---
> Samples: 5000
> Mean: 35.978 ms
> Median: 28.787 ms
> p95: 112.553 ms
> p99: 126.988 ms
> Min: 12.353 ms
> Max: 333.015 ms
>
> --- WebSocket push latency ---
> Samples: 24
> Mean: -27.887 ms
> Median: -29.987 ms
> p95: -28.211 ms
> p99: 23.339 ms
>
> Total program runtime: 6.661 seconds
>
> Process finished with exit code 0

**Part 2 的两 resort 测试通过了，Java 11 和运行参数也都设置正确。** 启动路径中的 `amazon-corretto-11.jdk` 确认了这一点。:codex-annotation{index="1"}

结果符合预期：

- Warmup **320** 条、Main **4,680** 条，全部成功。
- 只有 Resort 1、2，成功数量合计 **5,000**。
- 只有两个 WebSocket 订阅，各收到 **12** 次更新。
- HTTP 重试为 **0**，队列剩余为 **0**。

**下一步：验证 client-part1。**

1. 保持 EC2 Tomcat 运行，在 IntelliJ 切到 **client-part1**。
2. 打开 **Run → Edit Configurations → LoadClient**，确认 JRE 是 **Java 11**。
3. 在 **Program arguments** 填入：

   ```text
   http://34.220.189.189:8080/ski-servlet 5000 32 10 2
   ```

4. 把控制台输出保存路径改成：

   ```text
   /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part1-2resorts-smoke.txt
   ```

5. 点击 **Apply → OK**，运行 `LoadClient`，等待结束。

预期仍是 **320 + 4,680 = 5,000 条全部成功**，WebSocket 汇总只显示 **Resort 1、2**。Part 1 没有每 resort 吞吐量表，这是正常的。

把结果贴给我，之后我们补齐 Part 1 最终汇总中的 WebSocket 建连时间。

> /Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home/bin/java -javaagent:/Applications/IntelliJ IDEA.app/Contents/lib/idea_rt.jar=54743:/Applications/IntelliJ IDEA.app/Contents/bin -Dfile.encoding=UTF-8 -classpath /Users/anne/Desktop/CS6650distributedsystem/client-part1/target/classes:/Users/anne/.m2/repository/org/apache/httpcomponents/client5/httpclient5/5.6.4/httpclient5-5.6.4.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5/5.4.3/httpcore5-5.4.3.jar:/Users/anne/.m2/repository/org/apache/httpcomponents/core5/httpcore5-h2/5.4.3/httpcore5-h2-5.4.3.jar:/Users/anne/.m2/repository/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar:/Users/anne/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar LoadClient http://34.220.189.189:8080/ski-servlet 5000 32 10 2
> Console output is saving to: /Users/anne/Desktop/CS6650distributedsystem/results/ec2/apache5-java11-part1-2resorts-smoke.txt
> HTTP transport: Apache HttpClient 5.6.4; events=5000, mainThreads=32
> SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
> SLF4J: Defaulting to no-operation (NOP) logger implementation
> SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
> Connected to resort 2
> Connected to resort 1
> Resort 1 connected in 249.267 ms
> Resort 2 connected in 222.482 ms
> Resort 1, update 1, server time: 2026-10-16T02:45:46.528925Z, received at: 2026-10-16T02:45:46.499493Z
> Resort 2, update 1, server time: 2026-10-16T02:45:46.529011Z, received at: 2026-10-16T02:45:46.538985Z
> Resort 1, update 2, server time: 2026-10-16T02:45:47.028922Z, received at: 2026-10-16T02:45:46.999713Z
> Resort 2, update 2, server time: 2026-10-16T02:45:47.028993Z, received at: 2026-10-16T02:45:47.000198Z
> Resort 1, update 3, server time: 2026-10-16T02:45:47.528929Z, received at: 2026-10-16T02:45:47.500039Z
> Resort 2, update 3, server time: 2026-10-16T02:45:47.529013Z, received at: 2026-10-16T02:45:47.500467Z
> Generation time: 0.006 seconds
>
> --- Warmup ---
> Successful: 320
> Failed: 0
> Runtime: 0.634 seconds
>
> --- Main ---
> Successful: 4680
> Failed: 0
> Runtime: 5.033 seconds
>
> --- Total ingest ---
> Successful: 5000
> Failed: 0
> Remaining in queue: 0
> Runtime: 5.667 seconds
> Throughput: 882.35 swipes/sec
>
> --- HTTP transport: Apache HttpClient 5.6.4 ---
> Application request attempts: 5000
> Application request retries: 0
> TCP connect attempts: 64
> TCP connections established: 64
> TCP connect attempts without success: 0
> TCP connections established during application retries: 0
> HTTP reconnection definition: new TCP opens during application retries; not all replacement/idle-expiry connections.
> Pool before close: leased=0, available=32, pending=0, max=32
> Sampled pool peaks (50 ms + attempt completion): leased=32, available=32, pending=0
>
> --- WebSocket subscriptions ---
> Resort 1: updates=12, connections=1, reconnections=0
> Resort 2: updates=12, connections=1, reconnections=0
> Total leaderboard updates: 24
> Push CSV saved to: /Users/anne/Desktop/CS6650distributedsystem/client-part1/results/pushes-1792118752077.csv
>
> --- WebSocket push latency ---
> Samples: 24
> Mean: -27.054 ms
> Median: -28.858 ms
> p95: -26.021 ms
> p99: 9.974 ms
>
> Total program runtime: 6.360 seconds
>
> Process finished with exit code 0

<details><summary>2 previous messages</summary>

> Part 1 的两 resort 测试也通过了。我先查看它的建连计时代码，再告诉你具体在哪里补上最终汇总。
>
> <details><summary>Explored 2 files</summary>
>
> - Read `./client-part1/src/main/java/LeaderboardConnection.java`
> - Read `./client-part1/src/main/java/LoadClient.java`
>
> </details>
</details>

**Part 1 也通过了：5,000 条全部成功，只有两个 resort 订阅，各收到 12 次更新。** 两个客户端的 resort 配置现在都验证成功。

> Last login: Thu Oct 15 19:51:14 on ttys006
> (base) anne@sousousous-MacBook-Air ~ % touch /Users/anne/Desktop/CS6650distributedsystem/DESIGN.md
> (base) anne@sousousous-MacBook-Air ~ % cd /Users/anne/Desktop/CS6650distributedsystem
> git init
> Initialized empty Git repository in /Users/anne/Desktop/CS6650distributedsystem/.git/
> (base) anne@sousousous-MacBook-Air CS6650distributedsystem % git check-ignore -v cs6650-lab1-key.pem
> .gitignore:2:*.pem	cs6650-lab1-key.pem
> (base) anne@sousousous-MacBook-Air CS6650distributedsystem % git status --short
> ?? .gitignore
> ?? DESIGN.md
> ?? README.md
> ?? client-part1/
> ?? client-part2/
> ?? lab2/
> ?? results/
> ?? server/
> (base) anne@sousousous-MacBook-Air CS6650distributedsystem %

<details><summary>2 previous messages</summary>

> 私钥忽略规则已生效。我先检查设计文档是否有内容，以及待提交文件中是否有过大的文件或嵌套仓库，再指导你暂存。
>
> <details><summary>Ran a command</summary>
>
> - Ran `wc -c DESIGN.md README.md; git status --short; python3 - <<'PY'
> import os
> for root, dirs, files in os.walk('.'):
>     if '.git' in dirs:
>         if root != '.': print('Nested repository:', root)
>         dirs.remove('.git')
>     dirs[:] = [d for d in dirs if d not in ('.venv','target','.idea','__pycache__')]
>     for name in files:
>         p=os.path.join(root,name)
>         size=os.path.getsize(p)
>         if size > 50*1024*1024: print('Large file:',p,round(size/1024/1024,1),'MiB')
> PY`
>
> </details>
</details>

**Git 初始化成功，私钥已被正确忽略。** 我也检查了 `DESIGN.md`，里面已有内容。

发现 `lab2` 中有一个约 **60.6 MiB** 的旧实验输出文件。Assignment 1 使用根目录的 `server` 和两个客户端，旧 `lab2` 可以保留在电脑上，不加入这次仓库。

**1. 在根目录 `.gitignore` 末尾添加：**

```gitignore
# Earlier lab work; Assignment 1 uses /server
/lab2/
```

保存。这不会删除 `lab2`。

**2. 在当前 Mac 终端暂存 Assignment 1 文件：**

```bash
git add .gitignore README.md DESIGN.md server client-part1 client-part2 results
```

“暂存”表示选择要放进下一次提交的文件，**还没有上传**。

**3. 检查暂存列表：**

```bash
git diff --cached --stat
```

再检查是否误加入私钥、构建目录或虚拟环境：

```bash
git diff --cached --name-only | grep -E '(\.pem$|\.key$|(^|/)(target|\.venv|\.idea|lab2)/)'
```

第二条命令预期**没有输出**。

把这两条检查命令的输出贴给我。确认后，我们再创建第一次本地提交。

> User context
>
> Attachments:
> - (base) anne@sousousous-MacBook-Air CS6650distributedsystem % git add .gitignore…: `~/.codex/attachments/9240459c-f236-446d-97e4-e08f317a394e/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先检查暂存结果，确认没有把私钥或本地构建文件加入提交。
>
> <details><summary>Explored a file, ran a command</summary>
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/9240459c-f236-446d-97e4-e08f317a394e/Pasted text.txt`
>
> </details>
>
> - Ran `git --no-pager diff --cached --name-only | grep -E '(\.pem$|\.key$|(^|/)(target|\.venv|\.idea|lab2)/)'`
>
> </details>
</details>

**暂存成功。我检查了实际暂存列表，没有私钥、`target`、`.venv`、`.idea` 或 `lab2`。**

你最后看到的 **`(END)` 是 Git 的分页查看器**，还没有回到终端，所以后面的命令没有正常执行。

**1. 按键盘上的 `q` 退出查看器。**

看到下面这样的提示符后再输入命令：

```text
(base) anne@... CS6650distributedsystem %
```

**2. 创建第一次本地提交：**

```bash
git commit -m "Add Assignment 1 implementation, design and test results"
```

这会保存当前暂存文件的版本，**不会上传到 GitHub**。

**3. 检查提交结果：**

```bash
git --no-pager log -1 --oneline
git status --short
```

预期：

- 第一条显示提交编号和刚才的提交说明。
- 第二条没有输出，表示没有未提交的改动。

如果 `git commit` 提示 `Author identity unknown`，把提示贴给我；否则把提交结果贴给我，下一步创建 GitHub 仓库。

> User context
>
> Attachments:
> - 94 files changed, 4032354 insertions(+) (base) anne@sousousous-MacBook-Air CS66…: `~/.codex/attachments/d72245c2-53e3-4ae2-972a-801ee33872d5/Pasted text.txt`

<details><summary>2 previous messages</summary>

> 我先确认本地提交是否成功，再指导你创建 GitHub 仓库。
>
> <details><summary>Explored a file</summary>
>
> - Read `~/.codex/attachments/d72245c2-53e3-4ae2-972a-801ee33872d5/Pasted text.txt`
>
> </details>
</details>

**本地提交成功了。** 提交编号是 `eb27ca9`，`git status --short` 没有输出，说明当前文件都已提交。

下一步创建 GitHub 空仓库：

1. 浏览器打开 [GitHub 新建仓库页面](https://github.com/new)，登录你的账号。
2. **Repository name** 填：

   ```text
   cs6650-assignment1
   ```

3. 可见性按课程要求选择。如果没有规定，先选 **Private**，提交前再给老师或 TA 访问权限。
4. **不要初始化 README、.gitignore 或 License**，因为本地已经有文件和提交。
5. 点击 **Create repository**。

创建后，把仓库页面的 URL 贴给我，例如：

```text
https://github.com/你的用户名/cs6650-assignment1
```

> (base) anne@sousousous-MacBook-Air CS6650distributedsystem % git push -u origin main
> Enumerating objects: 113, done.
> Counting objects: 100% (113/113), done.
> Delta compression using up to 8 threads
> Compressing objects: 100% (105/105), done.
> Writing objects: 100% (113/113), 53.49 MiB | 4.12 MiB/s, done.
> Total 113 (delta 19), reused 0 (delta 0), pack-reused 0 (from 0)
> remote: Resolving deltas: 100% (19/19), done.
> To [https://github.com/Anne-Zeng/cs6650-assignment1.git](https://github.com/Anne-Zeng/cs6650-assignment1.git)
>  \* [new branch]      main -> main
> branch 'main' set up to track 'origin/main'.

**上传成功了。** 本地 `main` 已关联 GitHub 的 `origin/main`，仓库地址是：

[Anne-Zeng/cs6650-assignment1](https://github.com/Anne-Zeng/cs6650-assignment1)
