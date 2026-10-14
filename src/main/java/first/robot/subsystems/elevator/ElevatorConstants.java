package first.robot.subsystems.elevator;

import static org.wpilib.units.Units.*;

import com.ctre.phoenix6.CANBus;
import org.wpilib.hardware.bus.CANPort;
import org.wpilib.units.measure.Current;
import org.wpilib.units.measure.Distance;
import org.wpilib.units.measure.LinearAcceleration;
import org.wpilib.units.measure.LinearVelocity;
import org.wpilib.units.measure.Mass;
import org.wpilib.units.measure.Time;
import org.wpilib.units.measure.Voltage;

/**
 * Constants for the trap/amp elevator, modeled on FRC 1706's 2024 "Riot" elevator.
 *
 * <p>1706 ran two NEOs on Spark MAXes with a roboRIO-side ProfiledPIDController and worked in motor
 * rotations. This port uses two Kraken/Falcon TalonFXs with Motion Magic running on the leader, and
 * works in meters of carriage travel. Every value marked TODO must be measured on our robot; the
 * defaults are 1706's numbers converted through the assumed gearing below.
 */
public final class ElevatorConstants {
  private ElevatorConstants() {}

  // CAN (1706 used IDs 14 and 15). SystemCore exposes five CAN-FD ports; put the elevator on the
  // port it is actually wired to.
  public static final CANBus CAN_BUS = new CANBus(CANPort.CAN_S0); // TODO: confirm port
  public static final int LEADER_ID = 14; // TODO: confirm
  public static final int FOLLOWER_ID = 15; // TODO: confirm

  /** 1706 inverted the left motor and not the right; the follower spins opposite the leader. */
  public static final boolean LEADER_INVERTED = true; // TODO: confirm positive = up

  // Mechanism geometry
  public static final double GEAR_RATIO = 9.0; // TODO: motor rotations per drum rotation
  public static final Distance DRUM_RADIUS = Inches.of(0.75); // TODO: measure
  public static final Mass CARRIAGE_MASS = Pounds.of(8.0); // TODO: weigh carriage + manipulator
  public static final Distance MAX_HEIGHT = Inches.of(15.5); // TODO: measure hard stop

  /** Motor rotations per meter of carriage travel. */
  public static final double MOTOR_ROTATIONS_PER_METER =
      GEAR_RATIO / (2.0 * Math.PI * DRUM_RADIUS.in(Meters));

  // Setpoints (1706's values in NEO rotations are noted for reference)
  public static final Distance INTAKE_HEIGHT = Inches.of(0.5); // 1706: 1.0 rot
  public static final Distance REST_HEIGHT = Inches.of(1.35); // 1706: 2.6 rot (kRest)
  public static final Distance STOW_HEIGHT = Inches.of(1.55); // 1706: 3.0 rot
  public static final Distance TRAP_HEIGHT = Inches.of(14.65); // 1706: 28.0 rot
  public static final Distance AT_GOAL_TOLERANCE = Inches.of(0.5);

  // Motion profile (1706: 75 rot/s, 125 rot/s^2 at the motor)
  public static final LinearVelocity MAX_VELOCITY = MetersPerSecond.of(1.0);
  public static final LinearAcceleration MAX_ACCELERATION = MetersPerSecondPerSecond.of(1.65);

  // Gains in volts per meter of travel. Derived from 1706's duty-cycle gains (x12 V, divided by
  // the conversion above). Retune with SysId.
  public static final double KP = 45.0;
  public static final double KI = 0.0; // 1706 ran kI = 0.01 to fight sag; prefer raising kG
  public static final double KD = 0.0;
  public static final double KS = 0.3;
  public static final double KG = 0.25;
  public static final double KV = 9.0;
  public static final double KA = 0.0;

  // Current limits (1706: 30 A smart current limit per NEO)
  public static final Current STATOR_CURRENT_LIMIT = Amps.of(40.0);
  public static final Current SUPPLY_CURRENT_LIMIT = Amps.of(30.0);

  // Homing: drive down until the summed motor current spikes on the hard stop (1706's ZeroElevator
  // used -25% output, a 0.1 s blanking window, and a 40 A combined threshold).
  public static final Voltage HOMING_VOLTAGE = Volts.of(-3.0);
  public static final Time HOMING_BLANKING_TIME = Seconds.of(0.1);
  public static final Current HOMING_CURRENT_THRESHOLD = Amps.of(40.0);
  public static final Time HOMING_TIMEOUT = Seconds.of(3.0);
}
