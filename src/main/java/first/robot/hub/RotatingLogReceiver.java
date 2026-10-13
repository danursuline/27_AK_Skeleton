package first.robot.hub;

import first.robot.util.HubIdentity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.littletonrobotics.junction.LogDataReceiver;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.LogTable.LogValue;
import org.wpilib.driverstation.RobotState;
import org.wpilib.hardware.hal.RobotMode;

/**
 * Opt-in experimental receiver. All file writes occur on AK's receiver thread, never in periodic.
 */
public final class RotatingLogReceiver implements LogDataReceiver {
  public record Policy(
      long disabledDelayNs, long idleAgeNs, long maxBytes, long retryDelayNs, int maxPages) {
    public Policy(long disabledDelayNs, long idleAgeNs, long maxBytes, long retryDelayNs) {
      this(disabledDelayNs, idleAgeNs, maxBytes, retryDelayNs, 64);
    }

    public Policy {
      if (disabledDelayNs < 0
          || idleAgeNs <= 0
          || maxBytes <= 0
          || retryDelayNs < 0
          || maxPages < 1
          || maxPages > 512) throw new IllegalArgumentException("Invalid rotation policy");
    }

    public static Policy defaults() {
      return new Policy(5_000_000_000L, 300_000_000_000L, 256L * 1024 * 1024, 1_000_000_000L);
    }
  }

  public record Health(
      String activeSegmentId, String writeState, String lastError, int pendingDigests) {}

  private record HashJob(String id, Path path, long size) {}

  private record HashResult(String id, String hash, String error, boolean identityChanged) {}

  static final class IdentityChanged extends IOException {
    IdentityChanged(String reason) {
      super(reason);
    }
  }

  static String classifyHashFailure(Exception exception, boolean running, boolean interrupted) {
    if (!running
        || interrupted
        || exception instanceof java.nio.channels.ClosedByInterruptException
        || exception instanceof InterruptedException) return "cancelled";
    return exception instanceof IdentityChanged ? "identity_conflict" : "digest_error";
  }

  private static final long HASH_LEASE_NS = 500_000_000L;
  private static final int MAX_HASH_JOBS = 32;
  private static final int PAGE_SIZE = 128;
  private static final long MAX_EPOCH_MICROS =
      9_223_372_036_854_775L; // floor(Long.MAX_VALUE / 1000)
  private final Path requestedRoot;
  private final Policy policy;
  private final BooleanSupplier liveRotationIdle, liveDigestIdle;
  private final SegmentWriter.Factory factory;
  private final Map<String, Properties> catalog = new LinkedHashMap<>();
  private final Map<String, LogValue> latest = new HashMap<>();
  private final Set<String> scheduled = new HashSet<>();
  private final Map<String, Long> hashRetryAt = new HashMap<>();
  private final ConcurrentLinkedQueue<HashJob> jobs = new ConcurrentLinkedQueue<>();
  private final ConcurrentLinkedQueue<HashResult> results = new ConcurrentLinkedQueue<>();
  private volatile Health health = new Health(null, "not_started", "", 0);
  private volatile boolean running, idle;
  private volatile long idleReceiptNs;
  private Thread worker;
  private Path root;
  private SegmentWriter writer;
  private Properties active;
  private String robotId, bootId;
  private long revision,
      lastTimestamp = -1,
      lastMonotonic = -1,
      lastSequence = -1,
      disabledSince = -1,
      retryAt;
  private boolean sawRun, gapBefore;
  private boolean indexComplete = true, catalogDirty = true;
  private long nextManifestAt;
  private java.util.List<String> pageDescriptors = java.util.List.of();

  public RotatingLogReceiver(Path root) {
    this(
        root,
        Policy.defaults(),
        RotatingLogReceiver::currentIdle,
        RotatingLogReceiver::currentIdle,
        SegmentWriter::openFile);
  }

  private static boolean currentIdle() {
    return RobotState.isDSAttached()
        && !RobotState.isEnabled()
        && !RobotState.isEStopped()
        && RobotState.getRobotMode() != RobotMode.UNKNOWN;
  }

