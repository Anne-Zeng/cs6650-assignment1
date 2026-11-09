package skitrak.common;
import com.rabbitmq.client.ConnectionFactory;
public final class RabbitConfig {
  public final String host, user, password, vhost;
  public final int port;
  private RabbitConfig() {
    host = Env.get("RABBITMQ_HOST", "localhost"); port = Env.integer("RABBITMQ_PORT", 5672, 1, 65535);
    user = Env.get("RABBITMQ_USER", "skitrak"); password = Env.get("RABBITMQ_PASSWORD", "skitrak-local-only");
    vhost = Env.get("RABBITMQ_VHOST", "/");
  }
  public static RabbitConfig fromEnv() { return new RabbitConfig(); }
  public ConnectionFactory newFactory() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(host); factory.setPort(port); factory.setUsername(user); factory.setPassword(password); factory.setVirtualHost(vhost);
    factory.setAutomaticRecoveryEnabled(false); factory.setTopologyRecoveryEnabled(false);
    factory.setConnectionTimeout(5000); factory.setHandshakeTimeout(5000); factory.setRequestedHeartbeat(15);
    return factory;
  }
}
