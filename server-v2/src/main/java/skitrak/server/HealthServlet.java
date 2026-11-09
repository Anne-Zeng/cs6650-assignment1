package skitrak.server;

import skitrak.common.Json;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@WebServlet({"/health", "/metrics"})
public final class HealthServlet extends HttpServlet {
  @Override protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
    ServerRuntime rt = ServerRuntime.get(); res.setHeader("X-Server-Id", rt.serverId);
    res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("serverId", rt.serverId); m.put("status", rt.ready() ? "UP" : "NOT_READY"); m.put("demoLifts", rt.lifts.isDemo());
    m.put("producerReady", rt.producerPool.ready()); m.put("backplaneReady", rt.hub.ready());
    m.put("producerChannelsAvailable", rt.producerPool.available()); m.put("producerConnectionsEstablished", rt.producerPool.connectionsEstablished());
    m.put("requests", SwipeServlet.requests.sum()); m.put("invalid", SwipeServlet.invalid.sum());
    m.put("publishesConfirmed", rt.publisher.confirmed.sum()); m.put("publishesFailed", rt.publisher.failed.sum()); m.put("unroutable", rt.publisher.unroutable.sum());
    m.put("publishFailureReasons", rt.publisher.failureCounts());
    m.put("localSessions", rt.hub.sessionCount()); m.put("backplaneBindings", rt.hub.bindingCount());
    m.put("snapshotFailures", rt.hub.snapshotFailures.sum()); m.put("backplaneUpdates", rt.hub.updates.sum());
    if ("/health".equals(req.getServletPath()) && !rt.ready()) res.setStatus(503);
    res.getWriter().write(Json.encode(m));
  }
}
