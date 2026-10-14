package first.robot.subsystems.elevator;

import org.littletonrobotics.junction.AutoLog;

public interface ElevatorIO {
  @AutoLog
  public static class ElevatorIOInputs {
    public boolean leftConnected = false;
    public boolean rightConnected = false;

    public double positionRot = 0.0;
    public double velocityRotPerSec = 0.0;

    public double leftPositionRot = 0.0;
    public double rightPositionRot = 0.0;

    public double leftVelocityRotPerSec = 0.0;
    public double rightVelocityRotPerSec = 0.0;

    public double leftAppliedVolts = 0.0;
    public double rightAppliedVolts = 0.0;

    public double leftCurrentAmps = 0.0;
    public double rightCurrentAmps = 0.0;

    public double leftTempCelsius = 0.0;
    public double rightTempCelsius = 0.0;
  }

  /** Updates the set of loggable inputs. */
  public default void updateInputs(ElevatorIOInputs inputs) {}

  /** Run the elevator motors at the specified voltage. */
  public default void setVoltage(double volts) {
    setVoltage(volts, volts);
  }

  /** Run the elevator left and right motors at individual voltages. */
  public default void setVoltage(double leftVolts, double rightVolts) {}

  /** Stop both elevator motors. */
  public default void stop() {
    setVoltage(0.0, 0.0);
  }

  /** Set the sensor position of both motors in rotations. */
  public default void setPosition(double positionRot) {}
}
