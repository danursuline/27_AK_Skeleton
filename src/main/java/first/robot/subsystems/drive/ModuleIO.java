// Copyright (c) 2021-2026 Littleton Robotics
// http://github.com/Mechanical-Advantage
//
// Use of this source code is governed by a BSD
// license that can be found in the LICENSE file
// at the root directory of this project.

package first.robot.subsystems.drive;

import org.littletonrobotics.junction.AutoLog;
import org.wpilib.math.geometry.Rotation2d;

public interface ModuleIO {
  @AutoLog
  public static class ModuleIOInputs {
    public boolean driveConnected = false;
    public double drivePositionRad = 0.0;
    public double driveVelocityRadPerSec = 0.0;
    public double driveAppliedVolts = 0.0;
    public double driveCurrentAmps = 0.0;

    public boolean turnConnected = false;
    public boolean turnEncoderConnected = false;
    public Rotation2d turnAbsolutePosition = Rotation2d.ZERO;
    public Rotation2d turnPosition = Rotation2d.ZERO;
    public double turnVelocityRadPerSec = 0.0;
    public double turnAppliedVolts = 0.0;
    public double turnCurrentAmps = 0.0;

    public double[] odometryTimestamps = new double[] {};
    public double[] odometryDrivePositionsRad = new double[] {};
    public Rotation2d[] odometryTurnPositions = new Rotation2d[] {};

    // Diagnostic-only SDK observations. Missing SIM/old replay inputs remain unavailable.
    // Java int fields are encoded as int64 by AdvantageKit/WPILOG.
    public boolean phoenixDiagnosticsPresent = false;
    public String phoenixDiagnosticsProfile = "unavailable";
    public String phoenixSnapshotMethod = "unavailable";
    public long phoenixObservationSequence = 0;
    public long phoenixRobotObservationStartNs = 0;
    public long phoenixRobotObservationEndNs = 0;
    public double phoenixVendorObservationStartSeconds = Double.NaN;
    public double phoenixVendorObservationEndSeconds = Double.NaN;
    public boolean phoenixObservationClockValid = false;
    public boolean phoenixObservationClockRegressed = false;
    public boolean phoenixPhysicalAcquisitionTimeQualified = false;
    public boolean phoenixNativeTimestampAvailabilityQualified = false;
    public int phoenixDriveGroupRefreshStatusCode = 0;
    public boolean phoenixDriveGroupRefreshStatusOk = false;
    public int phoenixTurnGroupRefreshStatusCode = 0;
    public boolean phoenixTurnGroupRefreshStatusOk = false;

    public double phoenixDriveVelocityRawValue = Double.NaN; // rotations per second
    public int phoenixDriveVelocityStatusCode = 0;
    public boolean phoenixDriveVelocityStatusOk = false;
    public double phoenixDriveVelocityBestTimestampSeconds = Double.NaN;
    public int phoenixDriveVelocityBestTimestampSource = -1;
    public boolean phoenixDriveVelocityBestTimestampValid = false;
    public double phoenixDriveVelocitySystemTimestampSeconds = Double.NaN;
    public boolean phoenixDriveVelocitySystemTimestampValid = false;
    public double phoenixDriveVelocityCANivoreTimestampSeconds = Double.NaN;
    public boolean phoenixDriveVelocityCANivoreTimestampValid = false;
    public double phoenixDriveVelocityDeviceTimestampSeconds = Double.NaN;
    public boolean phoenixDriveVelocityDeviceTimestampValid = false;
    public String phoenixDriveVelocityReceiptComparison = "unknown";
    public String phoenixDriveVelocityRawValueComparison = "unknown";
    public boolean phoenixDriveVelocityBestTimestampSourceChanged = false;
    public double phoenixDriveVelocityAgeAtObservationStartSeconds = Double.NaN;
    public double phoenixDriveVelocityAgeAtObservationEndSeconds = Double.NaN;
    public boolean phoenixDriveVelocityTimestampInFuture = false;

    public double phoenixTurnPositionRawValue = Double.NaN; // rotations
    public int phoenixTurnPositionStatusCode = 0;
    public boolean phoenixTurnPositionStatusOk = false;
    public double phoenixTurnPositionBestTimestampSeconds = Double.NaN;
    public int phoenixTurnPositionBestTimestampSource = -1;
    public boolean phoenixTurnPositionBestTimestampValid = false;
    public double phoenixTurnPositionSystemTimestampSeconds = Double.NaN;
    public boolean phoenixTurnPositionSystemTimestampValid = false;
    public double phoenixTurnPositionCANivoreTimestampSeconds = Double.NaN;
    public boolean phoenixTurnPositionCANivoreTimestampValid = false;
    public double phoenixTurnPositionDeviceTimestampSeconds = Double.NaN;
    public boolean phoenixTurnPositionDeviceTimestampValid = false;
    public String phoenixTurnPositionReceiptComparison = "unknown";
    public String phoenixTurnPositionRawValueComparison = "unknown";
    public boolean phoenixTurnPositionBestTimestampSourceChanged = false;
    public double phoenixTurnPositionAgeAtObservationStartSeconds = Double.NaN;
    public double phoenixTurnPositionAgeAtObservationEndSeconds = Double.NaN;
    public boolean phoenixTurnPositionTimestampInFuture = false;
  }

  /** Updates the set of loggable inputs. */
  public default void updateInputs(ModuleIOInputs inputs) {}

  /** Run the drive motor at the specified open loop value. */
  public default void setDriveOpenLoop(double output) {}

  /** Run the turn motor at the specified open loop value. */
  public default void setTurnOpenLoop(double output) {}

  /** Run the drive motor at the specified velocity. */
  public default void setDriveVelocity(double velocityRadPerSec) {}

  /** Run the turn motor to the specified rotation. */
  public default void setTurnPosition(Rotation2d rotation) {}
}
