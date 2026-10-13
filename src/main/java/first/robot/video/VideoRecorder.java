package first.robot.video;

import first.robot.Constants;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;
import org.wpilib.driverstation.RobotState;
import org.wpilib.networktables.BooleanEntry;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.system.Filesystem;
import org.wpilib.system.RobotController;
import org.wpilib.system.Timer;

/**
 * Records unannotated video from a USB camera to SystemCore storage using FFmpeg.
 *
 * <p>All blocking work (camera discovery, FFmpeg probing and supervision, disk checks, pruning,
 * shutdown) runs on a dedicated daemon worker thread. {@link #periodic()} only reads two
 * NetworkTables booleans, hands an immutable request/heartbeat to the worker, and publishes the
 * latest status snapshot, so it never blocks the robot loop.
 *
 * <p>NetworkTables interface (under {@code /VideoRecorder}): writable {@code ManualRecord} and
 * {@code Inhibit} (both reset to false at startup); read-only {@code State}, {@code Detail}, {@code
 * Session}, {@code Frames}, {@code Recording}.
 */
public final class VideoRecorder implements AutoCloseable {
  /** Name of the configuration file in the deploy directory. */
  public static final String CONFIG_FILE_NAME = "video-recorder.properties";

  static final String NT_PREFIX = "/VideoRecorder/";
  static final String LOG_PREFIX = "VideoRecorder/";
  private static final long LOOP_PERIOD_MS = 100;

  private final RecorderController controller;
  private final RecorderStatus fixedStatus;
  private final BooleanEntry manualRecord;
  private final BooleanEntry inhibit;
  private final StringPublisher statePub;
  private final StringPublisher detailPub;
  private final StringPublisher sessionPub;
  private final IntegerPublisher framesPub;
  private final BooleanPublisher recordingPub;
  private final Thread worker;
  private final Thread shutdownHook;
  private final Object wake = new Object();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile boolean stopRequested;

  /**
   * Creates and starts the recorder only in {@link Constants.Mode#REAL}. Returns null in SIM and
   * REPLAY, so no camera process, recording directory, or pruning ever happens there. Never throws.
   */
  public static VideoRecorder createForMode(Constants.Mode mode) {
    return createIfReal(mode, VideoRecorder::createFromDeployDirectory);
  }

  /** Invokes the factory only for REAL mode; failures are reported and yield null. */
  static <T> T createIfReal(Constants.Mode mode, Supplier<T> factory) {
    if (mode != Constants.Mode.REAL) {
      return null;
    }
    try {
      return factory.get();
    } catch (RuntimeException e) {
      System.err.println("[VideoRecorder] Failed to start; robot code continues: " + e);
      return null;
    }
  }

  private static VideoRecorder createFromDeployDirectory() {
    NetworkTableInstance nt = NetworkTableInstance.getDefault();
    Path configFile = Filesystem.getDeployDirectory().toPath().resolve(CONFIG_FILE_NAME);
    VideoRecorderConfig config;
    try {
      config = VideoRecorderConfig.load(configFile);
    } catch (VideoRecorderConfig.ConfigException e) {
      return new VideoRecorder(
          nt, null, RecorderStatus.of(RecorderState.CONFIG_ERROR, e.getMessage()));
    }
    if (!config.enabled()) {
      return new VideoRecorder(
          nt, null, RecorderStatus.of(RecorderState.DISABLED, "Disabled by configuration"));
    }
    return new VideoRecorder(nt, RecorderController.forRealHardware(config), null);
  }

