package first.robot.video;

/** Pure start/stop policy. Has no side effects so it can be tested exhaustively. */
final class RecordingPolicy {
  private RecordingPolicy() {}

  /**
   * Result of evaluating the policy.
   *
   * @param requestKnown false when there is no fresh heartbeat (request cannot be trusted)
   * @param requested robot enabled (with auto-record) or ManualRecord, ignoring blockers
   * @param record whether a recording should be running right now
   * @param state state to report when not recording
   * @param reason human-readable reason when not recording
   */
  record Decision(
      boolean requestKnown,
      boolean requested,
      boolean record,
      RecorderState state,
      String reason) {}

  static Decision evaluate(
      VideoRecorderConfig config, RecorderRequest request, boolean heartbeatFresh) {
    if (!config.enabled()) {
      return new Decision(false, false, false, RecorderState.DISABLED, "Disabled by configuration");
    }
    if (request == null || !heartbeatFresh) {
      return new Decision(
          false,
          false,
          false,
          RecorderState.HEARTBEAT_LOST,
          "HEARTBEAT_LOST: no robot-loop heartbeat within "
              + config.heartbeatTimeoutSeconds()
              + " s; recording stopped");
    }
    boolean requested =
        request.manualRecord() || (config.autoRecordWhenEnabled() && request.robotEnabled());
    if (request.inhibit()) {
      return new Decision(
          true, requested, false, RecorderState.INHIBITED, "Inhibited by /VideoRecorder/Inhibit");
    }
    if (request.fmsAttached() && !config.allowRecordingWithFms()) {
      return new Decision(
          true,
          requested,
          false,
          RecorderState.FMS_BLOCKED,
          "FMS attached; set allowRecordingWithFms=true after hardware validation to record");
    }
    if (!requested) {
      return new Decision(true, false, false, RecorderState.IDLE, "Idle");
    }
    return new Decision(true, true, true, RecorderState.RECORDING, "");
  }
}
