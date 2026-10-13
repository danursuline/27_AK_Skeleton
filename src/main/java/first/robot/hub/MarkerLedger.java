package first.robot.hub;

import java.util.HashMap;
import java.util.Map;

/** Scheduler-owned, boot-local admission ledger. No eviction can silently permit a duplicate. */
public final class MarkerLedger {
  public record Decision(String reason, boolean duplicate, Long receiptNs) {}

  private record Entry(String annotationHash, String payloadHash, long receiptNs) {}

  private final String robotId, bootId;
  private final int capacity;
  private final Map<String, Entry> entries = new HashMap<>();

  public MarkerLedger(String robotId, String bootId) {
    this(robotId, bootId, 1024);
  }

  public MarkerLedger(String robotId, String bootId, int capacity) {
    if (capacity < 1 || capacity > 1024) throw new IllegalArgumentException("invalid_capacity");
    this.robotId = robotId;
    this.bootId = bootId;
    this.capacity = capacity;
  }

  public Decision prepare(MarkerProtocol.Request r) {
    if (!r.robotId().equals(robotId)) return new Decision("wrong_robot", false, null);
    if (!r.bootId().equals(bootId)) return new Decision("wrong_boot", false, null);
    var old = entries.get(r.key());
    if (old != null) {
      if (!old.annotationHash().equals(r.annotationSha256())
          || !old.payloadHash().equals(r.payloadSha256()))
        return new Decision("payload_conflict", false, null);
      return new Decision(null, true, old.receiptNs());
    }
    return new Decision(entries.size() >= capacity ? "capacity" : null, false, null);
  }

  /** Call only after the exact envelope has entered the live AdvantageKit input table. */
  public void commit(MarkerProtocol.Request r, long receiptNs) {
    var decision = prepare(r);
    if (decision.reason() != null || decision.duplicate())
      throw new IllegalStateException("invalid_commit");
    entries.put(r.key(), new Entry(r.annotationSha256(), r.payloadSha256(), receiptNs));
  }

  public int size() {
    return entries.size();
  }
}
