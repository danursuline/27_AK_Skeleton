package first.robot.video;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A verified FFmpeg executable.
 *
 * @param executable absolute path of the executable
 * @param version version string as reported by {@code ffmpeg -version}
 * @param major parsed major version, or -1 if unknown (e.g. git builds)
 * @param minor parsed minor version, or -1 if unknown
 */
record FfmpegInfo(String executable, String version, int major, int minor) {
  private static final Pattern VERSION_LINE = Pattern.compile("ffmpeg version (\\S+)");
  private static final Pattern NUMERIC = Pattern.compile("^n?(\\d+)\\.(\\d+)");

  static FfmpegInfo fromVersionOutput(String executable, String output) {
    Matcher line = VERSION_LINE.matcher(output);
    if (!line.find()) {
      return null;
    }
    String version = line.group(1);
    Matcher numeric = NUMERIC.matcher(version);
    if (numeric.find()) {
      return new FfmpegInfo(
          executable,
          version,
          Integer.parseInt(numeric.group(1)),
          Integer.parseInt(numeric.group(2)));
    }
    return new FfmpegInfo(executable, version, -1, -1);
  }

  /**
   * {@code -fps_mode} replaced {@code -vsync} in FFmpeg 5.1. Unknown (git) versions are assumed to
   * be recent.
   */
  boolean supportsFpsMode() {
    return major < 0 || major > 5 || (major == 5 && minor >= 1);
  }
}
