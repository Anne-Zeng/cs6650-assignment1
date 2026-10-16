import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
//在错误处理里增加了 session == null 检查
@ServerEndpoint("/echo")//注册 WebSocket 地址
public class EchoEndpoint {

  @OnOpen//客户端连接成功时
  public void onOpen(Session session) {
    System.out.println("Opened: " + session.getId());
  }

  @OnMessage//收到消息时，返回时间戳和原消息
  public void onMessage(String message, Session session)
      throws IOException {
    session.getBasicRemote().sendText(
        "echo@" + System.currentTimeMillis() + ": " + message
    );
  }

  @OnClose//连接关闭时
  public void onClose(Session session) {
    System.out.println("Closed: " + session.getId());
  }

  @OnError//出现错误时
  public void onError(Session session, Throwable t) {
    String sessionId = session == null ? "unknown" : session.getId();
    System.err.println(
        "Error on " + sessionId + ": " + t.getMessage()
    );
  }
}