package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FfmpegCommandBuilderTest {
  private static final FfmpegInfo MODERN = new FfmpegInfo("/usr/bin/ffmpeg", "6.1.1", 6, 1);
  private static final FfmpegInfo OLD = new FfmpegInfo("/opt/ffmpeg", "5.0.1", 5, 0);
  private static final String DEVICE = "/dev/v4l/by-id/usb-Cam_With Space-video-index0";

  private static List<String> build(FfmpegInfo ff, VideoRecorderConfig c) {
    return new FfmpegCommandBuilder().build(ff, c, DEVICE, Path.of("/home/systemcore/videos/s"));
  }

  private static String after(List<String> cmd, String flag) {
    return cmd.get(cmd.indexOf(flag) + 1);
  }

  @Test
  void mjpegDefaultStreamCopiesWithoutRateConversion() {
    List<String> cmd = build(MODERN, VideoRecorderConfig.defaults());
    assertEquals("/usr/bin/ffmpeg", cmd.get(0));
    assertEquals("v4l2", after(cmd, "-f"));
    assertEquals("mjpeg", after(cmd, "-input_format"));
    assertEquals("640x480", after(cmd, "-video_size"));
    assertEquals("30", after(cmd, "-framerate"));
    assertEquals(DEVICE, after(cmd, "-i"), "device path is one argument, spaces intact");
    assertEquals("copy", after(cmd, "-c:v"));
    assertEquals("passthrough", after(cmd, "-fps_mode"));
    assertFalse(cmd.contains("-r"), "no output frame-rate forcing");
    assertFalse(cmd.contains("-vf") || cmd.contains("-filter:v"), "no filters");
    assertFalse(cmd.contains("-q:v"), "no re-encode");
    assertEquals("0", after(cmd, "-reset_timestamps"), "one continuous timeline per session");
    assertEquals("matroska", after(cmd, "-segment_format"));
    assertEquals("10", after(cmd, "-segment_time"));
    assertEquals("180", after(cmd, "-t"));
    assertTrue(cmd.get(cmd.size() - 1).endsWith("seg_%05d.mkv"));
    assertEquals("pipe:1", after(cmd, "-progress"));
  }

  @Test
  void oldFfmpegUsesVsyncPassthrough() {
    List<String> cmd = build(OLD, VideoRecorderConfig.defaults());
    assertEquals("passthrough", after(cmd, "-vsync"));
    assertFalse(cmd.contains("-fps_mode"));
  }

  @Test
  void rawFallbackEncodesMjpeg() throws Exception {
    VideoRecorderConfig c =
        VideoRecorderConfig.parse(Map.of("inputFormat", "YUYV", "rawEncodeQuality", "8"));
    List<String> cmd = build(MODERN, c);
    assertEquals("yuyv422", after(cmd, "-input_format"));
    assertEquals("mjpeg", after(cmd, "-c:v"));
    assertEquals("8", after(cmd, "-q:v"));
    assertEquals("yuvj422p", after(cmd, "-pix_fmt"));
    assertEquals("passthrough", after(cmd, "-fps_mode"));
    assertFalse(cmd.contains("-r"));
  }

  @Test
  void noArgumentContainsShellSyntax() {
    for (String arg : build(MODERN, VideoRecorderConfig.defaults())) {
      assertFalse(arg.contains("|") || arg.contains(";") || arg.contains("&&"), arg);
    }
    assertEquals(1, Collections.frequency(build(MODERN, VideoRecorderConfig.defaults()), "-i"));
  }
}
