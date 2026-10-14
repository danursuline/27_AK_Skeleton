package first.robot.video;

/** Top-level recorder state, published as {@code /VideoRecorder/State}. */
public enum RecorderState {
  /** Recorder disabled by {@code enabled=false} in the configuration. */
  DISABLED,
  /** The configuration file is missing or invalid; nothing will be recorded. */
  CONFIG_ERROR,
  /** No recording requested (robot disabled and ManualRecord false). */
  IDLE,
  /** Recording is blocked by /VideoRecorder/Inhibit. */
  INHIBITED,
  /** Recording is blocked because the FMS is attached and FMS recording is not allowed. */
  FMS_BLOCKED,
  /** The robot loop stopped providing heartbeats; recording is stopped. */
  HEARTBEAT_LOST,
  /** The maximum session length was reached; release the request to rearm. */
  SESSION_LIMIT,
  /** FFmpeg is missing or lacks required features. Retrying periodically. */
  FFMPEG_ERROR,
  /** No usable camera found (missing or ambiguous). Retrying periodically. */
  WAITING_FOR_CAMERA,
  /** Camera present but unusable (busy, permission, unsupported mode). */
  CAMERA_ERROR,
  /** Output directory, lock, or free-space problem. */
  STORAGE_ERROR,
  /** FFmpeg launched; waiting for the first frame. */
  STARTING,
  /** Frames are actively arriving and being written. */
  RECORDING,
  /** Finalizing the current session. */
  STOPPING,
  /** FFmpeg exited unexpectedly or the camera stalled. Retrying with backoff. */
  PROCESS_ERROR,
  /** Unexpected internal exception in the recorder worker (robot code is unaffected). */
  INTERNAL_ERROR,
  /** The recorder has been shut down. */
  CLOSED
}
