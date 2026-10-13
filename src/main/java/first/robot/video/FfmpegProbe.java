package first.robot.video;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Real {@link DependencyProbe} that runs FFmpeg with bounded timeouts. */
final class FfmpegProbe implements DependencyProbe {
  private static final long PROBE_TIMEOUT_MS = 5000;
  private static final int MAX_OUTPUT_CHARS = 64 * 1024;

  private static final Pattern FORMAT_LINE =
      Pattern.compile("(Compressed|Raw)\\s*:\\s*(\\S+)\\s*:\\s*(.*?)\\s*:\\s*(.*)$");

  private final Map<String, String> environment;

  FfmpegProbe() {
    this(System.getenv());
  }

  FfmpegProbe(Map<String, String> environment) {
    this.environment = environment;
  }

  @Override
  public FfmpegInfo probeFfmpeg(String configuredPath, boolean needV4l2, boolean needMjpegEncoder)
      throws ProbeException {
    Path exe = resolveExecutable(configuredPath, environment);
    String exePath = exe.toString();
    CommandRunner.Result version = run(List.of(exePath, "-hide_banner", "-version"));
    FfmpegInfo info = FfmpegInfo.fromVersionOutput(exePath, version.output());
    if (version.exitCode() != 0 || info == null) {
      throw new ProbeException(
          "FFMPEG_UNUSABLE: '" + exePath + "' -version failed: " + firstLine(version.output()));
    }
    List<String> missing = new ArrayList<>();
    String muxers = run(List.of(exePath, "-hide_banner", "-muxers")).output();
    if (!hasListedName(muxers, "matroska")) {
      missing.add("matroska muxer");
    }
    if (!hasListedName(muxers, "segment")) {
      missing.add("segment muxer");
    }
    if (needV4l2) {
      String devices = run(List.of(exePath, "-hide_banner", "-devices")).output();
      if (!Pattern.compile("\\bv4l2\\b").matcher(devices).find()) {
        missing.add("v4l2 input device");
      }
    }
    if (needMjpegEncoder) {
      String encoders = run(List.of(exePath, "-hide_banner", "-encoders")).output();
      if (!Pattern.compile("(?m)^\\s*V\\S*\\s+mjpeg\\s").matcher(encoders).find()) {
        missing.add("mjpeg encoder");
      }
    }
    if (!missing.isEmpty()) {
      throw new ProbeException(
          "FFMPEG_FEATURES_MISSING: FFmpeg "
              + info.version()
              + " at "
              + exePath
              + " lacks: "
              + String.join(", ", missing));
    }
    return info;
  }

  @Override
  public ModeCheck checkMode(FfmpegInfo ffmpeg, String devicePath, VideoRecorderConfig config) {
    CommandRunner.Result result;
    try {
      result =
          CommandRunner.run(
              List.of(
                  ffmpeg.executable(),
                  "-hide_banner",
                  "-f",
                  "v4l2",
                  "-list_formats",
                  "all",
                  "-i",
                  devicePath),
              PROBE_TIMEOUT_MS,
              MAX_OUTPUT_CHARS);
    } catch (IOException e) {
      return new ModeCheck(ModeResult.UNKNOWN, "mode check could not run: " + e.getMessage());
    }
    return parseListFormats(result.output(), config);
  }

