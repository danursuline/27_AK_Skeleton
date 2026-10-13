package first.robot.subsystems.drive;

import com.ctre.phoenix6.AllTimestamps;
import com.ctre.phoenix6.BaseStatusSignal;

/**
 * Copies the scheduler-owned Phoenix Java cache without refreshing it or consuming hasUpdated().
 * Vendor timestamps describe SDK receipt/transmit timing, not qualified sensor acquisition time.
 * The pinned SDK does not promise an atomic native snapshot; clock bookends are observation spans.
 */
public final class PhoenixSignalObservation {
  public static final String PROFILE = "phoenix6-status-observation-1";
  public static final String SNAPSHOT_METHOD = "scheduler_owned_cached_read_after_existing_refresh";

  private PhoenixSignalObservation() {}

  /** Independent primitive copies; no mutable SDK Timestamp escapes the copy. */
  public record Sample(
      double rawValue,
      int statusCode,
      boolean statusOk,
      double bestTimestampSeconds,
      int bestTimestampSource,
      boolean bestTimestampValid,
      double systemTimestampSeconds,
      boolean systemTimestampValid,
      double canivoreTimestampSeconds,
      boolean canivoreTimestampValid,
      double deviceTimestampSeconds,
      boolean deviceTimestampValid) {}

  /** Caller must own this exact Java signal object throughout the cached read. */
  public static Sample copyCached(BaseStatusSignal signal) {
    double rawValue = signal.getValueAsDouble();
    // Clone immediately: getAllTimestamps() itself returns mutable owned state.
    AllTimestamps timestamps = signal.getAllTimestamps().clone();
    var status = signal.getStatus();
    return fromCopiedTimestamps(rawValue, status.value, status.isOK(), timestamps);
  }

  /** Pure test seam; the supplied mutable SDK timestamps are copied immediately. */
  public static Sample copy(
      double rawValue, int statusCode, boolean statusOk, AllTimestamps timestamps) {
    return fromCopiedTimestamps(rawValue, statusCode, statusOk, timestamps.clone());
  }

  private static Sample fromCopiedTimestamps(
      double rawValue, int statusCode, boolean statusOk, AllTimestamps timestamps) {
    var best = timestamps.getBestTimestamp();
    var system = timestamps.getSystemTimestamp();
    var canivore = timestamps.getCANivoreTimestamp();
    var device = timestamps.getDeviceTimestamp();
    return new Sample(
        rawValue,
        statusCode,
        statusOk,
        best.getTime(),
        best.getSource().value,
        best.isValid(),
        system.getTime(),
        system.isValid(),
        canivore.getTime(),
        canivore.isValid(),
        device.getTime(),
        device.isValid());
  }

  public record Observation(
      Sample sample,
      String receiptComparison,
      String rawValueComparison,
      boolean bestTimestampSourceChanged,
      double ageAtObservationStartSeconds,
      double ageAtObservationEndSeconds,
      boolean timestampInFuture) {
    public void writeDrive(ModuleIO.ModuleIOInputs inputs) {
      inputs.phoenixDriveVelocityRawValue = sample.rawValue();
      inputs.phoenixDriveVelocityStatusCode = sample.statusCode();
      inputs.phoenixDriveVelocityStatusOk = sample.statusOk();
      inputs.phoenixDriveVelocityBestTimestampSeconds = sample.bestTimestampSeconds();
      inputs.phoenixDriveVelocityBestTimestampSource = sample.bestTimestampSource();
      inputs.phoenixDriveVelocityBestTimestampValid = sample.bestTimestampValid();
      inputs.phoenixDriveVelocitySystemTimestampSeconds = sample.systemTimestampSeconds();
      inputs.phoenixDriveVelocitySystemTimestampValid = sample.systemTimestampValid();
      inputs.phoenixDriveVelocityCANivoreTimestampSeconds = sample.canivoreTimestampSeconds();
      inputs.phoenixDriveVelocityCANivoreTimestampValid = sample.canivoreTimestampValid();
      inputs.phoenixDriveVelocityDeviceTimestampSeconds = sample.deviceTimestampSeconds();
      inputs.phoenixDriveVelocityDeviceTimestampValid = sample.deviceTimestampValid();
      inputs.phoenixDriveVelocityReceiptComparison = receiptComparison;
      inputs.phoenixDriveVelocityRawValueComparison = rawValueComparison;
      inputs.phoenixDriveVelocityBestTimestampSourceChanged = bestTimestampSourceChanged;
      inputs.phoenixDriveVelocityAgeAtObservationStartSeconds = ageAtObservationStartSeconds;
      inputs.phoenixDriveVelocityAgeAtObservationEndSeconds = ageAtObservationEndSeconds;
      inputs.phoenixDriveVelocityTimestampInFuture = timestampInFuture;
    }

    public void writeTurn(ModuleIO.ModuleIOInputs inputs) {
      inputs.phoenixTurnPositionRawValue = sample.rawValue();
      inputs.phoenixTurnPositionStatusCode = sample.statusCode();
      inputs.phoenixTurnPositionStatusOk = sample.statusOk();
      inputs.phoenixTurnPositionBestTimestampSeconds = sample.bestTimestampSeconds();
      inputs.phoenixTurnPositionBestTimestampSource = sample.bestTimestampSource();
      inputs.phoenixTurnPositionBestTimestampValid = sample.bestTimestampValid();
      inputs.phoenixTurnPositionSystemTimestampSeconds = sample.systemTimestampSeconds();
      inputs.phoenixTurnPositionSystemTimestampValid = sample.systemTimestampValid();
      inputs.phoenixTurnPositionCANivoreTimestampSeconds = sample.canivoreTimestampSeconds();
      inputs.phoenixTurnPositionCANivoreTimestampValid = sample.canivoreTimestampValid();
      inputs.phoenixTurnPositionDeviceTimestampSeconds = sample.deviceTimestampSeconds();
      inputs.phoenixTurnPositionDeviceTimestampValid = sample.deviceTimestampValid();
      inputs.phoenixTurnPositionReceiptComparison = receiptComparison;
      inputs.phoenixTurnPositionRawValueComparison = rawValueComparison;
      inputs.phoenixTurnPositionBestTimestampSourceChanged = bestTimestampSourceChanged;
      inputs.phoenixTurnPositionAgeAtObservationStartSeconds = ageAtObservationStartSeconds;
      inputs.phoenixTurnPositionAgeAtObservationEndSeconds = ageAtObservationEndSeconds;
      inputs.phoenixTurnPositionTimestampInFuture = timestampInFuture;
    }
  }

