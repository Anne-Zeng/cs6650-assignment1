import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.Timeout;

/** One shared HTTP/1.1 pool. Java HttpClient remains responsible for WebSockets. */
public final class HttpTransport implements AutoCloseable {
  private static final String RETRY = "skitrak.applicationRetry";
  private final AtomicLong connectAttempts = new AtomicLong();
  private final AtomicLong connectionsEstablished = new AtomicLong();
  private final AtomicLong connectionsOnRetry = new AtomicLong();
  private final AtomicLong requestAttempts = new AtomicLong();
  private final AtomicLong requestRetries = new AtomicLong();
  private final AtomicInteger peakLeased = new AtomicInteger();
  private final AtomicInteger peakAvailable = new AtomicInteger();
  private final AtomicInteger peakPending = new AtomicInteger();
  private final PoolingHttpClientConnectionManager pool;
  private final CloseableHttpClient client;
  private final ScheduledExecutorService sampler;

  public HttpTransport(int capacity) {
    this(capacity, 5000, 5000, 10000);
  }

  // Separate timeouts: pool acquisition, TCP establishment, response/socket reads.
  public HttpTransport(int capacity, int leaseMs, int connectMs, int responseMs) {
    if (capacity < 1 || leaseMs < 1 || connectMs < 1 || responseMs < 1) {
      throw new IllegalArgumentException("Capacity and timeouts must be positive");
    }
    pool = new PoolingHttpClientConnectionManagerBuilder() {
      @Override
      protected HttpClientConnectionOperator createConnectionOperator(
          SchemePortResolver resolver, DnsResolver dns, TlsSocketStrategy tls) {
        return new DefaultHttpClientConnectionOperator(resolver, dns,
            RegistryBuilder.<TlsSocketStrategy>create().register("https", tls).build()) {
          @Override
          protected void onBeforeSocketConnect(HttpContext context, HttpHost host) {
            connectAttempts.incrementAndGet();
          }
          @Override
          protected void onAfterSocketConnect(HttpContext context, HttpHost host) {
            // Called only after an actual TCP socket.connect succeeds, not on pool reuse.
            connectionsEstablished.incrementAndGet();
            if (Boolean.TRUE.equals(context.getAttribute(RETRY))) {
              connectionsOnRetry.incrementAndGet();
            }
          }
        };
      }
    }.setPoolConcurrencyPolicy(PoolConcurrencyPolicy.STRICT)
        .setMaxConnTotal(capacity)
        .setMaxConnPerRoute(capacity)
        .setDefaultConnectionConfig(ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
            .setSocketTimeout(Timeout.ofMilliseconds(responseMs))
            .build())
        .build();

    client = HttpClients.custom().setConnectionManager(pool)
        .disableAutomaticRetries()
        .disableRedirectHandling()
        .disableCookieManagement()
        .setDefaultRequestConfig(RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(leaseMs))
            .setResponseTimeout(Timeout.ofMilliseconds(responseMs))
            .setProtocolUpgradeEnabled(false)
            .build())
        .build();
    sampler = Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "http-pool-sampler");
      thread.setDaemon(true);
      return thread;
    });
    sampler.scheduleAtFixedRate(this::samplePool, 0, 50, TimeUnit.MILLISECONDS);
  }

  /** Exactly one application attempt; retries belong to SwipeSender. */
  public int post(String url, String body, boolean retry) throws IOException {
    if (!"http".equalsIgnoreCase(URI.create(url).getScheme())) {
      throw new IllegalArgumentException("This assignment transport expects an http:// URL");
    }
    HttpPost request = new HttpPost(url);
    request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
    HttpClientContext context = HttpClientContext.create();
    context.setAttribute(RETRY, retry);
    requestAttempts.incrementAndGet();
    if (retry) {
      requestRetries.incrementAndGet();
    }
    try {
      return client.execute(request, context, response -> {
        int status = response.getCode();
        // Fully consume response before returning the connection to the pool.
        EntityUtils.consume(response.getEntity());
        return status;
      });
    } finally {
      samplePool();
    }
  }

  private void samplePool() {
    PoolStats stats = pool.getTotalStats();
    peakLeased.accumulateAndGet(stats.getLeased(), Math::max);
    peakAvailable.accumulateAndGet(stats.getAvailable(), Math::max);
    peakPending.accumulateAndGet(stats.getPending(), Math::max);
  }

  public long getConnectionsEstablished() { return connectionsEstablished.get(); }
  public long getConnectAttempts() { return connectAttempts.get(); }
  public long getConnectionsOnRetry() { return connectionsOnRetry.get(); }
  public long getRequestAttempts() { return requestAttempts.get(); }
  public long getRequestRetries() { return requestRetries.get(); }
  public PoolStats getPoolStats() { return pool.getTotalStats(); }

  // Print after all senders finish, before closing the pool.
  public void printSummary() {
    samplePool();
    PoolStats stats = pool.getTotalStats();
    System.out.println("\n--- HTTP transport: Apache HttpClient 5.6.4 ---");
    System.out.println("Application request attempts: " + getRequestAttempts());
    System.out.println("Application request retries: " + getRequestRetries());
    System.out.println("TCP connect attempts: " + getConnectAttempts());
    System.out.println("TCP connections established: " + getConnectionsEstablished());
    System.out.println("TCP connect attempts without success: "
        + (getConnectAttempts() - getConnectionsEstablished()));
    System.out.println("TCP connections established during application retries: "
        + getConnectionsOnRetry());
    System.out.println("HTTP reconnection definition: new TCP opens during application retries; "
        + "not all replacement/idle-expiry connections.");
    System.out.printf("Pool before close: leased=%d, available=%d, pending=%d, max=%d%n",
        stats.getLeased(), stats.getAvailable(), stats.getPending(), stats.getMax());
    System.out.printf("Sampled pool peaks (50 ms + attempt completion): "
        + "leased=%d, available=%d, pending=%d%n",
        peakLeased.get(), peakAvailable.get(), peakPending.get());
  }

  @Override
  public void close() throws IOException {
    sampler.shutdownNow();
    client.close();
  }
}
