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