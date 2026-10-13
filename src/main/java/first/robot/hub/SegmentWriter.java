package first.robot.hub;

import first.robot.util.HubIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.littletonrobotics.junction.LogTable.LogValue;

/** Bounded WPILOG 1.0 encoder, validated against the pinned official reader/replay source. */
final class SegmentWriter implements AutoCloseable {
  interface Output extends AutoCloseable {
    OutputStream stream();

    void force() throws IOException;

    long size() throws IOException;

    void close() throws IOException;
  }

  interface Factory {
    Output open(Path path) throws IOException;
  }

  static Output openFile(Path path) throws IOException {
    Files.createFile(path); // Exclusive creation; never replace an artifact.
    var file = new RandomAccessFile(path.toFile(), "rw");
    return new Output() {
      private final OutputStream stream =
          new OutputStream() {
            @Override
            public void write(int value) {
              write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
              try {
                file.write(bytes, offset, length);
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
            }
          };

      public OutputStream stream() {
        return stream;
      }

      public void force() throws IOException {
        file.getFD().sync();
      }

      public long size() throws IOException {
        return file.length();
      }

      public void close() throws IOException {
        file.close();
      }
    };
  }

  private final Output output;
  private final OutputStream stream;
  private final Map<String, Integer> entries = new HashMap<>();
  private final Map<String, LogValue> previous = new HashMap<>();
  private int nextEntry = 2;
  private boolean closed;
  private long lastTimestamp;
  private static final int MAX_RECORD_BYTES = 16 * 1024 * 1024;

  private static final class BoundedBytes extends ByteArrayOutputStream {
    @Override
    public synchronized void write(int value) {
      if (count == MAX_RECORD_BYTES) throw new IllegalArgumentException("Record exceeds bound");
      super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
      if (length > MAX_RECORD_BYTES - count)
        throw new IllegalArgumentException("Record exceeds bound");
      super.write(bytes, offset, length);
    }
  }

  SegmentWriter(Path path, Factory factory) throws IOException {
    output = factory.open(path);
    stream = output.stream();
    try {
      var header = new ByteArrayOutputStream();
      header.writeBytes("WPILOG".getBytes(StandardCharsets.US_ASCII));
      header.write(0);
      header.write(1); // Version 0x0100, little endian.
      byte[] extra = "AdvantageKit".getBytes(StandardCharsets.UTF_8);
      little(header, extra.length, 4);
      header.writeBytes(extra);
      stream.write(header.toByteArray());
      start(1, "/Timestamp", "int64", metadata("nanoseconds"), 0);
    } catch (Exception exception) {
      output.close();
      throw exception;
    }
  }

  private static String metadata(String unit) {
    return "{\"source\":\"AdvantageKit\""
        + (unit == null ? "" : ",\"unit\":" + HubIdentity.quote(unit))
        + "}";
  }

  private static void little(ByteArrayOutputStream out, long value, int bytes) {
    for (int index = 0; index < bytes; index++) out.write((int) (value >>> (index * 8)) & 255);
  }

  private static void string(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    little(out, bytes.length, 4);
    out.writeBytes(bytes);
  }

  private static int width(long value, int maximum) {
    int result = 1;
    while (result < maximum && value >>> (result * 8) != 0) result++;
    return result;
  }

  private void record(int entry, long timestampNs, byte[] payload) throws IOException {
    if (payload.length > MAX_RECORD_BYTES || timestampNs < 0)
      throw new IOException("WPILOG record/time bound exceeded");
    long timestampUs =
        timestampNs / 1000; // Binary header uses microseconds; /Timestamp payload stays ns.
    int entryBytes = width(entry, 4),
        sizeBytes = width(payload.length, 4),
        timeBytes = width(timestampUs, 8);
    var header = new ByteArrayOutputStream(17);
    header.write((entryBytes - 1) | ((sizeBytes - 1) << 2) | ((timeBytes - 1) << 4));
    little(header, entry, entryBytes);
    little(header, payload.length, sizeBytes);
    little(header, timestampUs, timeBytes);
    stream.write(header.toByteArray());
    stream.write(payload);
  }

  private void start(int id, String name, String type, String metadata, long time)
      throws IOException {
    var payload = new ByteArrayOutputStream();
    payload.write(0);
    little(payload, id, 4);
    string(payload, name);
    string(payload, type);
    string(payload, metadata);
    record(0, time, payload.toByteArray());
  }

  private void finish(int id, long time) throws IOException {
    var payload = new ByteArrayOutputStream();
    payload.write(1);
    little(payload, id, 4);
    record(0, time, payload.toByteArray());
  }

  private void setMetadata(int id, String metadata, long time) throws IOException {
    var payload = new ByteArrayOutputStream();
    payload.write(2);
    little(payload, id, 4);
    string(payload, metadata);
    record(0, time, payload.toByteArray());
  }

  private static byte[] number(long value, int size) {
    var out = new ByteArrayOutputStream();
    little(out, value, size);
    return out.toByteArray();
  }

  private static byte[] encode(LogValue value) {
    var out = new BoundedBytes();
    switch (value.type) {
      case Raw -> out.writeBytes(value.getRaw());
      case Boolean -> out.write(value.getBoolean() ? 1 : 0);
      case Integer -> little(out, value.getInteger(), 8);
      case Float -> little(out, Float.floatToRawIntBits(value.getFloat()), 4);
      case Double -> little(out, Double.doubleToRawLongBits(value.getDouble()), 8);
      case String -> out.writeBytes(value.getString().getBytes(StandardCharsets.UTF_8));
      case BooleanArray -> {
        for (boolean item : value.getBooleanArray()) out.write(item ? 1 : 0);
      }
      case IntegerArray -> {
        for (long item : value.getIntegerArray()) little(out, item, 8);
      }
      case FloatArray -> {
        for (float item : value.getFloatArray()) little(out, Float.floatToRawIntBits(item), 4);
      }
      case DoubleArray -> {
        for (double item : value.getDoubleArray()) little(out, Double.doubleToRawLongBits(item), 8);
      }
      case StringArray -> {
        var items = value.getStringArray();
        little(out, items.length, 4);
        for (String item : items) string(out, item);
      }
    }
    return out.toByteArray();
  }

  void write(long timestamp, Map<String, LogValue> values) throws IOException {
    lastTimestamp = timestamp;
    record(1, timestamp, number(timestamp, 8));
    var ordered =
        new TreeMap<String, LogValue>(
            (a, b) -> {
              int category =
                  Boolean.compare(!a.startsWith("/.schema/"), !b.startsWith("/.schema/"));
              return category == 0 ? a.compareTo(b) : category;
            });
    ordered.putAll(values);
    for (var field : ordered.entrySet()) {
      String key = field.getKey();
      LogValue value = field.getValue(), old = previous.get(key);
      if (old != null && !value.getWPILOGType().equals(old.getWPILOGType())) {
        finish(entries.remove(key), timestamp);
        old = null;
      }
      int id;
      if (!entries.containsKey(key)) {
        id = nextEntry++;
        if (id <= 1) throw new IOException("Entry ID exhausted");
        entries.put(key, id);
        start(id, key, value.getWPILOGType(), metadata(value.unitStr), timestamp);
      } else {
        id = entries.get(key);
        if (!Objects.equals(value.unitStr, old.unitStr))
          setMetadata(id, metadata(value.unitStr), timestamp);
      }
      if (old == null || !value.equals(old) || !Objects.equals(value.unitStr, old.unitStr))
        record(id, timestamp, encode(value));
    }
    stream.flush();
    output.force();
    previous.putAll(
        values); // Omitted keys remain held state, matching AK/receiver table semantics.
  }

  long size() throws IOException {
    return output.size();
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      // Alpha 7 iterator requires 16 remaining bytes: trailer preserves the final short data
      // record.
      setMetadata(1, metadata("nanoseconds"), lastTimestamp);
      stream.flush();
      output.force();
    } finally {
      output.close();
    }
  }
}
