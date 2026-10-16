/** Same HTTP transport as LoadClient: measure a fresh request, then pooled RTT. */
public class SequentialClient {
  public static void main(String[] args) throws Exception {
    String baseUrl = args.length > 0 ? args[0] : "http://54.201.46.55:8080/ski-servlet";
    String url = baseUrl + "/skiers/1/seasons/2026/days/1/skiers/123";
    String body = "{\"time\":217,\"liftID\":21}";
    try (HttpTransport client = new HttpTransport(1)) {
      long firstStart = System.nanoTime();
      int firstStatus = client.post(url, body, false);
      System.out.printf("Fresh-client request (includes connection setup): %.3f ms%n",
          (System.nanoTime() - firstStart) / 1_000_000.0);
      if (firstStatus != 201) throw new IllegalStateException("First request: HTTP " + firstStatus);
      for (int i = 0; i < 20; i++) {
        if (client.post(url, body, false) != 201) {
          throw new IllegalStateException("Sequential warmup failed");
        }
      }
      int successful = 0;
      int count = 100;
      long latencyNanos = 0;
      long started = System.nanoTime();
      for (int i = 0; i < count; i++) {
        long requestStart = System.nanoTime();
        int status = client.post(url, body, false);
        latencyNanos += System.nanoTime() - requestStart;
        if (status == 201) successful++;
      }
      double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
      double meanMs = latencyNanos / 1_000_000.0 / count;
      System.out.println("Successful: " + successful);
      System.out.println("Failed: " + (count - successful));
      System.out.printf("Average pooled latency: %.3f ms%n", meanMs);
      System.out.printf("Measured throughput: %.2f swipes/sec%n", successful / seconds);
      if (successful == count) {
        for (int threads : new int[] {32, 64, 128}) {
          System.out.printf("Initial prediction for %d senders: %.2f swipes/sec%n",
              threads, threads / (meanMs / 1000.0));
        }
      }
      client.printSummary();
    }
  }
}