  /**
   * @param nt NetworkTables instance
   * @param controller controller to run on the worker thread, or null for a fixed status
   * @param fixedStatus status to publish when there is no controller
   */
  VideoRecorder(
      NetworkTableInstance nt, RecorderController controller, RecorderStatus fixedStatus) {
    this.controller = controller;
    this.fixedStatus = fixedStatus;

    manualRecord = nt.getBooleanTopic(NT_PREFIX + "ManualRecord").getEntry(false);
    inhibit = nt.getBooleanTopic(NT_PREFIX + "Inhibit").getEntry(false);
    // Controls always start released when robot code starts.
    manualRecord.set(false);
    inhibit.set(false);
    statePub = nt.getStringTopic(NT_PREFIX + "State").publish();
    detailPub = nt.getStringTopic(NT_PREFIX + "Detail").publish();
    sessionPub = nt.getStringTopic(NT_PREFIX + "Session").publish();
    framesPub = nt.getIntegerTopic(NT_PREFIX + "Frames").publish();
    recordingPub = nt.getBooleanTopic(NT_PREFIX + "Recording").publish();
    publish(currentStatus());

    if (controller != null) {
      worker = new Thread(this::runWorker, "VideoRecorder");
      worker.setDaemon(true);
      worker.start();
      shutdownHook = new Thread(this::close, "VideoRecorder-shutdown");
      try {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
      } catch (IllegalStateException | SecurityException e) {
        // JVM already shutting down; close() will still be called by Robot.close().
      }
    } else {
      worker = null;
      shutdownHook = null;
    }
  }

  /** Robot-loop hook: exchanges state with the worker and acts as its heartbeat. Non-blocking. */
  public void periodic() {
    periodic(
        RobotState.isEnabled(),
        RobotState.isFMSAttached(),
        Timer.getTimestamp(),
        RobotController.isSystemTimeValid());
  }

  /** Testable core of {@link #periodic()}. */
  void periodic(
      boolean robotEnabled, boolean fmsAttached, double robotTimestamp, boolean systemTimeValid) {
    boolean manual = manualRecord.get();
    boolean inhibited = inhibit.get();
    if (controller != null && !closed.get()) {
      controller.submitRequest(
          new RecorderRequest(
              robotEnabled,
              fmsAttached,
              manual,
              inhibited,
              System.nanoTime(),
              robotTimestamp,
              systemTimeValid));
    }
    RecorderStatus status = currentStatus();
    publish(status);
    Logger.recordOutput(LOG_PREFIX + "ManualRecord", manual);
    Logger.recordOutput(LOG_PREFIX + "Inhibit", inhibited);
    Logger.recordOutput(LOG_PREFIX + "State", status.state().name());
    Logger.recordOutput(LOG_PREFIX + "Detail", status.detail());
    Logger.recordOutput(LOG_PREFIX + "Session", status.session());
    Logger.recordOutput(LOG_PREFIX + "Frames", status.frames());
    Logger.recordOutput(LOG_PREFIX + "Recording", status.recording());
  }

  /** Latest status snapshot. */
  public RecorderStatus status() {
    return currentStatus();
  }

  /**
   * Stops recording (bounded: graceful 'q', then SIGTERM, then SIGKILL) and releases resources.
   * Idempotent and safe to call from a shutdown hook. Never throws.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException | SecurityException ignored) {
        // Shutdown already in progress.
      }
    }
    if (worker != null) {
      stopRequested = true;
      synchronized (wake) {
        wake.notifyAll();
      }
      long budget = RecorderController.worstCaseStopMillis(controller.config());
      try {
        worker.join(budget);
        if (worker.isAlive()) {
          controller.emergencyKill();
          worker.join(1000);
        }
      } catch (InterruptedException e) {
        controller.emergencyKill();
        Thread.currentThread().interrupt();
      }
    }
    try {
      publish(currentStatus());
    } catch (RuntimeException ignored) {
      // NetworkTables may already be shut down.
    }
  }

  private RecorderStatus currentStatus() {
    return controller != null ? controller.status() : fixedStatus;
  }

  private void runWorker() {
    while (!stopRequested) {
      try {
        controller.step();
      } catch (Throwable t) {
        controller.onInternalError(t);
      }
      synchronized (wake) {
        if (!stopRequested) {
          try {
            wake.wait(LOOP_PERIOD_MS);
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    }
    try {
      controller.shutdown();
    } catch (Throwable t) {
      controller.emergencyKill();
    }
  }

  private void publish(RecorderStatus status) {
    statePub.set(status.state().name());
    detailPub.set(status.detail());
    sessionPub.set(status.session());
    framesPub.set(status.frames());
    recordingPub.set(status.recording());
  }
}
