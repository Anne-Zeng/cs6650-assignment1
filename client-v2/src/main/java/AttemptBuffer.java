import java.util.ArrayList;
import java.util.List;

/**
 * 一个发送任务独占的 HTTP 尝试记录缓冲区。
 *
 * 写入期间只允许所属发送任务访问。
 * 主线程必须等待发送任务完成后，才能读取记录。
 */
//同一个下标代表同一次尝试。
//例如五个数组的 [0]，共同保存第一条记录的发送时间、结束时间、耗时、状态码和 resort。
//
//每 1,024 条才扩充一次。
//不再每次创建 Sample 和共享队列节点。记录量超过一块就新增一块，因此可以容纳额外的重试记录。
//
//它故意不是线程安全的。
//后面每个 SwipeSender 会拿到自己的实例，只有一个线程写；主线程等任务结束后再读，所以不需要每次记录都进行同步。
public final class AttemptBuffer {

  // 每块存放 1024 条记录；写满后再增加一块。
  private static final int BLOCK_SIZE = 1024;

  private final List<Block> blocks = new ArrayList<>();
  private Block current;
  private long size;

  public AttemptBuffer() {
    current = new Block();
    blocks.add(current);
  }

  public void record(
      long sentEpochMillis,
      long receivedEpochMillis,
      long latencyNanos,
      int statusCode,
      int resortID) {

    if (current.used == BLOCK_SIZE) {
      current = new Block();
      blocks.add(current);
    }

    int index = current.used;

    current.sentEpochMillis[index] = sentEpochMillis;
    current.receivedEpochMillis[index] = receivedEpochMillis;
    current.latencyNanos[index] = latencyNanos;
    current.statusCodes[index] = statusCode;
    current.resortIds[index] = resortID;

    current.used++;
    size++;
  }

  public long size() {
    return size;
  }

  // 仅在所属发送任务结束后调用。
  public void forEach(AttemptConsumer consumer) {
    for (Block block : blocks) {
      for (int i = 0; i < block.used; i++) {
        consumer.accept(
            block.sentEpochMillis[i],
            block.receivedEpochMillis[i],
            block.latencyNanos[i],
            block.statusCodes[i],
            block.resortIds[i]
        );
      }
    }
  }

  @FunctionalInterface
  public interface AttemptConsumer {
    void accept(
        long sentEpochMillis,
        long receivedEpochMillis,
        long latencyNanos,
        int statusCode,
        int resortID);
  }

  private static final class Block {
    final long[] sentEpochMillis = new long[BLOCK_SIZE];
    final long[] receivedEpochMillis = new long[BLOCK_SIZE];
    final long[] latencyNanos = new long[BLOCK_SIZE];
    final int[] statusCodes = new int[BLOCK_SIZE];
    final int[] resortIds = new int[BLOCK_SIZE];

    int used;
  }
}