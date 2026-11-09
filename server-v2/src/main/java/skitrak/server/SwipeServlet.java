package skitrak.server;

import skitrak.common.Json;
import skitrak.common.SwipeMessage;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

@WebServlet("/skiers/*")
public final class SwipeServlet extends HttpServlet {
  public static final LongAdder requests = new LongAdder(), invalid = new LongAdder();
  @Override protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
    requests.increment(); ServerRuntime runtime = ServerRuntime.get();
    res.setHeader("X-Server-Id", runtime.serverId); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
    try {
      if (req.getContentLengthLong() > 8192) { respond(res, 413, "request body too large"); return; }
      StringBuilder body = new StringBuilder(); char[] chunk = new char[1024]; int count;
      Reader reader = req.getReader();
      while ((count = reader.read(chunk)) != -1) { body.append(chunk, 0, count); if (body.length() > 8192) { respond(res, 413, "request body too large"); return; } }
      SwipeMessage event;
      try { event = SwipeValidator.parse(req.getPathInfo(), body.toString(), req.getHeader("Idempotency-Key"), req.getHeader("X-Event-Id"), runtime.serverId, runtime.lifts); }
      catch (RuntimeException bad) { invalid.increment(); respond(res, 400, bad.getMessage()); return; }
      try {
        runtime.publisher.publish(event);
        res.setStatus(201); Map<String, Object> response = new LinkedHashMap<>();
        response.put("eventId", event.eventId); response.put("serverId", runtime.serverId); response.put("status", "QUEUED");
        response.put("demoLifts", runtime.lifts.isDemo()); res.getWriter().write(Json.encode(response));
      } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); res.setHeader("Retry-After", "1"); respond(res, 503, "publish interrupted; retry with the same Idempotency-Key");
      } catch (Exception unavailable) { res.setHeader("Retry-After", "1"); respond(res, 503, "broker publish not confirmed; retry with the same Idempotency-Key"); }
    } catch (IOException disconnected) { throw disconnected; }
  }
  private static void respond(HttpServletResponse res, int status, String error) throws IOException {
    res.setStatus(status); res.getWriter().write(Json.encode(Collections.singletonMap("error", error)));
  }
}
