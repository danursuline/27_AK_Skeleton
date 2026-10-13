package first.robot.hub;

import first.robot.Constants;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.system.RobotController;

/** Scheduler-owned passive note admission, independent of enable state and bulk transfer gating. */
public final class TestHubMarkers implements AutoCloseable {
  private final MarkerIO io;
  private final MarkerLedger ledger;
  private final MarkerIOInputsAutoLogged inputs = new MarkerIOInputsAutoLogged();
  private final String runtimeMode;
  private final boolean live;
  private final LongSupplier receiptClock;
  private final BooleanSupplier queueFault;
  private final Consumer<LoggableInputs> logInputs;
  private boolean closed;

  public static TestHubMarkers create(
      Constants.Mode mode,
      String robotId,
      String bootId,
      boolean enabled,
      NetworkTableInstance instance) {
    boolean live = enabled && mode != Constants.Mode.REPLAY;
    // REPLAY/default-off must never construct live IO even when a JVM property is present.
    return new TestHubMarkers(
        mode.name(),
        robotId,
        bootId,
        live,
        live ? new MarkerIONetworkTables(instance) : new MarkerIO() {},
        RobotController::getMonotonicTime,
        Logger::getReceiverQueueFault,
        values -> Logger.processInputs("TestHub/Notebook", values));
  }

  /** Injection points allow exact admission/no-op/replay and transport behavior tests. */
  public TestHubMarkers(
      String runtimeMode,
      String robotId,
      String bootId,
      boolean live,
      MarkerIO io,
      LongSupplier receiptClock,
      BooleanSupplier queueFault,
      Consumer<LoggableInputs> logInputs) {
    if (!java.util.Set.of("REAL", "SIM", "REPLAY").contains(runtimeMode))
      throw new IllegalArgumentException("invalid_runtime");
    this.runtimeMode = runtimeMode;
    this.live = live && !runtimeMode.equals("REPLAY");
    this.io = io;
    this.receiptClock = receiptClock;
    this.queueFault = queueFault;
    this.logInputs = logInputs;
    ledger = new MarkerLedger(robotId, bootId);
  }

  /** At most one prevalidated candidate per cycle; no network or file operations here. */
  public void periodic() {
    if (closed) return;
    inputs.acceptedEnvelopes = new String[0];
    inputs.runtimeMode = runtimeMode;
    inputs.liveEnabled = live;
    io.updateInputs(inputs);
    var request = live ? io.poll() : null;
    MarkerLedger.Decision decision = request == null ? null : ledger.prepare(request);
    long receipt = 0;
    if (request != null && decision.reason() == null && !decision.duplicate()) {
      receipt = receiptClock.getAsLong();
      if (receipt >= 0)
        inputs.acceptedEnvelopes = new String[] {MarkerProtocol.loggedEnvelope(request, receipt)};
    }
    inputs.ledgerEntries = ledger.size();
    // This flag belongs to this invocation; fromLog or a stopped Logger cannot set it.
    var admission = new Admission(inputs);
    logInputs.accept(admission);
    if (request == null) return;
    String reason = decision.reason();
    Long acknowledgedReceipt = decision.receiptNs();
    if (reason == null && !decision.duplicate()) {
      if (admission.admitted && inputs.acceptedEnvelopes.length == 1 && receipt >= 0) {
        ledger.commit(request, receipt);
        acknowledgedReceipt = receipt;
      } else reason = "log_input_unavailable";
    }
    io.acknowledge(
        MarkerProtocol.acknowledgement(
            request,
            acknowledgedReceipt,
            decision.duplicate(),
            reason,
            queueFault.getAsBoolean(),
            runtimeMode));
  }

  /** Delegate generated AutoLog serialization, then mark actual live table admission. */
  public static final class Admission implements LoggableInputs {
    private final LoggableInputs delegate;
    private boolean admitted;

    public Admission(LoggableInputs delegate) {
      this.delegate = delegate;
    }

    public boolean admitted() {
      return admitted;
    }

    @Override
    public void toLog(LogTable table) {
      delegate.toLog(table);
      admitted = true;
    }

    @Override
    public void fromLog(LogTable table) {
      delegate.fromLog(table);
    }
  }

  @Override
  public void close() {
    closed = true;
    io.close();
  }
}
