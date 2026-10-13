package first.robot.util;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.SteppedRobot;
import org.wpilib.command2.CommandScheduler;
import org.wpilib.driverstation.internal.DriverStationBackend;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;

class TestHubStatusIntegrationTest {
  private void tick(SteppedRobot robot) {
    DriverStationSim.notifyNewData();
    SimHooks.stepTiming(0.02);
    robot.step();
  }

  private String field(String json, String name) {
    var matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
    assertTrue(matcher.find(), "Missing field " + name + " in " + json);
    return matcher.group(1);
  }

  private long sequence(String json) {
    var matcher = Pattern.compile("\"sequence\":([0-9]+)").matcher(json);
    assertTrue(matcher.find());
    return Long.parseLong(matcher.group(1));
  }

  @Test
  void actualDisabledRobotPublishesFreshEnvelopeAndEffectiveTunableRevision() throws Exception {
    Map<Path, byte[]> preferences = new HashMap<>();
    for (String name : new String[] {"networktables.json", "networktables.json.bck"}) {
      var path = Path.of(name);
      preferences.put(path, Files.exists(path) ? Files.readAllBytes(path) : null);
    }
    assertTrue(HAL.initialize());
    SimHooks.pauseTiming();
    DriverStationSim.resetData();
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setRobotMode(RobotMode.TELEOPERATED);
    DriverStationSim.setEnabled(false);
    DriverStationSim.setSendError(false);
    var nt = NetworkTableInstance.getDefault();
    var tunable = nt.getEntry("/Tuning/Test/Value");
    tunable.setDouble(0.0);
    HubConfiguration.register("/Tuning/Test/Value", () -> tunable.getDouble(0.0));
    try (var robot = new SteppedRobot();
        var status = nt.getStringTopic(TestHubStatus.STATUS_TOPIC).subscribe("");
        var snapshot =
            nt.getStringTopic("/Telemetry/TestHub/ConfigurationSnapshot").subscribe("")) {
      tick(robot);
      String before = status.get();
      assertTrue(before.contains("\"enabled\":false"));
      assertTrue(before.contains("\"transfer_allowed\":true"));
      assertTrue(before.contains("\"runtime_mode\":\"SIM\""));
      assertTrue(before.contains("\"run_id\":null"));
      String boot = field(before, "boot_id");
      String hash = field(before, "configuration_sha256");
      long clock = Long.parseLong(field(before, "robot_monotonic_ns"));
      nt.getEntry("/Tuning/Test/Value").setDouble(1729.0);
      tick(robot);
      String changed = status.get();
      assertEquals(boot, field(changed, "boot_id"));
      assertTrue(sequence(changed) > sequence(before));
      assertTrue(Long.parseLong(field(changed, "robot_monotonic_ns")) > clock);
      assertNotEquals(hash, field(changed, "configuration_sha256"));
      assertTrue(snapshot.get().contains("\"/Tuning/Test/Value\":\"1729.0\""));
      DriverStationSim.setDsAttached(false);
      DriverStationBackend.refreshData();
      SimHooks.stepTiming(0.02);
      robot.step();
      assertTrue(status.get().contains("\"enabled\":null"), status.get());
      assertTrue(status.get().contains("\"transfer_allowed\":false"));
      assertTrue(sequence(status.get()) > sequence(changed));
    } finally {
      CommandScheduler.getInstance().cancelAll();
      Logger.end();
      SimHooks.resumeTiming();
      for (var entry : preferences.entrySet()) {
        if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
        else Files.write(entry.getKey(), entry.getValue());
      }
    }
  }
}
