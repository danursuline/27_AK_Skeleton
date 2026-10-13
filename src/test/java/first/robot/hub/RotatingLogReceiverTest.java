package first.robot.hub;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.wpilog.WPILOGReader;
import org.wpilib.datalog.DataLogReader;
import org.wpilib.math.geometry.Pose2d;
import org.wpilib.math.geometry.Rotation2d;

class RotatingLogReceiverTest {
  private Path root;

  @BeforeEach
  void recordingDirectory() throws Exception {
    // The pinned official replay reader memory-maps files without a close API on Windows.
    // Keep genuine validation artifacts in build output for inspection instead of deleting
    // mappings.
    root = Path.of("build/reports/testhub/segments", UUID.randomUUID().toString()).toAbsolutePath();
    Files.createDirectories(root);
  }

  private final String boot = UUID.randomUUID().toString();
  private final RotatingLogReceiver.Policy policy =
      new RotatingLogReceiver.Policy(40_000_000L, 200_000_000L, 100_000_000L, 0);

  private LogTable table(long sequence, boolean enabled) {
    long time = sequence * 20_000_000L;
    var t = new LogTable(time);
    t.put("RealMetadata/RobotId", "6391-practice");
    t.put("RealMetadata/BootId", boot);
    t.put("RealMetadata/SourceSHA256", "fixture-source");
    t.put("RealOutputs/TestHub/Sequence", sequence);
    t.put("RealOutputs/TestHub/RobotMonotonicNs", time + 9007199254740993L);
    t.put("RealOutputs/TestHub/StateKnown", true);
    t.put("RealOutputs/TestHub/Enabled", enabled);
    t.put("RealOutputs/TestHub/TransferAllowed", !enabled);
    t.put("RealOutputs/Events/Sequence", sequence);
    t.put("RealOutputs/Events/Text", "same repeated event text");
    t.put("Inputs/Drive/Pose", new Pose2d(1, 2, Rotation2d.ZERO));
    t.put("Inputs/Drive/PoseArray", new Pose2d[] {new Pose2d(3, 4, Rotation2d.ZERO)});
    t.put("Inputs/Drive/ProtoPose", Pose2d.proto, new Pose2d(5, 6, Rotation2d.ZERO));
    t.put("Inputs/Drive/Arrays", new double[][] {{1, 2}, {3, 4}});
    t.put("Inputs/Drive/Voltage", 12.0, "volts");
    t.put("Inputs/Types/Boolean", true);
    t.put("Inputs/Types/BooleanArray", new boolean[] {true, false});
    t.put("Inputs/Types/LongArray", new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
    t.put("Inputs/Types/Float", 2.75f);
    t.put("Inputs/Types/FloatArray", new float[] {Float.NaN, -1.25f});
    t.put("Inputs/Types/StringArray", new String[] {"Unicode \uD83D\uDC3B", "", "quoted\""});
    return t;
  }

  private RotatingLogReceiver receiver(AtomicBoolean live) {
    return new RotatingLogReceiver(root, policy, () -> true, live::get, SegmentWriter::openFile);
  }

  private List<Properties> catalog() throws Exception {
    var entries = new ArrayList<Properties>();
    try (var paths = Files.list(root)) {
      for (Path path : paths.filter(p -> p.toString().endsWith(".segment.properties")).toList()) {
        var p = new Properties();
        try (var in = Files.newInputStream(path)) {
          p.load(in);
        }
        entries.add(p);
      }
    }
    entries.sort(
        (a, b) ->
            Long.compare(
                Long.parseLong(a.getProperty("first_sequence", "0")),
                Long.parseLong(b.getProperty("first_sequence", "0"))));
    return entries;
  }

  private Path artifact(Properties p) {
    return root.resolve(p.getProperty("segment_id") + ".wpilog");
  }

  private String manifestEntries() throws Exception {
    String head = Files.readString(root.resolve("manifest.json"));
    var match = java.util.regex.Pattern.compile("manifest-page-[a-f0-9]{64}\\.json").matcher(head);
    var pages = new StringBuilder();
    while (match.find()) pages.append(Files.readString(root.resolve(match.group())));
    return pages.toString();
  }

  private long manifestRevision(String head) {
    return Long.parseLong(head.split("\"manifest_revision\":\"")[1].split("\"")[0]);
  }

  private List<LogTable> replay(Path path) {
    var source = new WPILOGReader(path.toString());
    source.start();
    var current = new LogTable(0);
    var cycles = new ArrayList<LogTable>();
    boolean more;
    do {
      more = source.updateTable(current);
      if (current.getTimestamp() > 0) cycles.add(LogTable.clone(current));
    } while (more);
    return cycles;
  }

  @Test
  void everySegmentIndependentlyReplaysSchemasFullStateAndUniqueOrderedCycles() throws Exception {
    var live = new AtomicBoolean(false);
    var r = receiver(live);
    r.start();
    for (long seq = 1; seq <= 3; seq++) r.putTable(table(seq, true));
    for (long seq = 4; seq <= 6; seq++) r.putTable(table(seq, false));
    assertEquals(1, catalog().size(), "disabled delay closes before cycle 6 enters successor");
    r.putTable(table(7, true));
    r.end();
    assertEquals(2, catalog().size());
    var seen = new ArrayList<Long>();
    for (var entry : catalog()) {
      assertEquals("closed_pending_digest", entry.getProperty("state"));
      var path = artifact(entry);
      var binary = new DataLogReader(path.toString());
      assertTrue(binary.isValid());
      assertEquals("AdvantageKit", binary.getExtraHeader());
      var cycles = replay(path);
      assertEquals(Long.parseLong(entry.getProperty("cycles")), cycles.size());
      assertEquals(
          Long.parseLong(entry.getProperty("first_sequence")),
          cycles.getFirst().get("RealOutputs/TestHub/Sequence", -1L));
      for (var cycle : cycles) {
        seen.add(cycle.get("RealOutputs/TestHub/Sequence", -1L));
        assertEquals("same repeated event text", cycle.get("RealOutputs/Events/Text", ""));
        assertEquals(1.0, cycle.get("Inputs/Drive/Pose", Pose2d.struct, Pose2d.ZERO).getX());
        assertEquals(
            3.0, cycle.get("Inputs/Drive/PoseArray", Pose2d.struct, new Pose2d[0])[0].getX());
        assertEquals(5.0, cycle.get("Inputs/Drive/ProtoPose", Pose2d.proto, Pose2d.ZERO).getX());
        assertArrayEquals(
            new double[] {3, 4}, cycle.get("Inputs/Drive/Arrays", new double[0][])[1]);
        assertEquals("volts", cycle.get("Inputs/Drive/Voltage").unitStr);
        assertNotNull(cycle.get(".schema/struct:Pose2d"));
        assertEquals(boot, cycle.get("RealMetadata/BootId", ""));
        assertTrue(cycle.get("Inputs/Types/Boolean", false));
        assertArrayEquals(
            new boolean[] {true, false}, cycle.get("Inputs/Types/BooleanArray", new boolean[0]));
        assertArrayEquals(
            new long[] {Long.MIN_VALUE, Long.MAX_VALUE},
            cycle.get("Inputs/Types/LongArray", new long[0]));
        assertEquals(2.75f, cycle.get("Inputs/Types/Float", 0.0f));
        assertArrayEquals(
            new float[] {Float.NaN, -1.25f}, cycle.get("Inputs/Types/FloatArray", new float[0]));
        assertArrayEquals(
            new String[] {"Unicode \uD83D\uDC3B", "", "quoted\""},
            cycle.get("Inputs/Types/StringArray", new String[0]));
      }
      assertTrue(cycles.getFirst().get("TestHubSegment/InitialSnapshot", false));
      if (seen.getFirst() != cycles.getFirst().get("RealOutputs/TestHub/Sequence", -1L))
        assertTrue(
            List.of(cycles.getFirst().get("TestHubSegment/BootstrapKeys", new String[0]))
                .contains("/RealOutputs/Events/Text"),
            "Held initial text must be labeled bootstrap");
    }
    assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), seen);
    String manifest = Files.readString(root.resolve("manifest.json"));
    assertTrue(manifestEntries().contains("\"sha256\":null"));
    assertTrue(manifest.contains("\"open_segment\":null"));
    assertTrue(manifestEntries().contains("\"start_monotonic_ns\":\"9007199274740993\""));
  }

  @Test
  void enabledDefersSizeRotationAndUnknownStateCannotStartDisabledTimer() throws Exception {
    var tiny = new RotatingLogReceiver.Policy(40_000_000L, 200_000_000L, 1, 0);
    var r = new RotatingLogReceiver(root, tiny, () -> true, () -> false, SegmentWriter::openFile);
    r.start();
    for (long seq = 1; seq <= 5; seq++) r.putTable(table(seq, true));
    assertTrue(catalog().isEmpty());
    for (long seq = 6; seq <= 8; seq++) {
      var unknown = table(seq, false);
      unknown.put("RealOutputs/TestHub/StateKnown", false);
      r.putTable(unknown);
    }
    assertTrue(catalog().isEmpty());
    for (long seq = 9; seq <= 11; seq++) r.putTable(table(seq, false));
    assertEquals(1, catalog().size());
    r.end();
    assertEquals(2, catalog().size());
  }

  @Test
  void queuedDisabledTablesCannotRotateWhileLiveEnabledOrUnknownAndDelayRestarts()
      throws Exception {
    var livePermission = new AtomicBoolean(false);
    var tiny = new RotatingLogReceiver.Policy(40_000_000L, 200_000_000L, 1, 0);
    var r =
        new RotatingLogReceiver(
            root, tiny, livePermission::get, () -> false, SegmentWriter::openFile);
    r.start();
    try {
      r.putTable(table(1, true));
      // Receiver backlog says disabled, but the current driver station is enabled.
      for (long seq = 2; seq <= 5; seq++) r.putTable(table(seq, false));
      assertTrue(catalog().isEmpty());
      livePermission.set(true);
      r.putTable(table(6, false));
      r.putTable(table(7, false));
      assertTrue(catalog().isEmpty(), "Current disable must begin a new delay");
      // Current status becomes unknown or reenabled before the close boundary.
      livePermission.set(false);
      r.putTable(table(8, false));
      livePermission.set(true);
      r.putTable(table(9, false));
      r.putTable(table(10, false));
      assertTrue(catalog().isEmpty(), "Loss of current permission resets the delay");
      var unknown = table(11, false);
      unknown.put("RealOutputs/TestHub/StateKnown", false);
      r.putTable(unknown);
      r.putTable(table(12, false));
      r.putTable(table(13, true));
      r.putTable(table(14, false));
      r.putTable(table(15, false));
      assertTrue(catalog().isEmpty(), "Logged unknown and reenable also reset the delay");
      r.putTable(table(16, false));
      assertEquals(1, catalog().size());
    } finally {
      r.end();
    }
  }

  @Test
  void closedDigestWaitsForPermissionAndRemainsImmutableAcrossRestart() throws Exception {
    var live = new AtomicBoolean(false);
    var r = receiver(live);
    r.start();
    r.putTable(table(1, true));
    for (long seq = 2; seq <= 4; seq++) r.putTable(table(seq, false));
    assertEquals("closed_pending_digest", catalog().getFirst().getProperty("state"));
    String pendingHead = Files.readString(root.resolve("manifest.json"));
    var pageMatch =
        java.util.regex.Pattern.compile("manifest-page-[a-f0-9]{64}\\.json").matcher(pendingHead);
    assertTrue(pageMatch.find());
    Path pendingPage = root.resolve(pageMatch.group());
    byte[] pendingPageBytes = Files.readAllBytes(pendingPage);
    Thread.sleep(30);
    r.putTable(table(5, false));
    assertEquals("closed_pending_digest", catalog().getFirst().getProperty("state"));
    live.set(true);
    long seq = 6;
    long deadline = System.nanoTime() + 3_000_000_000L;
    while (!catalog().getFirst().getProperty("state").equals("closed")
        && System.nanoTime() < deadline) {
      r.putTable(table(seq++, false));
      Thread.sleep(10);
    }
    var closed = catalog().getFirst();
    assertEquals("closed", closed.getProperty("state"));
    assertArrayEquals(
        pendingPageBytes,
        Files.readAllBytes(pendingPage),
        "Old revision page remains immutable after digest publication");
    byte[] bytes = Files.readAllBytes(artifact(closed));
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        closed.getProperty("sha256"));
    r.end();
    String manifest = Files.readString(root.resolve("manifest.json"));
    long revision = manifestRevision(manifest);
    var next = receiver(new AtomicBoolean(false));
    next.start();
    var newBoot = table(1, false);
    newBoot.put("RealMetadata/BootId", UUID.randomUUID().toString());
    next.putTable(newBoot);
    next.end();
    assertArrayEquals(bytes, Files.readAllBytes(artifact(closed)));
    assertTrue(manifestRevision(Files.readString(root.resolve("manifest.json"))) > revision);
    assertEquals(
        "closed",
        catalog().stream()
            .filter(p -> p.getProperty("segment_id").equals(closed.getProperty("segment_id")))
            .findFirst()
            .orElseThrow()
            .getProperty("state"));
  }

  @Test
  void writeFailureAndMissingCyclesCreateExplicitGapsAndNeverEligiblePartialFiles()
      throws Exception {
    var openings = new AtomicInteger();
    SegmentWriter.Factory failOnce =
        path -> {
          var real = SegmentWriter.openFile(path);
          if (openings.incrementAndGet() != 1) return real;
          return new SegmentWriter.Output() {
            public OutputStream stream() {
              return new OutputStream() {
                public void write(int value) {
                  throw new UncheckedIOException(new IOException("injected disk failure"));
                }

                public void write(byte[] bytes, int offset, int count) {
                  throw new UncheckedIOException(new IOException("injected disk failure"));
                }
              };
            }

            public void force() throws IOException {
              real.force();
            }

            public long size() throws IOException {
              return real.size();
            }

            public void close() throws IOException {
              real.close();
            }
          };
        };
    var r = new RotatingLogReceiver(root, policy, () -> true, () -> false, failOnce);
    r.start();
    r.putTable(table(1, true));
    assertEquals("failed", r.health().writeState());
    assertTrue(r.health().lastError().contains("injected disk failure"));
    r.putTable(table(2, true));
    r.putTable(table(4, true));
    r.end();
    var entries = catalog();
    assertEquals(3, entries.size());
    assertEquals("failed_incomplete", entries.getFirst().getProperty("state"));
    assertNull(entries.getFirst().getProperty("sha256"));
    var afterFailure = replay(artifact(entries.get(1))).getFirst();
    assertTrue(afterFailure.get("TestHubSegment/GapBefore", false));
    var afterDrop = replay(artifact(entries.get(2))).getFirst();
    assertTrue(afterDrop.get("TestHubSegment/GapBefore", false));
    assertEquals(4, afterDrop.get("RealOutputs/TestHub/Sequence", -1L));
  }

  @Test
  void crashOrphanIsRetainedWithoutInferredClosure() throws Exception {
    Files.createDirectories(root);
    Path orphan = root.resolve(UUID.randomUUID() + ".wpilog");
    Files.write(orphan, new byte[] {1, 2, 3});
    var r = receiver(new AtomicBoolean(false));
    r.start();
    r.putTable(table(1, false));
    r.end();
    var orphanEntry =
        catalog().stream()
            .filter(p -> p.getProperty("state").equals("orphan_incomplete"))
            .findFirst()
            .orElseThrow();
    assertNull(orphanEntry.getProperty("sha256"));
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(orphan));
  }

  @Test
  void digestCancellationDiscardsPartialHashAndResumesOnlyWithIdlePermission() throws Exception {
    var enabledHash = new AtomicBoolean(false);
    var unlimited = new AtomicBoolean(false);
    var checks = new AtomicInteger();
    var r =
        new RotatingLogReceiver(
            root,
            policy,
            () -> true,
            () -> enabledHash.get() && (unlimited.get() || checks.incrementAndGet() <= 3),
            SegmentWriter::openFile);
    r.start();
    var first = table(1, true);
    first.put("Inputs/LargeRawFixture", new byte[2 * 1024 * 1024]);
    r.putTable(first);
    for (long seq = 2; seq <= 4; seq++) r.putTable(table(seq, false));
    enabledHash.set(true);
    long deadline = System.nanoTime() + 2_000_000_000L;
    while (checks.get() < 4 && System.nanoTime() < deadline) Thread.sleep(5);
    assertTrue(checks.get() >= 4, "worker reached cancellation between bounded chunks");
    r.putTable(table(5, false));
    assertEquals("closed_pending_digest", catalog().getFirst().getProperty("state"));
    unlimited.set(true);
    long seq = 6;
    deadline = System.nanoTime() + 3_000_000_000L;
    while (!catalog().getFirst().getProperty("state").equals("closed")
        && System.nanoTime() < deadline) {
      r.putTable(table(seq++, false));
      Thread.sleep(10);
    }
    assertEquals("closed", catalog().getFirst().getProperty("state"));
    r.end();
  }

  @Test
  void successorReplaysMetadataChangesNewSchemasAndUnitRemoval() throws Exception {
    var r = receiver(new AtomicBoolean(false));
    r.start();
    r.putTable(table(1, true));
    for (long seq = 2; seq <= 4; seq++) r.putTable(table(seq, false));
    var changed = table(5, false);
    changed.put("RealMetadata/ConfigurationRevision", "second configuration");
    changed.put("Inputs/Drive/Voltage", new LogTable.LogValue(11.0, null));
    changed.put("Inputs/NewNested/Pose", new Pose2d(9, 8, Rotation2d.ZERO));
    r.putTable(changed);
    r.end();
    var cycles = replay(artifact(catalog().get(1)));
    assertEquals(2, cycles.size());
    var last = cycles.getLast();
    assertEquals("second configuration", last.get("RealMetadata/ConfigurationRevision", ""));
    assertNull(last.get("Inputs/Drive/Voltage").unitStr);
    assertEquals(9, last.get("Inputs/NewNested/Pose", Pose2d.struct, Pose2d.ZERO).getX());
    assertNotNull(last.get(".schema/struct:Pose2d"));
  }

  @Test
  void manifestIndexIsBoundedAndNeverClaimsCompleteAfterLimit() throws Exception {
    for (int index = 0; index < 129; index++)
      Files.write(root.resolve(UUID.randomUUID() + ".wpilog"), new byte[] {1});
    var bounded = new RotatingLogReceiver.Policy(40_000_000L, 200_000_000L, 100_000_000L, 0, 1);
    var r =
        new RotatingLogReceiver(root, bounded, () -> true, () -> false, SegmentWriter::openFile);
    r.start();
    var first = table(1, false);
    first.put("SystemStats/EpochTimeValid", false);
    first.put("SystemStats/EpochTime", 1720000000000000L);
    r.putTable(first);
    String head = Files.readString(root.resolve("manifest.json"));
    assertTrue(head.contains("\"segments_complete\":false"));
    assertTrue(head.contains("\"known_segment_count\":128"));
    assertTrue(head.contains("\"segment_count_lower_bound\":129"));
    assertTrue(head.contains("\"created_at\":null"));
    var matcher =
        java.util.regex.Pattern.compile("manifest-page-([a-f0-9]{64})\\.json").matcher(head);
    assertTrue(matcher.find());
    byte[] bytes = Files.readAllBytes(root.resolve(matcher.group()));
    assertTrue(bytes.length <= 256 * 1024);
    assertEquals(
        matcher.group(1),
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    assertFalse(matcher.find());
    r.end();
  }

  @Test
  void typeAndSchemaChangesStartIndependentDecodingContextsWithoutLosingCycles() throws Exception {
    var r = receiver(new AtomicBoolean(false));
    r.start();
    var first = table(1, true);
    first.put(
        ".schema/struct:Dynamic",
        new LogTable.LogValue(
            "int32 value;".getBytes(java.nio.charset.StandardCharsets.UTF_8), "structschema"));
    first.put("Inputs/Dynamic", new LogTable.LogValue(new byte[] {1, 0, 0, 0}, "struct:Dynamic"));
    r.putTable(first);
    var second = table(2, true);
    second.getAll(false).remove("/Inputs/Drive/Voltage");
    second.put("Inputs/Drive/Voltage", "explicit replacement type");
    second.put(
        ".schema/struct:Dynamic",
        new LogTable.LogValue(
            "int32 value;".getBytes(java.nio.charset.StandardCharsets.UTF_8), "structschema"));
    second.put("Inputs/Dynamic", new LogTable.LogValue(new byte[] {2, 0, 0, 0}, "struct:Dynamic"));
    r.putTable(second);
    var third = LogTable.clone(second);
    third.setTimestamp(60_000_000L);
    third.put("RealOutputs/TestHub/Sequence", 3L);
    third.put("RealOutputs/TestHub/RobotMonotonicNs", 9007199314740993L);
    third.put(
        ".schema/struct:Dynamic",
        new LogTable.LogValue(
            "int64 value;".getBytes(java.nio.charset.StandardCharsets.UTF_8), "structschema"));
    third.put(
        "Inputs/Dynamic",
        new LogTable.LogValue(new byte[] {3, 0, 0, 0, 0, 0, 0, 0}, "struct:Dynamic"));
    r.putTable(third);
    r.end();
    var entries = catalog();
    assertEquals(3, entries.size());
    var a = replay(artifact(entries.get(0))).getFirst();
    var b = replay(artifact(entries.get(1))).getFirst();
    var c = replay(artifact(entries.get(2))).getFirst();
    assertEquals(12.0, a.get("Inputs/Drive/Voltage", 0.0));
    assertEquals("explicit replacement type", b.get("Inputs/Drive/Voltage", ""));
    assertEquals("explicit replacement type", c.get("Inputs/Drive/Voltage", ""));
    assertTrue(b.get("TestHubSegment/SchemaBoundary", false));
    assertTrue(c.get("TestHubSegment/SchemaBoundary", false));
    assertFalse(c.get("TestHubSegment/GapBefore", true));
    assertEquals(
        "int64 value;",
        new String(
            c.get(".schema/struct:Dynamic").getRaw(), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(8, c.get("Inputs/Dynamic").getRaw().length);
    assertEquals(
        List.of(1L, 2L, 3L),
        List.of(
            a.get("RealOutputs/TestHub/Sequence", -1L),
            b.get("RealOutputs/TestHub/Sequence", -1L),
            c.get("RealOutputs/TestHub/Sequence", -1L)));
  }

  @Test
  void createdUtcUsesQualifiedDoubleMicrosecondsAndRejectsInvalidClockEvidence() throws Exception {
    var r = receiver(new AtomicBoolean(false));
    r.start();
    var first = table(1, false);
    first.put("SystemStats/EpochTimeValid", true);
    first.put("SystemStats/EpochTime", 1_720_000_000_123_456.0, "microseconds");
    r.putTable(first);
    assertTrue(
        Files.readString(root.resolve("manifest.json"))
            .contains("\"created_at\":\"2024-07-03T09:46:40.123456Z\""));
    r.end();
    Path badRoot = root.resolve("bad-clock");
    var bad =
        new RotatingLogReceiver(badRoot, policy, () -> true, () -> false, SegmentWriter::openFile);
    bad.start();
    var invalid = table(1, false);
    invalid.put("SystemStats/EpochTimeValid", true);
    invalid.put("SystemStats/EpochTime", Double.NaN, "microseconds");
    bad.putTable(invalid);
    assertTrue(Files.readString(badRoot.resolve("manifest.json")).contains("\"created_at\":null"));
    bad.end();
  }

  @Test
  void shutdownDigestInterruptionRemainsPendingAndDoesNotClaimIdentityConflict() {
    assertEquals(
        "cancelled",
        RotatingLogReceiver.classifyHashFailure(
            new java.nio.channels.ClosedByInterruptException(), true, false));
    assertEquals(
        "cancelled",
        RotatingLogReceiver.classifyHashFailure(
            new IOException("interrupted channel"), false, false));
    assertEquals(
        "cancelled",
        RotatingLogReceiver.classifyHashFailure(
            new IOException("interrupted channel"), true, true));
    assertEquals(
        "digest_error",
        RotatingLogReceiver.classifyHashFailure(
            new IOException("disk temporarily unavailable"), true, false));
    assertEquals(
        "identity_conflict",
        RotatingLogReceiver.classifyHashFailure(
            new RotatingLogReceiver.IdentityChanged("size changed"), true, false));
  }
}
