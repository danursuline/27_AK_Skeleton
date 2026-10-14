package first.robot.video;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owns the recording pool directory: an exclusive lock file, recorder-owned session directories,
 * metadata, and conservative pruning.
 *
 * <p>A directory is treated as recorder-owned only if its name matches {@link #SESSION_DIR}, it is
 * a real directory (not a symlink), and it contains a marker file with the exact expected content.
 * A session is prunable only if its metadata says it ended ({@code complete} or {@code failed}) and
 * every entry inside it is a regular file with a recorder-owned name. Anything else is preserved.
 */
final class SessionStore implements AutoCloseable {
  static final String LOCK_FILE = ".video-recorder.lock";
  static final String MARKER_FILE = ".video-recorder-session";
  static final String MARKER_CONTENT = "first.robot.video recorder session v1\n";
  static final String METADATA_FILE = "session.json";
  static final String LAST_FAILURE_FILE = "last-failure.log";
  static final Pattern SESSION_DIR = Pattern.compile("^vr-(\\d{6})-(\\d{8}T\\d{6}Z)$");
  static final Pattern OWNED_FILE =
      Pattern.compile(
          "^(seg_\\d{5}\\.mkv|session\\.json|session\\.json\\.tmp|segments\\.csv|ffmpeg\\.log|"
              + "\\.video-recorder-session)$");
  private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([a-z_]+)\"");
  private static final DateTimeFormatter NAME_TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final long MIB = 1024L * 1024L;
  private static final int MAX_FAILURE_LOG_CHARS = 64 * 1024;

  /** Storage problem with an error-code-prefixed message. */
  static final class StorageException extends Exception {
    StorageException(String message) {
      super(message);
    }
  }

  /** A session created by this process. */
  record Session(String id, Path dir, int sequence) {}

  /** A recorder-owned session found on disk. */
  record OwnedSession(
      String id, Path dir, int sequence, String status, long bytes, boolean prunable) {}

  /** Result of a capacity check. */
  record Capacity(boolean ok, List<String> pruned, String detail) {}

  private final Path root;
  private final ToLongFunction<Path> usableSpace;
  private FileChannel lockChannel;
  private FileLock lock;

  SessionStore(Path root) {
    this(root, SessionStore::fileStoreUsableSpace);
  }

  /** Test constructor with a substitute free-space function. */
  SessionStore(Path root, ToLongFunction<Path> usableSpace) {
    this.root = root;
    this.usableSpace = usableSpace;
  }

  Path root() {
    return root;
  }

  boolean isOpen() {
    return lock != null;
  }

  /** Creates the output directory if needed and takes the exclusive pool lock. */
  void open() throws StorageException {
    if (lock != null) {
      return;
    }
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new StorageException("STORAGE_UNAVAILABLE: cannot create " + root + ": " + e);
    }
    if (!Files.isDirectory(root) || !Files.isWritable(root)) {
      throw new StorageException(
          "PERMISSION_DENIED: output directory " + root + " is not writable by the robot program");
    }
    FileChannel channel;
    try {
      channel =
          FileChannel.open(
              root.resolve(LOCK_FILE),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      throw new StorageException(
          "STORAGE_UNAVAILABLE: cannot open lock file in " + root + ": " + e);
    }
    FileLock acquired = null;
    try {
      acquired = channel.tryLock();
    } catch (OverlappingFileLockException e) {
      acquired = null;
    } catch (IOException e) {
      closeQuietly(channel);
      throw new StorageException("STORAGE_UNAVAILABLE: cannot lock " + root + ": " + e);
    }
    if (acquired == null) {
      closeQuietly(channel);
      throw new StorageException(
          "LOCKED: another video recorder instance is already managing " + root);
    }
    lockChannel = channel;
    lock = acquired;
  }

  @Override
  public void close() {
    if (lock != null) {
      try {
        lock.release();
      } catch (IOException ignored) {
        // Released when the channel closes anyway.
      }
      lock = null;
    }
    if (lockChannel != null) {
      closeQuietly(lockChannel);
      lockChannel = null;
    }
  }

  /** Creates a new uniquely named, marked session directory. */
  Session createSession(Instant now) throws StorageException {
    requireOpen();
    int next = nextSequence();
    for (int attempt = 0; attempt < 100; attempt++) {
      int seq = (next + attempt) % 1_000_000;
      String id = String.format(Locale.ROOT, "vr-%06d-%s", seq, NAME_TIME.format(now));
      Path dir = root.resolve(id);
      try {
        Files.createDirectory(dir);
      } catch (FileAlreadyExistsException e) {
        continue;
      } catch (IOException e) {
        throw new StorageException("STORAGE_UNAVAILABLE: cannot create " + dir + ": " + e);
      }
      try {
        writeNoFollow(dir.resolve(MARKER_FILE), MARKER_CONTENT);
      } catch (IOException e) {
        throw new StorageException("STORAGE_UNAVAILABLE: cannot write session marker: " + e);
      }
      return new Session(id, dir, seq);
    }
    throw new StorageException("STORAGE_UNAVAILABLE: could not allocate a session directory");
  }

  /** Atomically (where supported) writes session metadata. */
  void writeMetadata(Session session, SessionMetadata metadata) throws IOException {
    Path tmp = session.dir().resolve(METADATA_FILE + ".tmp");
    writeNoFollow(tmp, metadata.toJson());
    Path target = session.dir().resolve(METADATA_FILE);
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** Writes a bounded diagnostic log for a failed attempt that produced no video. */
  void writeLastFailure(String text) {
    String bounded =
        text.length() > MAX_FAILURE_LOG_CHARS ? text.substring(0, MAX_FAILURE_LOG_CHARS) : text;
    try {
      writeNoFollow(root.resolve(LAST_FAILURE_FILE), bounded);
    } catch (IOException ignored) {
      // Diagnostic only.
    }
  }

  /**
   * Removes a session created by this process that recorded no frames. Only recorder-owned files
   * are deleted; if anything unexpected is present the directory is left alone.
   */
  boolean discardEmptySession(Session session) {
    return deleteOwnedSessionDir(session.dir());
  }

  /** Lists recorder-owned sessions, oldest first. */
  List<OwnedSession> listOwnedSessions() {
    List<OwnedSession> sessions = new ArrayList<>();
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      return sessions;
    }
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        Matcher m = SESSION_DIR.matcher(entry.getFileName().toString());
        if (!m.matches()
            || Files.isSymbolicLink(entry)
            || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
            || !hasValidMarker(entry)) {
          continue;
        }
        String status = readStatus(entry);
        boolean ended = status.equals("complete") || status.equals("failed");
        sessions.add(
            new OwnedSession(
                entry.getFileName().toString(),
                entry,
                Integer.parseInt(m.group(1)),
                status,
                directorySize(entry),
                ended && containsOnlyOwnedFiles(entry)));
      }
    } catch (IOException e) {
      return sessions;
    }
    sessions.sort(Comparator.comparingInt(OwnedSession::sequence).thenComparing(OwnedSession::id));
    return sessions;
  }

  /**
   * Ensures room for one more session, pruning the oldest completed recorder-owned sessions if
   * needed. Never deletes the active session, incomplete sessions, or unrecognized data.
   */
  Capacity ensureCapacity(long requiredBytes, Path activeDir, long poolBytes, long minFreeBytes) {
    List<String> pruned = new ArrayList<>();
    while (true) {
      List<OwnedSession> sessions = listOwnedSessions();
      long used = sessions.stream().mapToLong(OwnedSession::bytes).sum();
      long free = usableBytes();
      if (used + requiredBytes <= poolBytes && free - requiredBytes >= minFreeBytes) {
        return new Capacity(
            true,
            pruned,
            "storage ok: pool "
                + mib(used)
                + "/"
                + mib(poolBytes)
                + " MiB, free "
                + mib(free)
                + " MiB"
                + (pruned.isEmpty() ? "" : ", pruned " + pruned.size() + " old session(s)"));
      }
      OwnedSession victim = null;
      for (OwnedSession s : sessions) {
        if (s.prunable() && (activeDir == null || !s.dir().equals(activeDir))) {
          victim = s;
          break;
        }
      }
      if (victim == null) {
        long preserved = sessions.stream().filter(s -> !s.prunable()).count();
        return new Capacity(
            false,
            pruned,
            "STORAGE_FULL: need "
                + mib(requiredBytes)
                + " MiB for a session; recorder pool uses "
                + mib(used)
                + "/"
                + mib(poolBytes)
                + " MiB and filesystem has "
                + mib(free)
                + " MiB free (minimum "
                + mib(minFreeBytes)
                + " MiB). No more completed recorder sessions can be pruned ("
                + preserved
                + " incomplete or non-prunable session(s) preserved; unrelated files are never"
                + " deleted). Copy off and remove recordings manually.");
      }
      if (!deleteOwnedSessionDir(victim.dir())) {
        return new Capacity(false, pruned, "PRUNE_FAILED: could not safely delete " + victim.dir());
      }
      pruned.add(victim.id());
    }
  }

  long usableBytes() {
    return usableSpace.applyAsLong(root);
  }

  /** Sum of regular-file sizes directly inside a directory (symlinks not followed). */
  static long directorySize(Path dir) {
    long total = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
          try {
            total += Files.size(entry);
          } catch (IOException ignored) {
            // File vanished.
          }
        }
      }
    } catch (IOException e) {
      return total;
    }
    return total;
  }

  private boolean deleteOwnedSessionDir(Path dir) {
    if (Files.isSymbolicLink(dir)
        || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
        || !hasValidMarker(dir)
        || !containsOnlyOwnedFiles(dir)) {
      return false;
    }
    List<Path> files = new ArrayList<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        files.add(entry);
      }
    } catch (IOException e) {
      return false;
    }
    // Delete the marker last so a partially deleted directory is still recognizable.
    files.sort(Comparator.comparing(p -> p.getFileName().toString().equals(MARKER_FILE)));
    try {
      for (Path f : files) {
        Files.delete(f);
      }
      Files.delete(dir);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean containsOnlyOwnedFiles(Path dir) {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        if (!OWNED_FILE.matcher(entry.getFileName().toString()).matches()
            || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
          return false;
        }
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private static boolean hasValidMarker(Path dir) {
    Path marker = dir.resolve(MARKER_FILE);
    if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      return Files.size(marker) < 256
          && Files.readString(marker, StandardCharsets.UTF_8).equals(MARKER_CONTENT);
    } catch (IOException e) {
      return false;
    }
  }

  private static String readStatus(Path dir) {
    Path meta = dir.resolve(METADATA_FILE);
    if (!Files.isRegularFile(meta, LinkOption.NOFOLLOW_LINKS)) {
      return "unknown";
    }
    try {
      if (Files.size(meta) > 1024 * 1024) {
        return "unknown";
      }
      Matcher m = STATUS.matcher(Files.readString(meta, StandardCharsets.UTF_8));
      return m.find() ? m.group(1) : "unknown";
    } catch (IOException e) {
      return "unknown";
    }
  }

  private int nextSequence() {
    int max = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
      for (Path entry : entries) {
        Matcher m = SESSION_DIR.matcher(entry.getFileName().toString());
        if (m.matches()) {
          max = Math.max(max, Integer.parseInt(m.group(1)));
        }
      }
    } catch (IOException ignored) {
      // Start from 1.
    }
    return max + 1;
  }

  private void requireOpen() throws StorageException {
    if (lock == null) {
      throw new StorageException("STORAGE_UNAVAILABLE: storage not open");
    }
  }

  private static void writeNoFollow(Path file, String content) throws IOException {
    try (OutputStream out =
        Files.newOutputStream(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
    }
  }

  private static long fileStoreUsableSpace(Path p) {
    try {
      return Files.getFileStore(p).getUsableSpace();
    } catch (IOException e) {
      return 0;
    }
  }

  static long mib(long bytes) {
    return bytes / MIB;
  }

  private static void closeQuietly(FileChannel c) {
    try {
      c.close();
    } catch (IOException ignored) {
      // Best-effort.
    }
  }
}
