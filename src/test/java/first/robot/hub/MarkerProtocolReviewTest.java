package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import first.robot.util.HubIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;

/** Independent protocol/admission goldens; no devices or native NT are constructed. */
class MarkerProtocolReviewTest {
  static String wire(String delivery, String event, String boot, String text) {
    JsonObject note = new JsonObject();
    note.addProperty("schema_version", 1);
    note.addProperty("event_id", event);
    note.addProperty("revision", 1);
    note.addProperty("submitted_utc_ns", "1790000000000000001");
    note.addProperty("client_monotonic_ns", "9007199254740993");
    note.addProperty("event_utc_start_ns", "1789999970000000001");
    note.addProperty("event_utc_end_ns", "1789999970000000001");
    JsonObject when = new JsonObject();
    when.addProperty("kind", "seconds_ago");
    when.addProperty("seconds", 30);
    note.add("when", when);
    note.addProperty("uncertainty_ms", 500);
    note.addProperty("clock_domain", "invented-client-clock");
    note.addProperty("clock_quality", "user_estimate");
    note.addProperty("text", text);
    note.add("tags", JsonParser.parseString("[\"synthetic\"]"));
    note.addProperty("source", "public-test");
    JsonObject request = new JsonObject();
    request.addProperty("schema_version", 1);
    request.addProperty("profile", MarkerProtocol.PROFILE);
    request.addProperty("delivery_id", delivery);
    request.addProperty("destination_robot_id", "robot-a");
    request.addProperty("destination_boot_id", boot);
    request.addProperty("event_id", event);
    request.addProperty("revision", 1);
    request.addProperty("annotation_sha256", "a".repeat(64));
    request.addProperty("payload_sha256", HubIdentity.sha256(note.toString()));
    request.addProperty("payload_json", note.toString());
    return request.toString();
  }

  static class MemoryIO implements MarkerIO {
    final ArrayDeque<MarkerProtocol.Request> pending = new ArrayDeque<>();
    final List<String> acks = new ArrayList<>();
    int polls;

    public MarkerProtocol.Request poll() {
      polls++;
      return pending.poll();
    }

    public void acknowledge(String json) {
      acks.add(json);
    }
  }

  @Test
  void sourceTimeSurvivesDelayedReceiptAndExactAutoLogRoundTrip() {
    var request = MarkerProtocol.parse(wire("delivery-a", "event-a", "boot-a", "invented note"));
    var io = new MemoryIO();
    io.pending.add(request);
    var tables = new ArrayList<LogTable>();
    try (var markers =
        new TestHubMarkers(
            "SIM",
            "robot-a",
            "boot-a",
            true,
            io,
            () -> 60000000001L,
            () -> true,
            input -> {
              var table = new LogTable(0);
              input.toLog(table);
              tables.add(table);
            })) {
      markers.periodic();
      markers.periodic();
    }
    var first = new MarkerIOInputsAutoLogged();
    first.fromLog(tables.get(0));
    assertEquals(1, first.acceptedEnvelopes.length);
    var logged = JsonParser.parseString(first.acceptedEnvelopes[0]).getAsJsonObject();
    assertEquals(request.wire(), logged.get("request_json").getAsString());
    assertEquals("60000000001", logged.get("receipt_robot_ns").getAsString());
    assertEquals(
        "1789999970000000001",
        JsonParser.parseString(request.payloadJson())
            .getAsJsonObject()
            .get("event_utc_start_ns")
            .getAsString());
    var next = new MarkerIOInputsAutoLogged();
    next.fromLog(tables.get(1));
    assertEquals(0, next.acceptedEnvelopes.length, "held cycle must not repeat the marker input");
    var ack = JsonParser.parseString(io.acks.get(0)).getAsJsonObject();
    assertEquals("unavailable", ack.get("usb_durability").getAsString());
    assertTrue(ack.get("logger_queue_fault").getAsBoolean());
  }

