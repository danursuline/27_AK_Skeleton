package first.robot.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Build evidence produced by Gradle, plus stable JSON/hash encoding for hub metadata. */
public final class HubIdentity {
  private final Properties build = new Properties();

  public HubIdentity() {
    try (var stream = HubIdentity.class.getResourceAsStream("/testhub-build.properties")) {
      if (stream == null) throw new IOException("Missing generated build identity resource");
      build.load(stream);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Build identity unavailable; build with the checked-in wrapper", exception);
    }
  }

  public String get(String key) {
    return build.getProperty(key, "unavailable");
  }

  /**
   * Startup-only digest of the actual loaded JAR; classes directories are explicitly unavailable.
   */
  public String artifactSha256() {
    try {
      var source = HubIdentity.class.getProtectionDomain().getCodeSource();
      if (source == null) return "unavailable";
      var path = Path.of(source.getLocation().toURI());
      if (!Files.isRegularFile(path)) return "unavailable";
      var digest = MessageDigest.getInstance("SHA-256");
      try (var stream = Files.newInputStream(path)) {
        byte[] block = new byte[1024 * 1024];
        int count;
        while ((count = stream.read(block)) >= 0) digest.update(block, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception exception) {
      return "unavailable";
    }
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static String quote(String value) {
    if (value == null) return "null";
    var result = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> result.append("\\\"");
        case '\\' -> result.append("\\\\");
        case '\n' -> result.append("\\n");
        case '\r' -> result.append("\\r");
        case '\t' -> result.append("\\t");
        default -> {
          if (c < 32) result.append(String.format("\\u%04x", (int) c));
          else result.append(c);
        }
      }
    }
    return result.append('"').toString();
  }

  public static String json(Map<String, String> values) {
    var result = new StringBuilder("{");
    for (var entry : new TreeMap<>(values).entrySet()) {
      if (result.length() > 1) result.append(',');
      result.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
    }
    return result.append('}').toString();
  }
}
