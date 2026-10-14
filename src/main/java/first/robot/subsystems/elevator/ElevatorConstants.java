package first.robot.subsystems.elevator;

import static org.wpilib.units.Units.*;

import com.ctre.phoenix6.CANBus;
import com.ctre.phoenix6.signals.InvertedValue;
import first.robot.generated.TunerConstants;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.Voltage;

/**
 * Constants for the Elevator subsystem inspired by FRC 1706 (Ratchet Rockers) 2024 Trap Elevator.
 * Configured for CTRE Phoenix 6 Talon FX motors running on NI SystemCore CAN bus.
 */
public final class ElevatorConstants {
  // CAN Bus & IDs
  public static final CANBus kCANBus = TunerConstants.kCANBus;
  public static final int kLeftMotorId = 14;
  public static final int kRightMotorId = 15;

  // Motor Inversions (Left inverted, Right standard, matching 1706)
  public static final InvertedValue kLeftInverted = InvertedValue.Clockwise_Positive;
  public static final InvertedValue kRightInverted = InvertedValue.CounterClockwise_Positive;

  // Current & Voltage Limits
  public static final Current kStatorCurrentLimit = Amps.of(60.0);
  public static final Current kSupplyCurrentLimit = Amps.of(40.0);
  public static final Voltage kPeakVoltage = Volts.of(12.0);

  // Mechanism Geometry
  public static final Distance kDrumRadius = Inches.of(1.0);
  public static final double kGearRatio = 10.0;
  public static final double kMetersPerRotation =
      (2.0 * Math.PI * kDrumRadius.in(Meters)) / kGearRatio;

  // Setpoints in Rotations (matching 1706)
  public static final double kBottomRotations = 0.0;
  public static final double kIntakeRotations = 1.0;
  public static final double kRestRotations = 2.6;
  public static final double kTrapScoreRotations = 28.0;

  // Soft Limits
  public static final double kMinRotations = 0.0;
  public static final double kMaxRotations = 30.0;

  // Profile Constraints (Velocity in rot/s, Acceleration in rot/s^2, matching 1706)
  public static final double kMaxVelocityRotPerSec = 75.0;
  public static final double kMaxAccelerationRotPerSec2 = 125.0;

  // Feedback & Feedforward Gains (Voltage-scaled from 1706's duty-cycle controller)
  public static final double kP = 0.60; // 0.05 * 12V
  public static final double kI = 0.12; // 0.01 * 12V
  public static final double kD = 0.00;
  public static final double kS = 0.30; // 0.025 * 12V
  public static final double kG = 0.24; // 0.02 * 12V
  public static final double kV = 0.0656; // (1.0 / 183.0) * 12V
  public static final double kA = 0.00;

  public static final double kPositionToleranceRot = 0.25;

  // Homing Routine Parameters (1706 Current Spike Detection)
  public static final Voltage kHomingVoltage = Volts.of(-3.0); // -0.25 duty cycle
  public static final double kHomingCurrentSpikeThresholdAmps = 40.0; // Sum of both motors
  public static final double kHomingDebounceSeconds = 0.10;

  // Simulation Parameters
  public static final double kCarriageMassKg = 5.0;
  public static final Distance kMinHeight = Meters.of(0.0);
  public static final Distance kMaxHeight = Meters.of(1.1);
}
