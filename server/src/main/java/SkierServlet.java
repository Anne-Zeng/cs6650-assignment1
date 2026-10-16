import java.util.logging.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import model.LiftRide;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import util.RequestStats;

public class SkierServlet extends HttpServlet {
  private Gson gson = new Gson();
  private static final Logger logger =
      Logger.getLogger(SkierServlet.class.getName());
  //| `doGet()` | 处理 GET 请求，读取 URL 参数并返回 JSON |
  //| `doPost()` | 处理 POST 请求，解析并验证 JSON 请求体 |
  //| `isUrlValid()` | 检查路径结构及参数是否能转换为整数 |
  //| `isLiftRideValid()` | 检查 `time` 和 `liftID` 的范围 |
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    logger.info("GET request received: " + req.getPathInfo());
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // 查询统计，不计入 GET 请求数量
    if ("/stats".equals(urlPath)) {
      res.setStatus(HttpServletResponse.SC_OK);
      res.getWriter().write(
          gson.toJson(new ResponseMsg(RequestStats.getStats()))
      );
      return;
    }
    RequestStats.incrementGet();

    // Check we have a URL缺少参数检查、URL 验证和响应代码
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_NOT_FOUND);
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }
    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      logger.warning("Invalid URL: " + urlPath);
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
    } else {
      res.setStatus(HttpServletResponse.SC_OK);

      // Extract parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // Create response
      ResponseMsg response = new ResponseMsg(
          String.format("GET request processed for skier %s at resort %s, season %s, day %s",
              skierID, resortID, seasonID, dayID)
      );

      res.getWriter().write(gson.toJson(response));
    }
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {
    RequestStats.incrementPost();
    res.setContentType("application/json");
    res.setCharacterEncoding("UTF-8");

    String urlPath = req.getPathInfo();

    // Check we have a URL
    if (urlPath == null || urlPath.isEmpty()) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);//SC_NOT_FOUND
      res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
      return;
    }

    String[] urlParts = urlPath.split("/");

    if (!isUrlValid(urlParts)) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
      return;
    }

    // Parse JSON body
    try {
      StringBuilder jsonBody = new StringBuilder();
      BufferedReader reader = req.getReader();
      String line;
      while ((line = reader.readLine()) != null) {
        jsonBody.append(line);
      }

      LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

      //数据校验失败分支
      if (liftRide == null || !isLiftRideValid(liftRide)) {
        logger.warning("Invalid lift ride data");
        res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
        return;
      }

      // Extract URL parameters
      String resortID = urlParts[1];
      String seasonID = urlParts[3];
      String dayID = urlParts[5];
      String skierID = urlParts[7];

      // Process the lift ride (in real app, save to database)
      res.setStatus(HttpServletResponse.SC_CREATED);
      ResponseMsg response = new ResponseMsg(
          String.format("Lift ride recorded: skier %s, resort %s, season %s, day %s, time %d, lift %d",
              skierID, resortID, seasonID, dayID, liftRide.getTime(), liftRide.getLiftID())
      );

      res.getWriter().write(gson.toJson(response));

    } catch (JsonSyntaxException e) {
      res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
    }
  }

  private boolean isUrlValid(String[] urlParts) {
    // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
    // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

    if (urlParts.length != 8) {
      return false;
    }

    try {
      // Check structure
      if (!urlParts[2].equals("seasons") ||
          !urlParts[4].equals("days") ||
          !urlParts[6].equals("skiers")) {
        return false;
      }

      // Validate numeric parameters
      return Integer.parseInt(urlParts[1]) > 0// resortID
          && Integer.parseInt(urlParts[3]) > 0// seasonID
          && Integer.parseInt(urlParts[5]) > 0// dayID
          && Integer.parseInt(urlParts[7]) > 0;// skierID

    } catch (NumberFormatException e) {
      return false;
    }
  }

  private boolean isLiftRideValid(LiftRide liftRide) {
    // Basic validation
    return liftRide.getTime() >= 1 &&
        liftRide.getTime() <= 360 && // Max 6 hours (360 minutes)
        liftRide.getLiftID() >= 1 &&
        liftRide.getLiftID() <= 40;
  }
}