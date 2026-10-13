package first.robot.subsystems.drive;

import static org.junit.jupiter.api.Assertions.*;

import com.ctre.phoenix6.AllTimestamps;
import com.ctre.phoenix6.Timestamp.TimestampSource;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Independent diagnostic goldens, without Talon/CAN construction or freshness qualification. */
class PhoenixSignalObservationReviewTest {
  private static PhoenixSignalObservation.Sample sample(
      double raw, double receipt, double best, int source, int status, boolean ok, boolean valid) {
    return new PhoenixSignalObservation.Sample(
        raw, status, ok, best, source, valid, receipt, valid, best, valid, best, false);
  }

  @Test
  void receiptAndValueHaveIndependentMeaningDespiteFailureStatus() {
    var tracker = new PhoenixSignalObservation.Tracker();
    var first = tracker.observe(sample(2, 10, 9.9, 0, 0, true, true), 10, 10.01);
    assertEquals("first", first.receiptComparison());
    assertEquals("first", first.rawValueComparison());
    var repeated = tracker.observe(sample(2, 10.02, 9.92, 0, 0, true, true), 10.02, 10.03);
    assertEquals("advanced", repeated.receiptComparison());
    assertEquals("held", repeated.rawValueComparison());
    var changed = tracker.observe(sample(3, 10.02, 9.92, 0, -100, false, true), 10.10, 10.11);
    assertEquals("held", changed.receiptComparison());
    assertEquals("changed", changed.rawValueComparison());
    assertFalse(changed.sample().statusOk());
    assertEquals(-100, changed.sample().statusCode());
    assertEquals(.18, changed.ageAtObservationStartSeconds(), 1e-12);
    assertEquals(.19, changed.ageAtObservationEndSeconds(), 1e-12);
    var inputs = new ModuleIO.ModuleIOInputs();
    inputs.driveConnected = true;
    inputs.phoenixDriveGroupRefreshStatusOk = false;
    inputs.driveVelocityRadPerSec = 123;
    changed.writeDrive(inputs);
    assertTrue(inputs.driveConnected);
    assertFalse(inputs.phoenixDriveGroupRefreshStatusOk);
    assertEquals(123, inputs.driveVelocityRadPerSec);
    assertEquals(3, inputs.phoenixDriveVelocityRawValue);
    assertFalse(inputs.phoenixPhysicalAcquisitionTimeQualified);
    assertFalse(inputs.phoenixNativeTimestampAvailabilityQualified);
  }

