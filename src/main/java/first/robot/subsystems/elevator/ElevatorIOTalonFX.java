package first.robot.subsystems.elevator;

import static first.robot.util.PhoenixUtil.tryUntilOk;
import static org.wpilib.units.Units.Amps;

import com.ctre.phoenix6.BaseStatusSignal;
import com.ctre.phoenix6.CANBus;
import com.ctre.phoenix6.StatusSignal;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.ParentDevice;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.signals.NeutralModeValue;
import org.wpilib.math.filter.Debouncer;
import org.wpilib.units.measure.Angle;
import org.wpilib.units.measure.AngularVelocity;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Temperature;
import org.wpilib.units.measure.Voltage;

/**
 * Concrete CTRE Phoenix 6 Talon FX implementation for the Elevator subsystem. Communicates with
 * dual Talon FX motors on the NI SystemCore CAN bus.
 */
public class ElevatorIOTalonFX implements ElevatorIO {
  private final TalonFX leftTalon;
  private final TalonFX rightTalon;

  private final VoltageOut voltageRequest = new VoltageOut(0.0);

  // Status signals from Left Talon
  private final StatusSignal<Angle> leftPosition;
  private final StatusSignal<AngularVelocity> leftVelocity;
  private final StatusSignal<Voltage> leftAppliedVolts;
  private final StatusSignal<Current> leftCurrent;
  private final StatusSignal<Temperature> leftTemp;

  // Status signals from Right Talon
  private final StatusSignal<Angle> rightPosition;
  private final StatusSignal<AngularVelocity> rightVelocity;
  private final StatusSignal<Voltage> rightAppliedVolts;
  private final StatusSignal<Current> rightCurrent;
  private final StatusSignal<Temperature> rightTemp;

  // Connection debouncers
  private final Debouncer leftConnectedDebounce =
      new Debouncer(0.5, Debouncer.DebounceType.FALLING);
  private final Debouncer rightConnectedDebounce =
      new Debouncer(0.5, Debouncer.DebounceType.FALLING);

  public ElevatorIOTalonFX() {
    this(
        ElevatorConstants.kLeftMotorId, ElevatorConstants.kRightMotorId, ElevatorConstants.kCANBus);
  }

  public ElevatorIOTalonFX(int leftMotorId, int rightMotorId, CANBus canBus) {
    leftTalon = new TalonFX(leftMotorId, canBus);
    rightTalon = new TalonFX(rightMotorId, canBus);

    // Left motor configuration
    var leftConfig = new TalonFXConfiguration();
    leftConfig.MotorOutput.NeutralMode = NeutralModeValue.Brake;
    leftConfig.MotorOutput.Inverted = ElevatorConstants.kLeftInverted;
    leftConfig.CurrentLimits.StatorCurrentLimit = ElevatorConstants.kStatorCurrentLimit.in(Amps);
    leftConfig.CurrentLimits.StatorCurrentLimitEnable = true;
    leftConfig.CurrentLimits.SupplyCurrentLimit = ElevatorConstants.kSupplyCurrentLimit.in(Amps);
    leftConfig.CurrentLimits.SupplyCurrentLimitEnable = true;

    // Right motor configuration
    var rightConfig = new TalonFXConfiguration();
    rightConfig.MotorOutput.NeutralMode = NeutralModeValue.Brake;
    rightConfig.MotorOutput.Inverted = ElevatorConstants.kRightInverted;
    rightConfig.CurrentLimits.StatorCurrentLimit = ElevatorConstants.kStatorCurrentLimit.in(Amps);
    rightConfig.CurrentLimits.StatorCurrentLimitEnable = true;
    rightConfig.CurrentLimits.SupplyCurrentLimit = ElevatorConstants.kSupplyCurrentLimit.in(Amps);
    rightConfig.CurrentLimits.SupplyCurrentLimitEnable = true;

    tryUntilOk(5, () -> leftTalon.getConfigurator().apply(leftConfig, 0.25));
    tryUntilOk(5, () -> rightTalon.getConfigurator().apply(rightConfig, 0.25));

    tryUntilOk(5, () -> leftTalon.setPosition(0.0, 0.25));
    tryUntilOk(5, () -> rightTalon.setPosition(0.0, 0.25));

    // Initialize status signals
    leftPosition = leftTalon.getPosition();
    leftVelocity = leftTalon.getVelocity();
    leftAppliedVolts = leftTalon.getMotorVoltage();
    leftCurrent = leftTalon.getStatorCurrent();
    leftTemp = leftTalon.getDeviceTemp();

    rightPosition = rightTalon.getPosition();
    rightVelocity = rightTalon.getVelocity();
    rightAppliedVolts = rightTalon.getMotorVoltage();
    rightCurrent = rightTalon.getStatorCurrent();
    rightTemp = rightTalon.getDeviceTemp();

    BaseStatusSignal.setUpdateFrequencyForAll(
        50.0,
        leftPosition,
        leftVelocity,
        leftAppliedVolts,
        leftCurrent,
        leftTemp,
        rightPosition,
        rightVelocity,
        rightAppliedVolts,
        rightCurrent,
        rightTemp);

    ParentDevice.optimizeBusUtilizationForAll(leftTalon, rightTalon);
  }

  @Override
  public void updateInputs(ElevatorIOInputs inputs) {
    var leftStatus =
        BaseStatusSignal.refreshAll(
            leftPosition, leftVelocity, leftAppliedVolts, leftCurrent, leftTemp);
    var rightStatus =
        BaseStatusSignal.refreshAll(
            rightPosition, rightVelocity, rightAppliedVolts, rightCurrent, rightTemp);

    inputs.leftConnected = leftConnectedDebounce.calculate(leftStatus.isOK());
    inputs.rightConnected = rightConnectedDebounce.calculate(rightStatus.isOK());

    inputs.leftPositionRot = leftPosition.getValueAsDouble();
    inputs.rightPositionRot = rightPosition.getValueAsDouble();
    inputs.positionRot = (inputs.leftPositionRot + inputs.rightPositionRot) / 2.0;

    inputs.leftVelocityRotPerSec = leftVelocity.getValueAsDouble();
    inputs.rightVelocityRotPerSec = rightVelocity.getValueAsDouble();
    inputs.velocityRotPerSec = (inputs.leftVelocityRotPerSec + inputs.rightVelocityRotPerSec) / 2.0;

    inputs.leftAppliedVolts = leftAppliedVolts.getValueAsDouble();
    inputs.rightAppliedVolts = rightAppliedVolts.getValueAsDouble();

    inputs.leftCurrentAmps = leftCurrent.getValueAsDouble();
    inputs.rightCurrentAmps = rightCurrent.getValueAsDouble();

    inputs.leftTempCelsius = leftTemp.getValueAsDouble();
    inputs.rightTempCelsius = rightTemp.getValueAsDouble();
  }

  @Override
  public void setVoltage(double leftVolts, double rightVolts) {
    leftTalon.setControl(voltageRequest.withOutput(leftVolts));
    rightTalon.setControl(voltageRequest.withOutput(rightVolts));
  }

  @Override
  public void stop() {
    leftTalon.stopMotor();
    rightTalon.stopMotor();
  }

  @Override
  public void setPosition(double positionRot) {
    tryUntilOk(5, () -> leftTalon.setPosition(positionRot, 0.05));
    tryUntilOk(5, () -> rightTalon.setPosition(positionRot, 0.05));
  }
}
