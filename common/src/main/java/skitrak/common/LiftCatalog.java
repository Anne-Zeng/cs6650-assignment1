package skitrak.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.util.Map;

/** Official course values supplied by the user; optional strict file override. */
public final class LiftCatalog {
  private final int[] verticals;
  private final boolean demo;
  private LiftCatalog(int[] verticals, boolean demo) { this.verticals = verticals; this.demo = demo; }
  public static LiftCatalog fromEnv() throws IOException {
    String path = Env.get("LIFT_VERTICAL_FILE", "");
    if (!path.isEmpty()) return load(Path.of(path));
    boolean demo = Env.bool("ALLOW_DEMO_LIFTS", false);
    String resource = demo ? "/demo-lift-vertical.csv" : "/lift_vertical.json";
    try (InputStream in = LiftCatalog.class.getResourceAsStream(resource)) {
      if (in == null) throw new IOException("Lift table missing: " + resource + "; set LIFT_VERTICAL_FILE");
      return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)), demo);
    }
  }
  public static LiftCatalog load(Path path) throws IOException {
    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { return parse(reader, false); }
  }
  private static LiftCatalog parse(BufferedReader reader, boolean demo) throws IOException {
    StringBuilder content = new StringBuilder(); String next;
    while ((next = reader.readLine()) != null) {
      if (content.length() > 1048576) throw new IOException("Lift table exceeds 1 MiB");
      content.append(next).append('\n');
    }
    String text = content.toString().trim();
    if (!demo && text.contains("LOCAL TEST FIXTURE ONLY")) throw new IOException("The demo fixture is not an official course lift table");
    if (text.startsWith("{") || text.startsWith("[")) return parseJson(text, demo);
    return parseCsv(new BufferedReader(new java.io.StringReader(text)), demo);
  }
  private static LiftCatalog parseCsv(BufferedReader reader, boolean demo) throws IOException {
    int[] values = new int[41]; String line; int lineNo = 0;
    while ((line = reader.readLine()) != null) {
      lineNo++; line = line.trim();
      if (line.isEmpty() || line.startsWith("#") || line.equalsIgnoreCase("liftID,vertical")) continue;
      String[] parts = line.split(",", -1);
      try {
        if (parts.length != 2) throw new IllegalArgumentException();
        int id = Integer.parseInt(parts[0].trim()), feet = Integer.parseInt(parts[1].trim());
        if (id < 1 || id > 40 || feet < 60 || feet > 400 || values[id] != 0) throw new IllegalArgumentException();
        values[id] = feet;
      } catch (IllegalArgumentException e) { throw new IOException("Invalid lift table at line " + lineNo + ": expected unique liftID 1..40 and vertical 60..400", e); }
    }
    for (int id = 1; id <= 40; id++) if (values[id] == 0) throw new IOException("Lift table missing lift " + id);
    return new LiftCatalog(values, demo);
  }
  private static LiftCatalog parseJson(String text, boolean demo) throws IOException {
    int[] values = new int[41];
    try {
      JsonElement root = JsonParser.parseString(text);
      if (root.isJsonObject() && root.getAsJsonObject().has("lifts")) root = root.getAsJsonObject().get("lifts");
      if (root.isJsonObject()) {
        for (Map.Entry<String,JsonElement> entry : root.getAsJsonObject().entrySet()) {
          int id = Integer.parseInt(entry.getKey());
          JsonElement value = entry.getValue();
          if (value.isJsonObject()) value = value.getAsJsonObject().get("vertical");
          put(values, id, exactInt(value));
        }
      } else if (root.isJsonArray()) {
        int index = 1;
        for (JsonElement item : root.getAsJsonArray()) {
          if (item.isJsonObject()) {
            JsonObject lift = item.getAsJsonObject();
            JsonElement id = lift.has("liftID") ? lift.get("liftID") : lift.has("liftId") ? lift.get("liftId") : lift.get("id");
            put(values, exactInt(id), exactInt(lift.get("vertical")));
          } else put(values, index, exactInt(item));
          index++;
        }
      } else throw new IllegalArgumentException("Expected an object or array");
      for (int id=1; id<=40; id++) if (values[id]==0) throw new IllegalArgumentException("Missing lift " + id);
      return new LiftCatalog(values, demo);
    } catch (RuntimeException invalid) {
      throw new IOException("Invalid lift JSON: expected all 40 unique lifts, vertical 60..400. Supported: {\"1\":200,...}, [{\"liftID\":1,\"vertical\":200},...], or a 40-value array", invalid);
    }
  }
  private static int exactInt(JsonElement value) { return new BigDecimal(value.getAsString()).intValueExact(); }
  private static void put(int[] values, int id, int feet) {
    if (id<1 || id>40 || feet<60 || feet>400 || values[id]!=0) throw new IllegalArgumentException("Invalid or duplicate lift " + id);
    values[id]=feet;
  }
  public int vertical(int id) {
    if (id < 1 || id > 40) throw new IllegalArgumentException("liftID outside 1..40");
    return verticals[id];
  }
  public boolean isDemo() { return demo; }
}
