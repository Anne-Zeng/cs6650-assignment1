package model;

//一次缆车乘坐记录，对应 POST 请求中的 JSON：{"time": 217, "liftID": 21}
public class LiftRide {
  private int time;//讲义中要求为 1～360 的时间值。
  private int liftID;//缆车编号，要求大于 0。

  public LiftRide() {}
  public LiftRide(int time, int liftID) {
    this.time = time;
    this.liftID = liftID;
  }

  public int getTime() {
    return time;
  }

  public void setTime(int time) {
    this.time = time;
  }

  public int getLiftID() {
    return liftID;
  }

  public void setLiftID(int liftID) {
    this.liftID = liftID;
  }

  @Override
  public String toString() {
    return "LiftRide{time=" + time + ", liftID=" + liftID + "}";
  }
}