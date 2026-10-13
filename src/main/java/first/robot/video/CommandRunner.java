package first.robot.video;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived command with separate arguments (no shell), a hard timeout, and bounded output
 * capture. Output is drained on a separate thread so the child can never block on a full pipe.
 */
final class CommandRunner {
  private CommandRunner() {}

  /**
   * Command result.
   *
   * @param exitCode process exit code, or -1 if it timed out and was killed
   * @param output combined stdout/stderr, truncated to the requested size
   * @param timedOut true if the timeout expired
   */
  record Result(int exitCode, String output, boolean timedOut) {}

  static Result run(List<String> command, long timeoutMillis, int maxChars) throws IOException {
    ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
    Process process = pb.start();
    try {
      process.getOutputStream().close();
    } catch (IOException ignored) {
      // Child may already have exited.
    }
    StringBuilder out = new StringBuilder();
    Thread drain =
        new Thread(
            () -> drain(process.getInputStream(), out, maxChars), "VideoRecorder-cmd-output");
    drain.setDaemon(true);
    drain.start();
    boolean finished;
    try {
      finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      finished = false;
    }
    if (!finished) {
      process.destroyForcibly();
      try {
        process.waitFor(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      drain.join(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    String text;
    synchronized (out) {
      text = out.toString();
    }
    return new Result(finished ? process.exitValue() : -1, text, !finished);
  }

  private static void drain(InputStream in, StringBuilder out, int maxChars) {
    char[] buf = new char[4096];
    try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
      int n;
      while ((n = reader.read(buf)) >= 0) {
        synchronized (out) {
          int room = maxChars - out.length();
          if (room > 0) {
            out.append(buf, 0, Math.min(room, n));
          }
        }
      }
    } catch (IOException ignored) {
      // Process ended or stream closed.
    }
  }
}
