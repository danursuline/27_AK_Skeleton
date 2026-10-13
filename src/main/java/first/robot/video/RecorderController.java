package first.robot.video;

import first.robot.video.DependencyProbe.ModeCheck;
import first.robot.video.DependencyProbe.ProbeException;
import first.robot.video.FfmpegOutputMonitor.Problem;
import first.robot.video.SessionStore.Capacity;
import first.robot.video.SessionStore.StorageException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Recorder state machine. {@link #step()} is called only from the recorder worker thread and may
 * block (probing, process shutdown, disk I/O). The robot loop interacts only through {@link
 * #submitRequest} and {@link #status()}, which are non-blocking volatile exchanges.
 */
final class RecorderController {
  private static final long STORAGE_CHECK_INTERVAL_NANOS = 1_000_000_000L;
  private static final long TERMINATE_WAIT_NANOS = 2_000_000_000L;
  private static final long KILL_WAIT_NANOS = 2_000_000_000L;
  private static final long OUTPUT_DRAIN_MILLIS = 1000;
  private static final long LIMIT_TOLERANCE_NANOS = 2_000_000_000L;
  private static final int MAX_DETAIL_CHARS = 600;

  /** Total worst-case time {@link #shutdown()} may spend stopping FFmpeg, plus margin. */
  static long worstCaseStopMillis(VideoRecorderConfig config) {
    return (config.stopTimeoutNanos() + TERMINATE_WAIT_NANOS + KILL_WAIT_NANOS) / 1_000_000
        + OUTPUT_DRAIN_MILLIS
        + 1000;
  }

  private enum StopKind {
    REQUEST,
    LIMIT,
    FAILURE,
    SHUTDOWN
  }

  private static final class Active {
    final SessionStore.Session session;
    final SessionMetadata meta;
    final ManagedProcess process;
    final String device;
    final long launchNanos;
    long lastFrames;
    long lastAdvanceNanos;
    boolean firstFrame;
    long nextStorageCheckNanos;

    Active(
        SessionStore.Session session,
        SessionMetadata meta,
        ManagedProcess process,
        String device,
        long launchNanos) {
      this.session = session;
      this.meta = meta;
      this.process = process;
      this.device = device;
      this.launchNanos = launchNanos;
      this.lastAdvanceNanos = launchNanos;
      this.nextStorageCheckNanos = launchNanos + STORAGE_CHECK_INTERVAL_NANOS;
    }
  }

  private final VideoRecorderConfig config;
  private final LongSupplier clock;
  private final Supplier<Instant> wallClock;
  private final SessionStore store;
  private final CameraLocator locator;
  private final DependencyProbe probe;
  private final ManagedProcess.Launcher launcher;
  private final FfmpegCommandBuilder commandBuilder;

  private volatile RecorderRequest request;
  private volatile RecorderStatus status = RecorderStatus.of(RecorderState.IDLE, "Starting up");
  private final AtomicReference<ManagedProcess> liveProcess = new AtomicReference<>();

  // Worker-thread-only state below.
  private FfmpegInfo ffmpeg;
  private String modeVerifiedDevice;
  private String modeNote = "";
  private Active active;
  private boolean limitLatched;
  private RecorderState errorState;
  private String errorDetail = "";
  private long nextAttemptNanos;
  private int consecutiveFailures;
  private long nextReadinessNanos;
  private String readiness = "readiness not checked yet";
  private String lastSessionId = "";
  private long lastFrames;
  private boolean closed;

  RecorderController(
      VideoRecorderConfig config,
      LongSupplier clock,
      Supplier<Instant> wallClock,
      SessionStore store,
      CameraLocator locator,
      DependencyProbe probe,
      ManagedProcess.Launcher launcher,
      FfmpegCommandBuilder commandBuilder) {
    this.config = config;
    this.clock = clock;
    this.wallClock = wallClock;
    this.store = store;
    this.locator = locator;
    this.probe = probe;
    this.launcher = launcher;
    this.commandBuilder = commandBuilder;
    long now = clock.getAsLong();
    this.nextAttemptNanos = now;
    this.nextReadinessNanos = now;
  }

  /** Controller wired to the real filesystem, V4L2 devices, and FFmpeg. */
  static RecorderController forRealHardware(VideoRecorderConfig config) {
    return new RecorderController(
        config,
        System::nanoTime,
        Instant::now,
        new SessionStore(config.outputPath()),
        new LinuxCameraLocator(),
        new FfmpegProbe(),
        new JavaProcessLauncher(),
        new FfmpegCommandBuilder());
  }

  VideoRecorderConfig config() {
    return config;
  }

  /** Non-blocking; called from the robot loop. Also serves as the heartbeat. */
  void submitRequest(RecorderRequest request) {
    this.request = request;
  }

  /** Non-blocking; latest published status. */
  RecorderStatus status() {
    return status;
  }

  /** Runs one supervision cycle. Worker thread only. */
  void step() {
    if (closed) {
      return;
    }
    long now = clock.getAsLong();
    RecorderRequest req = request;
    boolean fresh = req != null && now - req.heartbeatNanos() <= config.heartbeatTimeoutNanos();
    RecordingPolicy.Decision decision = RecordingPolicy.evaluate(config, req, fresh);
    if (decision.requestKnown() && !decision.requested()) {
      // Request released: rearm the session limit and forget previous failures.
      limitLatched = false;
      consecutiveFailures = 0;
      errorState = null;
      nextAttemptNanos = now;
    }
    if (active != null) {
      supervise(now, decision, req);
      return;
    }
    if (!decision.record()) {
      idle(now, decision);
      return;
    }
    if (limitLatched) {
      publish(
          RecorderState.SESSION_LIMIT,
          "SESSION_LIMIT: maximum session length reached; disable the robot and clear"
              + " ManualRecord, then request recording again to start a new session",
          false);
      return;
    }
    if (errorState != null && now - nextAttemptNanos < 0) {
      publishError(now);
      return;
    }
    tryStart(now, req);
  }

  /** Stops any recording (bounded) and releases the pool lock. Worker thread only. */
  void shutdown() {
    if (closed) {
      return;
    }
    if (active != null) {
      stopActive(
          clock.getAsLong(),
          request,
          StopKind.SHUTDOWN,
          RecorderState.CLOSED,
          "robot program shutting down");
    }
    store.close();
    closed = true;
    publish(RecorderState.CLOSED, "Recorder closed", false);
  }

  /** Last-resort kill from another thread if the worker failed to stop in time. */
  void emergencyKill() {
    ManagedProcess p = liveProcess.get();
    if (p != null) {
      p.kill();
    }
  }

  /** Called by the worker if {@link #step()} throws. Fails safe by killing FFmpeg. */
  void onInternalError(Throwable t) {
    try {
      if (active != null) {
        active.process.kill();
      }
    } catch (RuntimeException ignored) {
      // Already failing; best-effort.
    }
    active = null;
    liveProcess.set(null);
    long now = clock.getAsLong();
    errorState = RecorderState.INTERNAL_ERROR;
    errorDetail = "INTERNAL_ERROR: " + t;
    nextAttemptNanos = now + config.maxRetryBackoffNanos();
    publishError(now);
  }

  private void idle(long now, RecordingPolicy.Decision decision) {
    if (decision.state() == RecorderState.DISABLED
        || decision.state() == RecorderState.HEARTBEAT_LOST) {
      publish(decision.state(), decision.reason(), false);
      return;
    }
    if (now - nextReadinessNanos >= 0) {
      readiness = checkReadiness();
      nextReadinessNanos = now + config.retryIntervalNanos();
    }
    publish(decision.state(), decision.reason() + "; " + readiness, false);
  }

  private String checkReadiness() {
    try {
      store.open();
    } catch (StorageException e) {
      return "not ready: " + e.getMessage();
    }
    if (ffmpeg == null) {
      try {
        ffmpeg = probeFfmpeg();
      } catch (ProbeException e) {
        return "not ready: " + e.getMessage();
      }
    }
    CameraLocator.Lookup cam = locator.locate(config.device());
    if (cam.kind() != CameraLocator.Kind.FOUND) {
      modeVerifiedDevice = null;
      return "not ready: " + cam.detail();
    }
    return "ready: camera " + cam.devicePath() + ", FFmpeg " + ffmpeg.version();
  }

  private FfmpegInfo probeFfmpeg() throws ProbeException {
    return probe.probeFfmpeg(config.ffmpegPath(), commandBuilder.usesV4l2(), !config.streamCopy());
  }

  private void tryStart(long now, RecorderRequest req) {
    try {
      store.open();
    } catch (StorageException e) {
      fail(now, RecorderState.STORAGE_ERROR, e.getMessage(), false);
      return;
    }
    if (ffmpeg == null) {
      try {
        ffmpeg = probeFfmpeg();
      } catch (ProbeException e) {
        fail(now, RecorderState.FFMPEG_ERROR, e.getMessage(), false);
        return;
      }
    }
    CameraLocator.Lookup cam = locator.locate(config.device());
    switch (cam.kind()) {
      case MISSING, AMBIGUOUS -> {
        modeVerifiedDevice = null;
        fail(now, RecorderState.WAITING_FOR_CAMERA, cam.detail(), false);
        return;
      }
      case NO_PERMISSION -> {
        fail(now, RecorderState.CAMERA_ERROR, cam.detail(), false);
        return;
      }
      case FOUND -> {}
    }
    String device = cam.devicePath();
    if (commandBuilder.usesV4l2() && !device.equals(modeVerifiedDevice)) {
      ModeCheck mc = probe.checkMode(ffmpeg, device, config);
      switch (mc.result()) {
        case UNSUPPORTED -> {
          fail(now, RecorderState.CAMERA_ERROR, mc.detail(), true);
          return;
        }
        case BUSY -> {
          fail(now, RecorderState.CAMERA_ERROR, busyMessage(device, mc.detail()), false);
          return;
        }
        case PERMISSION -> {
          fail(
              now,
              RecorderState.CAMERA_ERROR,
              "PERMISSION_DENIED: cannot open "
                  + device
                  + "; the robot program user needs read/write access (e.g. 'video' group)",
              false);
          return;
        }
        case MISSING -> {
          fail(
              now,
              RecorderState.WAITING_FOR_CAMERA,
              "CAMERA_DISCONNECTED: " + device + " disappeared during mode check",
              false);
          return;
        }
        case NOT_CAPTURE_DEVICE -> {
          fail(
              now,
              RecorderState.CAMERA_ERROR,
              "NOT_CAPTURE_DEVICE: "
                  + device
                  + " is not a video capture node; use the camera's ...-video-index0 path",
              true);
          return;
        }
        case SUPPORTED, UNKNOWN -> {
          modeVerifiedDevice = device;
          modeNote = mc.detail();
        }
      }
    }
    Capacity capacity =
        store.ensureCapacity(
            config.maxSessionBytes(), null, config.poolBytes(), config.minFreeBytes());
    if (!capacity.ok()) {
      fail(now, RecorderState.STORAGE_ERROR, capacity.detail(), false);
      return;
    }
    SessionStore.Session session;
    try {
      session = store.createSession(wallClock.get());
    } catch (StorageException e) {
      fail(now, RecorderState.STORAGE_ERROR, e.getMessage(), false);
      return;
    }
    SessionMetadata meta = newMetadata(session, cam, req);
    List<String> command = commandBuilder.build(ffmpeg, config, device, session.dir());
    meta.command = List.copyOf(command);
    writeMetadata(session, meta);
    ManagedProcess process;
    try {
      process = launcher.launch(command, session.dir().resolve("ffmpeg.log"));
    } catch (IOException e) {
      meta.status = "failed";
      meta.outcome = "launch_failed";
      meta.detail = "FFMPEG_LAUNCH_FAILED: " + e.getMessage();
      meta.endWallTime = wallClock.get();
      writeMetadata(session, meta);
      store.writeLastFailure(meta.toJson());
      store.discardEmptySession(session);
      ffmpeg = null; // Re-probe next time; the executable may have been removed.
      fail(now, RecorderState.FFMPEG_ERROR, meta.detail, true);
      return;
    }
    liveProcess.set(process);
    active = new Active(session, meta, process, device, now);
    lastSessionId = session.id();
    lastFrames = 0;
    publish(
        RecorderState.STARTING,
        "FFmpeg started on " + device + " (" + config.describeCapture() + "); waiting for frames",
        false);
  }

  private void supervise(long now, RecordingPolicy.Decision decision, RecorderRequest req) {
    Active a = active;
    FfmpegOutputMonitor monitor = a.process.monitor();
    long frames = monitor.frames();
    if (frames > a.lastFrames) {
      a.lastFrames = frames;
      a.lastAdvanceNanos = now;
      if (!a.firstFrame) {
        a.firstFrame = true;
        a.meta.firstFrameWallTime = wallClock.get();
        a.meta.robotTimestampAtFirstFrame = req == null ? null : req.robotTimestampSeconds();
        a.meta.secondsLaunchToFirstFrame = seconds(now - a.launchNanos);
        consecutiveFailures = 0;
        errorState = null;
        writeMetadata(a.session, a.meta);
      }
    }
    lastFrames = a.lastFrames;

    if (!a.process.isAlive()) {
      onProcessExited(now, req);
      return;
    }
    if (!decision.record()) {
      stopActive(now, req, StopKind.REQUEST, decision.state(), decision.reason());
      return;
    }
    Problem problem = monitor.problem();
    if (problem.fatal()) {
      stopActive(
          now,
          req,
          StopKind.FAILURE,
          problemState(problem),
          problemMessage(problem, a.device, monitor.problemLine()));
      return;
    }
    long elapsed = now - a.launchNanos;
    if (elapsed >= config.maxSessionNanos()) {
      stopActive(
          now,
          req,
          StopKind.LIMIT,
          RecorderState.SESSION_LIMIT,
          "SESSION_LIMIT: reached maxSessionSeconds="
              + config.maxSessionSeconds()
              + "; disable the robot and clear ManualRecord to rearm");
      return;
    }
    if (!a.firstFrame && elapsed > config.startupTimeoutNanos()) {
      stopActive(
          now,
          req,
          StopKind.FAILURE,
          RecorderState.PROCESS_ERROR,
          "NO_FRAMES: "
              + a.device
              + " delivered no frames within "
              + config.startupTimeoutSeconds()
              + " s"
              + lastLineSuffix(monitor));
      return;
    }
    if (a.firstFrame && now - a.lastAdvanceNanos > config.stallTimeoutNanos()) {
      stopActive(
          now,
          req,
          StopKind.FAILURE,
          RecorderState.PROCESS_ERROR,
          "CAMERA_STALLED: no new frames from "
              + a.device
              + " for "
              + config.stallTimeoutSeconds()
              + " s"
              + lastLineSuffix(monitor));
      return;
    }
    if (now - a.nextStorageCheckNanos >= 0) {
      a.nextStorageCheckNanos = now + STORAGE_CHECK_INTERVAL_NANOS;
      if (SessionStore.directorySize(a.session.dir()) > config.maxSessionBytes()) {
        stopActive(
            now,
            req,
            StopKind.LIMIT,
            RecorderState.SESSION_LIMIT,
            "SESSION_SIZE_LIMIT: session reached maxSessionMiB="
                + config.maxSessionMiB()
                + "; disable the robot and clear ManualRecord to rearm");
        return;
      }
      long free = store.usableBytes();
      if (free < config.minFreeBytes()) {
        stopActive(
            now,
            req,
            StopKind.FAILURE,
            RecorderState.STORAGE_ERROR,
            "LOW_DISK: free space "
                + SessionStore.mib(free)
                + " MiB fell below minFreeMiB="
                + config.minFreeMiB());
        return;
      }
    }
    String warnings =
        monitor.warnings().isEmpty() ? "" : "; " + String.join("; ", monitor.warnings());
    if (a.firstFrame) {
      publish(
          RecorderState.RECORDING,
          "Recording " + a.device + " (" + config.describeCapture() + ")" + warnings,
          true);
    } else {
      publish(
          RecorderState.STARTING,
          "FFmpeg started on " + a.device + "; waiting for frames" + warnings,
          false);
    }
  }

  private void onProcessExited(long now, RecorderRequest req) {
    Active a = active;
    drain(a.process);
    FfmpegOutputMonitor monitor = a.process.monitor();
    OptionalInt exit = a.process.exitCode();
    int code = exit.isPresent() ? exit.getAsInt() : -1;
    Problem problem = monitor.problem();
    if (code == 0
        && !problem.fatal()
        && now - a.launchNanos >= config.maxSessionNanos() - LIMIT_TOLERANCE_NANOS) {
      String detail =
          "SESSION_LIMIT: FFmpeg reached maxSessionSeconds="
              + config.maxSessionSeconds()
              + "; disable the robot and clear ManualRecord to rearm";
      finish(a, req, StopKind.LIMIT, "self", detail);
      limitLatched = true;
      publish(RecorderState.SESSION_LIMIT, detail, false);
      return;
    }
    String detail =
        problem.fatal()
            ? problemMessage(problem, a.device, monitor.problemLine())
            : "PROCESS_EXITED: FFmpeg exited unexpectedly with code "
                + code
                + lastLineSuffix(monitor);
    RecorderState state = problem.fatal() ? problemState(problem) : RecorderState.PROCESS_ERROR;
    finish(a, req, StopKind.FAILURE, "self", detail);
    afterFailure(now, state, problem, detail);
  }

  private void stopActive(
      long now, RecorderRequest req, StopKind kind, RecorderState nextState, String detail) {
    Active a = active;
    publish(RecorderState.STOPPING, "Stopping: " + detail, false);
    String method = stopProcess(a.process);
    Problem problem = a.process.monitor().problem();
    finish(a, req, kind, method, detail);
    switch (kind) {
      case REQUEST -> publish(nextState, detail, false);
      case LIMIT -> {
        limitLatched = true;
        publish(RecorderState.SESSION_LIMIT, detail, false);
      }
      case FAILURE -> afterFailure(now, nextState, problem, detail);
      case SHUTDOWN -> {}
    }
  }

  /** Bounded escalation: 'q' -> SIGTERM -> SIGKILL. */
  private String stopProcess(ManagedProcess p) {
    try {
      p.requestGracefulStop();
      if (p.waitForExit(config.stopTimeoutNanos())) {
        return "q";
      }
      p.terminate();
      if (p.waitForExit(TERMINATE_WAIT_NANOS)) {
        return "SIGTERM";
      }
      p.kill();
      if (p.waitForExit(KILL_WAIT_NANOS)) {
        return "SIGKILL";
      }
      return "SIGKILL (exit not confirmed)";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.kill();
      return "SIGKILL (interrupted)";
    }
  }

  private void finish(
      Active a, RecorderRequest req, StopKind kind, String stopMethod, String detail) {
    drain(a.process);
    FfmpegOutputMonitor monitor = a.process.monitor();
    long end = clock.getAsLong();
    SessionMetadata meta = a.meta;
    meta.frames = Math.max(a.lastFrames, monitor.frames());
    meta.dupFrames = monitor.dupFrames();
    meta.dropFrames = monitor.dropFrames();
    OptionalInt exit = a.process.exitCode();
    meta.exitCode = exit.isPresent() ? exit.getAsInt() : null;
    meta.stopMethod = stopMethod;
    meta.endWallTime = wallClock.get();
    meta.robotTimestampAtEnd = req == null ? null : req.robotTimestampSeconds();
    meta.secondsLaunchToEnd = seconds(end - a.launchNanos);
    for (String w : monitor.warnings()) {
      if (!meta.warnings.contains(w)) {
        meta.warnings.add(w);
      }
    }
    boolean graceful =
        stopMethod.equals("q") || stopMethod.equals("SIGTERM") || stopMethod.equals("self");
    boolean ok = kind != StopKind.FAILURE && graceful && meta.frames > 0;
    meta.status = ok ? "complete" : "failed";
    meta.outcome = kind.name().toLowerCase(Locale.ROOT);
    meta.detail = detail;
    meta.segments.clear();
    meta.segments.putAll(listSegments(a.session.dir()));
    writeMetadata(a.session, meta);
    lastFrames = meta.frames;
    if (meta.frames == 0) {
      StringBuilder report = new StringBuilder(meta.toJson()).append("\nFFmpeg output tail:\n");
      for (String line : monitor.tail()) {
        report.append(line).append('\n');
      }
      store.writeLastFailure(report.toString());
      if (store.discardEmptySession(a.session)) {
        lastSessionId = "";
      }
    }
    active = null;
    liveProcess.set(null);
  }

  private void afterFailure(long now, RecorderState state, Problem problem, String detail) {
    modeVerifiedDevice = null; // Re-verify the camera before the next attempt.
    if (problem == Problem.FFMPEG_FEATURE_MISSING) {
      ffmpeg = null;
    }
    boolean backoff =
        state == RecorderState.PROCESS_ERROR
            || problem == Problem.UNSUPPORTED_MODE
            || problem == Problem.NOT_CAPTURE_DEVICE
            || problem == Problem.FFMPEG_FEATURE_MISSING;
    fail(now, state, detail, backoff);
  }

  private void fail(long now, RecorderState state, String detail, boolean backoff) {
    long delay = config.retryIntervalNanos();
    if (backoff) {
      consecutiveFailures++;
      int shift = Math.min(consecutiveFailures - 1, 20);
      delay = Math.min(config.retryIntervalNanos() << shift, config.maxRetryBackoffNanos());
    }
    errorState = state;
    errorDetail = detail;
    nextAttemptNanos = now + delay;
    publishError(now);
  }

  private void publishError(long now) {
    double wait = Math.max(0, seconds(nextAttemptNanos - now));
    publish(
        errorState, errorDetail + String.format(Locale.ROOT, " (retry in %.1f s)", wait), false);
  }

  private SessionMetadata newMetadata(
      SessionStore.Session session, CameraLocator.Lookup cam, RecorderRequest req) {
    SessionMetadata meta = new SessionMetadata();
    meta.sessionId = session.id();
    meta.device = cam.devicePath();
    meta.deviceRealPath = realPath(cam.devicePath());
    meta.cameraSelection = cam.detail();
    meta.modeCheck = commandBuilder.usesV4l2() ? modeNote : "not applicable";
    meta.ffmpegExecutable = ffmpeg.executable();
    meta.ffmpegVersion = ffmpeg.version();
    meta.capture = config.describeCapture();
    meta.inputFormat = config.inputFormat().v4l2Name();
    meta.width = config.width();
    meta.height = config.height();
    meta.requestedFps = config.fps();
    meta.streamCopy = config.streamCopy();
    meta.segmentSeconds = config.segmentSeconds();
    meta.maxSessionSeconds = config.maxSessionSeconds();
    meta.launchWallTime = wallClock.get();
    meta.systemTimeValid = req != null && req.systemTimeValid();
    meta.robotTimestampAtLaunch = req == null ? null : req.robotTimestampSeconds();
    return meta;
  }

  private void writeMetadata(SessionStore.Session session, SessionMetadata meta) {
    try {
      store.writeMetadata(session, meta);
    } catch (IOException e) {
      meta.warnings.add("metadata write failed: " + e.getMessage());
    }
  }

  private static TreeMap<String, Long> listSegments(Path dir) {
    TreeMap<String, Long> segments = new TreeMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "seg_*.mkv")) {
      for (Path p : entries) {
        try {
          segments.put(p.getFileName().toString(), Files.size(p));
        } catch (IOException ignored) {
          // Vanished.
        }
      }
    } catch (IOException ignored) {
      // Directory unreadable; leave empty.
    }
    return segments;
  }

  private static void drain(ManagedProcess p) {
    try {
      p.awaitOutputDrained(OUTPUT_DRAIN_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String realPath(String device) {
    try {
      return Path.of(device).toRealPath().toString();
    } catch (IOException | RuntimeException e) {
      return "";
    }
  }

  private static RecorderState problemState(Problem p) {
    return switch (p) {
      case DEVICE_MISSING -> RecorderState.WAITING_FOR_CAMERA;
      case NO_SPACE -> RecorderState.STORAGE_ERROR;
      case FFMPEG_FEATURE_MISSING -> RecorderState.FFMPEG_ERROR;
      case NONE -> RecorderState.PROCESS_ERROR;
      default -> RecorderState.CAMERA_ERROR;
    };
  }

  private String problemMessage(Problem p, String device, String line) {
    return switch (p) {
      case CAMERA_BUSY -> busyMessage(device, line);
      case PERMISSION_DENIED -> "PERMISSION_DENIED: " + line;
      case DEVICE_MISSING -> "CAMERA_DISCONNECTED: " + device + ": " + line;
      case UNSUPPORTED_MODE ->
          "UNSUPPORTED_MODE: camera did not accept " + config.describeCapture() + ": " + line;
      case NOT_CAPTURE_DEVICE -> "NOT_CAPTURE_DEVICE: " + device + ": " + line;
      case NO_SPACE -> "NO_SPACE: " + line;
      case FFMPEG_FEATURE_MISSING -> "FFMPEG_FEATURES_MISSING: " + line;
      case NONE -> "PROCESS_ERROR";
    };
  }

  private static String busyMessage(String device, String detail) {
    return "CAMERA_BUSY: "
        + device
        + " is in use by another process. If SystemCore's built-in vision is capturing this USB"
        + " port, disable USB camera capture for that port in the SystemCore web UI Config tab ("
        + detail
        + ")";
  }

  private static String lastLineSuffix(FfmpegOutputMonitor m) {
    String last = m.lastLine();
    return last.isEmpty() ? "" : "; last FFmpeg output: " + last;
  }

  private void publish(RecorderState state, String detail, boolean recording) {
    String bounded =
        detail.length() > MAX_DETAIL_CHARS ? detail.substring(0, MAX_DETAIL_CHARS) + "..." : detail;
    Active a = active;
    status =
        new RecorderStatus(
            state,
            bounded,
            a != null ? a.session.id() : lastSessionId,
            a != null ? a.lastFrames : lastFrames,
            recording);
  }

  private static double seconds(long nanos) {
    return nanos / 1e9;
  }
}
