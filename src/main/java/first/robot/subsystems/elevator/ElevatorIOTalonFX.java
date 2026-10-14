package first.robot.subsystems.elevator;

import static first.robot.subsystems.elevator.ElevatorConstants.*;
import static first.robot.util.PhoenixUtil.*;
import static org.wpilib.units.Units.*;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.controls.Follower;
import com.ctre.phoenix6.controls.MotionMagicVoltage;
import com.ctre.phoenix6.controls.NeutralOut;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.ParentDevice;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.GravityTypeValue;
import com.ctre.phoenix6.signals.InvertedValue;
import com.ctre.phoenix6.signals.MotorAlignmentValue;
import com.ctre.phoenix6.signals.NeutralModeValue;
import org.wpilib.math.filter.Debouncer;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.AngularVelocity;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Temperature;
import org.wpilib.units.measure.Voltage;

/**
 * Elevator IO for two TalonFXs. The follower mirrors the leader (opposed, as 1706's left/right NEOs
 * were), and the trapezoid profile + feedforward run on the leader via Motion Magic instead of on
 * the robot controller. SensorToMechanismRatio makes Phoenix "rotations" equal meters.
 */
public class ElevatorIOTalonFX implements ElevatorIO {
  private final TalonFX leader = new TalonFX(LEADER_ID, CAN_BUS);
  private final TalonFX follower = new TalonFX(FOLLOWER_ID, CAN_BUS);

  private final VoltageOut voltageRequest = new VoltageOut(0.0).withEnableFOC(true);
  private final MotionMagicVoltage positionRequest =
      new MotionMagicVoltage(0.0).withEnableFOC(true);
  private final NeutralOut neutralRequest = new NeutralOut();

  private final StatusSignal<Angle> position = leader.getPosition();
  private final StatusSignal<AngularVelocity> velocity = leader.getVelocity();
  private final StatusSignal<Voltage> leaderAppliedVolts = leader.getMotorVoltage();
  private final StatusSignal<Voltage> followerAppliedVolts = follower.getMotorVoltage();
  private final StatusSignal<Current> leaderStatorCurrent = leader.getStatorCurrent();
  private final StatusSignal<Current> followerStatorCurrent = follower.getStatorCurrent();
  private final StatusSignal<Current> leaderSupplyCurrent = leader.getSupplyCurrent();
  private final StatusSignal<Current> followerSupplyCurrent = follower.getSupplyCurrent();
  private final StatusSignal<Temperature> leaderTemp = leader.getDeviceTemp();
  private final StatusSignal<Temperature> followerTemp = follower.getDeviceTemp();

  private final Debouncer leaderConnectedDebounce =
      new Debouncer(0.5, Debouncer.DebounceType.FALLING);
  private final Debouncer followerConnectedDebounce =
      new Debouncer(0.5, Debouncer.DebounceType.FALLING);

  private double goalMeters = 0.0;

  public ElevatorIOTalonFX() {
    var config = new TalonFXConfiguration();
    config.MotorOutput.NeutralMode = NeutralModeValue.Brake;
    config.MotorOutput.Inverted =
        LEADER_INVERTED
            ? InvertedValue.Clockwise_Positive
            : InvertedValue.CounterClockwise_Positive;
    config.Feedback.SensorToMechanismRatio = MOTOR_ROTATIONS_PER_METER;

    config.CurrentLimits.StatorCurrentLimit = STATOR_CURRENT_LIMIT.in(Amps);
    config.CurrentLimits.StatorCurrentLimitEnable = true;
    config.CurrentLimits.SupplyCurrentLimit = SUPPLY_CURRENT_LIMIT.in(Amps);
    config.CurrentLimits.SupplyCurrentLimitEnable = true;

    config.Slot0.GravityType = GravityTypeValue.Elevator_Static;
    config.Slot0.kP = KP;
    config.Slot0.kI = KI;
    config.Slot0.kD = KD;
    config.Slot0.kS = KS;
    config.Slot0.kG = KG;
    config.Slot0.kV = KV;
    config.Slot0.kA = KA;

    config.MotionMagic.MotionMagicCruiseVelocity = MAX_VELOCITY.in(MetersPerSecond);
    config.MotionMagic.MotionMagicAcceleration = MAX_ACCELERATION.in(MetersPerSecondPerSecond);

    // No reverse soft limit: homing deliberately drives into the bottom hard stop.
    config.SoftwareLimitSwitch.ForwardSoftLimitThreshold = MAX_HEIGHT.in(Meters);
    config.SoftwareLimitSwitch.ForwardSoftLimitEnable = true;

    tryUntilOk(5, () -> leader.getConfigurator().apply(config, 0.25));
    tryUntilOk(5, () -> follower.getConfigurator().apply(config, 0.25));
    tryUntilOk(5, () -> leader.setPosition(0.0, 0.25));
    follower.setControl(new Follower(LEADER_ID, MotorAlignmentValue.Opposed));

    // SystemCore CAN-FD has headroom, but the elevator only needs robot-loop rate.
    BaseStatusSignal.setUpdateFrequencyForAll(
        50.0,
        position,
        velocity,
        leaderAppliedVolts,
        followerAppliedVolts,
        leaderStatorCurrent,
        followerStatorCurrent,
        leaderSupplyCurrent,
        followerSupplyCurrent,
        leaderTemp,
        followerTemp);
    ParentDevice.optimizeBusUtilizationForAll(leader, follower);
  }

  @Override
  public void updateInputs(ElevatorIOInputs inputs) {
    var leaderStatus =
        BaseStatusSignal.refreshAll(
            position,
            velocity,
            leaderAppliedVolts,
            leaderStatorCurrent,
            leaderSupplyCurrent,
            leaderTemp);
    var followerStatus =
        BaseStatusSignal.refreshAll(
            followerAppliedVolts, followerStatorCurrent, followerSupplyCurrent, followerTemp);

    inputs.leaderConnected = leaderConnectedDebounce.calculate(leaderStatus.isOK());
    inputs.followerConnected = followerConnectedDebounce.calculate(followerStatus.isOK());
    // Phoenix "rotations" are meters thanks to SensorToMechanismRatio.
    inputs.positionMeters = position.getValueAsDouble();
    inputs.velocityMetersPerSec = velocity.getValueAsDouble();
    inputs.goalMeters = goalMeters;
    inputs.appliedVolts =
        new double[] {
          leaderAppliedVolts.getValueAsDouble(), followerAppliedVolts.getValueAsDouble()
        };
    inputs.statorCurrentAmps =
        new double[] {
          leaderStatorCurrent.getValueAsDouble(), followerStatorCurrent.getValueAsDouble()
        };
    inputs.supplyCurrentAmps =
        new double[] {
          leaderSupplyCurrent.getValueAsDouble(), followerSupplyCurrent.getValueAsDouble()
        };
    inputs.tempCelsius =
        new double[] {leaderTemp.getValueAsDouble(), followerTemp.getValueAsDouble()};
  }

  @Override
  public void setVoltage(double volts) {
    leader.setControl(voltageRequest.withOutput(volts));
  }

  @Override
  public void setPosition(double positionMeters) {
    goalMeters = positionMeters;
    leader.setControl(positionRequest.withPosition(positionMeters));
  }

  @Override
  public void resetPosition(double positionMeters) {
    leader.setPosition(positionMeters);
  }

  @Override
  public void stop() {
    leader.setControl(neutralRequest);
  }
}
