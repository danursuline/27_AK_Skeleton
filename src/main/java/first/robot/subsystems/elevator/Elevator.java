package first.robot.subsystems.elevator;

import static org.wpilib.units.Units.*;

import org.littletonrobotics.junction.Logger;
import org.wpilib.command2.Command;
import org.wpilib.command2.Commands;
import org.wpilib.command2.SubsystemBase;
import org.wpilib.command2.sysid.SysIdRoutine;
import org.wpilib.math.controller.ElevatorFeedforward;
import org.wpilib.math.controller.ProfiledPIDController;
import org.wpilib.math.trajectory.TrapezoidProfile;
import org.wpilib.math.trajectory.TrapezoidProfile.Constraints;
import org.wpilib.system.Timer;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Voltage;
import org.wpilib.util.Alert;
import org.wpilib.util.Alert.Level;

/**
 * Elevator Subsystem inspired by FRC 1706 (Ratchet Rockers) 2024 Trap Elevator.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Dual motor control with current limiting and brake mode on NI SystemCore.
 *   <li>WPILib ProfiledPIDController and ElevatorFeedforward motion profile tracking.
 *   <li>Stall current detection homing / zeroing routine matching 1706.
 *   <li>AdvantageKit hardware abstraction with full telemetry logging.
 *   <li>SysId routine integration for mechanism characterization.
 * </ul>
 */
public class Elevator extends SubsystemBase {
  private final ElevatorIO io;
  private final ElevatorIOInputsAutoLogged inputs = new ElevatorIOInputsAutoLogged();

  private final ProfiledPIDController controller =
      new ProfiledPIDController(
          ElevatorConstants.kP,
          ElevatorConstants.kI,
          ElevatorConstants.kD,
          new Constraints(
              ElevatorConstants.kMaxVelocityRotPerSec,
              ElevatorConstants.kMaxAccelerationRotPerSec2));

  private final ElevatorFeedforward feedforward =
      new ElevatorFeedforward(
          ElevatorConstants.kS, ElevatorConstants.kG, ElevatorConstants.kV, ElevatorConstants.kA);

  private final SysIdRoutine sysId;

  private final Alert leftDisconnectedAlert =
      new Alert("Elevator", "Elevator left motor disconnected!", Level.HIGH);
  private final Alert rightDisconnectedAlert =
      new Alert("Elevator", "Elevator right motor disconnected!", Level.HIGH);

  private boolean closedLoop = false;
  private boolean isZeroed = false;

  public Elevator(ElevatorIO io) {
    this.io = io;
    controller.setTolerance(ElevatorConstants.kPositionToleranceRot);

    sysId =
        new SysIdRoutine(
            new SysIdRoutine.Config(
                null,
                null,
                null,
                (state) -> Logger.recordOutput("Elevator/SysIdState", state.toString())),
            new SysIdRoutine.Mechanism((voltage) -> runVolts(voltage.in(Volts)), null, this));
  }

  @Override
  public void periodic() {
    io.updateInputs(inputs);
    Logger.processInputs("Elevator", inputs);

    leftDisconnectedAlert.set(!inputs.leftConnected);
    rightDisconnectedAlert.set(!inputs.rightConnected);

    if (closedLoop) {
      double pidVolts = controller.calculate(inputs.positionRot);
      TrapezoidProfile.State setpoint = controller.getSetpoint();
      double ffVolts = feedforward.calculate(setpoint.velocity);
      double totalVolts = Math.clamp(pidVolts + ffVolts, -12.0, 12.0);

      io.setVoltage(totalVolts);

      Logger.recordOutput("Elevator/SetpointPositionRot", setpoint.position);
      Logger.recordOutput("Elevator/SetpointVelocityRotPerSec", setpoint.velocity);
      Logger.recordOutput("Elevator/PIDVolts", pidVolts);
      Logger.recordOutput("Elevator/FeedforwardVolts", ffVolts);
    }

    Logger.recordOutput("Elevator/PositionRot", inputs.positionRot);
    Logger.recordOutput("Elevator/PositionMeters", getPositionMeters());
    Logger.recordOutput("Elevator/VelocityRotPerSec", inputs.velocityRotPerSec);
    Logger.recordOutput("Elevator/GoalPositionRot", controller.getGoal().position);
    Logger.recordOutput("Elevator/TotalCurrentAmps", getTotalCurrentAmps());
    Logger.recordOutput("Elevator/IsZeroed", isZeroed);
    Logger.recordOutput("Elevator/AtGoal", atGoal());
    Logger.recordOutput("Elevator/ClosedLoopEnabled", closedLoop);
  }

