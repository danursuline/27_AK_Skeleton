package first.robot.subsystems.elevator;

import static org.wpilib.units.Units.*;

import org.wpilib.math.system.DCMotor;
import org.wpilib.simulation.ElevatorSim;

/** Physics simulation implementation of ElevatorIO using WPILib ElevatorSim. */
public class ElevatorIOSim implements ElevatorIO {
  private final ElevatorSim sim =
      new ElevatorSim(
          DCMotor.getKrakenX60Foc(2),
          ElevatorConstants.kGearRatio,
          ElevatorConstants.kCarriageMassKg,
          ElevatorConstants.kDrumRadius.in(Meters),
          ElevatorConstants.kMinHeight.in(Meters),
          ElevatorConstants.kMaxHeight.in(Meters),
          true,
          0.0);

  private double appliedVolts = 0.0;

  public ElevatorIOSim() {}

  @Override
  public void updateInputs(ElevatorIOInputs inputs) {
    sim.setInputVoltage(Math.clamp(appliedVolts, -12.0, 12.0));
    sim.update(0.020);

    inputs.leftConnected = true;
    inputs.rightConnected = true;

    double positionMeters = sim.getPosition();
    double velocityMetersPerSec = sim.getVelocity();

    inputs.positionRot = positionMeters / ElevatorConstants.kMetersPerRotation;
    inputs.leftPositionRot = inputs.positionRot;
    inputs.rightPositionRot = inputs.positionRot;

    inputs.velocityRotPerSec = velocityMetersPerSec / ElevatorConstants.kMetersPerRotation;
    inputs.leftVelocityRotPerSec = inputs.velocityRotPerSec;
    inputs.rightVelocityRotPerSec = inputs.velocityRotPerSec;

    inputs.leftAppliedVolts = appliedVolts;
    inputs.rightAppliedVolts = appliedVolts;

    // Simulate current draw; spike current if hard-stopped at bottom while driving down
    double totalCurrent = sim.getCurrentDraw();
    if (sim.hasHitLowerLimit() && appliedVolts < -0.5) {
      totalCurrent = Math.max(totalCurrent, 50.0);
    }

    inputs.leftCurrentAmps = totalCurrent / 2.0;
    inputs.rightCurrentAmps = totalCurrent / 2.0;

    inputs.leftTempCelsius = 30.0;
    inputs.rightTempCelsius = 30.0;
  }

  @Override
  public void setVoltage(double leftVolts, double rightVolts) {
    appliedVolts = (leftVolts + rightVolts) / 2.0;
  }

  @Override
  public void stop() {
    appliedVolts = 0.0;
  }

  @Override
  public void setPosition(double positionRot) {
    double positionMeters = positionRot * ElevatorConstants.kMetersPerRotation;
    sim.setState(positionMeters, sim.getVelocity());
  }
}
