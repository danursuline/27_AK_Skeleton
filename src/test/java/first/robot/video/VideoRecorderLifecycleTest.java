package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import first.robot.Constants;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.NetworkTableInstance;

/** REAL-mode boundary, NetworkTables controls, non-blocking periodic, and close() behavior. */
class VideoRecorderLifecycleTest {
  @TempDir Path tmp;

  @Test
  void factoryRunsOnlyInRealMode() {
    AtomicInteger calls = new AtomicInteger();
    Object marker = new Object();
    assertNull(VideoRecorder.createIfReal(Constants.Mode.SIM, () -> calls.incrementAndGet()));
    assertNull(VideoRecorder.createIfReal(Constants.Mode.REPLAY, () -> calls.incrementAndGet()));
    assertEquals(0, calls.get(), "SIM and REPLAY must not construct a recorder");
    assertSame(marker, VideoRecorder.createIfReal(Constants.Mode.REAL, () -> marker));
  }

  @Test
  void simAndReplayCreateNothing() {
    assertNull(VideoRecorder.createForMode(Constants.Mode.SIM));
    assertNull(VideoRecorder.createForMode(Constants.Mode.REPLAY));
  }

  @Test
  void factoryFailureNeverPropagatesToRobotCode() {
    assertNull(
        VideoRecorder.createIfReal(
            Constants.Mode.REAL,
            () -> {
              throw new IllegalStateException("simulated startup failure");
            }));
  }

  @Test
  void controlsResetToFalseAtStartupAndStatusIsPublished() {
    NetworkTableInstance nt = NetworkTableInstance.create();
    try {
      BooleanPublisher dashboardManual =
          nt.getBooleanTopic(VideoRecorder.NT_PREFIX + "ManualRecord").publish();
      BooleanPublisher dashboardInhibit =
          nt.getBooleanTopic(VideoRecorder.NT_PREFIX + "Inhibit").publish();
      dashboardManual.set(true);
      dashboardInhibit.set(true);

      VideoRecorder recorder =
          new VideoRecorder(nt, null, RecorderStatus.of(RecorderState.CONFIG_ERROR, "bad key"));
      assertFalse(
          nt.getBooleanTopic(VideoRecorder.NT_PREFIX + "ManualRecord").subscribe(true).get());
      assertFalse(nt.getBooleanTopic(VideoRecorder.NT_PREFIX + "Inhibit").subscribe(true).get());
      recorder.periodic(false, false, 0, true);
      assertEquals(
          "CONFIG_ERROR", nt.getStringTopic(VideoRecorder.NT_PREFIX + "State").subscribe("").get());
      assertEquals(
          "bad key", nt.getStringTopic(VideoRecorder.NT_PREFIX + "Detail").subscribe("").get());
      assertFalse(nt.getBooleanTopic(VideoRecorder.NT_PREFIX + "Recording").subscribe(true).get());
      recorder.close();
      dashboardManual.close();
      dashboardInhibit.close();
    } finally {
      nt.close();
    }
  }

  @Test
  void periodicNeverBlocksWhileWorkerIsBusyAndCloseIsBounded() throws Exception {
    NetworkTableInstance nt = NetworkTableInstance.create();
    try {
      VideoRecorderConfig config =
          VideoRecorderConfig.parse(
              Map.of(
                  "outputDirectory", tmp.resolve("videos").toString(), "stopTimeoutSeconds", "1"));
      // A process that ignores 'q' and SIGTERM and whose waits really sleep, so the worker blocks.
      ManagedProcess.Launcher slowLauncher =
          (command, logFile) ->
              new Fakes.Process(command) {
                {
                  exitsOnQ = false;
                  exitsOnTerm = false;
                  frames(5);
                }

                @Override
                public boolean waitForExit(long timeoutNanos) {
                  if (!isAlive()) {
                    return true;
                  }
                  try {
                    Thread.sleep(timeoutNanos / 1_000_000);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  return !isAlive();
                }
              };
      RecorderController controller =
          new RecorderController(
              config,
              System::nanoTime,
              Instant::now,
              new SessionStore(config.outputPath()),
              new Fakes.Locator(),
              new Fakes.Probe(),
              slowLauncher,
              new FfmpegCommandBuilder());
      VideoRecorder recorder = new VideoRecorder(nt, controller, null);

      long deadline = System.nanoTime() + 5_000_000_000L;
      while (!recorder.status().recording() && System.nanoTime() < deadline) {
        recorder.periodic(true, false, 0, true);
        Thread.sleep(20);
      }
      assertTrue(recorder.status().recording(), recorder.status().toString());

      // Disable: the worker now spends ~3 s escalating q -> SIGTERM -> SIGKILL.
      long worst = 0;
      long stopDeadline = System.nanoTime() + 6_000_000_000L;
      while (recorder.status().state() != RecorderState.IDLE && System.nanoTime() < stopDeadline) {
        long t0 = System.nanoTime();
        recorder.periodic(false, false, 0, true);
        worst = Math.max(worst, System.nanoTime() - t0);
        Thread.sleep(20);
      }
      assertEquals(RecorderState.IDLE, recorder.status().state());
      assertTrue(worst < 50_000_000L, "periodic() took " + worst / 1e6 + " ms");

      long c0 = System.nanoTime();
      recorder.close();
      assertTrue(System.nanoTime() - c0 < 10_000_000_000L);
      assertEquals(RecorderState.CLOSED, recorder.status().state());
      assertTrue(Files.isDirectory(tmp.resolve("videos")));
    } finally {
      nt.close();
    }
  }
}
