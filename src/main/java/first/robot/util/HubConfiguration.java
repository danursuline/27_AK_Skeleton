package first.robot.util;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.DoubleSupplier;

/** Read-only registry of effective logged tunables. Used only on the normal robot thread. */
public final class HubConfiguration {
  private static final Map<String, DoubleSupplier> values = new TreeMap<>();

  private HubConfiguration() {}

  public static void register(String key, DoubleSupplier value) {
    values.put(key, value);
  }

  public static Map<String, String> snapshot() {
    var result = new TreeMap<String, String>();
    values.forEach((key, supplier) -> result.put(key, Double.toString(supplier.getAsDouble())));
    return result;
  }
}
