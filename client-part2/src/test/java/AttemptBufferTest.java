import org.junit.Test;
import static org.junit.Assert.*;
//三个测试分别检查：空缓冲区不会读出不存在的数据。
//跨块写入不会丢记录，五个字段仍对应同一次尝试，顺序保持一致。
//两个缓冲区互不混用，为后续每个发送任务独占一个缓冲区做准备。
public class AttemptBufferTest {

  @Test
  public void emptyBufferHasNoRecords() {
    AttemptBuffer buffer = new AttemptBuffer();

    assertEquals(0L, buffer.size());

    buffer.forEach((sent, received, latency, status, resort) -> {
      fail("Empty buffer should not contain records");
    });
  }

  @Test
  public void preservesRecordsAcrossBlockBoundaries() {
    AttemptBuffer buffer = new AttemptBuffer();

    // 包含：空缓冲区、第一块边界、第二块边界及新块第一条。
    int[] checkpoints = {1, 1023, 1024, 1025, 2048, 2049};
    int written = 0;

    for (int expectedCount : checkpoints) {
      while (written < expectedCount) {
        int i = written;

        buffer.record(
            1_700_000_000_000L + i,
            1_700_000_000_010L + i,
            10_000_000L + i,
            i % 2 == 0 ? 201 : 0,
            i % 10 + 1
        );

        written++;
      }

      assertEquals((long) expectedCount, buffer.size());

      int[] visited = {0};

      buffer.forEach((sent, received, latency, status, resort) -> {
        int i = visited[0];

        assertEquals(1_700_000_000_000L + i, sent);
        assertEquals(1_700_000_000_010L + i, received);
        assertEquals(10_000_000L + i, latency);
        assertEquals(i % 2 == 0 ? 201 : 0, status);
        assertEquals(i % 10 + 1, resort);

        visited[0]++;
      });

      assertEquals(expectedCount, visited[0]);
    }
  }

  @Test
  public void buffersKeepTheirRecordsSeparate() {
    AttemptBuffer first = new AttemptBuffer();
    AttemptBuffer second = new AttemptBuffer();

    first.record(100L, 110L, 10_000_000L, 201, 1);
    second.record(200L, 220L, 20_000_000L, 503, 2);

    assertEquals(1L, first.size());
    assertEquals(1L, second.size());

    first.forEach((sent, received, latency, status, resort) -> {
      assertEquals(100L, sent);
      assertEquals(201, status);
      assertEquals(1, resort);
    });

    second.forEach((sent, received, latency, status, resort) -> {
      assertEquals(200L, sent);
      assertEquals(503, status);
      assertEquals(2, resort);
    });
  }
}