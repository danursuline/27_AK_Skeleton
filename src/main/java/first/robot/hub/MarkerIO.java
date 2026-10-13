package first.robot.hub;

import org.littletonrobotics.junction.AutoLog;

/** Passive marker transport; REPLAY/default-off implementation performs no native/network work. */
public interface MarkerIO extends AutoCloseable {
  @AutoLog
  class MarkerIOInputs {
    public String profile = MarkerProtocol.PROFILE;
    public String runtimeMode = "unavailable";
    public boolean liveEnabled = false;
    public String[] acceptedEnvelopes = new String[0];
    public long malformedRequests = 0;
    public long requestQueueDrops = 0;
    public long ackQueueDrops = 0;
    public long transportFaults = 0;
    public int queuedRequests = 0;
    public int ledgerEntries = 0;
  }

  default MarkerProtocol.Request poll() {
    return null;
  }

  default void acknowledge(String json) {}

  default void updateInputs(MarkerIOInputs inputs) {}

  @Override
  default void close() {}
}
