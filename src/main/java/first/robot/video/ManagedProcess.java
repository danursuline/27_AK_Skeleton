package first.robot.video;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;

/** A running recorder subprocess. Implementations must never block the caller indefinitely. */
interface ManagedProcess {
  /** Starts recorder subprocesses. */
  interface Launcher {
    /**
     * Starts the command (arguments passed directly, never through a shell).
     *
     * @param command executable followed by its arguments
     * @param logFile file receiving a size-bounded copy of non-progress output
     */
    ManagedProcess launch(List<String> command, Path logFile) throws IOException;
  }

  FfmpegOutputMonitor monitor();

  boolean isAlive();

  /** Exit code once the process has exited. */
  OptionalInt exitCode();

  /** Asks FFmpeg to finish cleanly (writes 'q' to stdin). */
  void requestGracefulStop();

  /** Sends SIGTERM (FFmpeg also finalizes outputs on SIGTERM). */
  void terminate();

  /** Sends SIGKILL. */
  void kill();

  /** Waits up to the timeout for exit; returns true if exited. */
  boolean waitForExit(long timeoutNanos) throws InterruptedException;

  /** Waits briefly for the output pump to read the final lines. */
  void awaitOutputDrained(long timeoutMillis) throws InterruptedException;
}
