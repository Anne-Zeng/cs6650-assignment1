package skitrak.common;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
public final class Topology {
  public static final String SWIPE_EXCHANGE = "skitrak.exchange";
  public static final String LEADERBOARD_EXCHANGE = "leaderboard.topic";
  public static final String DEAD_EXCHANGE = "skitrak.dlx";
  public static final int RESORT_COUNT = 10;
  private Topology() {}
  public static void declare(Channel channel) throws IOException {
    channel.exchangeDeclare(SWIPE_EXCHANGE, "topic", true);
    channel.exchangeDeclare(LEADERBOARD_EXCHANGE, "topic", true);
    channel.exchangeDeclare(DEAD_EXCHANGE, "direct", true);
    for (int resort = 1; resort <= RESORT_COUNT; resort++) {
      String name = "resort." + resort;
      channel.queueDeclare(name + ".dead", true, false, false, null);
      channel.queueBind(name + ".dead", DEAD_EXCHANGE, name);
      Map<String,Object> args = new HashMap<>();
      args.put("x-message-ttl", Env.longValue("QUEUE_TTL_MS", 86400000L, 1, Integer.MAX_VALUE));
      args.put("x-max-length", Env.longValue("QUEUE_MAX_LENGTH", 100000L, 1, Integer.MAX_VALUE));
      args.put("x-overflow", "reject-publish"); args.put("x-single-active-consumer", true);
      args.put("x-dead-letter-exchange", DEAD_EXCHANGE); args.put("x-dead-letter-routing-key", name);
      channel.queueDeclare(name, true, false, false, args);
      channel.queueBind(name, SWIPE_EXCHANGE, name);
    }
  }
}
