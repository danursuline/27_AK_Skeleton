package first.robot.video;

import java.util.List;

/** Finds the camera device to record from. Implementations must not open the device. */
interface CameraLocator {
  /** Outcome of a camera lookup. */
  enum Kind {
    FOUND,
    MISSING,
    AMBIGUOUS,
    NO_PERMISSION
  }

  /**
   * Lookup result.
   *
   * @param kind outcome
   * @param devicePath selected device path (FOUND / NO_PERMISSION only)
   * @param detail human-readable explanation
   * @param candidates candidate devices considered (for AMBIGUOUS)
   */
  record Lookup(Kind kind, String devicePath, String detail, List<String> candidates) {
    static Lookup found(String path, String detail) {
      return new Lookup(Kind.FOUND, path, detail, List.of(path));
    }

    static Lookup missing(String detail) {
      return new Lookup(Kind.MISSING, null, detail, List.of());
    }
  }

  Lookup locate(String configuredDevice);
}
