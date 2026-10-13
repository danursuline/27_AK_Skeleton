package first.robot.hub;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import first.robot.util.HubIdentity;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Set;

/** Bounded, strict wire parsing, performed off the robot scheduler thread. */
public final class MarkerProtocol {
  public static final String PROFILE = "testhub-note-marker-1";
  public static final String REQUEST_TOPIC = "/TestHub/Notebook/MarkerRequest";
  public static final String ACK_TOPIC = "/Telemetry/TestHub/MarkerAck";
  public static final int MAX_BYTES = 16384;
  private static final Gson JSON =
      new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
  private static final Set<String> OUTER =
      Set.of(
          "schema_version",
          "profile",
          "delivery_id",
          "destination_robot_id",
          "destination_boot_id",
          "event_id",
          "revision",
          "annotation_sha256",
          "payload_sha256",
          "payload_json");
  private static final Set<String> INNER =
      Set.of(
          "schema_version",
          "event_id",
          "revision",
          "submitted_utc_ns",
          "client_monotonic_ns",
          "event_utc_start_ns",
          "event_utc_end_ns",
          "when",
          "uncertainty_ms",
          "clock_domain",
          "clock_quality",
          "text",
          "tags",
          "source");

  public record Request(
      String wire,
      String deliveryId,
      String robotId,
      String bootId,
      String eventId,
      int revision,
      String annotationSha256,
      String payloadSha256,
      String payloadJson) {
    public String key() {
      return eventId + ":" + revision + ":" + robotId + ":" + bootId;
    }
  }

  public static Request parse(String wire) {
    try {
      require(wire != null && wire.length() <= MAX_BYTES);
      require(utf8Size(wire) <= MAX_BYTES);
      JsonObject outer = object(parseJson(wire));
      require(outer.keySet().equals(OUTER));
      require(integer(outer.get("schema_version"), 1, 1) == 1);
      require(string(outer.get("profile")).equals(PROFILE));
      String delivery = id(outer.get("delivery_id"));
      String robot = id(outer.get("destination_robot_id"));
      String boot = id(outer.get("destination_boot_id"));
      String event = id(outer.get("event_id"));
      int revision = integer(outer.get("revision"), 1, 1000000);
      String annotation = hash(outer.get("annotation_sha256"));
      String payloadHash = hash(outer.get("payload_sha256"));
      String payload = string(outer.get("payload_json"));
      require(payloadHash.equals(HubIdentity.sha256(payload)));
      JsonObject note = object(parseJson(payload));
      require(note.keySet().equals(INNER));
      require(integer(note.get("schema_version"), 1, 1) == 1);
      require(id(note.get("event_id")).equals(event));
      require(integer(note.get("revision"), 1, 1000000) == revision);
      validateNote(note);
      return new Request(
          wire, delivery, robot, boot, event, revision, annotation, payloadHash, payload);
    } catch (Exception invalid) {
      // Never propagate a parser diagnostic containing a driver's private note.
      throw new IllegalArgumentException("invalid_payload");
    }
  }

  private static void validateNote(JsonObject n) {
    Long submitted = ns(n.get("submitted_utc_ns"));
    ns(n.get("client_monotonic_ns"));
    Long start = ns(n.get("event_utc_start_ns")), end = ns(n.get("event_utc_end_ns"));
    require((start == null) == (end == null));
    if (start != null) require(end >= start);
    bounded(n.get("clock_domain"), 128, false);
    String quality = string(n.get("clock_quality"));
    require(Set.of("unverified_client", "user_estimate", "unknown").contains(quality));
    JsonElement uncertainty = n.get("uncertainty_ms");
    if (!uncertainty.isJsonNull()) number(uncertainty, 86400000);
    if (start != null && uncertainty.isJsonNull()) require(quality.equals("unknown"));
    bounded(n.get("text"), 4096, true);
    bounded(n.get("source"), 128, false);
    require(n.get("tags").isJsonArray());
    var tags = n.getAsJsonArray("tags");
    require(tags.size() <= 20);
    for (var tag : tags) bounded(tag, 64, false);
    JsonObject when = object(n.get("when"));
    String kind = string(when.get("kind"));
    BigDecimal seconds = BigDecimal.ZERO;
    switch (kind) {
      case "now", "unknown":
        require(when.keySet().equals(Set.of("kind")));
        if (kind.equals("unknown")) require(start == null);
        break;
      case "seconds_ago":
        require(when.keySet().equals(Set.of("kind", "seconds")));
        seconds = number(when.get("seconds"), 604800);
        break;
      case "exact_interval":
        require(when.keySet().equals(Set.of("kind", "start", "end")));
        require(
            start != null && start == isoNs(when.get("start")) && end == isoNs(when.get("end")));
        break;
      default:
        throw new IllegalArgumentException();
    }
    if (!kind.equals("exact_interval")
        && !kind.equals("unknown")
        && submitted != null
        && start != null) {
      long expected =
          Math.subtractExact(submitted, seconds.multiply(new BigDecimal("1000000000")).longValue());
      require(start == expected && end == expected);
    }
  }

