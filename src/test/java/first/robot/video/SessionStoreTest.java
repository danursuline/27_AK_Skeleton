package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreTest {
  private static final long MIB = 1024L * 1024L;
  private static final Instant NOW = Instant.parse("2026-10-12T20:00:00Z");

  @TempDir Path tmp;
  private Path root;
  private final AtomicLong free = new AtomicLong(100_000 * MIB);
  private SessionStore store;

  @BeforeEach
  void setUp() throws Exception {
    root = tmp.resolve("videos");
    store = new SessionStore(root, p -> free.get());
    store.open();
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private SessionStore.Session session(String status, int segmentBytes) throws Exception {
    SessionStore.Session s = store.createSession(NOW);
    SessionMetadata meta = new SessionMetadata();
    meta.sessionId = s.id();
    meta.status = status;
    store.writeMetadata(s, meta);
    Files.write(s.dir().resolve("seg_00000.mkv"), new byte[segmentBytes]);
    return s;
  }

  private static boolean symlinksSupported(Path dir) {
    try {
      Path target = Files.createTempFile(dir, "t", ".tmp");
      Path link = dir.resolve("probe-link");
      Files.createSymbolicLink(link, target);
      Files.delete(link);
      Files.delete(target);
      return true;
    } catch (IOException | UnsupportedOperationException | SecurityException e) {
      return false;
    }
  }

  @Test
  void createsUniquelyNumberedMarkedSessions() throws Exception {
    SessionStore.Session a = store.createSession(NOW);
    SessionStore.Session b = store.createSession(NOW);
    assertEquals("vr-000001-20261012T200000Z", a.id());
    assertEquals("vr-000002-20261012T200000Z", b.id());
    assertEquals(
        SessionStore.MARKER_CONTENT, Files.readString(a.dir().resolve(SessionStore.MARKER_FILE)));
  }

  @Test
  void listsOnlyMarkedSessionDirectories() throws Exception {
    SessionStore.Session real = session("complete", 10);
    Files.createDirectories(root.resolve("vr-000050-20261012T200000Z")); // no marker
    Files.createDirectories(root.resolve("other-folder"));
    Path fakeMarker = Files.createDirectories(root.resolve("vr-000051-20261012T200000Z"));
    Files.writeString(fakeMarker.resolve(SessionStore.MARKER_FILE), "something else\n");
    List<SessionStore.OwnedSession> owned = store.listOwnedSessions();
    assertEquals(1, owned.size());
    assertEquals(real.id(), owned.get(0).id());
  }

  @Test
  void prunesOldestCompletedSessionsFirstAndPreservesEverythingElse() throws Exception {
    SessionStore.Session oldComplete = session("complete", (int) (3 * MIB));
    SessionStore.Session incomplete = session("recording", (int) (3 * MIB));
    SessionStore.Session withForeignFile = session("complete", (int) (3 * MIB));
    Files.writeString(withForeignFile.dir().resolve("my-annotations.txt"), "keep");
    SessionStore.Session failed = session("failed", (int) (3 * MIB));
    SessionStore.Session newest = session("complete", (int) (3 * MIB));
    Path unrelatedFile = Files.write(root.resolve("important.mkv"), new byte[(int) MIB]);
    Path unrelatedDir = Files.createDirectories(root.resolve("vr-notes"));

    // Pool 17 MiB, need 4 MiB; used ~15 MiB -> must free ~2 MiB (one session).
    SessionStore.Capacity c = store.ensureCapacity(4 * MIB, null, 17 * MIB, 0);
    assertTrue(c.ok(), c.detail());
    assertEquals(List.of(oldComplete.id()), c.pruned());
    assertFalse(Files.exists(oldComplete.dir()));

    // Need far more: prunes failed and newest, but never incomplete / foreign / unrelated data.
    c = store.ensureCapacity(14 * MIB, null, 16 * MIB, 0);
    assertFalse(c.ok());
    assertTrue(c.detail().startsWith("STORAGE_FULL"), c.detail());
    assertEquals(List.of(failed.id(), newest.id()), c.pruned());
    assertTrue(Files.exists(incomplete.dir().resolve("seg_00000.mkv")));
    assertTrue(Files.exists(withForeignFile.dir().resolve("seg_00000.mkv")));
    assertTrue(Files.exists(withForeignFile.dir().resolve("my-annotations.txt")));
    assertTrue(Files.exists(unrelatedFile));
    assertTrue(Files.isDirectory(unrelatedDir));
  }

  @Test
  void neverPrunesTheActiveSession() throws Exception {
    SessionStore.Session active = session("complete", (int) (4 * MIB));
    SessionStore.Capacity c = store.ensureCapacity(4 * MIB, active.dir(), 6 * MIB, 0);
    assertFalse(c.ok());
    assertTrue(Files.exists(active.dir().resolve("seg_00000.mkv")));
  }

  @Test
  void refusesWhenPruningCannotRestoreMinimumFreeSpace() throws Exception {
    SessionStore.Session old = session("complete", 1000);
    free.set(1500 * MIB);
    SessionStore.Capacity c = store.ensureCapacity(512 * MIB, null, 2048 * MIB, 1024 * MIB);
    // Pruning a tiny session cannot create 36 MiB of free space: refuse rather than delete more.
    assertFalse(c.ok());
    assertEquals(List.of(old.id()), c.pruned());
    assertTrue(c.detail().contains("STORAGE_FULL"), c.detail());
  }

  @Test
  void symlinkedSessionDirectoryIsNeverFollowedOrDeleted() throws Exception {
    assumeTrue(symlinksSupported(tmp), "symlinks not supported on this filesystem/user");
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(outside.resolve(SessionStore.MARKER_FILE), SessionStore.MARKER_CONTENT);
    Files.writeString(outside.resolve("session.json"), "{\"status\": \"complete\"}");
    Files.write(outside.resolve("seg_00000.mkv"), new byte[(int) (8 * MIB)]);
    Files.createSymbolicLink(root.resolve("vr-000001-20261012T200000Z"), outside);

    assertTrue(store.listOwnedSessions().isEmpty());
    SessionStore.Capacity c = store.ensureCapacity(4 * MIB, null, 6 * MIB, 0);
    assertTrue(c.ok(), "symlink target is not counted as recorder data");
    assertTrue(Files.exists(outside.resolve("seg_00000.mkv")));
  }

  @Test
  void sessionContainingSymlinkIsNotPruned() throws Exception {
    assumeTrue(symlinksSupported(tmp), "symlinks not supported on this filesystem/user");
    SessionStore.Session s = session("complete", (int) (4 * MIB));
    Path precious = Files.writeString(tmp.resolve("precious.txt"), "do not delete");
    Files.createSymbolicLink(s.dir().resolve("seg_00001.mkv"), precious);
    SessionStore.Capacity c = store.ensureCapacity(4 * MIB, null, 6 * MIB, 0);
    assertFalse(c.ok());
    assertTrue(Files.exists(s.dir().resolve("seg_00000.mkv")));
    assertTrue(Files.exists(precious));
  }

  @Test
  void lockPreventsASecondManager() throws Exception {
    SessionStore second = new SessionStore(root);
    SessionStore.StorageException e =
        assertThrows(SessionStore.StorageException.class, second::open);
    assertTrue(e.getMessage().startsWith("LOCKED"), e.getMessage());
    store.close();
    second.open();
    second.close();
  }

  @Test
  void discardRemovesOnlyRecorderFiles() throws Exception {
    SessionStore.Session empty = store.createSession(NOW);
    Files.writeString(empty.dir().resolve("ffmpeg.log"), "error");
    assertTrue(store.discardEmptySession(empty));
    assertFalse(Files.exists(empty.dir()));

    SessionStore.Session foreign = store.createSession(NOW);
    Files.writeString(foreign.dir().resolve("notes.txt"), "keep");
    assertFalse(store.discardEmptySession(foreign));
    assertTrue(Files.exists(foreign.dir().resolve("notes.txt")));
  }

  @Test
  void createSessionRequiresLock() {
    SessionStore unopened = new SessionStore(tmp.resolve("other"));
    assertThrows(SessionStore.StorageException.class, () -> unopened.createSession(NOW));
    assertFalse(Files.exists(tmp.resolve("other")), "nothing is created before open()");
  }
}
