package first.robot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TestHubStatusTest {
  @Test
  void bootAndRunsAreIndependentAndAutoTeleopKeepsOneRun() {
    var state = new TestHubStatus.State("6391-practice", "SIM");
    var reboot = new TestHubStatus.State("6391-practice", "SIM");
    assertNotEquals(state.bootId, reboot.bootId);
    state.update(false, "disabled", true, 10);
    long generation = state.generation;
    state.update(false, "disabled", true, 20);
    assertEquals(generation, state.generation);
    assertEquals(2, state.sequence);
    state.update(true, "autonomous", false, 30);
    String first = state.runId;
    assertNotNull(first);
    assertTrue(state.runStartComplete);
    assertEquals(generation + 1, state.generation);
    state.update(true, "teleoperated", false, 40);
    assertEquals(first, state.runId);
    assertEquals(generation + 2, state.generation);
    state.update(false, "disabled", true, 50);
    assertNull(state.runId);
    state.update(true, "teleoperated", false, 60);
    assertNotEquals(first, state.runId);
  }

  @Test
  void unknownStateClosesRunAndReplayCannotPretendFreshDisabled() {
    var state = new TestHubStatus.State("6391-practice", "SIM");
    state.update(true, "autonomous", false, 1);
    assertFalse(state.runStartComplete);
    String previous = state.runId;
    state.update(null, "unknown", false, 2);
    assertNull(state.runId);
    state.update(true, "autonomous", false, 3);
    assertNotEquals(previous, state.runId);
    assertFalse(state.runStartComplete);
    state.update(false, "disabled", true, 4);
    long before = state.generation;
    state.update(false, "disabled", false, 5);
    assertEquals(before + 1, state.generation);
    state.update(false, "disabled", false, 6, "autonomous");
    long operatingGeneration = state.generation;
    state.update(false, "disabled", false, 7, "teleoperated");
    assertEquals(operatingGeneration + 1, state.generation);
    var replay = new TestHubStatus.State("6391-practice", "REPLAY");
    replay.update(null, "replay", false, 9007199254740993L);
    String json = replay.json(false, "hash", 1);
    assertTrue(json.contains("\"enabled\":null"));
    assertTrue(json.contains("\"transfer_allowed\":false"));
    assertTrue(json.contains("\"robot_monotonic_ns\":\"9007199254740993\""));
    assertTrue(json.contains("\"usb_write_health\":\"unavailable\""));
    assertTrue(json.contains("\"active_segment_id\":null"));
    assertTrue(
        replay
            .json(false, "hash", 1, "segment-123")
            .contains("\"active_segment_id\":\"segment-123\""));
  }

  @Test
  void canonicalConfigurationChangesAndGeneratedBuildEvidenceAreBound() {
    String first = HubIdentity.json(Map.of("gain", "1.0", "source", "a"));
    assertEquals(first, HubIdentity.json(Map.of("source", "a", "gain", "1.0")));
    assertNotEquals(
        HubIdentity.sha256(first),
        HubIdentity.sha256(HubIdentity.json(Map.of("gain", "2.0", "source", "a"))));
    assertEquals("\"quoted\\\"\\n\"", HubIdentity.quote("quoted\"\n"));
    var identity = new HubIdentity();
    assertTrue(identity.get("source_sha256").matches("[a-f0-9]{64}"));
    assertTrue(identity.get("config_sha256").matches("[a-f0-9]{64}"));
    assertTrue(identity.get("git_sha").matches("[a-f0-9]{40,64}"));
    assertNotEquals("2026-04-26 21:30:14 EDT", identity.get("build_utc"));
    assertThrows(IllegalArgumentException.class, () -> new TestHubStatus.State("../unsafe", "SIM"));
  }
}
