package first.robot.video;

/** Checks external dependencies. Called only from the recorder worker thread. */
interface DependencyProbe {
  /** Thrown when FFmpeg is missing or unusable. */
  final class ProbeException extends Exception {
    ProbeException(String message) {
      super(message);
    }
  }

  /** Result category of a camera capture-mode check. */
  enum ModeResult {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN,
    BUSY,
    PERMISSION,
    MISSING,
    NOT_CAPTURE_DEVICE
  }

  /**
   * Capture-mode check result.
   *
   * @param result category
   * @param detail human-readable explanation
   */
  record ModeCheck(ModeResult result, String detail) {}

  /**
   * Locates and verifies FFmpeg.
   *
   * @param configuredPath configured executable name or absolute path
   * @param needV4l2 require the v4l2 input device
   * @param needMjpegEncoder require the mjpeg encoder (raw-input fallback)
   */
  FfmpegInfo probeFfmpeg(String configuredPath, boolean needV4l2, boolean needMjpegEncoder)
      throws ProbeException;

  /** Asks the camera which formats/sizes it advertises. Briefly opens the device. */
  ModeCheck checkMode(FfmpegInfo ffmpeg, String devicePath, VideoRecorderConfig config);
}
