package skitrak.server;

import skitrak.common.*;
import javax.servlet.*;
import javax.servlet.annotation.WebListener;
import java.util.UUID;

@WebListener
public final class ServerRuntime implements ServletContextListener {
  private static volatile ServerRuntime instance;
  String serverId;
  LiftCatalog lifts;
  ChannelPool producerPool;
  SwipePublisher publisher;
  LeaderboardHub hub;
  public static ServerRuntime get() {
    ServerRuntime current = instance;
    if (current == null) throw new IllegalStateException("server runtime not started");
    return current;
  }
  @Override public void contextInitialized(ServletContextEvent event) {
    serverId = Env.get("SERVER_ID", "ingest-" + UUID.randomUUID());
    try { lifts = LiftCatalog.fromEnv(); } // Never silently invent the official table.
    catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    producerPool = new ChannelPool(() -> RabbitConfig.fromEnv().newFactory().newConnection("producer-" + serverId),
        channel -> { try { Topology.declare(channel); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } },
        Env.integer("CHANNEL_POOL_SIZE", 32, 1, 1024));
    publisher = new SwipePublisher(producerPool, Env.longValue("CHANNEL_BORROW_TIMEOUT_MS", 1000, 1, 60000),
        Env.longValue("PUBLISH_CONFIRM_TIMEOUT_MS", 3000, 1, 60000));
    hub = new LeaderboardHub(serverId, Env.get("CONSUMER_URL", "http://localhost:8081"));
    instance = this; producerPool.start(); hub.start();
    event.getServletContext().log("SkiTrak server started: " + serverId + ", demoLifts=" + lifts.isDemo());
  }
  @Override public void contextDestroyed(ServletContextEvent event) {
    instance = null; if (hub != null) hub.close(); if (producerPool != null) producerPool.close();
  }
  boolean ready() { return producerPool.ready() && hub.ready(); }
}
