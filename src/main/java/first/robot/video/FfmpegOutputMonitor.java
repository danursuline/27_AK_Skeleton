package first.robot.video;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe parser for FFmpeg's combined stdout/stderr when run with {@code -progress pipe:1}.
 * Tracks the written-frame counter and classifies the first fatal problem. Retains only a small
 * bounded tail of non-progress lines.
 */
final class FfmpegOutputMonitor {
  /** Problems detected in FFmpeg output. */
  enum Problem {
    NONE(false),
    CAMERA_BUSY(true),
    PERMISSION_DENIED(true),
    DEVICE_MISSING(true),
    UNSUPPORTED_MODE(true),
    NOT_CAPTURE_DEVICE(true),
    NO_SPACE(true),
    FFMPEG_FEATURE_MISSING(true);

    private final boolean fatal;

    Problem(boolean fatal) {
      this.fatal = fatal;
    }

    boolean fatal() {
      return fatal;
    }
  }

  static final int TAIL_LINES = 40;
  private static final int MAX_LINE_CHARS = 500;

  private static final Pattern PROGRESS_KV = Pattern.compile("^([a-z0-9_]+)=(\\S*)$");
  private static final Pattern SIZE_CHANGED =
      Pattern.compile("driver changed the video from (\\d+x\\d+) to (\\d+x\\d+)");
  private static final Pattern FPS_CHANGED =
      Pattern.compile("changed the time per frame from (\\d+/\\d+) to (\\d+/\\d+)");

  private final ArrayDeque<String> tail = new ArrayDeque<>();
  private volatile long frames;
  private volatile long dupFrames;
  private volatile long dropFrames;
  private volatile boolean progressEnded;
  private volatile Problem problem = Problem.NONE;
  private volatile String problemLine = "";
  private final List<String> warnings = new ArrayList<>();

  /** Returns true for {@code key=value} lines produced by {@code -progress}. */
  static boolean isProgressLine(String line) {
    return PROGRESS_KV.matcher(line).matches();
  }

  /** Feeds one output line. Called from the output pump thread. */
  void accept(String rawLine) {
    String line =
        rawLine.length() > MAX_LINE_CHARS ? rawLine.substring(0, MAX_LINE_CHARS) : rawLine.strip();
    Matcher kv = PROGRESS_KV.matcher(line);
    if (kv.matches()) {
      String value = kv.group(2);
      switch (kv.group(1)) {
        case "frame" -> frames = parseLong(value, frames);
        case "dup_frames" -> dupFrames = parseLong(value, dupFrames);
        case "drop_frames" -> dropFrames = parseLong(value, dropFrames);
        case "progress" -> progressEnded = "end".equals(value);
        default -> {}
      }
      return;
    }
    if (line.isEmpty()) {
      return;
    }
    synchronized (tail) {
      if (tail.size() == TAIL_LINES) {
        tail.removeFirst();
      }
      tail.addLast(line);
    }
    Matcher fps = FPS_CHANGED.matcher(line);
    if (fps.find()) {
      addWarning(
          "FPS_CHANGED: camera driver changed frame interval from "
              + fps.group(1)
              + " to "
              + fps.group(2)
              + " s");
    }
    if (problem == Problem.NONE) {
      Problem p = classifyLine(line);
      if (p != Problem.NONE) {
        problemLine = line;
        problem = p;
      }
    }
  }

  /** Classifies a single line. */
  static Problem classifyLine(String line) {
    String l = line.toLowerCase(Locale.ROOT);
    if (l.contains("device or resource busy")) {
      return Problem.CAMERA_BUSY;
    }
    if (l.contains("permission denied")) {
      return Problem.PERMISSION_DENIED;
    }
    if (l.contains("no space left on device")) {
      return Problem.NO_SPACE;
    }
    if (l.contains("no such device")
        || (l.contains("no such file or directory") && l.contains("/dev/"))) {
      return Problem.DEVICE_MISSING;
    }
    if (l.contains("not a video capture device")) {
      return Problem.NOT_CAPTURE_DEVICE;
    }
    if (SIZE_CHANGED.matcher(line).find()
        || l.contains("driver changed the pixel format")
        || l.contains("cannot find a proper format")) {
      return Problem.UNSUPPORTED_MODE;
    }
    if (l.contains("unknown input format")
        || l.contains("unrecognized option")
        || l.contains("option not found")
        || l.contains("unknown encoder")
        || l.contains("requested output format 'segment' is not")) {
      return Problem.FFMPEG_FEATURE_MISSING;
    }
    return Problem.NONE;
  }

  /** Returns the first problem found in a block of text. */
  static Problem classifyText(String text) {
    for (String line : text.split("\\R")) {
      Problem p = classifyLine(line);
      if (p != Problem.NONE) {
        return p;
      }
    }
    return Problem.NONE;
  }

  long frames() {
    return frames;
  }

  long dupFrames() {
    return dupFrames;
  }

  long dropFrames() {
    return dropFrames;
  }

  boolean progressEnded() {
    return progressEnded;
  }

  Problem problem() {
    return problem;
  }

  String problemLine() {
    return problemLine;
  }

  List<String> warnings() {
    synchronized (warnings) {
      return List.copyOf(warnings);
    }
  }

  List<String> tail() {
    synchronized (tail) {
      return List.copyOf(tail);
    }
  }

  /** Last non-progress line, or "" if none. */
  String lastLine() {
    synchronized (tail) {
      return tail.isEmpty() ? "" : tail.getLast();
    }
  }

  private void addWarning(String w) {
    synchronized (warnings) {
      if (!warnings.contains(w) && warnings.size() < 10) {
        warnings.add(w);
      }
    }
  }

  private static long parseLong(String v, long fallback) {
    try {
      return Long.parseLong(v);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
