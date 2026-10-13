package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.LoggedRobot;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;

/** Actual locked NT/AK loopback qualification using public invented note bytes only. */
class MarkerNTReviewTest {
  @TempDir Path temporary;

  static class LoggingFixture extends LoggedRobot {
    // Exact AK constructor-stack guard checks the declaring class; no RobotBase is constructed.
    static void startLogger() {
      Logger.disableConsoleCapture();
      Logger.start();
    }
  }

  private static int localPort() throws Exception {
    try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static void until(BooleanSupplier condition, Runnable step) throws Exception {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      step.run();
      Thread.sleep(5);
    }
    assertTrue(condition.getAsBoolean(), "bounded local NT fixture did not reach expected state");
  }

  @Test
  void lostAcknowledgementRetryUsesActualLoggerAndOriginalReceiptExactlyOnce() throws Exception {
    assertTrue(HAL.initialize());
    int port = localPort();
    var logged = new ArrayList<String>();
    LoggingFixture.startLogger();
    try (var server = NetworkTableInstance.create();
        var client = NetworkTableInstance.create()) {
      server.startServer(temporary.resolve("nt.json").toString(), "127.0.0.1", "", port);
      client.setServer("127.0.0.1", port);
      client.startClient("public-marker-review");
      until(client::isConnected, () -> {});
      try (var io = new MarkerIONetworkTables(server);
          var marker =
              new TestHubMarkers(
                  "SIM",
                  "robot-a",
                  "boot-a",
                  true,
                  io,
                  () -> 9007199254740993L,
                  () -> false,
                  input ->
                      Logger.processInputs(
                          "ReviewMarker",
                          new LoggableInputs() {
                            public void toLog(LogTable table) {
                              input.toLog(table);
                              logged.addAll(
                                  Arrays.asList(table.get("AcceptedEnvelopes", new String[0])));
                            }

                            public void fromLog(LogTable table) {
                              input.fromLog(table);
                            }
                          }));
          var publisher =
              client
                  .getStringTopic(MarkerProtocol.REQUEST_TOPIC)
                  .publish(
                      PubSubOption.SEND_ALL,
                      PubSubOption.KEEP_DUPLICATES,
                      PubSubOption.periodic(.02));
          var subscriber =
              client
                  .getStringTopic(MarkerProtocol.ACK_TOPIC)
                  .subscribe(
                      "",
                      PubSubOption.SEND_ALL,
                      PubSubOption.KEEP_DUPLICATES,
                      PubSubOption.pollStorage(8),
                      PubSubOption.periodic(.02))) {
        String request =
            MarkerProtocolReviewTest.wire("delivery-a", "event-a", "boot-a", "public test");
        var received = new ArrayList<JsonObject>();
        Runnable step =
            () -> {
              marker.periodic();
              server.flush();
              client.flush();
              for (var value : subscriber.readQueue())
                received.add(JsonParser.parseString(value.value).getAsJsonObject());
            };
        publisher.set(request);
        client.flush();
        until(() -> !received.isEmpty(), step);
        JsonObject discardedAck = received.get(0);
        assertFalse(discardedAck.get("duplicate").getAsBoolean());
        assertEquals("accepted_into_log_input", discardedAck.get("state").getAsString());
        assertEquals(1, logged.size(), "actual AK table received the original envelope");
        received.clear(); // Deliberately lose delivery of the first receipt to the caller.
        publisher.set(request);
        client.flush();
        until(() -> received.stream().anyMatch(ack -> ack.get("duplicate").getAsBoolean()), step);
        JsonObject duplicate =
            received.stream()
                .filter(ack -> ack.get("duplicate").getAsBoolean())
                .findFirst()
                .orElseThrow();
        assertEquals(discardedAck.get("receipt_robot_ns"), duplicate.get("receipt_robot_ns"));
        assertEquals("9007199254740993", duplicate.get("receipt_robot_ns").getAsString());
        assertEquals(1, logged.size(), "NT duplicate retry must not log a second marker");
        assertEquals("unavailable", duplicate.get("usb_durability").getAsString());
        var envelope = JsonParser.parseString(logged.get(0)).getAsJsonObject();
        assertEquals(request, envelope.get("request_json").getAsString());
      }
    } finally {
      Logger.end();
    }
  }
}
