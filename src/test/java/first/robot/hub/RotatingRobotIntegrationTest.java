package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.SteppedRobot;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.wpilib.command2.CommandScheduler;
import org.wpilib.hardware.hal.HAL;
import org.wpilib.hardware.hal.RobotMode;
import org.wpilib.simulation.DriverStationSim;
import org.wpilib.simulation.SimHooks;

class RotatingRobotIntegrationTest {
  @Test
  void optInSimulationRecordsActualRobotTablesAndIndependentlyReplays() throws Exception {
    Path root =
        Path.of("build/reports/testhub/robot-recordings", UUID.randomUUID().toString())
            .toAbsolutePath();
    Map<Path, byte[]> preferences = new HashMap<>();
    for (String name : new String[] {"networktables.json", "networktables.json.bck"}) {
      Path path = Path.of(name);
      preferences.put(path, Files.exists(path) ? Files.readAllBytes(path) : null);
    }
    String previous = System.getProperty("frc.testHubRecordingDir");
    System.setProperty("frc.testHubRecordingDir", root.toString());
    assertTrue(HAL.initialize());
    SimHooks.pauseTiming();
    DriverStationSim.resetData();
    DriverStationSim.setDsAttached(true);
    DriverStationSim.setRobotMode(RobotMode.TELEOPERATED);
    DriverStationSim.setEnabled(false);
    DriverStationSim.setSendError(false);
    try (var robot = new SteppedRobot()) {
      for (int cycle = 0; cycle < 20; cycle++) {
        DriverStationSim.notifyNewData();
        SimHooks.stepTiming(0.02);
        robot.step();
        if (cycle == 0) {
          // Stepped physics can outrun the asynchronous receiver. Await its
          // first health publication rather than assuming a fixed cycle lag.
          long deadline = System.nanoTime() + 3_000_000_000L;
          while (!Files.exists(root.resolve("manifest.json")) && System.nanoTime() < deadline) {
            Thread.sleep(5);
          }
          assertTrue(Files.exists(root.resolve("manifest.json")));
        }
      }
      Logger.end();
      String head = Files.readString(root.resolve("manifest.json"));
      assertTrue(head.contains("\"write_state\":\"stopped\""), head);
      assertTrue(head.contains("\"pending_digest_count\":1"), head);
      assertFalse(head.contains("\"write_state\":\"failed\""), head);
      Path artifact;
      try (var files = Files.list(root)) {
        artifact = files.filter(p -> p.toString().endsWith(".wpilog")).findFirst().orElseThrow();
      }
      var replay = new WPILOGReader(artifact.toString());
      replay.start();
      var table = new LogTable(0);
      long expected = 1;
      boolean more;
      do {
        more = replay.updateTable(table);
        assertEquals(expected++, table.get("RealOutputs/TestHub/Sequence", -1L));
        assertEquals("6391-practice", table.get("RealMetadata/RobotId", ""));
        assertEquals("SIM-opt-in-experimental", table.get("RealMetadata/TestHubRecording", ""));
        if (expected > 3) {
          String active = table.get("RealOutputs/TestHubRecording/ActiveSegmentId", "");
          assertFalse(active.isEmpty());
          assertTrue(
              table
                  .get("RealOutputs/TestHub/Status", "")
                  .contains("\"active_segment_id\":\"" + active + "\""));
        }
        assertTrue(table.get("RealOutputs/TestHub/StateKnown", false));
        assertFalse(table.get("RealOutputs/TestHub/Enabled", true));
        assertTrue(
            table.getAll(false).keySet().stream().anyMatch(key -> key.startsWith("/.schema/")));
      } while (more);
      assertEquals(21, expected);
    } finally {
      CommandScheduler.getInstance().cancelAll();
      Logger.end();
      SimHooks.resumeTiming();
      if (previous == null) System.clearProperty("frc.testHubRecordingDir");
      else System.setProperty("frc.testHubRecordingDir", previous);
      for (var entry : preferences.entrySet()) {
        if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
        else Files.write(entry.getKey(), entry.getValue());
      }
    }
  }
}
