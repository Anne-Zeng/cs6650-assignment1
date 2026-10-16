//创建滑雪事件的数据类，之后生成器swipeGenerator负责创建它，swipeSender发送线程负责发送它。
public class SwipeEvent {
//final 表示字段赋值后不能再修改。这样，一个事件在线程之间传递时，内容不会被其他线程改变
  private final int resortID;
  private final int seasonID;
  private final int dayID;
  private final int skierID;
  private final int time;
  private final int liftID;

  public SwipeEvent(int resortID, int seasonID, int dayID,
      int skierID, int time, int liftID) {
    this.resortID = resortID;
    this.seasonID = seasonID;
    this.dayID = dayID;
    this.skierID = skierID;
    this.time = time;
    this.liftID = liftID;
  }

  public String path() {
    return "/skiers/" + resortID
        + "/seasons/" + seasonID
        + "/days/" + dayID
        + "/skiers/" + skierID;
  }

  public String body() {
    return "{\"time\":" + time
        + ",\"liftID\":" + liftID + "}";
  }

  public int getResortID() {
    return resortID;
  }
}