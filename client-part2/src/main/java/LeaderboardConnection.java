import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

//这个类负责三件事：
//- 连接失败或中途断开后，等待一段时间再连接。
//- 防止同一个订阅同时安排多个重连任务。
//- 测试结束调用 close() 后，停止重连并释放连接。
public class LeaderboardConnection implements AutoCloseable {

  private final HttpClient client;
  private final URI uri;
  private final int resortID;

  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor();
  private final java.util.List<Double> connectionTimesMs =
      new java.util.ArrayList<>();

  private final AtomicInteger connections = new AtomicInteger();
  private final AtomicInteger completedUpdates = new AtomicInteger();

  private final PushMetrics pushMetrics;

  // 以下状态由 synchronized 方法保护。
  private boolean stopped;
  private boolean connecting;
  private boolean retryScheduled;
  private int consecutiveFailures;
  private WebSocket socket;
  private LeaderboardSubscriber currentSubscriber;

  private long connectionGeneration;
  private boolean disconnectedDuringConnect;

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
    long generation = ++connectionGeneration;
    disconnectedDuringConnect = false;

    //重连时继续传同一个 pushMetrics，因此之前记录的延迟不会丢失。
    LeaderboardSubscriber subscriber =
        new LeaderboardSubscriber(
            resortID, () -> disconnected(generation), pushMetrics
        );

    currentSubscriber = subscriber;

    long start = System.nanoTime();

    client.newWebSocketBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .buildAsync(uri, subscriber)
        .whenComplete((connectedSocket, error) ->
            connectionFinished(connectedSocket, error, start, generation));
  }

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

    //每次成功连接，包括成功重连，都会保存一次建立连接的耗时。失败连接的等待时间不包含在这里。
    double connectionMs =
        (System.nanoTime() - start) / 1_000_000.0;

    connectionTimesMs.add(connectionMs);

    System.out.printf(
        "Resort %d connected in %.3f ms%n",
        resortID, connectionMs
    );

    // 连接可能在握手完成后立即关闭。
    if (disconnectedDuringConnect
        || socket.isInputClosed()
        || socket.isOutputClosed()) {
      disconnected(generation);
    }
  }

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

  //connectionFinished() 和这个读取方法都使用 synchronized，保护共享列表的访问。
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

  @Override
  public synchronized void close() {
    stopped = true;
    connectionGeneration++;
    scheduler.shutdownNow();

    if (socket != null) {
      socket.abort();
      socket = null;
    }
  }
}