package first.robot.subsystems.elevator;

import org.littletonrobotics.junction.AutoLog;

public interface ElevatorIO {
  @AutoLog
  public static class ElevatorIOInputs {
    public boolean leaderConnected = false;
    public boolean followerConnected = false;

    public double positionMeters = 0.0;
    public double velocityMetersPerSec = 0.0;
    public double goalMeters = 0.0;

    /** Index 0 is the leader, index 1 is the follower. */
    public double[] appliedVolts = new double[] {0.0, 0.0};

    public double[] statorCurrentAmps = new double[] {0.0, 0.0};
    public double[] supplyCurrentAmps = new double[] {0.0, 0.0};
    public double[] tempCelsius = new double[] {0.0, 0.0};
  }

  /** Updates the set of loggable inputs. */
  public default void updateInputs(ElevatorIOInputs inputs) {}

  /** Run both motors open loop at the specified voltage. */
  public default void setVoltage(double volts) {}

  /** Run a trapezoid-profiled move to the specified height with gravity feedforward. */
  public default void setPosition(double positionMeters) {}

  /** Tell the encoder the carriage is at the specified height. */
  public default void resetPosition(double positionMeters) {}

  /** Stop both motors (brake mode). */
  public default void stop() {}
}