  @Test
  void fullLedgerKeepsOriginalReceiptAcrossNewDeliveryAndRejectsChangedPins() {
    var ledger = new MarkerLedger("robot-a", "boot-a", 1);
    var first = MarkerProtocol.parse(wire("delivery-a", "event-a", "boot-a", "one"));
    ledger.commit(first, 9007199254740993L);
    var retry = MarkerProtocol.parse(wire("delivery-b", "event-a", "boot-a", "one"));
    assertTrue(ledger.prepare(retry).duplicate());
    assertEquals(9007199254740993L, ledger.prepare(retry).receiptNs());
    assertEquals(
        "capacity",
        ledger
            .prepare(MarkerProtocol.parse(wire("delivery-c", "event-b", "boot-a", "two")))
            .reason());
    assertEquals(
        "payload_conflict",
        ledger
            .prepare(MarkerProtocol.parse(wire("delivery-d", "event-a", "boot-a", "changed")))
            .reason());
    var changedSource = JsonParser.parseString(retry.wire()).getAsJsonObject();
    changedSource.addProperty("annotation_sha256", "b".repeat(64));
    assertEquals(
        "payload_conflict",
        ledger.prepare(MarkerProtocol.parse(changedSource.toString())).reason());
    assertEquals("wrong_boot", new MarkerLedger("robot-a", "boot-b").prepare(first).reason());
    assertEquals(1, ledger.size());
  }

  @Test
  void noOpAndReplayInputCannotCommitButLaterLiveAdmissionCan() {
    var io = new MemoryIO();
    var request = MarkerProtocol.parse(wire("delivery-a", "event-a", "boot-a", "one"));
    var invocation = new AtomicInteger();
    try (var markers =
        new TestHubMarkers(
            "REAL",
            "robot-a",
            "boot-a",
            true,
            io,
            () -> 77L,
            () -> false,
            input -> {
              int n = invocation.getAndIncrement();
              if (n == 1) input.fromLog(new LogTable(0));
              if (n >= 2) input.toLog(new LogTable(0));
            })) {
      for (int i = 0; i < 4; i++) {
        io.pending.add(request);
        markers.periodic();
      }
    }
    for (int i = 0; i < 2; i++) {
      var ack = JsonParser.parseString(io.acks.get(i)).getAsJsonObject();
      assertEquals("log_input_unavailable", ack.get("reason").getAsString());
      assertTrue(ack.get("receipt_robot_ns").isJsonNull());
    }
    var accepted = JsonParser.parseString(io.acks.get(2)).getAsJsonObject();
    var duplicate = JsonParser.parseString(io.acks.get(3)).getAsJsonObject();
    assertFalse(accepted.get("duplicate").getAsBoolean());
    assertTrue(duplicate.get("duplicate").getAsBoolean());
    assertEquals("77", duplicate.get("receipt_robot_ns").getAsString());
    var replayIO = new MemoryIO();
    replayIO.pending.add(request);
    try (var replay =
        new TestHubMarkers(
            "REPLAY",
            "robot-a",
            "boot-a",
            true,
            replayIO,
            () -> {
              fail("replay receipt clock must not be consulted");
              return 0;
            },
            () -> false,
            input -> input.fromLog(new LogTable(0)))) {
      replay.periodic();
    }
    assertEquals(0, replayIO.polls);
    assertTrue(replayIO.acks.isEmpty());
  }

  @Test
  void strictSchemaAndPrivateParserErrorsRemainRedacted() {
    String valid = wire("delivery-a", "event-a", "boot-a", "private-test-secret");
    String[] invalid = {
      valid.replace("\"revision\":1", "\"revision\":1.0"),
      valid.replace("\"schema_version\":1,", "\"schema_version\":1,\"schema_version\":1,"),
      valid.substring(0, valid.length() - 1) + ",\"unexpected\":\"secret\"}",
      valid.replace("\"payload_sha256\":\"", "\"payload_sha256\":\"0"),
      "{\"private-test-secret\":\"" + "x".repeat(20000) + "\"}"
    };
    for (String body : invalid) {
      var error = assertThrows(IllegalArgumentException.class, () -> MarkerProtocol.parse(body));
      assertEquals("invalid_payload", error.getMessage());
      assertNull(error.getCause());
    }
  }
}
