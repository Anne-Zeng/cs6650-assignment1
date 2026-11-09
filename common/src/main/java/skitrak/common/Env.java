package skitrak.common;

/** Central, strict configuration parsing. System properties simplify local tests. */
public final class Env {
  private Env() {}
  public static String get(String key, String fallback) {
    String value = System.getProperty(key);
    if (value == null) value = System.getenv(key);
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }
  public static int integer(String key, int fallback, int min, int max) {
    return (int) longValue(key, fallback, min, max);
  }
  public static long longValue(String key, long fallback, long min, long max) {
    try {
      long value = Long.parseLong(get(key, Long.toString(fallback)));
      if (value < min || value > max) throw new IllegalArgumentException(key + " outside [" + min + "," + max + "]");
      return value;
    } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer", e); }
  }
  public static boolean bool(String key, boolean fallback) {
    String value = get(key, Boolean.toString(fallback));
    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IllegalArgumentException(key + " must be true or false");
    return Boolean.parseBoolean(value);
  }
}
