package first.robot.subsystems.drive;

import static org.junit.jupiter.api.Assertions.*;

import com.ctre.phoenix6.AllTimestamps;
import com.ctre.phoenix6.Timestamp;
import com.ctre.phoenix6.Timestamp.TimestampSource;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.wpilib.math.geometry.Rotation2d;
import org.wpilib.math.util.Units;

/** Pure invented SDK observations; these tests construct no device or CAN bus. */
class PhoenixSignalObservationTest {
  private PhoenixSignalObservation.Sample sample(double value, double receipt) {
    return new PhoenixSignalObservation.Sample(
        value, 0, true, receipt, 1, true, receipt, true, receipt, true, 0, false);
  }

  @Test
  void equalValueAndNewReceiptDifferFromChangedValueAndHeldReceipt() {
    var tracker = new PhoenixSignalObservation.Tracker();
    var first = tracker.observe(sample(2, 10), 10.1, 10.2);
    assertEquals("first", first.receiptComparison());
    assertEquals("first", first.rawValueComparison());
    var newReceipt = tracker.observe(sample(2, 11), 11.1, 11.2);
    assertEquals("advanced", newReceipt.receiptComparison());
    assertEquals("held", newReceipt.rawValueComparison());
    var changedValue = tracker.observe(sample(3, 11), 11.3, 11.4);
    assertEquals("held", changedValue.receiptComparison());
    assertEquals("changed", changedValue.rawValueComparison());
    assertEquals(.3, changedValue.ageAtObservationStartSeconds(), 1e-12);
    assertEquals(.4, changedValue.ageAtObservationEndSeconds(), 1e-12);
  }

