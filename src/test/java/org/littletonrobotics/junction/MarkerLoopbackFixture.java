package org.littletonrobotics.junction;

import first.robot.Constants;
import first.robot.hub.TestHubMarkers;
import first.robot.util.TestHubStatus;
import java.nio.file.Path;
import org.littletonrobotics.junction.wpilog.WPILOGWriter;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;
import org.wpilib.system.RobotController;

/** Local-only SIM producer for cross-process hub qualification; no Robot or device construction. */
public final class MarkerLoopbackFixture extends LoggedRobot {
  public static void main(String[] args) throws Exception {
    if (args.length != 3)
      throw new IllegalArgumentException("port, output directory and seconds required");
    int port = Integer.parseInt(args[0]);
    int seconds = Integer.parseInt(args[2]);
    if (port < 1024 || port > 65535 || seconds < 1 || seconds > 120)
      throw new IllegalArgumentException("invalid fixture bounds");
    if (!HAL.initialize()) throw new IllegalStateException("HAL unavailable");
    Path output = Path.of(args[1]).toAbsolutePath();
    var state = new TestHubStatus.State("6391-practice", "SIM");
    Logger.disableConsoleCapture();
    Logger.recordMetadata("RobotId", state.robotId);
    Logger.recordMetadata("BootId", state.bootId);
    Logger.recordMetadata("RuntimeMode", "SIM");
    Logger.addDataReceiver(new WPILOGWriter(output.resolve("marker-proof.wpilog").toString()));
    Logger.start();
    try (var nt = NetworkTableInstance.create();
        var status =
            nt.getStringTopic(TestHubStatus.STATUS_TOPIC)
                .publish(
                    PubSubOption.SEND_ALL,
                    PubSubOption.KEEP_DUPLICATES,
                    PubSubOption.periodic(.02));
        var marker =
            TestHubMarkers.create(Constants.Mode.SIM, state.robotId, state.bootId, true, nt)) {
      nt.startServer(output.resolve("nt.json").toString(), "127.0.0.1", "", port);
      long deadline = System.nanoTime() + seconds * 1000000000L;
      while (System.nanoTime() < deadline) {
        Logger.periodicBeforeUser();
        state.update(true, "teleoperated", false, RobotController.getMonotonicTime());
        String envelope = state.json(Logger.getReceiverQueueFault(), "a".repeat(64), 1);
        status.set(envelope);
        marker.periodic();
        Logger.periodicAfterUser(0, 0);
        nt.flush();
        Thread.sleep(20);
      }
    } finally {
      Logger.end();
    }
  }
}
