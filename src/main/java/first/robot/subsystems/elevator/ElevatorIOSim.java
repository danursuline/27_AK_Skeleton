package first.robot.subsystems.elevator;

import static first.robot.subsystems.elevator.ElevatorConstants.*;
import static org.wpilib.units.Units.*;

import org.wpilib.driverstation.RobotState;
import org.wpilib.math.controller.ElevatorFeedforward;
import org.wpilib.math.controller.ProfiledPIDController;
import org.wpilib.math.system.DCMotor;
import org.wpilib.math.trajectory.TrapezoidProfile;
import org.wpilib.simulation.ElevatorSim;

/**
 * Physics sim for the elevator. Closed loop runs here on the robot controller with a
 * ProfiledPIDController + ElevatorFeedforward, which is exactly how 1706 ran their real elevator,
 * using the same constants Motion Magic gets on the real TalonFXs.
 */
public class ElevatorIOSim implements ElevatorIO {
  private static final double LOOP_PERIOD_SECS = 0.02;
  private static final DCMotor GEARBOX = DCMotor.getKrakenX60Foc(2);

  private final ElevatorSim sim =
      new ElevatorSim(
          GEARBOX,
          GEAR_RATIO,
          CARRIAGE_MASS.in(Kilograms),
          DRUM_RADIUS.in(Meters),
          0.0,
          MAX_HEIGHT.in(Meters),
          true,
          0.0);

  private final ProfiledPIDController controller =
      new ProfiledPIDController(
          KP,
          KI,
          KD,
          new TrapezoidProfile.Constraints(
              MAX_VELOCITY.in(MetersPerSecond), MAX_ACCELERATION.in(MetersPerSecondPerSecond)));
  private final ElevatorFeedforward feedforward = new ElevatorFeedforward(KS, KG, KV, KA);

  private boolean closedLoop = false;
  private double appliedVolts = 0.0;

  /** Encoder offset so resetPosition() behaves like re-zeroing a real encoder. */
  private double encoderOffsetMeters = 0.0;

  @Override
  public void updateInputs(ElevatorIOInputs inputs) {
    if (RobotState.isDisabled()) {
      closedLoop = false;
      appliedVolts = 0.0;
    } else if (closedLoop) {
      appliedVolts =
          controller.calculate(getPositionMeters())
              + feedforward.calculate(controller.getSetpoint().velocity);
    }
    appliedVolts = Math.clamp(appliedVolts, -12.0, 12.0);

    sim.setInputVoltage(appliedVolts);
    sim.update(LOOP_PERIOD_SECS);

    // Split the gearbox current evenly across the two motors.
    double perMotorAmps = Math.abs(sim.getCurrentDraw()) / 2.0;

    inputs.leaderConnected = true;
    inputs.followerConnected = true;
    inputs.positionMeters = getPositionMeters();
    inputs.velocityMetersPerSec = sim.getVelocity();
    inputs.goalMeters = controller.getGoal().position;
    inputs.appliedVolts = new double[] {appliedVolts, appliedVolts};
    inputs.statorCurrentAmps = new double[] {perMotorAmps, perMotorAmps};
    inputs.supplyCurrentAmps =
        new double[] {
          perMotorAmps * Math.abs(appliedVolts) / 12.0, perMotorAmps * Math.abs(appliedVolts) / 12.0
        };
    inputs.tempCelsius = new double[] {25.0, 25.0};
  }

  @Override
  public void setVoltage(double volts) {
    closedLoop = false;
    appliedVolts = volts;
  }

  @Override
  public void setPosition(double positionMeters) {
    if (!closedLoop) {
      controller.reset(getPositionMeters(), sim.getVelocity());
    }
    closedLoop = true;
    controller.setGoal(positionMeters);
  }

  @Override
  public void resetPosition(double positionMeters) {
    encoderOffsetMeters = positionMeters - sim.getPosition();
    controller.reset(positionMeters, sim.getVelocity());
  }

  @Override
  public void stop() {
    closedLoop = false;
    appliedVolts = 0.0;
  }

  private double getPositionMeters() {
    return sim.getPosition() + encoderOffsetMeters;
  }
}
