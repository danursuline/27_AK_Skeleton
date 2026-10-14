package first.robot.video;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Launches FFmpeg with {@link ProcessBuilder} (separate arguments, no shell). A daemon pump thread
 * continuously drains merged stdout/stderr so the child can never block on a full pipe; it feeds
 * the {@link FfmpegOutputMonitor} and writes a size-capped log file.
 */
final class JavaProcessLauncher implements ManagedProcess.Launcher {
  static final long DEFAULT_MAX_LOG_BYTES = 512 * 1024;

  private final long maxLogBytes;

  JavaProcessLauncher() {
    this(DEFAULT_MAX_LOG_BYTES);
  }

  JavaProcessLauncher(long maxLogBytes) {
    this.maxLogBytes = maxLogBytes;
  }

  @Override
  public ManagedProcess launch(List<String> command, Path logFile) throws IOException {
    ProcessBuilder pb =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE);
    if (logFile.getParent() != null) {
      pb.directory(logFile.getParent().toFile());
    }
    Process process = pb.start();
    return new JavaManagedProcess(process, logFile, maxLogBytes);
  }

  private static final class JavaManagedProcess implements ManagedProcess {
    private final Process process;
    private final FfmpegOutputMonitor monitor = new FfmpegOutputMonitor();
    private final Thread pump;

    JavaManagedProcess(Process process, Path logFile, long maxLogBytes) {
      this.process = process;
      this.pump = new Thread(() -> pump(logFile, maxLogBytes), "VideoRecorder-ffmpeg-output");
      pump.setDaemon(true);
      pump.start();
    }

    private void pump(Path logFile, long maxLogBytes) {
      Writer log = null;
      try {
        log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
      } catch (IOException e) {
        // Logging is best-effort; parsing continues regardless.
      }
      long written = 0;
      boolean truncated = false;
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          monitor.accept(line);
          if (log == null || FfmpegOutputMonitor.isProgressLine(line.strip())) {
            continue;
          }
          if (written + line.length() + 1 <= maxLogBytes) {
            log.write(line);
            log.write('\n');
            written += line.length() + 1;
          } else if (!truncated) {
            log.write("[video recorder: log truncated at " + maxLogBytes + " bytes]\n");
            log.flush();
            truncated = true;
          }
        }
      } catch (IOException e) {
        // Stream closed: process ended.
      } finally {
        if (log != null) {
          try {
            log.close();
          } catch (IOException ignored) {
            // Best-effort.
          }
        }
      }
    }

    @Override
    public FfmpegOutputMonitor monitor() {
      return monitor;
    }

    @Override
    public boolean isAlive() {
      return process.isAlive();
    }

    @Override
    public OptionalInt exitCode() {
      return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
    }

    @Override
    public void requestGracefulStop() {
      try {
        OutputStream stdin = process.getOutputStream();
        stdin.write('q');
        stdin.write('\n');
        stdin.flush();
        stdin.close();
      } catch (IOException ignored) {
        // Process already exited or closed stdin; escalation handles the rest.
      }
    }

    @Override
    public void terminate() {
      process.destroy();
    }

    @Override
    public void kill() {
      process.destroyForcibly();
    }

    @Override
    public boolean waitForExit(long timeoutNanos) throws InterruptedException {
      return process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void awaitOutputDrained(long timeoutMillis) throws InterruptedException {
      pump.join(timeoutMillis);
    }
  }
}
