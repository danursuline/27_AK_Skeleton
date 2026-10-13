package first.robot.hub;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.wpilib.networktables.NetworkTableInstance;
import org.wpilib.networktables.PubSubOption;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.networktables.StringSubscriber;

/** Explicit opt-in transport. Parsing and native polling/publication stay off the scheduler. */
public final class MarkerIONetworkTables implements MarkerIO {
  private final ArrayBlockingQueue<MarkerProtocol.Request> requests = new ArrayBlockingQueue<>(8);
  private final ArrayBlockingQueue<String> acknowledgements = new ArrayBlockingQueue<>(8);
  private final AtomicLong malformed = new AtomicLong(),
      requestDrops = new AtomicLong(),
      ackDrops = new AtomicLong(),
      faults = new AtomicLong();
  private final StringSubscriber subscriber;
  private final StringPublisher publisher;
  private final Thread worker;
  private volatile boolean closed;

  public MarkerIONetworkTables(NetworkTableInstance instance) {
    subscriber =
        instance
            .getStringTopic(MarkerProtocol.REQUEST_TOPIC)
            .subscribe(
                "",
                PubSubOption.SEND_ALL,
                PubSubOption.KEEP_DUPLICATES,
                PubSubOption.pollStorage(4),
                PubSubOption.periodic(.02));
    publisher =
        instance
            .getStringTopic(MarkerProtocol.ACK_TOPIC)
            .publish(
                PubSubOption.SEND_ALL, PubSubOption.KEEP_DUPLICATES, PubSubOption.periodic(.02));
    worker = new Thread(this::run, "testhub-passive-markers");
    worker.setDaemon(true);
    worker.start();
  }

  private void run() {
    try {
      while (!closed) {
        // Native pollStorage bounds item count, not network/native allocation bytes.
        for (var value : subscriber.readQueue()) {
          if (closed) break;
          try {
            var request = MarkerProtocol.parse(value.value);
            if (!requests.offer(request)) requestDrops.incrementAndGet();
          } catch (IllegalArgumentException invalid) {
            malformed.incrementAndGet();
          }
        }
        for (int i = 0; i < 8 && !closed; i++) {
          String ack = acknowledgements.poll();
          if (ack == null) break;
          publisher.set(ack);
        }
        Thread.sleep(20);
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    } catch (Exception transportFault) {
      faults.incrementAndGet();
    } finally {
      closed = true;
      requests.clear();
      acknowledgements.clear();
      subscriber.close();
      publisher.close();
    }
  }

  @Override
  public MarkerProtocol.Request poll() {
    return closed ? null : requests.poll();
  }

  @Override
  public void acknowledge(String json) {
    if (!closed && !acknowledgements.offer(json)) ackDrops.incrementAndGet();
  }

  @Override
  public void updateInputs(MarkerIOInputs inputs) {
    inputs.malformedRequests = malformed.get();
    inputs.requestQueueDrops = requestDrops.get();
    inputs.ackQueueDrops = ackDrops.get();
    inputs.transportFaults = faults.get();
    inputs.queuedRequests = requests.size();
    inputs.liveEnabled = !closed;
  }

  @Override
  public void close() {
    closed = true;
    worker.interrupt();
    try {
      worker.join(250);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    // Native handles remain worker-owned if a native operation is still completing.
  }
}