  private static long isoNs(JsonElement value) {
    bounded(value, 128, false);
    String s = string(value);
    require(
        s.matches(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2})?(?:\\.\\d{1,9})?(?:Z|[+-]\\d{2}:\\d{2})"));
    var instant = OffsetDateTime.parse(s).toInstant();
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1000000000L), instant.getNano());
  }

  private static JsonElement parseJson(String text) throws Exception {
    try (var reader = new JsonReader(new StringReader(text))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement value = read(reader, 0, new int[] {256});
      require(reader.peek() == JsonToken.END_DOCUMENT);
      return value;
    }
  }

  private static JsonElement read(JsonReader r, int depth, int[] remaining) throws Exception {
    require(depth <= 6 && --remaining[0] >= 0);
    switch (r.peek()) {
      case BEGIN_OBJECT:
        var obj = new JsonObject();
        r.beginObject();
        while (r.hasNext()) {
          String name = r.nextName();
          utf8Size(name);
          require(!obj.has(name));
          obj.add(name, read(r, depth + 1, remaining));
        }
        r.endObject();
        return obj;
      case BEGIN_ARRAY:
        var array = new JsonArray();
        r.beginArray();
        while (r.hasNext()) array.add(read(r, depth + 1, remaining));
        r.endArray();
        return array;
      case STRING:
        String value = r.nextString();
        utf8Size(value);
        return new JsonPrimitive(value);
      case NUMBER:
        return new JsonPrimitive(new WireNumber(r.nextString()));
      case BOOLEAN:
        return new JsonPrimitive(r.nextBoolean());
      case NULL:
        r.nextNull();
        return JsonNull.INSTANCE;
      default:
        throw new IllegalArgumentException();
    }
  }

  private static JsonObject object(JsonElement value) {
    require(value != null && value.isJsonObject());
    return value.getAsJsonObject();
  }

  /** Preserve integer token spelling so 1e0 and 1.0 cannot become schema integer 1. */
  private static final class WireNumber extends Number {
    private final String raw;
    private final BigDecimal decimal;

    WireNumber(String raw) {
      this.raw = raw;
      decimal = new BigDecimal(raw);
    }

    @Override
    public int intValue() {
      return decimal.intValue();
    }

    @Override
    public long longValue() {
      return decimal.longValue();
    }

    @Override
    public float floatValue() {
      return decimal.floatValue();
    }

    @Override
    public double doubleValue() {
      return decimal.doubleValue();
    }

    @Override
    public String toString() {
      return raw;
    }
  }

  private static String string(JsonElement value) {
    require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString());
    return value.getAsString();
  }

  private static String id(JsonElement value) {
    String s = string(value);
    require(s.matches("[A-Za-z0-9_-]{1,100}"));
    return s;
  }

  private static String hash(JsonElement value) {
    String s = string(value);
    require(s.matches("[0-9a-f]{64}"));
    return s;
  }

  private static int integer(JsonElement value, int minimum, int maximum) {
    require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber());
    // Reject alternate numeric spellings for envelope integer pins.
    require(value.toString().matches("[1-9][0-9]{0,6}"));
    int result = value.getAsBigDecimal().intValueExact();
    require(result >= minimum && result <= maximum);
    return result;
  }

  private static BigDecimal number(JsonElement value, long maximum) {
    require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber());
    BigDecimal result = value.getAsBigDecimal();
    require(result.signum() >= 0 && result.compareTo(BigDecimal.valueOf(maximum)) <= 0);
    return result;
  }

  private static Long ns(JsonElement value) {
    if (value.isJsonNull()) return null;
    String s = string(value);
    require(s.matches("-?(?:0|[1-9][0-9]{0,18})") && !s.equals("-0"));
    return Long.parseLong(s);
  }

  private static void bounded(JsonElement value, int maximum, boolean empty) {
    String s = string(value);
    require((empty || !s.isBlank()) && utf8Size(s) <= maximum);
  }

  public static int utf8Size(String s) {
    try {
      require(s != null && s.indexOf('\0') < 0);
      return StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(s)).remaining();
    } catch (Exception invalid) {
      throw new IllegalArgumentException("invalid_payload");
    }
  }

  private static void require(boolean valid) {
    if (!valid) throw new IllegalArgumentException();
  }

  public static String acknowledgement(
      Request r,
      Long receiptNs,
      boolean duplicate,
      String reason,
      boolean queueFault,
      String runtimeMode) {
    JsonObject ack = new JsonObject();
    ack.addProperty("schema_version", 1);
    ack.addProperty("profile", PROFILE);
    ack.addProperty("delivery_id", r.deliveryId());
    ack.addProperty("destination_robot_id", r.robotId());
    ack.addProperty("destination_boot_id", r.bootId());
    ack.addProperty("event_id", r.eventId());
    ack.addProperty("revision", r.revision());
    ack.addProperty("annotation_sha256", r.annotationSha256());
    ack.addProperty("payload_sha256", r.payloadSha256());
    ack.addProperty("state", reason == null ? "accepted_into_log_input" : "rejected");
    ack.addProperty("duplicate", duplicate);
    ack.addProperty("receipt_robot_ns", receiptNs == null ? null : Long.toString(receiptNs));
    ack.addProperty("reason", reason);
    ack.addProperty("ack_scope", "contextual_receipt_only");
    ack.addProperty("usb_durability", "unavailable");
    ack.addProperty("logger_queue_fault", queueFault);
    ack.addProperty("runtime_mode", runtimeMode);
    return JSON.toJson(ack);
  }

  public static String loggedEnvelope(Request r, long receiptNs) {
    // Store exact original wire bytes as a string; do not recalculate the intended event time.
    JsonObject envelope = new JsonObject();
    envelope.addProperty("request_json", r.wire());
    envelope.addProperty("receipt_robot_ns", Long.toString(receiptNs));
    envelope.addProperty("ack_scope", "contextual_receipt_only");
    return JSON.toJson(envelope);
  }

  private MarkerProtocol() {}
}
