package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoRecorderConfigTest {
  @TempDir Path tmp;

  @Test
  void defaultsMatchSpecification() {
    VideoRecorderConfig c = VideoRecorderConfig.defaults();
    assertTrue(c.enabled());
    assertTrue(c.autoRecordWhenEnabled());
    assertFalse(c.allowRecordingWithFms(), "FMS recording must be opt-in");
    assertEquals("auto", c.device());
    assertEquals("ffmpeg", c.ffmpegPath());
    assertEquals(VideoRecorderConfig.InputFormat.MJPEG, c.inputFormat());
    assertTrue(c.streamCopy());
    assertEquals(640, c.width());
    assertEquals(480, c.height());
    assertEquals(30, c.fps());
    assertEquals(10, c.segmentSeconds());
    assertEquals(180, c.maxSessionSeconds());
    assertEquals("/home/systemcore/videos", c.outputDirectory());
    assertEquals(2048, c.poolMiB());
    assertEquals(1024, c.minFreeMiB());
    assertEquals(5.0, c.retryIntervalSeconds());
    assertEquals(5.0, c.stallTimeoutSeconds());
  }

  @Test
  void deployedPropertiesFileIsValidAndMatchesDefaults() throws Exception {
    Path deployed = Path.of("src", "main", "deploy", VideoRecorder.CONFIG_FILE_NAME);
    assertTrue(Files.isRegularFile(deployed), "run tests from the project directory");
    assertEquals(VideoRecorderConfig.defaults(), VideoRecorderConfig.load(deployed));
  }

  @Test
  void unknownKeysAreRejected() {
    VideoRecorderConfig.ConfigException e =
        assertThrows(
            VideoRecorderConfig.ConfigException.class,
            () -> VideoRecorderConfig.parse(Map.of("segmentSecs", "10")));
    assertTrue(e.getMessage().contains("unknown key 'segmentSecs'"), e.getMessage());
  }

  @Test
  void invalidValuesAreAllReported() {
    VideoRecorderConfig.ConfigException e =
        assertThrows(
            VideoRecorderConfig.ConfigException.class,
            () ->
                VideoRecorderConfig.parse(
                    Map.of(
                        "width", "abc",
                        "fps", "0",
                        "inputFormat", "H264",
                        "device", "video0",
                        "outputDirectory", "videos",
                        "enabled", "yes",
                        "poolMiB", "100",
                        "segmentSeconds", "300")));
    String m = e.getMessage();
    assertTrue(m.contains("width must be an integer"), m);
    assertTrue(m.contains("fps must be between"), m);
    assertTrue(m.contains("inputFormat must be MJPEG or YUYV"), m);
    assertTrue(m.contains("device must be 'auto'"), m);
    assertTrue(m.contains("outputDirectory must be an absolute"), m);
    assertTrue(m.contains("enabled must be true or false"), m);
    assertTrue(m.contains("poolMiB must be at least maxSessionMiB"), m);
    assertTrue(m.contains("segmentSeconds must not exceed maxSessionSeconds"), m);
    assertEquals(8, e.problems().size(), m);
  }

  @Test
  void rawFallbackMustBeExplicit() throws Exception {
    VideoRecorderConfig raw = VideoRecorderConfig.parse(Map.of("inputFormat", "yuyv"));
    assertEquals(VideoRecorderConfig.InputFormat.YUYV, raw.inputFormat());
    assertFalse(raw.streamCopy());
    assertTrue(raw.describeCapture().contains("software MJPEG encode"));
  }

  @Test
  void explicitDeviceAndFfmpegPathAccepted() throws Exception {
    VideoRecorderConfig c =
        VideoRecorderConfig.parse(
            Map.of(
                "device", "/dev/v4l/by-id/usb-Cam-video-index0",
                "ffmpegPath", "/home/systemcore/bin/ffmpeg"));
    assertEquals("/dev/v4l/by-id/usb-Cam-video-index0", c.device());
    assertEquals("/home/systemcore/bin/ffmpeg", c.ffmpegPath());
  }

  @Test
  void missingFileIsAConfigError() {
    VideoRecorderConfig.ConfigException e =
        assertThrows(
            VideoRecorderConfig.ConfigException.class,
            () -> VideoRecorderConfig.load(tmp.resolve("nope.properties")));
    assertTrue(e.getMessage().contains("CONFIG_MISSING"), e.getMessage());
  }

  @Test
  void loadsPropertiesFile() throws Exception {
    Path f = tmp.resolve("v.properties");
    Files.writeString(f, "# comment\nfps = 15\nallowRecordingWithFms=true\n");
    VideoRecorderConfig c = VideoRecorderConfig.load(f);
    assertEquals(15, c.fps());
    assertTrue(c.allowRecordingWithFms());
  }
}
