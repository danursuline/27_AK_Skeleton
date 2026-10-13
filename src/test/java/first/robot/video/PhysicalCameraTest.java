package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Physical camera smoke test. Runs only on Linux when {@code VIDEO_RECORDER_HW_DEVICE} names a V4L2
 * device (e.g. /dev/v4l/by-id/usb-...-video-index0). It is not run by CI or on Windows. It records
 * ~5 s with the production command line and reports the measured frame count; it does not validate
 * SystemCore CPU load or every camera's compatibility.
 */
@Tag("hardware")
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "VIDEO_RECORDER_HW_DEVICE", matches = "/dev/.+")
class PhysicalCameraTest {
  @TempDir Path tmp;

  @Test
  void recordsFromRealCamera() throws Exception {
    Map<String, String> m = new HashMap<>();
    m.put("device", System.getenv("VIDEO_RECORDER_HW_DEVICE"));
    m.put("outputDirectory", tmp.resolve("videos").toString());
    m.put("minFreeMiB", "0");
    String ffmpegPath = System.getenv("VIDEO_RECORDER_TEST_FFMPEG");
    if (ffmpegPath != null && !ffmpegPath.isBlank()) {
      m.put("ffmpegPath", ffmpegPath);
    }
    String format = System.getenv("VIDEO_RECORDER_HW_FORMAT");
    if (format != null && !format.isBlank()) {
      m.put("inputFormat", format);
    }
    VideoRecorderConfig config = VideoRecorderConfig.parse(m);
    RecorderController controller =
        new RecorderController(
            config,
            System::nanoTime,
            Instant::now,
            new SessionStore(config.outputPath()),
            new LinuxCameraLocator(),
            new FfmpegProbe(),
            new JavaProcessLauncher(),
            new FfmpegCommandBuilder());
    try {
      long start = System.nanoTime();
      while (System.nanoTime() - start < 7_000_000_000L) {
        controller.submitRequest(
            new RecorderRequest(true, false, false, false, System.nanoTime(), 0, true));
        controller.step();
        Thread.sleep(50);
      }
      RecorderStatus during = controller.status();
      System.out.println("[PhysicalCameraTest] status while recording: " + during);
      assertEquals(RecorderState.RECORDING, during.state(), during.detail());
      controller.submitRequest(
          new RecorderRequest(false, false, false, false, System.nanoTime(), 0, true));
      controller.step();
      Path dir = config.outputPath().resolve(during.session());
      String json = Files.readString(dir.resolve(SessionStore.METADATA_FILE));
      System.out.println("[PhysicalCameraTest] session.json:\n" + json);
      assertTrue(json.contains("\"status\": \"complete\""), json);
      assertTrue(during.frames() > 0);
    } finally {
      controller.shutdown();
    }
  }
}
