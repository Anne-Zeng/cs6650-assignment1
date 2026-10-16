import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.util.concurrent.*;
//This starts the timer when the application starts and stops it when the application shuts down.
// Keeping that lifecycle explicit avoids leaving a timer thread running after redeployment.
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