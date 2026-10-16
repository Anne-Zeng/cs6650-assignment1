//事件生成器 SwipeGenerator。它负责随机生成滑雪记录，然后放进队列，发送线程之后从队列取记录。
//生成器线程 → 队列 → 发送线程 → Tomcat
//先采用容易验证的方式：由一个独立线程提前生成全部事件，再启动发送线程。之后测性能时，再评估内存和启动耗时。
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