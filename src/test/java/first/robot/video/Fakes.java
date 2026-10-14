package first.robot.video;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic test doubles for the recorder's external dependencies. */
final class Fakes {
  private Fakes() {}

  /** Manually advanced monotonic clock. */
  static final class Clock {
    final AtomicLong nanos = new AtomicLong(1_000_000_000L);

    long now() {
      return nanos.get();
    }

    void advanceSeconds(double s) {
      nanos.addAndGet((long) (s * 1e9));
    }
  }

  /** Scriptable FFmpeg process. waitForExit never actually sleeps. */
  static class Process implements ManagedProcess {
    final FfmpegOutputMonitor monitor = new FfmpegOutputMonitor();
    final List<String> command;
    volatile boolean alive = true;
    volatile int exitCode = 0;
    boolean exitsOnQ = true;
    boolean exitsOnTerm = true;
    boolean exitsOnKill = true;
    int gracefulRequests;
    int terminates;
    int kills;

    Process(List<String> command) {
      this.command = command;
    }

    void emit(String line) {
      monitor.accept(line);
    }

    void frames(long n) {
      emit("frame=" + n);
      emit("progress=continue");
    }

    void exit(int code) {
      exitCode = code;
      alive = false;
    }

    @Override
    public FfmpegOutputMonitor monitor() {
      return monitor;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public OptionalInt exitCode() {
      return alive ? OptionalInt.empty() : OptionalInt.of(exitCode);
    }

    @Override
    public void requestGracefulStop() {
      gracefulRequests++;
      if (exitsOnQ && alive) {
        exit(0);
      }
    }

    @Override
    public void terminate() {
      terminates++;
      if (exitsOnTerm && alive) {
        exit(255);
      }
    }

    @Override
    public void kill() {
      kills++;
      if (exitsOnKill && alive) {
        exit(137);
      }
    }

    @Override
    public boolean waitForExit(long timeoutNanos) {
      return !alive;
    }

    @Override
    public void awaitOutputDrained(long timeoutMillis) {}
  }

  /** Records launches and hands out scriptable processes. */
  static final class Launcher implements ManagedProcess.Launcher {
    final List<Process> launched = new ArrayList<>();
    IOException failWith;

    @Override
    public ManagedProcess launch(List<String> command, Path logFile) throws IOException {
      if (failWith != null) {
        throw failWith;
      }
      Process p = new Process(List.copyOf(command));
      launched.add(p);
      return p;
    }

    Process last() {
      return launched.get(launched.size() - 1);
    }
  }

  /** Configurable dependency probe. */
  static final class Probe implements DependencyProbe {
    boolean ffmpegAvailable = true;
    DependencyProbe.ModeResult modeResult = DependencyProbe.ModeResult.SUPPORTED;
    String modeDetail = "camera advertises mjpeg 640x480";
    int ffmpegProbes;
    int modeChecks;
    boolean lastNeedV4l2;
    boolean lastNeedMjpegEncoder;

    @Override
    public FfmpegInfo probeFfmpeg(String configuredPath, boolean needV4l2, boolean needMjpegEncoder)
        throws ProbeException {
      ffmpegProbes++;
      lastNeedV4l2 = needV4l2;
      lastNeedMjpegEncoder = needMjpegEncoder;
      if (!ffmpegAvailable) {
        throw new ProbeException("FFMPEG_MISSING: 'ffmpeg' not found on PATH");
      }
      return new FfmpegInfo("/usr/bin/ffmpeg", "6.1.1", 6, 1);
    }

    @Override
    public ModeCheck checkMode(FfmpegInfo ffmpeg, String devicePath, VideoRecorderConfig config) {
      modeChecks++;
      return new ModeCheck(modeResult, modeDetail);
    }
  }

  /** Camera locator returning a settable result. */
  static final class Locator implements CameraLocator {
    Lookup result = Lookup.found("/dev/v4l/by-id/usb-Test_Cam-video-index0", "test camera");

    @Override
    public Lookup locate(String configuredDevice) {
      return result;
    }

    void missing() {
      result = Lookup.missing("NO_CAMERA: no USB video capture device found");
    }

    void present() {
      result = Lookup.found("/dev/v4l/by-id/usb-Test_Cam-video-index0", "test camera");
    }
  }
}
