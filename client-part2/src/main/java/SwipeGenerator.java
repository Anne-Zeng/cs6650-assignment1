//事件生成器 SwipeGenerator。它负责随机生成滑雪记录，然后放进队列，发送线程之后从队列取记录。
//生成器线程 → 队列 → 发送线程 → Tomcat
//先采用容易验证的方式：由一个独立线程提前生成全部事件，再启动发送线程。之后测性能时，再评估内存和启动耗时。
import java.util.concurrent.BlockingQueue;
import java.util.Random;
//SwipeGenerator creates immutable SwipeEvent objects and places them in an ArrayBlockingQueue.
public class SwipeGenerator implements Runnable {

  private final BlockingQueue<SwipeEvent> queue;
  private final int eventCount;
  private final int resortCount;
  private final int seasonID;
  private final int dayID;
  private final long seed;

  public SwipeGenerator(BlockingQueue<SwipeEvent> queue,
      int eventCount,
      int resortCount,
      int seasonID,
      int dayID) {
    this(queue, eventCount, resortCount, seasonID, dayID, 6650L);
  }

  // 固定种子让不同统计模式生成相同事件；不保证并发发送顺序相同。
  public SwipeGenerator(BlockingQueue<SwipeEvent> queue, int eventCount,
      int resortCount, int seasonID, int dayID, long seed) {
    this.seed = seed;
    this.queue = queue;
    this.eventCount = eventCount;
    this.resortCount = resortCount;
    this.seasonID = seasonID;
    this.dayID = dayID;
  }

  //生成事件并放入队列
  @Override
  public void run() {
    Random random = new Random(seed);

    for (int i = 0; i < eventCount; i++) {
      SwipeEvent event = new SwipeEvent(
          random.nextInt(resortCount) + 1,
          seasonID,
          dayID,
          random.nextInt(100000) + 1,
          random.nextInt(360) + 1,
          random.nextInt(40) + 1
      );

      queue.add(event);
    }
  }
}