  @Test
  void mutableSdkTimesDoNotEscapeCopiedPrimitiveSample() throws Exception {
    var timestamps = new AllTimestamps();
    var update =
        Timestamp.class.getDeclaredMethod(
            "update", double.class, TimestampSource.class, boolean.class);
    update.setAccessible(true);
    update.invoke(timestamps.getSystemTimestamp(), 10., TimestampSource.System, true);
    update.invoke(timestamps.getCANivoreTimestamp(), 9.8, TimestampSource.CANivore, true);
    var copy = PhoenixSignalObservation.copy(-0.0, -102, false, timestamps);
    update.invoke(timestamps.getSystemTimestamp(), 20., TimestampSource.System, true);
    update.invoke(timestamps.getCANivoreTimestamp(), 19.8, TimestampSource.CANivore, true);
    update.invoke(timestamps.getDeviceTimestamp(), 19.7, TimestampSource.Device, true);
    assertEquals(10, copy.systemTimestampSeconds());
    assertEquals(9.8, copy.bestTimestampSeconds());
    assertEquals(1, copy.bestTimestampSource());
    assertFalse(copy.deviceTimestampValid());
    assertEquals(-102, copy.statusCode());
    assertFalse(copy.statusOk());
    assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(copy.rawValue()));
  }

  @Test
  void invalidAndRegressingClocksAndNegativeAgesStayExplicit() {
    var tracker = new PhoenixSignalObservation.Tracker();
    var future = tracker.observe(sample(2, 12), 10, 11);
    assertTrue(future.timestampInFuture());
    assertEquals(-2, future.ageAtObservationStartSeconds());
    assertEquals(-1, future.ageAtObservationEndSeconds());
    assertEquals("regressed", tracker.observe(sample(2, 9), 10, 11).receiptComparison());
    var invalid = tracker.observe(sample(Double.NaN, Double.NaN), Double.NaN, 11);
    assertEquals("invalid", invalid.receiptComparison());
    assertEquals("invalid", invalid.rawValueComparison());
    assertTrue(Double.isNaN(invalid.ageAtObservationStartSeconds()));
    assertEquals("first", tracker.observe(sample(2, 13), 14, 15).receiptComparison());
    var clocks = new PhoenixSignalObservation.ClockTracker();
    assertTrue(clocks.observe(100, 200, 10, 11).valid());
    assertTrue(clocks.observe(190, 300, 10.5, 12).regressed());
    assertFalse(clocks.observe(300, 290, 12, 11).valid());
    assertFalse(clocks.observe(300, 400, Double.NaN, 12).valid());
  }

  @Test
  void annotationProcessorRoundTripRetainsExactDiagnosticTypesAndValues() throws Exception {
    var source = new ModuleIOInputsAutoLogged();
    source.phoenixDiagnosticsPresent = true;
    source.phoenixDiagnosticsProfile = PhoenixSignalObservation.PROFILE;
    source.phoenixSnapshotMethod = PhoenixSignalObservation.SNAPSHOT_METHOD;
    source.phoenixObservationSequence = 7;
    source.phoenixRobotObservationStartNs = 9007199254740993L;
    source.phoenixRobotObservationEndNs = 9007199254741027L;
    source.phoenixVendorObservationStartSeconds = 100;
    source.phoenixVendorObservationEndSeconds = 100.02;
    source.phoenixObservationClockValid = true;
    // Group failure must never be promoted/demoted to this individual signal status.
    source.phoenixDriveGroupRefreshStatusCode = -102;
    source.phoenixDriveGroupRefreshStatusOk = false;
    source.phoenixTurnGroupRefreshStatusCode = 0;
    source.phoenixTurnGroupRefreshStatusOk = true;
    var drive = new PhoenixSignalObservation.Tracker().observe(sample(2.5, 99.9), 100, 100.02);
    var turn = new PhoenixSignalObservation.Tracker().observe(sample(.125, 99.8), 100, 100.02);
    drive.writeDrive(source);
    turn.writeTurn(source);
    source.driveVelocityRadPerSec = Units.rotationsToRadians(drive.sample().rawValue());
    source.turnPosition = Rotation2d.fromRotations(turn.sample().rawValue());
    assertTrue(source.phoenixDriveVelocityStatusOk);
    assertFalse(source.driveConnected); // Existing debounced connection remains untouched.
    var table = new LogTable(123);
    source.toLog(table);
    assertEquals(
        LogTable.LoggableType.Integer,
        table.getAll(true).get("PhoenixDriveVelocityStatusCode").type);
    assertEquals(
        LogTable.LoggableType.Integer,
        table.getAll(true).get("PhoenixRobotObservationStartNs").type);
    assertEquals(
        LogTable.LoggableType.Double, table.getAll(true).get("PhoenixDriveVelocityRawValue").type);
    assertEquals(
        LogTable.LoggableType.Boolean, table.getAll(true).get("PhoenixDriveVelocityStatusOk").type);
    var restored = new ModuleIOInputsAutoLogged();
    restored.fromLog(table);
    for (Field field : ModuleIO.ModuleIOInputs.class.getFields()) {
      if (field.getName().startsWith("phoenix")) {
        assertEquals(field.get(source), field.get(restored), field.getName());
      }
    }
    assertEquals(source.driveVelocityRadPerSec, restored.driveVelocityRadPerSec);
    assertEquals(source.turnPosition, restored.turnPosition);
    assertFalse(restored.phoenixPhysicalAcquisitionTimeQualified);
    assertFalse(restored.phoenixNativeTimestampAvailabilityQualified);
  }

  @Test
  void MissingDefaultAndHistoricalInputsCannotInventPhoenixTiming() {
    var inputs = new ModuleIOInputsAutoLogged();
    new ModuleIO() {}.updateInputs(inputs);
    inputs.fromLog(new LogTable(0));
    assertFalse(inputs.phoenixDiagnosticsPresent);
    assertEquals("unavailable", inputs.phoenixDiagnosticsProfile);
    assertEquals("unknown", inputs.phoenixDriveVelocityReceiptComparison);
    assertEquals("unknown", inputs.phoenixTurnPositionRawValueComparison);
    assertTrue(Double.isNaN(inputs.phoenixDriveVelocityBestTimestampSeconds));
    assertTrue(Double.isNaN(inputs.phoenixTurnPositionRawValue));
    assertFalse(inputs.phoenixPhysicalAcquisitionTimeQualified);
  }
}
