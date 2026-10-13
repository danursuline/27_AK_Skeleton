package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import first.robot.util.HubIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.wpilib.hardware.hal.HAL;

class TestHubMarkersTest {
  /** Match AdvantageKit's constructor-stack guard without constructing robot hardware. */
  static final class LoggingFixture extends org.littletonrobotics.junction.LoggedRobot {
    static void startLogger() {
      Logger.start();
    }
  }

  static String request(String delivery, int revision, String boot) {
    var note = new JsonObject();
    note.addProperty("schema_version", 1);
    note.addProperty("event_id", "event-1");
    note.addProperty("revision", revision);
    note.addProperty("submitted_utc_ns", "1800000030000000001");
    note.addProperty("client_monotonic_ns", "9007199254740993");
    note.addProperty("event_utc_start_ns", "1800000000000000001");
    note.addProperty("event_utc_end_ns", "1800000000000000001");
    note.add("when", JsonParser.parseString("{\"kind\":\"seconds_ago\",\"seconds\":30}"));
    note.addProperty("uncertainty_ms", 2000);
    note.addProperty("clock_domain", "client-browser");
    note.addProperty("clock_quality", "unverified_client");
    note.addProperty("text", "Left module shuddered 🔧");
    note.add("tags", JsonParser.parseString("[\"drive\"]"));
    note.addProperty("source", "practice-notebook");
    String payload = note.toString();
    var outer = new JsonObject();
    outer.addProperty("schema_version", 1);
    outer.addProperty("profile", MarkerProtocol.PROFILE);
    outer.addProperty("delivery_id", delivery);
    outer.addProperty("destination_robot_id", "6391-practice");
    outer.addProperty("destination_boot_id", boot);
    outer.addProperty("event_id", "event-1");
    outer.addProperty("revision", revision);
    outer.addProperty("annotation_sha256", "a".repeat(64));
    outer.addProperty("payload_sha256", HubIdentity.sha256(payload));
    outer.addProperty("payload_json", payload);
    return outer.toString();
  }

  static final class FakeIO implements MarkerIO {
    final ArrayDeque<MarkerProtocol.Request> pending = new ArrayDeque<>();
    final List<String> acks = new ArrayList<>();

    void send(String wire) {
      pending.add(MarkerProtocol.parse(wire));
    }

    @Override
    public MarkerProtocol.Request poll() {
      return pending.poll();
    }

    @Override
    public void acknowledge(String json) {
      acks.add(json);
    }

    JsonObject ack(int i) {
      return JsonParser.parseString(acks.get(i)).getAsJsonObject();
    }
  }

  @Test
  void actualLoggerNoOpDoesNotReserveIdentityThenLiveAdmissionDoes() {
    assertTrue(HAL.initialize());
    var io = new FakeIO();
    var marker =
        new TestHubMarkers(
            "SIM",
            "6391-practice",
            "boot-a",
            true,
            io,
            () -> 9007199254740995L,
            () -> true,
            v -> Logger.processInputs("MarkerTest", v));
    try {
      io.send(request("delivery-a", 1, "boot-a"));
      marker.periodic();
      assertEquals("log_input_unavailable", io.ack(0).get("reason").getAsString());
      List<LogTable> tables = new ArrayList<>();
      Logger.addDataReceiver(table -> tables.add(table));
      LoggingFixture.startLogger();
      io.send(request("delivery-a", 1, "boot-a"));
      marker.periodic();
      assertEquals("accepted_into_log_input", io.ack(1).get("state").getAsString());
      assertEquals("9007199254740995", io.ack(1).get("receipt_robot_ns").getAsString());
      assertTrue(io.ack(1).get("logger_queue_fault").getAsBoolean());
      assertEquals("unavailable", io.ack(1).get("usb_durability").getAsString());
      io.send(request("delivery-new", 1, "boot-a"));
      marker.periodic();
      assertTrue(io.ack(2).get("duplicate").getAsBoolean());
      assertEquals(io.ack(1).get("receipt_robot_ns"), io.ack(2).get("receipt_robot_ns"));
      assertEquals("delivery-new", io.ack(2).get("delivery_id").getAsString());
    } finally {
      marker.close();
      Logger.end();
    }
  }

  @Test
  void admissionWrapperRoundTripsOriginalNanosecondStringsAndEmptyNextCycle() {
    var io = new FakeIO();
    List<LogTable> tables = new ArrayList<>();
    var marker =
        new TestHubMarkers(
            "REAL",
            "6391-practice",
            "boot-a",
            true,
            io,
            () -> 80000000000L,
            () -> false,
            v -> {
              var table = new LogTable(123);
              v.toLog(table);
              tables.add(table);
            });
    io.send(request("delivery-a", 1, "boot-a"));
    marker.periodic();
    marker.periodic();
    var restored = new MarkerIOInputsAutoLogged();
    restored.fromLog(tables.get(0));
    assertEquals(1, restored.acceptedEnvelopes.length);
    var recorded = JsonParser.parseString(restored.acceptedEnvelopes[0]).getAsJsonObject();
    assertEquals(request("delivery-a", 1, "boot-a"), recorded.get("request_json").getAsString());
    assertEquals("80000000000", recorded.get("receipt_robot_ns").getAsString());
    var admission = new TestHubMarkers.Admission(restored);
    admission.fromLog(tables.get(1));
    assertFalse(admission.admitted());
    assertEquals(0, restored.acceptedEnvelopes.length);
    marker.close();
  }

  @Test
  void replayAndDefaultOffNeverConsumeOrAcknowledgePendingNotes() {
    for (String mode : new String[] {"REPLAY", "SIM"}) {
      var io = new FakeIO();
      io.send(request("delivery-a", 1, "boot-a"));
      var marker =
          new TestHubMarkers(
              mode,
              "6391-practice",
              "boot-a",
              mode.equals("REPLAY"),
              io,
              () -> {
                fail("receipt clock should not run");
                return 0;
              },
              () -> false,
              v -> v.fromLog(new LogTable(1)));
      marker.periodic();
      assertEquals(1, io.pending.size());
      assertTrue(io.acks.isEmpty());
      marker.close();
    }
  }

  @Test
  void oneCandidatePerCycleAndWrongBootCannotReserveCapacity() {
    var io = new FakeIO();
    var marker =
        new TestHubMarkers(
            "SIM",
            "6391-practice",
            "boot-a",
            true,
            io,
            () -> 1L,
            () -> false,
            v -> v.toLog(new LogTable(1)));
    io.send(request("delivery-a", 1, "boot-b"));
    io.send(request("delivery-b", 1, "boot-a"));
    marker.periodic();
    assertEquals(1, io.pending.size());
    assertEquals("wrong_boot", io.ack(0).get("reason").getAsString());
    marker.periodic();
    assertEquals("accepted_into_log_input", io.ack(1).get("state").getAsString());
    assertFalse(io.ack(1).get("duplicate").getAsBoolean());
    marker.close();
  }
}
