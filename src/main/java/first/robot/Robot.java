// Copyright (c) 2021-2026 Littleton Robotics
// http://github.com/Mechanical-Advantage
//
// Use of this source code is governed by a BSD
// license that can be found in the LICENSE file
// at the root directory of this project.

package first.robot;

import first.robot.hub.RecordingOptions;
import first.robot.hub.RotatingLogReceiver;
import first.robot.hub.TestHubMarkers;
import first.robot.util.TestHubStatus;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.littletonrobotics.junction.LogFileUtil;
import org.littletonrobotics.junction.LoggedRobot;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.networktables.NT4Publisher;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.littletonrobotics.junction.wpilog.WPILOGWriter;
import org.wpilib.command2.Command;
import org.wpilib.command2.CommandScheduler;
import org.wpilib.networktables.NetworkTableInstance;

/**
 * The VM is configured to automatically run this class, and to call the functions corresponding to
 * each mode, as described in the TimedRobot documentation. If you change the name of this class or
 * the package after creating this project, you must also update the build.gradle file in the
 * project.
 */
public class Robot extends LoggedRobot {
  private Command autonomousCommand;
  private RobotContainer robotContainer;

  private RotatingLogReceiver testHubRecording;
  private TestHubMarkers testHubMarkers;
  private final TestHubStatus hubStatus =
      new TestHubStatus(
          () -> robotContainer == null ? "unavailable" : robotContainer.getSelectedAutoName());

  RobotContainer getContainer() {
    return robotContainer;
  }

  private void addTestHubRecording(Path directory) {
    testHubRecording = new RotatingLogReceiver(directory);
    Logger.addDataReceiver(testHubRecording);
    Logger.recordMetadata(
        "TestHubRecording", Constants.currentMode.name() + "-opt-in-experimental");
  }

  public Robot() {
    this(RobotContainer::new);
  }

  /** Container injection for lifecycle simulation checks. */
  public Robot(Supplier<RobotContainer> containerFactory) {
    // Fresh build/source identity and per-execution boot metadata.
    hubStatus.recordMetadata();
    var recordingDirectory =
        RecordingOptions.directory(
            Constants.currentMode, System.getProperty("frc.testHubRecordingDir", ""));

    // Set up data receivers & replay source
    switch (Constants.currentMode) {
      case REAL:
        // Running on a real robot, log to a USB stick ("/U/logs")
        if (recordingDirectory == null) {
          Logger.addDataReceiver(new WPILOGWriter());
        } else {
          // Explicit experimental opt-in selects one file writer, avoiding duplicate USB writes.
          addTestHubRecording(recordingDirectory);
        }
        Logger.addDataReceiver(new NT4Publisher());
        break;

      case SIM:
        // Running a physics simulator, log to NT
        Logger.addDataReceiver(new NT4Publisher());
        if (recordingDirectory != null) {
          addTestHubRecording(recordingDirectory);
        }
        break;

      case REPLAY:
        // Replaying a log, set up replay source
        setUseTiming(false); // Run as fast as possible
        String logPath = LogFileUtil.findReplayLog();
        Logger.setReplaySource(new WPILOGReader(logPath));
        Logger.addDataReceiver(new WPILOGWriter(LogFileUtil.addPathSuffix(logPath, "_sim")));
        break;
    }

    // Start AdvantageKit logger
    Logger.start();

    // Instantiate our RobotContainer. This will perform all our button bindings,
    // and put our autonomous chooser on the dashboard.
    robotContainer = containerFactory.get();
    testHubMarkers =
        TestHubMarkers.create(
            Constants.currentMode,
            hubStatus.robotId(),
            hubStatus.bootId(),
            Boolean.getBoolean("frc.testHubMarkersEnabled"),
            NetworkTableInstance.getDefault());
  }

  /** This function is called periodically during all modes. */
  @Override
  public void robotPeriodic() {
    // Optionally switch the thread to high priority to improve loop
    // timing (see the template project documentation for details)
    // Threads.setCurrentThreadPriority(true, 99);

    // Runs the Scheduler. This is responsible for polling buttons, adding
    // newly-scheduled commands, running already-scheduled commands, removing
    // finished or interrupted commands, and running subsystem periodic() methods.
    // This must be called from the robot's periodic block in order for anything in
    // the Command-based framework to work.
    CommandScheduler.getInstance().run();

    // Passive Test Hub status, recording health and note markers
    var health = testHubRecording == null ? null : testHubRecording.health();
    hubStatus.periodic(health == null ? null : health.activeSegmentId());
    testHubMarkers.periodic();
    if (health != null) { // One snapshot only; the scheduler performs no file I/O.
      Logger.recordOutput("TestHubRecording/WriteState", health.writeState());
      Logger.recordOutput("TestHubRecording/LastError", health.lastError());
      Logger.recordOutput(
          "TestHubRecording/ActiveSegmentId",
          health.activeSegmentId() == null ? "" : health.activeSegmentId());
      Logger.recordOutput("TestHubRecording/PendingDigests", health.pendingDigests());
      Logger.recordOutput("TestHubRecording/IntegrationQualified", false);
    }

    // Return to non-RT thread priority (do not modify the first argument)
    // Threads.setCurrentThreadPriority(false, 10);
  }

  /** This function is called once when the robot is disabled. */
  @Override
  public void disabledInit() {}

  /** This function is called periodically when disabled. */
  @Override
  public void disabledPeriodic() {}

  /** This autonomous runs the autonomous command selected by your {@link RobotContainer} class. */
  @Override
  public void autonomousInit() {
    autonomousCommand = robotContainer.getAutonomousCommand();

    // schedule the autonomous command (example)
    if (autonomousCommand != null) {
      CommandScheduler.getInstance().schedule(autonomousCommand);
    }
  }

  /** This function is called periodically during autonomous. */
  @Override
  public void autonomousPeriodic() {}

  /** This function is called once when teleop is enabled. */
  @Override
  public void teleopInit() {
    // This makes sure that the autonomous stops running when
    // teleop starts running. If you want the autonomous to
    // continue until interrupted by another command, remove
    // this line or comment it out.
    if (autonomousCommand != null) {
      autonomousCommand.cancel();
    }
  }

  /** This function is called periodically during operator control. */
  @Override
  public void teleopPeriodic() {}

  /** This function is called once when test mode is enabled. */
  @Override
  public void utilityInit() {
    // Cancels all running commands at the start of test mode.
    CommandScheduler.getInstance().cancelAll();
  }

  /** This function is called periodically during test mode. */
  @Override
  public void utilityPeriodic() {}

  /** This function is called once when the robot is first started up. */
  @Override
  public void simulationInit() {}

  /** This function is called periodically whilst in simulation. */
  @Override
  public void simulationPeriodic() {}

  @Override
  public void close() {
    if (testHubMarkers != null) testHubMarkers.close();
    super.close();
  }
}
