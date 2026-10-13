package first.robot.video;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Mutable per-session metadata, serialized to {@code session.json}. Worker-thread only. */
final class SessionMetadata {
  static final String SCHEMA = "first.robot.video/session-v1";
  static final String TIMING_NOTE =
      "Frame timestamps in the .mkv files are the V4L2 capture timestamps received by FFmpeg,"
          + " rebased so the session starts near 0 and continuous across segments (1 ms Matroska"
          + " resolution). Gaps between frames are preserved; no frames are duplicated or dropped"
          + " to force a constant rate. Wall-clock and robot timestamps below are sampled by the"
          + " recorder thread when it observed launch / the first progress report, so they are"
          + " coarse (typically within ~0.5-1 s) and are NOT per-frame synchronization to robot"
          + " logs.";

  String sessionId = "";
  String status = "recording";
  String outcome = "";
  String detail = "";
  String device = "";
  String deviceRealPath = "";
  String cameraSelection = "";
  String modeCheck = "";
  String ffmpegExecutable = "";
  String ffmpegVersion = "";
  List<String> command = List.of();
  String capture = "";
  String inputFormat = "";
  int width;
  int height;
  int requestedFps;
  boolean streamCopy;
  int segmentSeconds;
  int maxSessionSeconds;
  Instant launchWallTime;
  Instant firstFrameWallTime;
  Instant endWallTime;
  boolean systemTimeValid;
  Double robotTimestampAtLaunch;
  Double robotTimestampAtFirstFrame;
  Double robotTimestampAtEnd;
  Double secondsLaunchToFirstFrame;
  Double secondsLaunchToEnd;
  long frames;
  long dupFrames;
  long dropFrames;
  Integer exitCode;
  String stopMethod = "";
  final List<String> warnings = new ArrayList<>();
  final Map<String, Long> segments = new LinkedHashMap<>();

  String toJson() {
    StringBuilder b = new StringBuilder(2048);
    b.append("{\n");
    field(b, "schema", SCHEMA);
    field(b, "sessionId", sessionId);
    field(b, "status", status);
    field(b, "outcome", outcome);
    field(b, "detail", detail);
    field(b, "device", device);
    field(b, "deviceRealPath", deviceRealPath);
    field(b, "cameraSelection", cameraSelection);
    field(b, "modeCheck", modeCheck);
    field(b, "ffmpegExecutable", ffmpegExecutable);
    field(b, "ffmpegVersion", ffmpegVersion);
    b.append("  \"command\": [");
    for (int i = 0; i < command.size(); i++) {
      b.append(i == 0 ? "" : ", ").append(quote(command.get(i)));
    }
    b.append("],\n");
    field(b, "capture", capture);
    field(b, "inputFormat", inputFormat);
    raw(b, "width", Integer.toString(width));
    raw(b, "height", Integer.toString(height));
    raw(b, "requestedFps", Integer.toString(requestedFps));
    raw(b, "streamCopy", Boolean.toString(streamCopy));
    raw(b, "segmentSeconds", Integer.toString(segmentSeconds));
    raw(b, "maxSessionSeconds", Integer.toString(maxSessionSeconds));
    field(b, "launchWallTimeUtc", str(launchWallTime));
    field(b, "firstFrameWallTimeUtc", str(firstFrameWallTime));
    field(b, "endWallTimeUtc", str(endWallTime));
    raw(b, "systemTimeValid", Boolean.toString(systemTimeValid));
    raw(b, "robotTimestampAtLaunchSeconds", num(robotTimestampAtLaunch));
    raw(b, "robotTimestampAtFirstFrameSeconds", num(robotTimestampAtFirstFrame));
    raw(b, "robotTimestampAtEndSeconds", num(robotTimestampAtEnd));
    raw(b, "secondsLaunchToFirstFrame", num(secondsLaunchToFirstFrame));
    raw(b, "secondsLaunchToEnd", num(secondsLaunchToEnd));
    raw(b, "frames", Long.toString(frames));
    raw(b, "dupFrames", Long.toString(dupFrames));
    raw(b, "dropFrames", Long.toString(dropFrames));
    raw(b, "exitCode", exitCode == null ? "null" : exitCode.toString());
    field(b, "stopMethod", stopMethod);
    b.append("  \"warnings\": [");
    for (int i = 0; i < warnings.size(); i++) {
      b.append(i == 0 ? "" : ", ").append(quote(warnings.get(i)));
    }
    b.append("],\n");
    b.append("  \"segments\": [");
    int i = 0;
    for (Map.Entry<String, Long> e : segments.entrySet()) {
      b.append(i++ == 0 ? "\n" : ",\n")
          .append("    {\"file\": ")
          .append(quote(e.getKey()))
          .append(", \"bytes\": ")
          .append(e.getValue())
          .append('}');
    }
    b.append(segments.isEmpty() ? "],\n" : "\n  ],\n");
    b.append("  \"timingNote\": ").append(quote(TIMING_NOTE)).append('\n');
    b.append("}\n");
    return b.toString();
  }

  private static void field(StringBuilder b, String key, String value) {
    raw(b, key, quote(value));
  }

  private static void raw(StringBuilder b, String key, String json) {
    b.append("  ").append(quote(key)).append(": ").append(json).append(",\n");
  }

  private static String str(Instant t) {
    return t == null ? "" : t.toString();
  }

  private static String num(Double d) {
    return d == null || d.isNaN() || d.isInfinite()
        ? "null"
        : String.format(Locale.ROOT, "%.6f", d);
  }

  static String quote(String s) {
    StringBuilder b = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }
}