  @Test
  void invalidReceiptsResetHistoryAndPreserveInvalidNumbers() {
    for (double invalid :
        new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -.001}) {
      var tracker = new PhoenixSignalObservation.Tracker();
      tracker.observe(sample(1, 10, 10, 0, 0, true, true), 10, 11);
      var bad = tracker.observe(sample(Double.NaN, invalid, invalid, 0, 0, true, true), 12, 13);
      assertEquals("invalid", bad.receiptComparison());
      assertEquals("invalid", bad.rawValueComparison());
      assertEquals(invalid, bad.sample().bestTimestampSeconds());
      var next = tracker.observe(sample(1, 11, 11, 0, 0, true, true), 12, 13);
      assertEquals("first", next.receiptComparison());
      assertEquals("first", next.rawValueComparison());
      assertFalse(next.bestTimestampSourceChanged());
    }
    var tracker = new PhoenixSignalObservation.Tracker();
    var zero = tracker.observe(sample(0, 0, 0, 0, 0, true, true), 0, 0);
    assertEquals("first", zero.receiptComparison());
    assertEquals(0, zero.ageAtObservationEndSeconds());
    var noTimestamp = tracker.observe(sample(1, 1, 1, 0, 0, true, false), 2, 3);
    assertEquals("invalid", noTimestamp.receiptComparison());
    assertFalse(noTimestamp.timestampInFuture());
  }

  @Test
  void bestSourceDoesNotDetermineSystemReceiptAdvancement() {
    var tracker = new PhoenixSignalObservation.Tracker();
    tracker.observe(sample(1, 10, 9, 0, 0, true, true), 10, 10);
    var source = tracker.observe(sample(1, 10, 9.5, 2, 0, true, true), 10, 10);
    assertTrue(source.bestTimestampSourceChanged());
    assertEquals("held", source.receiptComparison());
    var regressed = tracker.observe(sample(1, 9, 9.5, 2, 0, true, true), 10, 11);
    assertEquals("regressed", regressed.receiptComparison());
    assertFalse(regressed.bestTimestampSourceChanged());
    var unknown = tracker.observe(sample(1, 10, 9.5, 42, 0, true, true), 10, 11);
    assertFalse(unknown.bestTimestampSourceChanged());
    assertEquals(42, unknown.sample().bestTimestampSource());
    assertFalse(
        tracker.observe(sample(1, 11, 10, 1, 0, true, true), 11, 12).bestTimestampSourceChanged());
  }

  @Test
  void futureAndClockJumpsRemainRawDiagnosticFacts() {
    var tracker = new PhoenixSignalObservation.Tracker();
    var future = tracker.observe(sample(1, 10, 12, 1, 0, true, true), 10, 11);
    assertTrue(future.timestampInFuture());
    assertEquals(-2, future.ageAtObservationStartSeconds());
    assertEquals(-1, future.ageAtObservationEndSeconds());
    var invalid =
        tracker.observe(sample(1, 10, 12, 1, 0, true, true), Double.NaN, Double.POSITIVE_INFINITY);
    assertTrue(Double.isNaN(invalid.ageAtObservationStartSeconds()));
    assertEquals(Double.POSITIVE_INFINITY, invalid.ageAtObservationEndSeconds());
    assertFalse(invalid.timestampInFuture());
    var clocks = new PhoenixSignalObservation.ClockTracker();
    assertTrue(clocks.observe(9007199254740993L, 9007199254741003L, 100, 100.01).valid());
    var jump = clocks.observe(9007199254741004L, 9007199254741014L, 99, 99.01);
    assertTrue(jump.valid());
    assertTrue(jump.regressed());
    var reverse = clocks.observe(200, 199, 20, 19);
    assertFalse(reverse.valid());
    assertTrue(reverse.regressed());
    assertFalse(clocks.observe(201, 202, Double.NaN, 20).valid());
    var fresh = clocks.observe(203, 204, 30, 31);
    assertTrue(fresh.valid());
    assertFalse(fresh.regressed());
  }

  private static void set(AllTimestamps timestamps, double system, double canivore, double device)
      throws Exception {
    Method update =
        AllTimestamps.class.getDeclaredMethod(
            "update",
            double.class,
            TimestampSource.class,
            boolean.class,
            double.class,
            TimestampSource.class,
            boolean.class,
            double.class,
            TimestampSource.class,
            boolean.class);
    update.setAccessible(true);
    update.invoke(
        timestamps,
        system,
        TimestampSource.System,
        true,
        canivore,
        TimestampSource.CANivore,
        true,
        device,
        TimestampSource.Device,
        device != 0);
  }

  @Test
  void laterSdkMutationCannotRewriteCapturedPrimitives() throws Exception {
    var original = new AllTimestamps();
    set(original, 100, 99.9, 99.8);
    var sample = PhoenixSignalObservation.copy(4, -2, false, original);
    var independent = original.clone();
    set(original, 200, 199.9, 199.8);
    set(independent, 300, 299.9, 299.8);
    assertEquals(100, sample.systemTimestampSeconds());
    assertEquals(99.9, sample.canivoreTimestampSeconds());
    assertEquals(99.8, sample.deviceTimestampSeconds());
    assertEquals(99.8, sample.bestTimestampSeconds());
    assertEquals(2, sample.bestTimestampSource());
    assertEquals(-2, sample.statusCode());
    assertFalse(sample.statusOk());
    assertEquals(200, original.getSystemTimestamp().getTime());
    assertEquals(300, independent.getSystemTimestamp().getTime());
    var defaults = new ModuleIO.ModuleIOInputs();
    assertFalse(defaults.phoenixDiagnosticsPresent);
    assertEquals("unavailable", defaults.phoenixDiagnosticsProfile);
    assertEquals("unknown", defaults.phoenixDriveVelocityReceiptComparison);
    assertEquals("unknown", defaults.phoenixTurnPositionReceiptComparison);
    assertTrue(Double.isNaN(defaults.phoenixVendorObservationEndSeconds));
    assertFalse(defaults.phoenixObservationClockValid);
    assertFalse(defaults.phoenixPhysicalAcquisitionTimeQualified);
    assertFalse(defaults.phoenixNativeTimestampAvailabilityQualified);
    new PhoenixSignalObservation.Tracker().observe(sample, 100, 101).writeTurn(defaults);
    assertEquals(4, defaults.phoenixTurnPositionRawValue);
    assertTrue(Double.isNaN(defaults.phoenixDriveVelocityRawValue));
    assertFalse(defaults.phoenixDiagnosticsPresent);
    assertFalse(defaults.phoenixPhysicalAcquisitionTimeQualified);
  }
}
