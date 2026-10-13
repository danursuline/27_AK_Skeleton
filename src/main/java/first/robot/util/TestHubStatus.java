package first.robot.util;

import first.robot.Constants;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.littletonrobotics.junction.Logger;
import org.wpilib.driverstation.RobotState;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.system.RobotController;
import org.wpilib.telemetry.Telemetry;

/** Passive identity/status instrumentation; no commands, file transfers, or actuator access. */
public final class TestHubStatus {
  public static final String STATUS_TOPIC = "/Telemetry/TestHub/Status";
  private final HubIdentity identity = new HubIdentity();
  private final State state;
  private final Supplier<String> selectedAuto;
  private String previousConfig = "";
  private String configHash = "";
  private long configRevision;

  public TestHubStatus(Supplier<String> selectedAuto) {
    String robotId = System.getProperty("first.robotId", "6391-practice");
    state = new State(robotId, Constants.currentMode.name());
    this.selectedAuto = selectedAuto;
  }

  public void recordMetadata() {
    Logger.recordMetadata("ProjectName", identity.get("project_name"));
    Logger.recordMetadata("BuildDate", identity.get("build_utc"));
    Logger.recordMetadata("GitSHA", identity.get("git_sha"));
    Logger.recordMetadata("GitBranch", identity.get("git_branch"));
    Logger.recordMetadata("GitDirty", identity.get("git_dirty"));
    Logger.recordMetadata("SourceSHA256", identity.get("source_sha256"));
    Logger.recordMetadata("FixedConfigurationSHA256", identity.get("config_sha256"));
    Logger.recordMetadata("SourceSnapshotReference", identity.get("snapshot_reference"));
    Logger.recordMetadata("ArtifactHashReference", identity.get("artifact_hash_reference"));
    Logger.recordMetadata("ArtifactSHA256", identity.artifactSha256());
    Logger.recordMetadata("LibraryVersions", identity.get("library_versions"));
    Logger.recordMetadata("RobotId", state.robotId);
    Logger.recordMetadata("BootId", state.bootId);
    Logger.recordMetadata("RuntimeMode", state.runtimeMode);
    Logger.recordMetadata("FieldFrame", "2026-REBUILT-blue-origin");
    Logger.recordMetadata("HubSchemaVersion", "1");
  }

  /** Immutable boot identity shared by passive instrumentation, never a second boot UUID. */
  public String robotId() {
    return state.robotId;
  }

  public String bootId() {
    return state.bootId;
  }

  /** Called after normal scheduler work; still called during every disabled loop. */
  public void periodic() {
    periodic(null);
  }

  /** The receiver's immutable snapshot identifies the file currently being written. */
  public void periodic(String activeSegmentId) {
    boolean replay = Constants.currentMode == Constants.Mode.REPLAY;
    boolean known =
        !replay && RobotState.isDSAttached() && RobotState.getRobotMode() != RobotMode.UNKNOWN;
    Boolean enabled = known ? RobotState.isEnabled() : null;
    String mode =
        replay
            ? "replay"
            : !known
                ? "unknown"
                : !enabled
                    ? "disabled"
                    : RobotState.getRobotMode().name().toLowerCase(java.util.Locale.ROOT);
    boolean allowed = known && !enabled && !RobotState.isEStopped();
    state.update(
        enabled,
        mode,
        allowed,
        RobotController.getMonotonicTime(),
        RobotState.getRobotMode().name().toLowerCase(java.util.Locale.ROOT));

    var config = new TreeMap<>(HubConfiguration.snapshot());
    config.put("runtime_mode", state.runtimeMode);
    config.put("robot_id", state.robotId);
    config.put("source_sha256", identity.get("source_sha256"));
    config.put("fixed_config_sha256", identity.get("config_sha256"));
    config.put("selected_autonomous", selectedAuto.get());
    config.put("tuning_enabled", Boolean.toString(Constants.tuningMode));
    String canonical = HubIdentity.json(config);
    if (!canonical.equals(previousConfig)) {
      previousConfig = canonical;
      configHash = HubIdentity.sha256(canonical);
      configRevision++;
      Logger.recordOutput("TestHub/ConfigurationSnapshot", canonical);
      Telemetry.log("TestHub/ConfigurationSnapshot", canonical);
    }
    boolean queueFault = Logger.getReceiverQueueFault();
    String json = state.json(queueFault, configHash, configRevision, activeSegmentId);
    // Atomic envelope avoids mixing independently arriving mode/generation topics.
    Telemetry.log("TestHub/Status", json);
    Logger.recordOutput("TestHub/Status", json);
    Logger.recordOutput("TestHub/RobotId", state.robotId);
    Logger.recordOutput("TestHub/BootId", state.bootId);
    Logger.recordOutput("TestHub/RunId", state.runId == null ? "" : state.runId);
    Logger.recordOutput("TestHub/RunActive", state.runId != null);
    Logger.recordOutput("TestHub/RunStartComplete", state.runStartComplete);
    Logger.recordOutput("TestHub/StateKnown", enabled != null);
    Logger.recordOutput("TestHub/Enabled", Boolean.TRUE.equals(enabled));
    Logger.recordOutput("TestHub/Mode", mode);
    Logger.recordOutput("TestHub/OperatingMode", state.previousOperatingMode);
    Logger.recordOutput("TestHub/ModeGeneration", state.generation);
    Logger.recordOutput("TestHub/Sequence", state.sequence);
    Logger.recordOutput("TestHub/RobotMonotonicNs", state.monotonicNs);
    Logger.recordOutput("TestHub/RobotMonotonicNsUnit", "nanoseconds");
    Logger.recordOutput("TestHub/TransferAllowed", allowed);
    Logger.recordOutput("TestHub/RuntimeMode", state.runtimeMode);
    Logger.recordOutput("TestHub/ConfigurationSHA256", configHash);
    Logger.recordOutput("TestHub/ConfigurationRevision", configRevision);
    Logger.recordOutput("TestHub/LoggerQueueFault", queueFault);
    Logger.recordOutput("TestHub/UsbWriteHealth", "unavailable");
  }

