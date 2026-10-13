package first.robot.video;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

/**
 * Immutable, validated configuration for the USB video recorder, normally loaded from {@code
 * src/main/deploy/video-recorder.properties}.
 *
 * <p>Every key has a default (see {@link #DEFAULTS}); unknown keys are rejected so that typos do
 * not silently fall back to defaults.
 */
public record VideoRecorderConfig(
    boolean enabled,
    boolean autoRecordWhenEnabled,
    boolean allowRecordingWithFms,
    String device,
    String ffmpegPath,
    InputFormat inputFormat,
    int width,
    int height,
    int fps,
    int rawEncodeQuality,
    String rawEncodePixelFormat,
    int segmentSeconds,
    int maxSessionSeconds,
    long maxSessionMiB,
    String outputDirectory,
    long poolMiB,
    long minFreeMiB,
    double retryIntervalSeconds,
    double maxRetryBackoffSeconds,
    double startupTimeoutSeconds,
    double stallTimeoutSeconds,
    double heartbeatTimeoutSeconds,
    double stopTimeoutSeconds) {

  /** Capture format requested from the camera. */
  public enum InputFormat {
    /** Compressed MJPEG from the camera, stream-copied without decoding. */
    MJPEG("mjpeg"),
    /** Raw YUYV 4:2:2 from the camera, software-encoded to MJPEG (higher CPU cost). */
    YUYV("yuyv422");

    private final String v4l2Name;

    InputFormat(String v4l2Name) {
      this.v4l2Name = v4l2Name;
    }

    /** The FFmpeg/V4L2 pixel format name passed to {@code -input_format}. */
    public String v4l2Name() {
      return v4l2Name;
    }
  }

  /** Thrown when the configuration file is missing, unreadable, or invalid. */
  public static final class ConfigException extends Exception {
    private final List<String> problems;

    ConfigException(List<String> problems) {
      super("Invalid video recorder configuration: " + String.join("; ", problems));
      this.problems = List.copyOf(problems);
    }

    /** Individual validation problems. */
    public List<String> problems() {
      return problems;
    }
  }

  /** Default values for every supported key. */
  public static final Map<String, String> DEFAULTS;

  static {
    Map<String, String> d = new LinkedHashMap<>();
    d.put("enabled", "true");
    d.put("autoRecordWhenEnabled", "true");
    d.put("allowRecordingWithFms", "false");
    d.put("device", "auto");
    d.put("ffmpegPath", "ffmpeg");
    d.put("inputFormat", "MJPEG");
    d.put("width", "640");
    d.put("height", "480");
    d.put("fps", "30");
    d.put("rawEncodeQuality", "5");
    d.put("rawEncodePixelFormat", "yuvj422p");
    d.put("segmentSeconds", "10");
    d.put("maxSessionSeconds", "180");
    d.put("maxSessionMiB", "512");
    d.put("outputDirectory", "/home/systemcore/videos");
    d.put("poolMiB", "2048");
    d.put("minFreeMiB", "1024");
    d.put("retryIntervalSeconds", "5");
    d.put("maxRetryBackoffSeconds", "60");
    d.put("startupTimeoutSeconds", "10");
    d.put("stallTimeoutSeconds", "5");
    d.put("heartbeatTimeoutSeconds", "1.0");
    d.put("stopTimeoutSeconds", "3");
    DEFAULTS = Collections.unmodifiableMap(d);
  }

  private static final long MIB = 1024L * 1024L;

  /** Returns the default configuration. */
  public static VideoRecorderConfig defaults() {
    try {
      return parse(Map.of());
    } catch (ConfigException e) {
      throw new IllegalStateException("Built-in defaults are invalid", e);
    }
  }

  /** Loads and validates a properties file. */
  public static VideoRecorderConfig load(Path file) throws ConfigException {
    if (!Files.isRegularFile(file)) {
      throw new ConfigException(List.of("CONFIG_MISSING: " + file + " was not found"));
    }
    Properties props = new Properties();
    try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(reader);
    } catch (IOException | IllegalArgumentException e) {
      throw new ConfigException(List.of("CONFIG_UNREADABLE: " + file + ": " + e.getMessage()));
    }
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : props.stringPropertyNames()) {
      values.put(key, props.getProperty(key));
    }
    return parse(values);
  }

  /** Validates a key/value map; keys that are absent take their defaults. */
  public static VideoRecorderConfig parse(Map<String, String> overrides) throws ConfigException {
    List<String> errors = new ArrayList<>();
    for (String key : new TreeSet<>(overrides.keySet())) {
      if (!DEFAULTS.containsKey(key)) {
        errors.add("unknown key '" + key + "'");
      }
    }
    Parser p = new Parser(overrides, errors);

    boolean enabled = p.bool("enabled");
    boolean autoRecord = p.bool("autoRecordWhenEnabled");
    boolean allowFms = p.bool("allowRecordingWithFms");

    String device = p.string("device");
    if (!device.equals("auto") && !(device.startsWith("/dev/") && device.length() > 5)) {
      errors.add("device must be 'auto' or an absolute /dev/... path, got '" + device + "'");
    }

    String ffmpegPath = p.string("ffmpegPath");
    if (ffmpegPath.isEmpty()) {
      errors.add("ffmpegPath must not be empty");
    }

    InputFormat inputFormat = InputFormat.MJPEG;
    String formatText = p.string("inputFormat").toUpperCase(Locale.ROOT);
    try {
      inputFormat = InputFormat.valueOf(formatText);
    } catch (IllegalArgumentException e) {
      errors.add("inputFormat must be MJPEG or YUYV, got '" + formatText + "'");
    }

    int width = p.integer("width", 16, 4096);
    int height = p.integer("height", 16, 4096);
    int fps = p.integer("fps", 1, 240);
    int rawQuality = p.integer("rawEncodeQuality", 2, 31);
    String rawPixFmt = p.string("rawEncodePixelFormat");
    if (!rawPixFmt.matches("[a-z0-9_]+")) {
      errors.add(
          "rawEncodePixelFormat must be an FFmpeg pixel format name, got '" + rawPixFmt + "'");
    }
    int segmentSeconds = p.integer("segmentSeconds", 1, 3600);
    int maxSessionSeconds = p.integer("maxSessionSeconds", 1, 24 * 3600);
    if (segmentSeconds > maxSessionSeconds) {
      errors.add("segmentSeconds must not exceed maxSessionSeconds");
    }
    long maxSessionMiB = p.integer("maxSessionMiB", 1, 1 << 20);

    String outputDirectory = p.string("outputDirectory");
    try {
      Path out = Path.of(outputDirectory);
      // Accept a leading '/' explicitly so Linux paths also validate in desktop (Windows) tests.
      boolean absolute = out.isAbsolute() || outputDirectory.startsWith("/");
      if (!absolute || out.getParent() == null) {
        errors.add(
            "outputDirectory must be an absolute, non-root path, got '" + outputDirectory + "'");
      }
    } catch (InvalidPathException e) {
      errors.add("outputDirectory is not a valid path: " + e.getMessage());
    }

    long poolMiB = p.integer("poolMiB", 1, 1 << 24);
    long minFreeMiB = p.integer("minFreeMiB", 0, 1 << 24);
    if (poolMiB < maxSessionMiB) {
      errors.add("poolMiB must be at least maxSessionMiB so one full session fits in the pool");
    }

    double retry = p.decimal("retryIntervalSeconds", 0.1, 3600);
    double maxBackoff = p.decimal("maxRetryBackoffSeconds", 0.1, 24 * 3600);
    if (maxBackoff < retry) {
      errors.add("maxRetryBackoffSeconds must be >= retryIntervalSeconds");
    }
    double startup = p.decimal("startupTimeoutSeconds", 1, 600);
    double stall = p.decimal("stallTimeoutSeconds", 1, 600);
    double heartbeat = p.decimal("heartbeatTimeoutSeconds", 0.1, 30);
    double stop = p.decimal("stopTimeoutSeconds", 0.5, 30);

    if (!errors.isEmpty()) {
      throw new ConfigException(errors);
    }
    return new VideoRecorderConfig(
        enabled,
        autoRecord,
        allowFms,
        device,
        ffmpegPath,
        inputFormat,
        width,
        height,
        fps,
        rawQuality,
        rawPixFmt,
        segmentSeconds,
        maxSessionSeconds,
        maxSessionMiB,
        outputDirectory,
        poolMiB,
        minFreeMiB,
        retry,
        maxBackoff,
        startup,
        stall,
        heartbeat,
        stop);
  }

  /** Output directory as a path. */
  public Path outputPath() {
    return Path.of(outputDirectory);
  }

  /** True when frames are stream-copied (no decode/re-encode). */
  public boolean streamCopy() {
    return inputFormat == InputFormat.MJPEG;
  }

  long maxSessionBytes() {
    return maxSessionMiB * MIB;
  }

  long poolBytes() {
    return poolMiB * MIB;
  }

  long minFreeBytes() {
    return minFreeMiB * MIB;
  }

  long retryIntervalNanos() {
    return seconds(retryIntervalSeconds);
  }

  long maxRetryBackoffNanos() {
    return seconds(maxRetryBackoffSeconds);
  }

  long startupTimeoutNanos() {
    return seconds(startupTimeoutSeconds);
  }

  long stallTimeoutNanos() {
    return seconds(stallTimeoutSeconds);
  }

  long heartbeatTimeoutNanos() {
    return seconds(heartbeatTimeoutSeconds);
  }

  long stopTimeoutNanos() {
    return seconds(stopTimeoutSeconds);
  }

  long maxSessionNanos() {
    return seconds(maxSessionSeconds);
  }

  static long seconds(double s) {
    return (long) (s * 1e9);
  }

  /** Human-readable capture description, e.g. "MJPEG 640x480@30 (stream copy)". */
  public String describeCapture() {
    return inputFormat
        + " "
        + width
        + "x"
        + height
        + "@"
        + fps
        + (streamCopy()
            ? " (stream copy)"
            : " (software MJPEG encode q=" + rawEncodeQuality + ", " + rawEncodePixelFormat + ")");
  }

  private static final class Parser {
    private final Map<String, String> values;
    private final List<String> errors;

    Parser(Map<String, String> values, List<String> errors) {
      this.values = values;
      this.errors = errors;
    }

    String string(String key) {
      String v = values.get(key);
      return (v == null ? DEFAULTS.get(key) : v).trim();
    }

    boolean bool(String key) {
      String v = string(key).toLowerCase(Locale.ROOT);
      if (v.equals("true")) {
        return true;
      }
      if (v.equals("false")) {
        return false;
      }
      errors.add(key + " must be true or false, got '" + v + "'");
      return Boolean.parseBoolean(DEFAULTS.get(key));
    }

    int integer(String key, int min, int max) {
      String v = string(key);
      try {
        int parsed = Integer.parseInt(v);
        if (parsed < min || parsed > max) {
          errors.add(key + " must be between " + min + " and " + max + ", got " + parsed);
        }
        return parsed;
      } catch (NumberFormatException e) {
        errors.add(key + " must be an integer, got '" + v + "'");
        return Integer.parseInt(DEFAULTS.get(key));
      }
    }

    double decimal(String key, double min, double max) {
      String v = string(key);
      try {
        double parsed = Double.parseDouble(v);
        if (!(parsed >= min && parsed <= max)) {
          errors.add(key + " must be between " + min + " and " + max + ", got " + v);
        }
        return parsed;
      } catch (NumberFormatException e) {
        errors.add(key + " must be a number, got '" + v + "'");
        return Double.parseDouble(DEFAULTS.get(key));
      }
    }
  }
}
