package first.robot.hub;

import first.robot.Constants;
import java.nio.file.Path;

/** Explicit recording selection. Replay never selects the physical collection receiver. */
public final class RecordingOptions {
  private RecordingOptions() {}

  public static Path directory(Constants.Mode mode, String configuredDirectory) {
    if (mode == Constants.Mode.REPLAY
        || configuredDirectory == null
        || configuredDirectory.isBlank()) {
      return null;
    }
    Path path = Path.of(configuredDirectory);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(
          "frc.testHubRecordingDir must be an explicit absolute directory");
    }
    return path.normalize();
  }
}