  /** Parses {@code ffmpeg -f v4l2 -list_formats all} output against the requested mode. */
  static ModeCheck parseListFormats(String output, VideoRecorderConfig config) {
    switch (FfmpegOutputMonitor.classifyText(output)) {
      case CAMERA_BUSY:
        return new ModeCheck(ModeResult.BUSY, "device busy while listing formats");
      case PERMISSION_DENIED:
        return new ModeCheck(ModeResult.PERMISSION, "permission denied while listing formats");
      case DEVICE_MISSING:
        return new ModeCheck(ModeResult.MISSING, "device disappeared while listing formats");
      case NOT_CAPTURE_DEVICE:
        return new ModeCheck(ModeResult.NOT_CAPTURE_DEVICE, "not a video capture device");
      default:
        break;
    }
    String wantedFormat = config.inputFormat().v4l2Name();
    String wantedSize = config.width() + "x" + config.height();
    List<String> advertised = new ArrayList<>();
    boolean formatFound = false;
    for (String line : output.split("\\R")) {
      Matcher m = FORMAT_LINE.matcher(line);
      if (!m.find()) {
        continue;
      }
      String format = m.group(2).toLowerCase(Locale.ROOT);
      String sizes = m.group(4).trim();
      advertised.add(format + " [" + sizes + "]");
      if (!format.equals(wantedFormat)) {
        continue;
      }
      formatFound = true;
      if (sizes.contains("{")) {
        return new ModeCheck(
            ModeResult.UNKNOWN,
            "camera advertises stepwise sizes for " + format + "; size not pre-verified");
      }
      for (String size : sizes.split("\\s+")) {
        if (size.equals(wantedSize)) {
          return new ModeCheck(
              ModeResult.SUPPORTED,
              "camera advertises "
                  + format
                  + " "
                  + wantedSize
                  + " (frame rate is verified when capture starts)");
        }
      }
    }
    if (advertised.isEmpty()) {
      return new ModeCheck(ModeResult.UNKNOWN, "camera formats could not be listed");
    }
    String requested = config.inputFormat() + " " + wantedSize;
    String reason = formatFound ? "size not advertised" : "format not advertised";
    return new ModeCheck(
        ModeResult.UNSUPPORTED,
        "UNSUPPORTED_MODE: "
            + requested
            + " ("
            + reason
            + "); camera advertises: "
            + String.join("; ", advertised));
  }

  /** Resolves an explicit path or searches PATH for a bare executable name. */
  static Path resolveExecutable(String configured, Map<String, String> env) throws ProbeException {
    boolean explicit = configured.contains("/") || configured.contains("\\");
    if (explicit) {
      Path p;
      try {
        p = Path.of(configured);
      } catch (InvalidPathException e) {
        throw new ProbeException("FFMPEG_MISSING: invalid ffmpegPath '" + configured + "'");
      }
      if (!Files.isRegularFile(p)) {
        throw new ProbeException(
            "FFMPEG_MISSING: ffmpegPath "
                + configured
                + " does not exist; install FFmpeg or correct ffmpegPath");
      }
      if (!Files.isExecutable(p)) {
        throw new ProbeException("FFMPEG_MISSING: " + configured + " is not executable");
      }
      return p.toAbsolutePath();
    }
    String pathVar = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
    boolean windows = File.separatorChar == '\\';
    for (String dir : pathVar.split(Pattern.quote(File.pathSeparator))) {
      if (dir.isBlank()) {
        continue;
      }
      List<String> names = windows ? List.of(configured + ".exe", configured) : List.of(configured);
      for (String name : names) {
        try {
          Path candidate = Path.of(dir.trim(), name);
          if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate.toAbsolutePath();
          }
        } catch (InvalidPathException ignored) {
          // Skip malformed PATH entries.
        }
      }
    }
    throw new ProbeException(
        "FFMPEG_MISSING: '"
            + configured
            + "' not found on PATH; install FFmpeg on SystemCore or set ffmpegPath to its"
            + " absolute path");
  }

  /** Matches a muxer/demuxer listing row such as " E matroska Matroska". */
  static boolean hasListedName(String listing, String name) {
    return Pattern.compile("(?m)^\\s*[DE.]*E[DE.]*\\s+(\\S+,)?" + Pattern.quote(name) + "[\\s,]")
        .matcher(listing)
        .find();
  }

  private static CommandRunner.Result run(List<String> cmd) throws ProbeException {
    try {
      CommandRunner.Result r = CommandRunner.run(cmd, PROBE_TIMEOUT_MS, MAX_OUTPUT_CHARS);
      if (r.timedOut()) {
        throw new ProbeException("FFMPEG_UNUSABLE: '" + String.join(" ", cmd) + "' timed out");
      }
      return r;
    } catch (IOException e) {
      throw new ProbeException("FFMPEG_UNUSABLE: could not run " + cmd.get(0) + ": " + e);
    }
  }

  private static String firstLine(String s) {
    String trimmed = s.strip();
    int nl = trimmed.indexOf('\n');
    return nl < 0 ? trimmed : trimmed.substring(0, nl).strip();
  }
}
