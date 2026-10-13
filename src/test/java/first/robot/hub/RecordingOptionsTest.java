package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import first.robot.Constants;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RecordingOptionsTest {
  @Test
  void defaultsAndReplayDoNotSelectCollectorRecording() {
    assertNull(RecordingOptions.directory(Constants.Mode.REAL, ""));
    assertNull(RecordingOptions.directory(Constants.Mode.SIM, null));
    assertNull(RecordingOptions.directory(Constants.Mode.REPLAY, "relative-ignored-in-replay"));
  }

  @Test
  void explicitRealAndSimulationPathsMustBeAbsolute() {
    Path directory = Path.of("build", "reports", "testhub", "..", "recordings").toAbsolutePath();
    assertEquals(
        directory.normalize(),
        RecordingOptions.directory(Constants.Mode.REAL, directory.toString()));
    assertEquals(
        directory.normalize(),
        RecordingOptions.directory(Constants.Mode.SIM, directory.toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> RecordingOptions.directory(Constants.Mode.REAL, "relative"));
  }
}
