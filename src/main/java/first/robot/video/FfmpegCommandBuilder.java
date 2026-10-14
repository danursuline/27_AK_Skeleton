package first.robot.video;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the FFmpeg argument list. Every argument is a separate list element; nothing is ever
 * passed through a shell.
 *
 * <p>Timing: no {@code -r}, frame-rate filter, or constant-frame-rate sync is applied, so each
 * frame keeps the capture timestamp FFmpeg receives from V4L2 (rebased so the session starts near
 * zero) and real gaps between frames are preserved. Segment timestamps are not reset, so all
 * segments of a session share one continuous timeline.
 */
class FfmpegCommandBuilder {
  static final String SEGMENT_PATTERN = "seg_%05d.mkv";
  static final String SEGMENT_LIST = "segments.csv";

  /** Whether this builder captures from a V4L2 device (affects dependency checks). */
  boolean usesV4l2() {
    return true;
  }

  List<String> build(
      FfmpegInfo ffmpeg, VideoRecorderConfig config, String devicePath, Path sessionDir) {
    List<String> cmd = new ArrayList<>();
    cmd.add(ffmpeg.executable());
    cmd.addAll(List.of("-hide_banner", "-loglevel", "info", "-nostats", "-progress", "pipe:1"));
    cmd.addAll(inputArgs(config, devicePath));
    cmd.addAll(List.of("-map", "0:v:0"));
    if (config.streamCopy()) {
      // Compressed MJPEG frames are copied byte-for-byte: no decode, no re-encode.
      cmd.addAll(List.of("-c:v", "copy"));
    } else {
      // Explicitly configured raw fallback: software MJPEG encode (higher CPU cost).
      cmd.addAll(
          List.of(
              "-c:v",
              "mjpeg",
              "-q:v",
              Integer.toString(config.rawEncodeQuality()),
              "-pix_fmt",
              config.rawEncodePixelFormat()));
    }
    // Pass timestamps through unchanged; never duplicate or drop frames to force constant FPS.
    if (ffmpeg.supportsFpsMode()) {
      cmd.addAll(List.of("-fps_mode", "passthrough"));
    } else {
      cmd.addAll(List.of("-vsync", "passthrough"));
    }
    // Backstop in case this JVM dies and leaves FFmpeg orphaned.
    cmd.addAll(List.of("-t", Integer.toString(config.maxSessionSeconds())));
    cmd.addAll(
        List.of(
            "-f",
            "segment",
            "-segment_time",
            Integer.toString(config.segmentSeconds()),
            "-segment_format",
            "matroska",
            "-reset_timestamps",
            "0",
            "-segment_list",
            sessionDir.resolve(SEGMENT_LIST).toString(),
            "-segment_list_type",
            "csv",
            sessionDir.resolve(SEGMENT_PATTERN).toString()));
    return cmd;
  }

  /** Input arguments. Overridden in tests to read synthetic video instead of a camera. */
  protected List<String> inputArgs(VideoRecorderConfig config, String devicePath) {
    return List.of(
        "-f",
        "v4l2",
        "-input_format",
        config.inputFormat().v4l2Name(),
        "-video_size",
        config.width() + "x" + config.height(),
        "-framerate",
        Integer.toString(config.fps()),
        "-thread_queue_size",
        "64",
        "-i",
        devicePath);
  }
}