  /** Pure lifecycle state so transitions can be checked without running any robot behavior. */
  public static final class State {
    public final String robotId, bootId, runtimeMode;
    public String runId;
    public boolean runStartComplete;
    public long sequence, generation, monotonicNs;
    private Boolean previousEnabled;
    private String previousMode;
    private String previousOperatingMode;
    private boolean previousAllowed;

    public State(String robotId, String runtimeMode) {
      if (robotId == null || !robotId.matches("[A-Za-z0-9_-]{1,100}")) {
        throw new IllegalArgumentException(
            "first.robotId must be 1 to 100 letters, digits, hyphens, or underscores");
      }
      this.robotId = robotId;
      this.runtimeMode = runtimeMode;
      this.bootId = UUID.randomUUID().toString();
    }

    public void update(Boolean enabled, String mode, boolean allowed, long monotonicNs) {
      update(enabled, mode, allowed, monotonicNs, mode);
    }

    public void update(
        Boolean enabled, String mode, boolean allowed, long monotonicNs, String operatingMode) {
      if (Boolean.TRUE.equals(enabled) && !Boolean.TRUE.equals(previousEnabled)) {
        runId = UUID.randomUUID().toString();
        runStartComplete = Boolean.FALSE.equals(previousEnabled);
      } else if (!Boolean.TRUE.equals(enabled)) {
        runId = null;
        runStartComplete = false;
      }
      if (!Objects.equals(enabled, previousEnabled)
          || !Objects.equals(mode, previousMode)
          || !Objects.equals(operatingMode, previousOperatingMode)
          || allowed != previousAllowed) generation++;
      previousEnabled = enabled;
      previousMode = mode;
      previousOperatingMode = operatingMode;
      previousAllowed = allowed;
      this.monotonicNs = monotonicNs;
      sequence++;
    }

    public String json(boolean queueFault, String configHash, long configRevision) {
      return json(queueFault, configHash, configRevision, null);
    }

    public String json(
        boolean queueFault, String configHash, long configRevision, String activeSegmentId) {
      return "{\"schema_version\":1,\"robot_id\":"
          + HubIdentity.quote(robotId)
          + ",\"boot_id\":"
          + HubIdentity.quote(bootId)
          + ",\"run_id\":"
          + HubIdentity.quote(runId)
          + ",\"sequence\":"
          + sequence
          + ",\"mode_generation\":"
          + generation
          + ",\"enabled\":"
          + previousEnabled
          + ",\"mode\":"
          + HubIdentity.quote(previousMode)
          + ",\"operating_mode\":"
          + HubIdentity.quote(previousOperatingMode)
          + ",\"robot_monotonic_ns\":"
          + HubIdentity.quote(Long.toString(monotonicNs))
          + ",\"transfer_allowed\":"
          + previousAllowed
          + ",\"active_segment_id\":"
          + HubIdentity.quote(activeSegmentId)
          + ",\"runtime_mode\":"
          + HubIdentity.quote(runtimeMode)
          + ",\"logger_queue_fault\":"
          + queueFault
          + ",\"usb_write_health\":\"unavailable\""
          + ",\"configuration_sha256\":"
          + HubIdentity.quote(configHash)
          + ",\"configuration_revision\":"
          + configRevision
          + "}";
    }
  }
}