  // Injection only changes storage/idle permission; it cannot issue robot commands.
  RotatingLogReceiver(
      Path root,
      Policy policy,
      BooleanSupplier liveRotationIdle,
      BooleanSupplier liveDigestIdle,
      SegmentWriter.Factory factory) {
    this.requestedRoot = root.toAbsolutePath().normalize();
    this.policy = policy;
    this.liveRotationIdle = liveRotationIdle;
    this.liveDigestIdle = liveDigestIdle;
    this.factory = factory;
  }

  public Health health() {
    return health;
  }

  @Override
  public void start() {
    if (running || root != null) return;
    try {
      Files.createDirectories(requestedRoot);
      if (Files.isSymbolicLink(requestedRoot)) throw new IOException("Recording root is a symlink");
      root = requestedRoot.toRealPath();
      Path manifest = root.resolve("manifest.json");
      if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(manifest)) throw new IOException("Manifest is a symlink");
        if (Files.size(manifest) > 1024 * 1024)
          throw new IOException("Prior root manifest exceeds bound");
        var match =
            Pattern.compile("\"manifest_revision\":\"([0-9]+)\"")
                .matcher(Files.readString(manifest));
        if (!match.find()) throw new IOException("Invalid prior manifest revision");
        revision = Long.parseLong(match.group(1));
      }
      try (var paths = Files.list(root)) {
        var selected =
            paths
                .filter(p -> p.toString().endsWith(".segment.properties"))
                .limit((long) policy.maxPages() * PAGE_SIZE + 1)
                .toList();
        if (selected.size() > policy.maxPages() * PAGE_SIZE) indexComplete = false;
        for (Path path :
            selected.stream().limit((long) policy.maxPages() * PAGE_SIZE).sorted().toList()) {
          String filename = path.getFileName().toString();
          if (!filename.endsWith(".segment.properties")) continue;
          if (Files.isSymbolicLink(path)) throw new IOException("Catalog symlink");
          if (Files.size(path) > 64 * 1024) throw new IOException("Catalog record exceeds bound");
          var entry = new Properties();
          try (var input = Files.newInputStream(path)) {
            entry.load(input);
          }
          validateCatalog(entry);
          String id = entry.getProperty("segment_id");
          if (!validId(id) || !filename.equals(id + ".segment.properties"))
            throw new IOException("Invalid catalog identity");
          Path artifact = root.resolve(id + ".wpilog");
          if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS))
            entry.setProperty("state", "source_missing");
          else if (entry.containsKey("size_bytes")
              && Files.size(artifact) != Long.parseLong(entry.getProperty("size_bytes")))
            entry.setProperty("state", "identity_conflict");
          remember(entry);
          saveEntry(entry);
        }
      }
      // Never call a crash orphan closed based on stable size or a decodable prefix.
      try (var paths = Files.list(root)) {
        var selected =
            paths
                .filter(p -> p.toString().endsWith(".wpilog"))
                .limit((long) policy.maxPages() * PAGE_SIZE + 1)
                .toList();
        if (selected.size() > policy.maxPages() * PAGE_SIZE) indexComplete = false;
        for (Path path : selected.stream().limit((long) policy.maxPages() * PAGE_SIZE).toList()) {
          String filename = path.getFileName().toString();
          if (!filename.endsWith(".wpilog")) continue;
          String id = filename.substring(0, filename.length() - 7);
          if (!validId(id) || catalog.containsKey(id)) continue;
          var entry = new Properties();
          entry.setProperty("segment_id", id);
          entry.setProperty("state", "orphan_incomplete");
          entry.setProperty(
              "failure", "No durable close record; retained without transfer eligibility");
          remember(entry);
          saveEntry(entry);
        }
      }
      running = true;
      worker = new Thread(this::hashLoop, "TestHub_IdleDigest");
      worker.setDaemon(true);
      worker.setPriority(Thread.MIN_PRIORITY);
      worker.start();
      publishHealth("waiting_for_table", "");
    } catch (Exception exception) {
      publishHealth("failed", message(exception));
    }
  }

  private static boolean validId(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException exception) {
      return false;
    }
  }

  private static void validateCatalog(Properties entry) throws IOException {
    try {
      String state = entry.getProperty("state", "");
      if (!Set.of(
              "closed",
              "closed_pending_digest",
              "failed_incomplete",
              "orphan_incomplete",
              "source_missing",
              "identity_conflict")
          .contains(state)) throw new IllegalArgumentException("state");
      for (String key :
          new String[] {
            "size_bytes",
            "first_sequence",
            "last_sequence",
            "cycles",
            "start_monotonic_ns",
            "end_monotonic_ns"
          })
        if (entry.containsKey(key) && Long.parseLong(entry.getProperty(key)) < 0)
          throw new IllegalArgumentException(key);
      if (state.equals("closed") || state.equals("closed_pending_digest")) {
        if (!entry.getProperty("robot_id", "").matches("[A-Za-z0-9_-]{1,100}")
            || !validId(entry.getProperty("boot_id")))
          throw new IllegalArgumentException("identity");
        if (!entry.containsKey("size_bytes")
            || Long.parseLong(entry.getProperty("cycles", "0")) < 1
            || Long.parseLong(entry.getProperty("end_monotonic_ns", "-1"))
                < Long.parseLong(entry.getProperty("start_monotonic_ns", "0")))
          throw new IllegalArgumentException("closed bounds");
      }
      if (state.equals("closed") && !entry.getProperty("sha256", "").matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("digest");
      if (entry.containsKey("created_at")) java.time.Instant.parse(entry.getProperty("created_at"));
    } catch (Exception exception) {
      throw new IOException("Invalid durable catalog", exception);
    }
  }

  private static String message(Exception exception) {
    return exception.getClass().getSimpleName() + ": " + exception.getMessage();
  }

  private void publishHealth(String state, String error) {
    int pending =
        (int)
            catalog.values().stream()
                .filter(p -> p.getProperty("state").equals("closed_pending_digest"))
                .count();
    health =
        new Health(active == null ? null : active.getProperty("segment_id"), state, error, pending);
  }

  private void remember(Properties entry) {
    String id = entry.getProperty("segment_id");
    if (catalog.containsKey(id) || catalog.size() < policy.maxPages() * PAGE_SIZE)
      catalog.put(id, entry);
    else indexComplete = false;
    catalogDirty = true;
  }

  @Override
  public void putTable(LogTable table) {
    if (!running) return;
    long timestamp = table.getTimestamp();
    try {
      drainHashes();
      String newRobot = table.get("RealMetadata/RobotId", "");
      String newBoot = table.get("RealMetadata/BootId", "");
      if (!newRobot.matches("[A-Za-z0-9_-]{1,100}") || !validId(newBoot))
        throw new IOException("Missing valid robot/boot metadata");
      if (robotId == null) {
        robotId = newRobot;
        bootId = newBoot;
        for (var entry : catalog.values()) {
          if (entry.containsKey("robot_id") && !newRobot.equals(entry.getProperty("robot_id")))
            throw new IOException("Recording root belongs to another robot");
        }
      } else if (!robotId.equals(newRobot) || !bootId.equals(newBoot))
        throw new IOException("Execution identity changed in receiver");

      long sequence = table.get("RealOutputs/TestHub/Sequence", -1L);
      long monotonic = table.get("RealOutputs/TestHub/RobotMonotonicNs", -1L);
      boolean fresh = sequence > lastSequence && timestamp > lastTimestamp;
      boolean known = table.get("RealOutputs/TestHub/StateKnown", false);
      boolean enabled = table.get("RealOutputs/TestHub/Enabled", true);
      idle = fresh && known && !enabled && table.get("RealOutputs/TestHub/TransferAllowed", false);
      if (idle) idleReceiptNs = System.nanoTime();
      if (timestamp <= lastTimestamp || sequence <= lastSequence || monotonic <= lastMonotonic)
        throw new IOException("Nonadvancing ordered table/time sequence");
      boolean gap = lastSequence >= 0 && sequence != lastSequence + 1;
      if (gap) {
        closeSegment("closed_pending_digest", "");
        gapBefore = true;
        disabledSince = -1;
      }
      boolean schemaBoundary =
          table.getAll(false).entrySet().stream()
              .anyMatch(
                  field -> {
                    LogValue old = latest.get(field.getKey());
                    return old != null
                        && (!old.getWPILOGType().equals(field.getValue().getWPILOGType())
                            || field.getKey().startsWith("/.schema/")
                                && !old.equals(field.getValue()));
                  });
      // AK replay stores each key at one type. Give changed types/schema definitions an
      // independent decoding context rather than letting an old type hide the new value.
      if (schemaBoundary && writer != null) closeSegment("closed_pending_digest", "");
      // Queued disabled tables cannot grant policy rotation after a live enable
      // or loss of known status. Schema/gap decoding boundaries remain separate.
      boolean rotationIdle = idle && liveRotationIdle.getAsBoolean();
      if (!rotationIdle) disabledSince = -1;
      else if (disabledSince < 0) disabledSince = monotonic;
      if (known && enabled) sawRun = true;
      if (writer != null
          && rotationIdle
          && disabledSince >= 0
          && monotonic - disabledSince >= policy.disabledDelayNs()
          && (sawRun
              || monotonic - Long.parseLong(active.getProperty("start_monotonic_ns"))
                  >= policy.idleAgeNs()
              || writer.size() >= policy.maxBytes())) {
        closeSegment("closed_pending_digest", "");
        sawRun = false;
        disabledSince = monotonic;
      }
      var old = new HashMap<>(latest);
      latest.putAll(table.getAll(false));
      lastTimestamp = timestamp;
      lastMonotonic = monotonic;
      lastSequence = sequence;
      if (timestamp < retryAt) return;
      boolean initial = writer == null;
      if (initial) {
        openSegment(monotonic, sequence);
        if (table.get("SystemStats/EpochTimeValid", false)) {
          double observed = table.get("SystemStats/EpochTime", Double.NaN);
          LogValue epoch = table.get("SystemStats/EpochTime");
          // Pinned LoggedSystemStats stores an integral DOUBLE in microseconds, with unit metadata.
          if (epoch != null
              && "microseconds".equals(epoch.unitStr)
              && Double.isFinite(observed)
              && observed >= 0
              && observed < (double) MAX_EPOCH_MICROS) {
            long micros = (long) Math.floor(observed);
            active.setProperty(
                "created_at",
                java.time.Instant.ofEpochSecond(micros / 1_000_000L, micros % 1_000_000L * 1000L)
                    .toString());
          }
        }
      }
      var values = new HashMap<>(latest);
      var segmentTable = new LogTable(timestamp);
      segmentTable.put("TestHubSegment/SegmentId", active.getProperty("segment_id"));
      segmentTable.put("TestHubSegment/InitialSnapshot", initial);
      segmentTable.put("TestHubSegment/GapBefore", initial && gapBefore);
      segmentTable.put("TestHubSegment/SchemaBoundary", initial && schemaBoundary);
      // Held values copied into an independent successor are explicit bootstrap state, not new
      // events.
      String[] bootstrap =
          initial
              ? latest.entrySet().stream()
                  .filter(e -> e.getValue().equals(old.get(e.getKey())))
                  .map(Map.Entry::getKey)
                  .sorted()
                  .toArray(String[]::new)
              : new String[0];
      segmentTable.put("TestHubSegment/BootstrapKeys", bootstrap);
      values.putAll(segmentTable.getAll(false));
      writer.write(timestamp, values);
      active.setProperty("end_monotonic_ns", Long.toString(monotonic));
      active.setProperty("last_sequence", Long.toString(sequence));
      active.setProperty("cycles", Long.toString(Long.parseLong(active.getProperty("cycles")) + 1));
      gapBefore = false;
      scheduleHashes();
      publishHealth("recording", health.lastError());
      if (catalogDirty || initial || System.nanoTime() >= nextManifestAt) publishManifest();
    } catch (Exception exception) {
      idle = false;
      gapBefore = true;
      retryAt = timestamp + policy.retryDelayNs();
      String error = message(exception);
      try {
        closeSegment("failed_incomplete", error);
      } catch (Exception ignored) {
      }
      publishHealth("failed", error);
      try {
        if (robotId != null) publishManifest();
      } catch (Exception ignored) {
      }
    }
  }

  private void openSegment(long timestamp, long sequence) throws IOException {
    String id = UUID.randomUUID().toString();
    active = new Properties();
    active.setProperty("segment_id", id);
    active.setProperty("robot_id", robotId);
    active.setProperty("boot_id", bootId);
    active.setProperty("state", "open");
    active.setProperty("start_monotonic_ns", Long.toString(timestamp));
    active.setProperty("end_monotonic_ns", Long.toString(timestamp));
    active.setProperty("first_sequence", Long.toString(sequence));
    active.setProperty("last_sequence", Long.toString(sequence));
    active.setProperty("cycles", "0");
    active.setProperty("gap_before", Boolean.toString(gapBefore));
    try {
      writer = new SegmentWriter(root.resolve(id + ".wpilog"), factory);
    } catch (Exception exception) {
      active.setProperty("state", "failed_incomplete");
      active.setProperty("failure", message(exception));
      remember(active);
      saveEntry(active);
      active = null;
      throw exception;
    }
  }

  private void closeSegment(String state, String failure) throws IOException {
    if (writer == null) return;
    var closing = writer;
    var entry = active;
    writer = null;
    active = null;
    try {
      closing.close();
    } catch (Exception exception) {
      state = "failed_incomplete";
      failure = message(exception);
      gapBefore = true;
    }
    entry.setProperty("state", state);
    entry.setProperty(
        "size_bytes",
        Long.toString(Files.size(root.resolve(entry.getProperty("segment_id") + ".wpilog"))));
    if (!failure.isEmpty()) entry.setProperty("failure", failure);
    remember(entry);
    saveEntry(entry);
    publishHealth("waiting_for_table", failure.isEmpty() ? health.lastError() : failure);
    publishManifest();
  }

  private void saveEntry(Properties entry) throws IOException {
    var bytes = new ByteArrayOutputStream();
    entry.store(bytes, "Immutable segment close evidence; never edit artifact bytes");
    atomic(
        root.resolve(entry.getProperty("segment_id") + ".segment.properties"), bytes.toByteArray());
  }

  private void atomic(Path target, byte[] bytes) throws IOException {
    if (Files.isSymbolicLink(target)) throw new IOException("Refusing catalog/manifest symlink");
    Path temp = root.resolve(".publish-" + UUID.randomUUID());
    try {
      Files.createFile(temp);
      // AK clean shutdown interrupts its receiver thread. Interruptible FileChannel writes
      // would close active files halfway through queue draining; native RAF writes preserve it.
      try (var file = new java.io.RandomAccessFile(temp.toFile(), "rw")) {
        file.write(bytes);
        file.getFD().sync();
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private String entryJson(Properties entry) {
    String id = entry.getProperty("segment_id");
    StringBuilder out =
        new StringBuilder(
            "{\"segment_id\":"
                + HubIdentity.quote(id)
                + ",\"robot_id\":"
                + HubIdentity.quote(entry.getProperty("robot_id"))
                + ",\"boot_id\":"
                + HubIdentity.quote(entry.getProperty("boot_id"))
                + ",\"relative_path\":"
                + HubIdentity.quote(id + ".wpilog")
                + ",\"original_name\":"
                + HubIdentity.quote(id + ".wpilog")
                + ",\"state\":"
                + HubIdentity.quote(entry.getProperty("state"))
                + ",\"sha256\":"
                + HubIdentity.quote(entry.getProperty("sha256"))
                + ",\"created_at\":"
                + HubIdentity.quote(entry.getProperty("created_at"))
                + ",\"format\":\"wpilog\",\"format_profile\":\"wpilib-2027.0.0-alpha-7_akit-27.0.0-alpha-6\"");
    out.append(",\"size_bytes\":").append(entry.getProperty("size_bytes", "null"));
    for (String field : new String[] {"start_monotonic_ns", "end_monotonic_ns"})
      out.append(",\"")
          .append(field)
          .append("\":")
          .append(HubIdentity.quote(entry.getProperty(field)));
    for (String field : new String[] {"first_sequence", "last_sequence", "cycles"})
      out.append(",\"").append(field).append("\":").append(entry.getProperty(field, "null"));
    return out.append(",\"gap_before\":")
        .append(entry.getProperty("gap_before", "true"))
        .append(",\"failure\":")
        .append(HubIdentity.quote(entry.getProperty("failure")))
        .append('}')
        .toString();
  }

  private void publishManifest() throws IOException {
    if (robotId == null) return;
    if (revision == Long.MAX_VALUE) throw new IOException("Manifest revision exhausted");
    if (catalogDirty) {
      var entries = new ArrayList<String>();
      catalog.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .forEach(p -> entries.add(entryJson(p.getValue())));
      var descriptors = new ArrayList<String>();
      for (int index = 0; index < entries.size(); index += PAGE_SIZE) {
        String page =
            "{\"schema_version\":1,\"entries\":["
                + String.join(
                    ",", entries.subList(index, Math.min(index + PAGE_SIZE, entries.size())))
                + "]}";
        byte[] bytes = page.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 256 * 1024) throw new IOException("Manifest page exceeds bound");
        String sha = HubIdentity.sha256(page);
        String filename = "manifest-page-" + sha + ".json";
        Path path = root.resolve(filename);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) atomic(path, bytes);
        else if (Files.isSymbolicLink(path)
            || Files.size(path) > 256 * 1024
            || !HubIdentity.sha256(Files.readString(path)).equals(sha))
          throw new IOException("Immutable manifest page identity changed");
        descriptors.add(
            "{\"relative_path\":"
                + HubIdentity.quote(filename)
                + ",\"sha256\":"
                + HubIdentity.quote(sha)
                + ",\"size_bytes\":"
                + bytes.length
                + "}");
      }
      pageDescriptors = descriptors;
      catalogDirty = false;
    }
    String open = "null";
    if (active != null) {
      active.setProperty("size_bytes", Long.toString(writer.size()));
      open = entryJson(active);
    }
    String json =
        "{\"schema_version\":1,\"robot_id\":"
            + HubIdentity.quote(robotId)
            + ",\"boot_id\":"
            + HubIdentity.quote(bootId)
            + ",\"manifest_revision\":"
            + HubIdentity.quote(Long.toString(++revision))
            + ",\"segments_complete\":"
            + indexComplete
            + ",\"known_segment_count\":"
            + catalog.size()
            + ",\"segment_count_lower_bound\":"
            + (catalog.size() + (indexComplete ? 0 : 1))
            + ",\"page_size\":"
            + PAGE_SIZE
            + ",\"max_pages\":"
            + policy.maxPages()
            + ",\"closed_ready_count\":"
            + catalog.values().stream().filter(p -> p.getProperty("state").equals("closed")).count()
            + ",\"pending_digest_count\":"
            + catalog.values().stream()
                .filter(p -> p.getProperty("state").equals("closed_pending_digest"))
                .count()
            + ",\"pages\":["
            + String.join(",", pageDescriptors)
            + "],\"observed_monotonic_ns\":"
            + HubIdentity.quote(Long.toString(lastMonotonic))
            + ",\"open_segment\":"
            + open
            + ",\"write_state\":"
            + HubIdentity.quote(health.writeState())
            + ",\"last_error\":"
            + HubIdentity.quote(health.lastError())
            + "}";
    atomic(root.resolve("manifest.json"), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    nextManifestAt = System.nanoTime() + 1_000_000_000L;
  }

  private void scheduleHashes() {
    for (var entry : catalog.values()) {
      String id = entry.getProperty("segment_id");
      if (scheduled.size() >= MAX_HASH_JOBS) break;
      if (System.nanoTime() < hashRetryAt.getOrDefault(id, Long.MIN_VALUE)) continue;
      if (!entry.getProperty("state").equals("closed_pending_digest") || !scheduled.add(id))
        continue;
      jobs.add(
          new HashJob(
              id, root.resolve(id + ".wpilog"), Long.parseLong(entry.getProperty("size_bytes"))));
    }
  }

  private void drainHashes() throws IOException {
    HashResult result;
    while ((result = results.poll()) != null) {
      scheduled.remove(result.id());
      var entry = catalog.get(result.id());
      if (entry == null || !entry.getProperty("state").equals("closed_pending_digest")) continue;
      entry.setProperty(
          "state",
          result.error().isEmpty()
              ? "closed"
              : result.identityChanged() ? "identity_conflict" : "closed_pending_digest");
      if (result.error().isEmpty()) {
        entry.setProperty("sha256", result.hash());
        entry.remove("failure");
        hashRetryAt.remove(result.id());
      } else {
        entry.setProperty("failure", result.error());
        hashRetryAt.put(result.id(), System.nanoTime() + 1_000_000_000L);
      }
      saveEntry(entry);
      catalogDirty = true;
    }
  }

  private boolean hashAllowed() {
    return running
        && idle
        && System.nanoTime() - idleReceiptNs < HASH_LEASE_NS
        && liveDigestIdle.getAsBoolean();
  }

  private void hashLoop() {
    while (running) {
      try {
        if (!hashAllowed() || jobs.isEmpty()) {
          Thread.sleep(10);
          continue;
        }
        HashJob job = jobs.poll();
        if (job == null) continue;
        try {
          var before =
              Files.readAttributes(
                  job.path(),
                  java.nio.file.attribute.BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS);
          if (!before.isRegularFile() || before.size() != job.size())
            throw new IdentityChanged("Closed artifact identity changed");
          var digest = MessageDigest.getInstance("SHA-256");
          boolean cancelled = false;
          try (var channel =
                  Files.newByteChannel(
                      job.path(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
              var input = Channels.newInputStream(channel)) {
            byte[] block = new byte[64 * 1024];
            while (true) {
              if (!hashAllowed()) {
                cancelled = true;
                break;
              }
              int count = input.read(block);
              if (count < 0) break;
              digest.update(block, 0, count);
            }
          }
          if (cancelled || !hashAllowed()) {
            jobs.add(job);
            continue;
          }
          var after =
              Files.readAttributes(
                  job.path(),
                  java.nio.file.attribute.BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS);
          if (!after.isRegularFile()
              || after.size() != before.size()
              || !after.lastModifiedTime().equals(before.lastModifiedTime()))
            throw new IdentityChanged("Artifact changed while hashing");
          results.add(
              new HashResult(job.id(), HexFormat.of().formatHex(digest.digest()), "", false));
        } catch (Exception exception) {
          String classification =
              classifyHashFailure(exception, running, Thread.currentThread().isInterrupted());
          if (classification.equals("cancelled")) {
            jobs.add(
                job); // Durable close evidence remains pending and can be retried after restart.
            if (!running || Thread.currentThread().isInterrupted()) return;
          } else
            results.add(
                new HashResult(
                    job.id(), "", message(exception), classification.equals("identity_conflict")));
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception exception) {
        idle = false;
      }
    }
  }

  @Override
  public void end() {
    if (!running) return;
    idle = false;
    running = false;
    worker.interrupt();
    try {
      worker.join(250);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    try {
      drainHashes();
      closeSegment("closed_pending_digest", "");
      publishHealth("stopped", health.lastError());
      publishManifest();
    } catch (Exception exception) {
      publishHealth("failed", message(exception));
    }
  }
}
