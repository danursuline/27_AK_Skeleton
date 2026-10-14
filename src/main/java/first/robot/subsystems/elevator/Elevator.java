package first.robot.subsystems.elevator;

import static first.robot.subsystems.elevator.ElevatorConstants.*;
import static org.wpilib.units.Units.*;

import org.littletonrobotics.junction.AutoLogOutput;
import org.littletonrobotics.junction.Logger;
import org.wpilib.command2.Command;
import org.wpilib.command2.Commands;
import org.wpilib.command2.SubsystemBase;
import org.wpilib.command2.sysid.SysIdRoutine;
import org.wpilib.units.measure.Distance;
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;

/**
 * Trap/amp elevator modeled on FRC 1706's 2024 robot: two motors driving one carriage,
 * trapezoid-profiled position control with gravity feedforward, and stall-current homing against
 * the bottom hard stop.
 */
public class Elevator extends SubsystemBase {
  private final ElevatorIO io;
  private final ElevatorIOInputsAutoLogged inputs = new ElevatorIOInputsAutoLogged();
  private final SysIdRoutine sysId;

  private final Alert leaderDisconnectedAlert =
      new Alert("Elevator", "Disconnected elevator leader motor.", Level.HIGH);
  private final Alert followerDisconnectedAlert =
      new Alert("Elevator", "Disconnected elevator follower motor.", Level.HIGH);
  private final Alert notHomedAlert =
      new Alert("Elevator", "Elevator has not been homed.", Level.MEDIUM);

  private boolean homed = false;

  public Elevator(ElevatorIO io) {
    this.io = io;
    sysId =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                Volts.of(1.0).per(Second),
                Volts.of(4.0),
                Seconds.of(5.0),
                state -> Logger.recordOutput("Elevator/SysIdState", state.toString())),
            new SysIdRoutine.Mechanism(volts -> io.setVoltage(volts.in(Volts)), null, this));
  }

  @Override
  public void periodic() {
    io.updateInputs(inputs);
    Logger.processInputs("Elevator", inputs);

    leaderDisconnectedAlert.set(!inputs.leaderConnected);
    followerDisconnectedAlert.set(!inputs.followerConnected);
    notHomedAlert.set(!homed);
  }

  /** Current carriage height. */
  public Distance getHeight() {
    return Meters.of(inputs.positionMeters);
  }

  @AutoLogOutput(key = "Elevator/AtGoal")
  public boolean atGoal() {
    return Math.abs(inputs.goalMeters - inputs.positionMeters) <= AT_GOAL_TOLERANCE.in(Meters);
  }

  @AutoLogOutput(key = "Elevator/Homed")
  public boolean isHomed() {
    return homed;
  }

  /** Combined stator current of both motors, used to detect the bottom hard stop. */
  @AutoLogOutput(key = "Elevator/TotalCurrentAmps")
  public double getTotalCurrentAmps() {
    return Math.abs(inputs.statorCurrentAmps[0]) + Math.abs(inputs.statorCurrentAmps[1]);
  }

  private void setGoal(Distance height) {
    double clamped = Math.clamp(height.in(Meters), 0.0, MAX_HEIGHT.in(Meters));
    Logger.recordOutput("Elevator/GoalMeters", clamped);
    io.setPosition(clamped);
  }

  /** Start a profiled move to the given height; the elevator holds it after the command ends. */
  public Command goTo(Distance height) {
    return runOnce(() -> setGoal(height)).withName("Elevator To " + height.in(Inches) + " in");
  }

  /** Move to the given height and finish once it arrives. */
  public Command goToAndWait(Distance height) {
    return goTo(height).andThen(Commands.waitUntil(this::atGoal)).withName("Elevator To And Wait");
  }

  /**
   * Port of 1706's ZeroElevator: drive down, ignore the start-up current spike, wait for the
   * combined current to spike on the hard stop, then zero the encoder and go to rest.
   */
  public Command home() {
    return Commands.sequence(
            runOnce(
                () -> {
                  homed = false;
                  io.setVoltage(HOMING_VOLTAGE.in(Volts));
                }),
            Commands.waitSeconds(HOMING_BLANKING_TIME.in(Seconds)),
            Commands.waitUntil(() -> getTotalCurrentAmps() > HOMING_CURRENT_THRESHOLD.in(Amps)),
            runOnce(
                () -> {
                  io.resetPosition(0.0);
                  homed = true;
                }))
        .withTimeout(HOMING_TIMEOUT)
        .finallyDo(
            () -> {
              if (homed) {
                setGoal(REST_HEIGHT);
              } else {
                io.stop();
              }
            })
        .withName("Elevator Home");
  }

  /** 1706's trap button press: raise to the trap. */
  public Command raiseToTrap() {
    return goTo(TRAP_HEIGHT);
  }

  /** 1706's trap button release: drop to stow, then re-home to clear any encoder drift. */
  public Command lowerAndRehome() {
    return goTo(STOW_HEIGHT)
        .andThen(Commands.waitSeconds(0.25), home())
        .withName("Elevator Lower And Rehome");
  }

  /** Stop the motors (brake). */
  public Command stop() {
    return runOnce(io::stop).withName("Elevator Stop");
  }

  /** Returns a command to run a quasistatic test in the specified direction. */
  public Command sysIdQuasistatic(SysIdRoutine.Direction direction) {
    return sysId.quasistatic(direction);
  }

  /** Returns a command to run a dynamic test in the specified direction. */
  public Command sysIdDynamic(SysIdRoutine.Direction direction) {
    return sysId.dynamic(direction);
  }
}
