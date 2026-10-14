package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordingPolicyTest {
  private static final VideoRecorderConfig DEFAULTS = VideoRecorderConfig.defaults();

  private static RecorderRequest req(
      boolean enabled, boolean fms, boolean manual, boolean inhibit) {
    return new RecorderRequest(enabled, fms, manual, inhibit, 0, 0, true);
  }

  private static RecordingPolicy.Decision eval(VideoRecorderConfig c, RecorderRequest r) {
    return RecordingPolicy.evaluate(c, r, true);
  }

  @Test
  void recordsWhenEnabledAndStopsWhenDisabled() {
    assertTrue(eval(DEFAULTS, req(true, false, false, false)).record());
    RecordingPolicy.Decision off = eval(DEFAULTS, req(false, false, false, false));
    assertFalse(off.record());
    assertFalse(off.requested());
    assertEquals(RecorderState.IDLE, off.state());
  }

  @Test
  void manualRecordsWhileDisabled() {
    assertTrue(eval(DEFAULTS, req(false, false, true, false)).record());
  }

  @Test
  void inhibitOverridesEverything() {
    for (boolean enabled : new boolean[] {false, true}) {
      for (boolean manual : new boolean[] {false, true}) {
        RecordingPolicy.Decision d = eval(DEFAULTS, req(enabled, false, manual, true));
        assertFalse(d.record());
        assertEquals(RecorderState.INHIBITED, d.state());
      }
    }
  }

  @Test
  void fmsBlocksByDefaultAndCanBeOptedIn() throws Exception {
    RecordingPolicy.Decision blocked = eval(DEFAULTS, req(true, true, true, false));
    assertFalse(blocked.record());
    assertEquals(RecorderState.FMS_BLOCKED, blocked.state());
    assertTrue(blocked.requested(), "request is still known so latches behave consistently");

    VideoRecorderConfig optIn = VideoRecorderConfig.parse(Map.of("allowRecordingWithFms", "true"));
    assertTrue(eval(optIn, req(true, true, false, false)).record());
  }

  @Test
  void staleOrMissingHeartbeatNeverRecords() {
    RecordingPolicy.Decision stale =
        RecordingPolicy.evaluate(DEFAULTS, req(true, false, true, false), false);
    assertFalse(stale.record());
    assertFalse(stale.requestKnown());
    assertEquals(RecorderState.HEARTBEAT_LOST, stale.state());
    assertFalse(RecordingPolicy.evaluate(DEFAULTS, null, true).record());
  }

  @Test
  void autoRecordCanBeTurnedOff() throws Exception {
    VideoRecorderConfig manualOnly =
        VideoRecorderConfig.parse(Map.of("autoRecordWhenEnabled", "false"));
    assertFalse(eval(manualOnly, req(true, false, false, false)).record());
    assertTrue(eval(manualOnly, req(true, false, true, false)).record());
  }

  @Test
  void disabledByConfigNeverRecords() throws Exception {
    VideoRecorderConfig off = VideoRecorderConfig.parse(Map.of("enabled", "false"));
    RecordingPolicy.Decision d = eval(off, req(true, false, true, false));
    assertFalse(d.record());
    assertEquals(RecorderState.DISABLED, d.state());
  }
}