  /** Run motors at raw open-loop voltage. */
  public void runVolts(double volts) {
    closedLoop = false;
    io.setVoltage(volts);
  }

  /** Stop elevator motors. */
  public void stop() {
    closedLoop = false;
    io.stop();
  }

  /** Set the target goal position in rotations. */
  public void setGoal(double targetRotations) {
    targetRotations =
        Math.clamp(
            targetRotations, ElevatorConstants.kMinRotations, ElevatorConstants.kMaxRotations);
    if (!closedLoop) {
      controller.reset(inputs.positionRot, inputs.velocityRotPerSec);
      closedLoop = true;
    }
    controller.setGoal(targetRotations);
  }

  /** Set the target goal position as a Distance. */
  public void setGoal(Distance targetDistance) {
    setGoal(targetDistance.in(Meters) / ElevatorConstants.kMetersPerRotation);
  }

  /** Reset the sensor position. */
  public void setPosition(double positionRot) {
    io.setPosition(positionRot);
    controller.reset(positionRot, 0.0);
  }

  /** Current position in rotations. */
  public double getPositionRot() {
    return inputs.positionRot;
  }

  /** Current position in meters. */
  public double getPositionMeters() {
    return inputs.positionRot * ElevatorConstants.kMetersPerRotation;
  }

  /** Current position as a type-safe Distance. */
  public Distance getPosition() {
    return Meters.of(getPositionMeters());
  }

  /** Current velocity in rotations per second. */
  public double getVelocityRotPerSec() {
    return inputs.velocityRotPerSec;
  }

  /** Total current draw from both motors in Amps. */
  public double getTotalCurrentAmps() {
    return inputs.leftCurrentAmps + inputs.rightCurrentAmps;
  }

  /** Returns whether the elevator is at its goal position. */
  public boolean atGoal() {
    return closedLoop && controller.atGoal();
  }

  /** Returns whether the elevator has been zeroed. */
  public boolean isZeroed() {
    return isZeroed;
  }

  // --- Command Factories ---

  /** Command to move the elevator to a goal position in rotations. */
  public Command moveToPosition(double targetRotations) {
    return Commands.run(() -> setGoal(targetRotations), this);
  }

  /** Command to move the elevator to a goal position specified by Distance. */
  public Command moveToPosition(Distance targetDistance) {
    return Commands.run(() -> setGoal(targetDistance), this);
  }

  /** Command to move elevator to rest position (1706 kRest = 2.6 rotations). */
  public Command toRest() {
    return moveToPosition(ElevatorConstants.kRestRotations);
  }

  /** Command to move elevator to Trap scoring position (1706 trap score = 28.0 rotations). */
  public Command toTrap() {
    return moveToPosition(ElevatorConstants.kTrapScoreRotations);
  }

  /** Command to move elevator to note intake position (1706 intake = 1.0 rotation). */
  public Command toIntake() {
    return moveToPosition(ElevatorConstants.kIntakeRotations);
  }

  /**
   * 1706 Current Spike Homing / Zeroing Routine. Drives the elevator down at low voltage, waits for
   * inrush settling, detects the current spike when hitting the hard bottom stop, zeros the
   * encoders, and returns to resting position.
   */
  public Command zeroCommand() {
    Timer timer = new Timer();
    return Commands.sequence(
        Commands.runOnce(
            () -> {
              closedLoop = false;
              timer.restart();
              runVolts(ElevatorConstants.kHomingVoltage.in(Volts));
            },
            this),
        Commands.run(() -> {}, this)
            .until(
                () ->
                    timer.hasElapsed(ElevatorConstants.kHomingDebounceSeconds)
                        && getTotalCurrentAmps()
                            > ElevatorConstants.kHomingCurrentSpikeThresholdAmps),
        Commands.runOnce(
            () -> {
              stop();
              setPosition(ElevatorConstants.kBottomRotations);
              isZeroed = true;
              setGoal(ElevatorConstants.kRestRotations);
            },
            this));
  }

  /** Run the elevator at raw voltage. */
  public Command runVoltage(Voltage voltage) {
    return Commands.run(() -> runVolts(voltage.in(Volts)), this);
  }

  /** Stop command. */
  public Command stopCommand() {
    return Commands.runOnce(this::stop, this);
  }

  /** SysId quasistatic test command. */
  public Command sysIdQuasistatic(SysIdRoutine.Direction direction) {
    return run(() -> runVolts(0.0)).withTimeout(1.0).andThen(sysId.quasistatic(direction));
  }

  /** SysId dynamic test command. */
  public Command sysIdDynamic(SysIdRoutine.Direction direction) {
    return run(() -> runVolts(0.0)).withTimeout(1.0).andThen(sysId.dynamic(direction));
  }
}