  /** Receipt comparison uses System time, matching the SDK latch without consuming it. */
  public static final class Tracker {
    private boolean receiptKnown;
    private double previousReceipt;
    private boolean valueKnown;
    private long previousValueBits;
    private boolean sourceKnown;
    private int previousSource;

    public Observation observe(Sample sample, double vendorStart, double vendorEnd) {
      String receipt;
      if (!sample.systemTimestampValid() || !validTime(sample.systemTimestampSeconds())) {
        receipt = "invalid";
        receiptKnown = false;
      } else {
        double current = sample.systemTimestampSeconds();
        receipt =
            !receiptKnown
                ? "first"
                : current == previousReceipt
                    ? "held"
                    : current > previousReceipt ? "advanced" : "regressed";
        previousReceipt = current;
        receiptKnown = true;
      }
      String value;
      if (!Double.isFinite(sample.rawValue())) {
        value = "invalid";
        valueKnown = false;
      } else {
        long bits = Double.doubleToRawLongBits(sample.rawValue());
        value = !valueKnown ? "first" : bits == previousValueBits ? "held" : "changed";
        previousValueBits = bits;
        valueKnown = true;
      }
      boolean validSource =
          sample.bestTimestampValid()
              && validTime(sample.bestTimestampSeconds())
              && sample.bestTimestampSource() >= 0
              && sample.bestTimestampSource() <= 2;
      boolean changedSource =
          validSource && sourceKnown && sample.bestTimestampSource() != previousSource;
      sourceKnown = validSource;
      previousSource = sample.bestTimestampSource();
      // Preserve raw negative/nonfinite ages; never clamp them to apparent freshness.
      return new Observation(
          sample,
          receipt,
          value,
          changedSource,
          vendorStart - sample.bestTimestampSeconds(),
          vendorEnd - sample.bestTimestampSeconds(),
          sample.bestTimestampValid()
              && validTime(sample.bestTimestampSeconds())
              && validTime(vendorEnd)
              && sample.bestTimestampSeconds() > vendorEnd);
    }
  }

  public record ClockObservation(boolean valid, boolean regressed) {}

  /** Both clocks remain separate; validity checks do not establish a shared epoch or offset. */
  public static final class ClockTracker {
    private boolean previousKnown;
    private long previousRobotEnd;
    private double previousVendorEnd;

    public ClockObservation observe(
        long robotStart, long robotEnd, double vendorStart, double vendorEnd) {
      boolean valid =
          robotStart >= 0
              && robotEnd >= robotStart
              && validTime(vendorStart)
              && validTime(vendorEnd)
              && vendorEnd >= vendorStart;
      boolean regressed =
          robotEnd < robotStart
              || (Double.isFinite(vendorStart)
                  && Double.isFinite(vendorEnd)
                  && vendorEnd < vendorStart)
              || (previousKnown
                  && (robotStart < previousRobotEnd || vendorStart < previousVendorEnd));
      previousKnown = valid;
      previousRobotEnd = robotEnd;
      previousVendorEnd = vendorEnd;
      return new ClockObservation(valid, regressed);
    }
  }

  private static boolean validTime(double time) {
    return Double.isFinite(time) && time >= 0;
  }
}
