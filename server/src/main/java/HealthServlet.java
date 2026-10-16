import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
//A simple GET returning canned server status. This becomes the load balancer's health check in Assignment 2.
@WebServlet("/health")//maps requests for /health to this servlet.
public class HealthServlet extends HttpServlet {

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    res.setStatus(HttpServletResponse.SC_OK);//sets status 200
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");
    res.getWriter().write("{\"status\":\"UP\"}");
  }
}