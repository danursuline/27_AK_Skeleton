package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderControllerTest {
  private static final long GIB = 1024L * 1024L * 1024L;

  @TempDir Path tmp;

  private final Fakes.Clock clock = new Fakes.Clock();
  private final Fakes.Launcher launcher = new Fakes.Launcher();
  private final Fakes.Probe probe = new Fakes.Probe();
  private final Fakes.Locator locator = new Fakes.Locator();
  private final AtomicLong freeBytes = new AtomicLong(100 * GIB);
  private SessionStore store;
  private RecorderController controller;

  private boolean enabled;
  private boolean fms;
  private boolean manual;
  private boolean inhibit;

  private RecorderController create(Map<String, String> overrides) throws Exception {
    Map<String, String> cfg = new HashMap<>(overrides);
    cfg.put("outputDirectory", tmp.resolve("videos").toString());
    VideoRecorderConfig config = VideoRecorderConfig.parse(cfg);
    store = new SessionStore(config.outputPath(), p -> freeBytes.get());
    controller =
        new RecorderController(
            config,
            clock::now,
            () -> Instant.parse("2026-10-12T20:00:00Z"),
            store,
            locator,
            probe,
            launcher,
            new FfmpegCommandBuilder());
    return controller;
  }

  private RecorderController create() throws Exception {
    return create(Map.of());
  }

  /** Simulates one robot loop (heartbeat) followed by one worker step. */
  private RecorderStatus cycle() {
    controller.submitRequest(
        new RecorderRequest(enabled, fms, manual, inhibit, clock.now(), clock.now() / 1e9, true));
    controller.step();
    return controller.status();
  }

  /** Advances time in 0.1 s robot/worker cycles. */
  private RecorderStatus run(double seconds) {
    RecorderStatus s = controller.status();
    for (int i = 0; i < Math.round(seconds * 10); i++) {
      clock.advanceSeconds(0.1);
      s = cycle();
    }
    return s;
  }

  private Fakes.Process startRecording() {
    enabled = true;
    cycle();
    Fakes.Process p = launcher.last();
    p.frames(3);
    clock.advanceSeconds(0.1);
    RecorderStatus s = cycle();
    assertEquals(RecorderState.RECORDING, s.state());
    return p;
  }

  private String sessionJson(String sessionId) throws IOException {
    return Files.readString(
        tmp.resolve("videos").resolve(sessionId).resolve("session.json"), StandardCharsets.UTF_8);
  }

  @Test
  void idleWhileDisabledAndNoProcessStarted() throws Exception {
    create();
    RecorderStatus s = cycle();
    assertEquals(RecorderState.IDLE, s.state());
    assertTrue(s.detail().contains("ready"), s.detail());
    assertEquals(0, launcher.launched.size());
  }

  @Test
  void startsOnEnableAndReportsRecordingOnlyAfterFramesArrive() throws Exception {
    create();
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(1, launcher.launched.size());
    assertEquals(RecorderState.STARTING, s.state());
    assertFalse(s.recording(), "launching FFmpeg alone must not report Recording=true");

    Fakes.Process p = launcher.last();
    p.frames(0);
    clock.advanceSeconds(0.1);
    assertFalse(cycle().recording(), "frame=0 is not a frame");

    p.frames(7);
    clock.advanceSeconds(0.1);
    s = cycle();
    assertEquals(RecorderState.RECORDING, s.state());
    assertTrue(s.recording());
    assertEquals(7, s.frames());
    assertTrue(s.session().startsWith("vr-000001-"), s.session());
  }

  @Test
  void stopsAndFinalizesWhenDisabled() throws Exception {
    create();
    Fakes.Process p = startRecording();
    String session = controller.status().session();
    enabled = false;
    clock.advanceSeconds(0.1);
    RecorderStatus s = cycle();
    assertEquals(1, p.gracefulRequests);
    assertEquals(0, p.kills);
    assertEquals(RecorderState.IDLE, s.state());
    assertFalse(s.recording());
    String json = sessionJson(session);
    assertTrue(json.contains("\"status\": \"complete\""), json);
    assertTrue(json.contains("\"stopMethod\": \"q\""), json);
    assertTrue(json.contains("\"device\": \"/dev/v4l/by-id/usb-Test_Cam-video-index0\""), json);
    assertTrue(json.contains("\"capture\": \"MJPEG 640x480@30 (stream copy)\""), json);
  }

  @Test
  void manualRecordWorksWhileDisabledAndPersistsThroughDisable() throws Exception {
    create();
    manual = true;
    cycle();
    assertEquals(1, launcher.launched.size(), "manual record while disabled");
    Fakes.Process p = launcher.last();
    p.frames(5);
    enabled = true;
    run(1);
    enabled = false;
    RecorderStatus s = run(1);
    assertEquals(RecorderState.RECORDING, s.state(), "manual keeps recording through disable");
    assertEquals(0, p.gracefulRequests);

    manual = false;
    p.frames(20);
    s = run(0.1);
    assertEquals(RecorderState.IDLE, s.state());
    assertEquals(1, p.gracefulRequests);
  }

  @Test
  void inhibitStopsAndPreventsRecordingRegardlessOfRequests() throws Exception {
    create();
    Fakes.Process p = startRecording();
    manual = true;
    inhibit = true;
    RecorderStatus s = run(0.1);
    assertEquals(RecorderState.INHIBITED, s.state());
    assertEquals(1, p.gracefulRequests);
    run(10);
    assertEquals(1, launcher.launched.size(), "no new launch while inhibited");

    inhibit = false;
    run(0.1);
    assertEquals(2, launcher.launched.size(), "recording resumes after inhibit is cleared");
  }

  @Test
  void fmsBlocksRecordingByDefaultEvenWithManual() throws Exception {
    create();
    fms = true;
    enabled = true;
    manual = true;
    RecorderStatus s = run(2);
    assertEquals(RecorderState.FMS_BLOCKED, s.state());
    assertEquals(0, launcher.launched.size());
  }

  @Test
  void fmsRecordingAllowedOnlyWithExplicitOptIn() throws Exception {
    create(Map.of("allowRecordingWithFms", "true"));
    fms = true;
    enabled = true;
    cycle();
    assertEquals(1, launcher.launched.size());
  }

  @Test
  void fmsAttachDuringRecordingStopsIt() throws Exception {
    create();
    Fakes.Process p = startRecording();
    fms = true;
    assertEquals(RecorderState.FMS_BLOCKED, run(0.1).state());
    assertEquals(1, p.gracefulRequests);
  }

  @Test
  void staleHeartbeatStopsRecording() throws Exception {
    create();
    Fakes.Process p = startRecording();
    // Robot loop stops calling periodic(); only the worker keeps stepping.
    for (int i = 0; i < 9; i++) {
      clock.advanceSeconds(0.1);
      controller.step();
    }
    assertEquals(0, p.gracefulRequests, "0.9 s gap is within the 1.0 s heartbeat timeout");
    clock.advanceSeconds(0.2);
    controller.step();
    assertEquals(1, p.gracefulRequests);
    assertEquals(RecorderState.HEARTBEAT_LOST, controller.status().state());
    for (int i = 0; i < 100; i++) {
      clock.advanceSeconds(0.1);
      controller.step();
    }
    assertEquals(1, launcher.launched.size(), "never restarts without a heartbeat");
  }

  @Test
  void noRequestEverMeansNoRecording() throws Exception {
    create();
    for (int i = 0; i < 50; i++) {
      clock.advanceSeconds(0.1);
      controller.step();
    }
    assertEquals(RecorderState.HEARTBEAT_LOST, controller.status().state());
    assertEquals(0, launcher.launched.size());
  }

  @Test
  void missingFfmpegIsReportedAndRetried() throws Exception {
    create();
    probe.ffmpegAvailable = false;
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(RecorderState.FFMPEG_ERROR, s.state());
    assertTrue(s.detail().startsWith("FFMPEG_MISSING"), s.detail());
    int probes = probe.ffmpegProbes;
    run(4.5);
    assertEquals(probes, probe.ffmpegProbes, "no re-probe before the retry interval");
    assertEquals(0, launcher.launched.size());

    probe.ffmpegAvailable = true;
    run(1);
    assertEquals(1, launcher.launched.size());
  }

  @Test
  void cameraAbsentThenPluggedInLater() throws Exception {
    create();
    locator.missing();
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(RecorderState.WAITING_FOR_CAMERA, s.state());
    assertTrue(s.detail().startsWith("NO_CAMERA"), s.detail());
    run(3);
    locator.present();
    run(2.5);
    assertEquals(1, launcher.launched.size());
  }

  @Test
  void ambiguousCamerasAreNotAutoSelected() throws Exception {
    create();
    locator.result =
        new CameraLocator.Lookup(
            CameraLocator.Kind.AMBIGUOUS,
            null,
            "AMBIGUOUS_CAMERA: 2 USB cameras found",
            java.util.List.of("/dev/v4l/by-id/a", "/dev/v4l/by-id/b"));
    enabled = true;
    RecorderStatus s = run(10);
    assertEquals(RecorderState.WAITING_FOR_CAMERA, s.state());
    assertTrue(s.detail().startsWith("AMBIGUOUS_CAMERA"), s.detail());
    assertEquals(0, launcher.launched.size());
  }

  @Test
  void unsupportedModeIsSurfacedAndNotLaunched() throws Exception {
    create();
    probe.modeResult = DependencyProbe.ModeResult.UNSUPPORTED;
    probe.modeDetail = "UNSUPPORTED_MODE: MJPEG 640x480 (format not advertised)";
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(RecorderState.CAMERA_ERROR, s.state());
    assertTrue(s.detail().startsWith("UNSUPPORTED_MODE"), s.detail());
    assertEquals(0, launcher.launched.size());
  }

  @Test
  void busyCameraIsClassifiedWithSystemCoreHint() throws Exception {
    create();
    enabled = true;
    cycle();
    Fakes.Process p = launcher.last();
    p.emit("[video4linux2,v4l2 @ 0x1] ioctl(VIDIOC_STREAMON): Device or resource busy");
    p.exit(1);
    clock.advanceSeconds(0.1);
    RecorderStatus s = cycle();
    assertEquals(RecorderState.CAMERA_ERROR, s.state());
    assertTrue(s.detail().startsWith("CAMERA_BUSY"), s.detail());
    assertTrue(s.detail().contains("Config tab"), s.detail());
    assertTrue(Files.exists(tmp.resolve("videos").resolve(SessionStore.LAST_FAILURE_FILE)));
  }

  @Test
  void unexpectedExitRetriesWithExponentialBackoff() throws Exception {
    create();
    enabled = true;
    cycle();
    launcher.last().emit("Some unexpected FFmpeg error");
    launcher.last().exit(1);
    clock.advanceSeconds(0.1);
    RecorderStatus s = cycle();
    assertEquals(RecorderState.PROCESS_ERROR, s.state());
    assertTrue(s.detail().startsWith("PROCESS_EXITED"), s.detail());
    assertTrue(s.detail().contains("Some unexpected FFmpeg error"), s.detail());

    run(4.8);
    assertEquals(1, launcher.launched.size());
    run(0.3);
    assertEquals(2, launcher.launched.size(), "first retry after 5 s");

    launcher.last().exit(1);
    run(9.8);
    assertEquals(2, launcher.launched.size(), "second retry backs off to 10 s");
    run(0.3);
    assertEquals(3, launcher.launched.size());
  }

  @Test
  void launchFailureIsReportedWithoutCrashing() throws Exception {
    create();
    launcher.failWith = new IOException("Cannot run program \"ffmpeg\": error=2");
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(RecorderState.FFMPEG_ERROR, s.state());
    assertTrue(s.detail().startsWith("FFMPEG_LAUNCH_FAILED"), s.detail());
  }

  @Test
  void cameraStallStopsAndRetries() throws Exception {
    create();
    Fakes.Process p = startRecording();
    String session = controller.status().session();
    run(5.0);
    assertEquals(0, p.gracefulRequests, "5.0 s without frames is still within the timeout");
    RecorderStatus s = run(0.2);
    assertEquals(RecorderState.PROCESS_ERROR, s.state());
    assertTrue(s.detail().startsWith("CAMERA_STALLED"), s.detail());
    assertEquals(1, p.gracefulRequests);
    String json = sessionJson(session);
    assertTrue(json.contains("\"status\": \"failed\""), "recorded footage kept: " + json);
    run(5.1);
    assertEquals(2, launcher.launched.size());
  }

  @Test
  void noFramesAfterStartupTimeoutDiscardsEmptySession() throws Exception {
    create();
    enabled = true;
    cycle();
    String session = controller.status().session();
    Path dir = tmp.resolve("videos").resolve(session);
    assertTrue(Files.isDirectory(dir));
    RecorderStatus s = run(10.2);
    assertEquals(RecorderState.PROCESS_ERROR, s.state());
    assertTrue(s.detail().startsWith("NO_FRAMES"), s.detail());
    assertFalse(Files.exists(dir), "empty failed session is removed");
    assertTrue(Files.exists(tmp.resolve("videos").resolve(SessionStore.LAST_FAILURE_FILE)));
  }

  @Test
  void fatalOutputProblemStopsRunningProcess() throws Exception {
    create();
    enabled = true;
    cycle();
    Fakes.Process p = launcher.last();
    p.emit("[video4linux2,v4l2 @ 0x1] The V4L2 driver changed the video from 640x480 to 320x240");
    RecorderStatus s = run(0.1);
    assertEquals(RecorderState.CAMERA_ERROR, s.state());
    assertTrue(s.detail().startsWith("UNSUPPORTED_MODE"), s.detail());
    assertEquals(1, p.gracefulRequests);
  }

  @Test
  void fpsChangeIsAWarningNotAFailure() throws Exception {
    create();
    Fakes.Process p = startRecording();
    p.emit("[video4linux2,v4l2 @ 0x1] The driver changed the time per frame from 1/30 to 1/15");
    p.frames(10);
    RecorderStatus s = run(0.1);
    assertEquals(RecorderState.RECORDING, s.state());
    assertTrue(s.detail().contains("FPS_CHANGED"), s.detail());
  }

  @Test
  void boundedShutdownEscalatesToKill() throws Exception {
    create();
    Fakes.Process p = startRecording();
    p.exitsOnQ = false;
    p.exitsOnTerm = false;
    String session = controller.status().session();
    controller.shutdown();
    assertEquals(1, p.gracefulRequests);
    assertEquals(1, p.terminates);
    assertEquals(1, p.kills);
    assertFalse(p.isAlive());
    assertEquals(RecorderState.CLOSED, controller.status().state());
    String json = sessionJson(session);
    assertTrue(json.contains("\"stopMethod\": \"SIGKILL\""), json);
    assertTrue(json.contains("\"status\": \"failed\""), json);
    // Lock released: another store can take the pool.
    try (SessionStore other = new SessionStore(tmp.resolve("videos"))) {
      other.open();
    }
  }

  @Test
  void terminateUsedWhenQuitIgnored() throws Exception {
    create();
    Fakes.Process p = startRecording();
    p.exitsOnQ = false;
    String session = controller.status().session();
    enabled = false;
    run(0.1);
    assertEquals(1, p.terminates);
    assertEquals(0, p.kills);
    assertTrue(sessionJson(session).contains("\"status\": \"complete\""));
  }

  @Test
  void worstCaseStopIsBounded() throws Exception {
    VideoRecorderConfig config = create().config();
    long budget = RecorderController.worstCaseStopMillis(config);
    assertTrue(budget > 3000 && budget < 15000, "budget " + budget);
  }

  @Test
  void sessionLimitLatchesUntilRequestReleasedAndRearmed() throws Exception {
    create(Map.of("maxSessionSeconds", "20", "segmentSeconds", "10"));
    Fakes.Process p = startRecording();
    for (int i = 0; i < 199; i++) {
      p.frames(10 + i);
      run(0.1);
    }
    assertEquals(RecorderState.SESSION_LIMIT, controller.status().state());
    assertEquals(1, p.gracefulRequests);
    run(30);
    assertEquals(1, launcher.launched.size(), "no automatic chaining while still enabled");

    enabled = false;
    assertEquals(RecorderState.IDLE, run(0.1).state());
    enabled = true;
    run(0.1);
    assertEquals(2, launcher.launched.size(), "re-armed after request released");
  }

  @Test
  void sessionLimitWithManualRequiresManualRelease() throws Exception {
    create(Map.of("maxSessionSeconds", "5", "segmentSeconds", "5"));
    manual = true;
    cycle();
    Fakes.Process p = launcher.last();
    for (int i = 0; i < 60; i++) {
      p.frames(i + 1);
      run(0.1);
    }
    assertEquals(RecorderState.SESSION_LIMIT, controller.status().state());
    enabled = true;
    run(1);
    enabled = false;
    run(1);
    assertEquals(1, launcher.launched.size(), "still latched while ManualRecord stays true");
    manual = false;
    run(0.1);
    manual = true;
    run(0.1);
    assertEquals(2, launcher.launched.size());
  }

  @Test
  void ffmpegSelfExitAtTimeBackstopCountsAsLimit() throws Exception {
    create(Map.of("maxSessionSeconds", "20", "segmentSeconds", "10"));
    Fakes.Process p = startRecording();
    for (int i = 0; i < 190; i++) {
      p.frames(10 + i);
      run(0.1);
    }
    p.exit(0);
    assertEquals(RecorderState.SESSION_LIMIT, run(0.1).state());
    run(10);
    assertEquals(1, launcher.launched.size());
  }

  @Test
  void insufficientStorageRefusesToRecordAndPreservesUnknownData() throws Exception {
    create();
    Path root = tmp.resolve("videos");
    Files.createDirectories(root);
    Path unrelated = Files.writeString(root.resolve("team-notes.txt"), "keep me");
    freeBytes.set(1200L * 1024 * 1024); // 1200 MiB free < 512 + 1024 required
    enabled = true;
    RecorderStatus s = cycle();
    assertEquals(RecorderState.STORAGE_ERROR, s.state());
    assertTrue(s.detail().startsWith("STORAGE_FULL"), s.detail());
    assertEquals(0, launcher.launched.size());
    assertTrue(Files.exists(unrelated));
  }

  @Test
  void lowDiskDuringRecordingStops() throws Exception {
    create();
    Fakes.Process p = startRecording();
    freeBytes.set(100L * 1024 * 1024);
    p.frames(50);
    RecorderStatus s = run(1.1);
    assertEquals(RecorderState.STORAGE_ERROR, s.state());
    assertTrue(s.detail().startsWith("LOW_DISK"), s.detail());
    assertEquals(1, p.gracefulRequests);
  }

  @Test
  void secondInstanceCannotManageSamePool() throws Exception {
    create();
    try (SessionStore first = new SessionStore(tmp.resolve("videos"))) {
      first.open();
      enabled = true;
      RecorderStatus s = cycle();
      assertEquals(RecorderState.STORAGE_ERROR, s.state());
      assertTrue(s.detail().startsWith("LOCKED"), s.detail());
      assertEquals(0, launcher.launched.size());
    }
  }

  @Test
  void internalErrorKillsProcessAndFailsSafe() throws Exception {
    create();
    Fakes.Process p = startRecording();
    controller.onInternalError(new IllegalStateException("boom"));
    assertEquals(1, p.kills);
    assertEquals(RecorderState.INTERNAL_ERROR, controller.status().state());
  }

  @Test
  void rawFallbackIsUsedOnlyWhenExplicitlyConfigured() throws Exception {
    create();
    enabled = true;
    cycle();
    String copyCmd = String.join(" ", launcher.last().command);
    assertTrue(copyCmd.contains("-c:v copy"), copyCmd);
    assertTrue(copyCmd.contains("-input_format mjpeg"), copyCmd);
    assertFalse(probe.lastNeedMjpegEncoder);

    launcher.launched.clear();
    store.close();
    Map<String, String> yuyv = new HashMap<>();
    yuyv.put("inputFormat", "YUYV");
    create(yuyv);
    cycle();
    String rawCmd = String.join(" ", launcher.last().command);
    assertTrue(rawCmd.contains("-input_format yuyv422"), rawCmd);
    assertTrue(rawCmd.contains("-c:v mjpeg -q:v 5"), rawCmd);
    assertTrue(probe.lastNeedMjpegEncoder, "raw mode requires the mjpeg encoder");
  }
}
