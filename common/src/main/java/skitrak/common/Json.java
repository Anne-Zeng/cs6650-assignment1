package skitrak.common;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
public final class Json {
  public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private Json() {}
  public static String encode(Object value) { return GSON.toJson(value); }
  public static <T> T decode(String value, Class<T> type) { return GSON.fromJson(value, type); }
}
