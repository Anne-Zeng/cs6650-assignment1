package model;

//用于统一响应格式。后面 Gson 会把：new ResponseMsg("invalid url")转换成：{"message":"invalid url"}
public class ResponseMsg {
  private String message;

  public ResponseMsg() {}
  public ResponseMsg(String message) {
    this.message = message;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}