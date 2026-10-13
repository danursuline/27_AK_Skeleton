package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the recorder's real FFmpeg command line against synthetic video (no camera needed).
 *
 * <p>Uses {@code VIDEO_RECORDER_TEST_FFMPEG} or {@code ffmpeg} on PATH. Skipped when FFmpeg is
 * unavailable unless {@code VIDEO_RECORDER_REQUIRE_FFMPEG=true} (set in CI), in which case it
 * fails.
 */
@Tag("ffmpeg")
class FfmpegIntegrationTest {
  private static FfmpegInfo ffmpeg;

  @TempDir Path tmp;

  @BeforeAll
  static void locateFfmpeg() {
    String configured = System.getenv("VIDEO_RECORDER_TEST_FFMPEG");
    boolean required = "true".equalsIgnoreCase(System.getenv("VIDEO_RECORDER_REQUIRE_FFMPEG"));
    try {
      Path exe =
          FfmpegProbe.resolveExecutable(
              configured == null || configured.isBlank() ? "ffmpeg" : configured, System.getenv());
      ffmpeg = new FfmpegProbe().probeFfmpeg(exe.toString(), false, true);
    } catch (DependencyProbe.ProbeException e) {
      if (required) {
        fail("FFmpeg is required for this run but unavailable: " + e.getMessage());
      }
      assumeTrue(false, "FFmpeg not available, skipping integration tests: " + e.getMessage());
    }
  }

  /** Builder that reads a file instead of a V4L2 device. */
  static final class FileInputBuilder extends FfmpegCommandBuilder {
    private final List<String> input;

    FileInputBuilder(List<String> input) {
      this.input = input;
    }

    @Override
    boolean usesV4l2() {
      return false;
    }

    @Override
    protected List<String> inputArgs(VideoRecorderConfig config, String devicePath) {
      return input;
    }
  }

  /** One packet from {@code -f framecrc}: presentation time, size, and payload checksum. */
  record Packet(double ptsSeconds, int size, String crc) {}

  private static String passthrough() {
    return ffmpeg.supportsFpsMode() ? "-fps_mode" : "-vsync";
  }

  private static CommandRunner.Result run(List<String> cmd) throws Exception {
    CommandRunner.Result r = CommandRunner.run(cmd, 120_000, 8 * 1024 * 1024);
    assertFalse(r.timedOut(), "timed out: " + cmd);
    return r;
  }

  /** Synthetic colour MJPEG at 30 FPS with a deliberate 0.75 s gap before frame 45. */
  private Path syntheticMjpeg() throws Exception {
    Path out = tmp.resolve("synthetic-mjpeg.mkv");
    CommandRunner.Result r =
        run(
            List.of(
                ffmpeg.executable(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=640x480:rate=30:duration=4",
                "-vf",
                "setpts='PTS+if(gte(N,45),0.75/TB,0)'",
                "-c:v",
                "mjpeg",
                "-q:v",
                "5",
                passthrough(),
                "passthrough",
                "-f",
                "matroska",
                out.toString()));
    assertEquals(0, r.exitCode(), r.output());
    return out;
  }

  /** Synthetic raw YUYV 4:2:2 at 30 FPS with a 0.5 s gap before frame 20. */
  private Path syntheticYuyv() throws Exception {
    Path out = tmp.resolve("synthetic-yuyv.nut");
    CommandRunner.Result r =
        run(
            List.of(
                ffmpeg.executable(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=640x480:rate=30:duration=2",
                "-vf",
                "setpts='PTS+if(gte(N,20),0.5/TB,0)',format=yuyv422",
                "-c:v",
                "rawvideo",
                passthrough(),
                "passthrough",
                "-f",
                "nut",
                out.toString()));
    assertEquals(0, r.exitCode(), r.output());
    return out;
  }

  /** Lists packets (with original timestamps) without decoding. */
  private static List<Packet> packets(Path file) throws Exception {
    CommandRunner.Result r =
        run(
            List.of(
                ffmpeg.executable(),
                "-hide_banner",
                "-loglevel",
                "error",
                "-copyts",
                "-i",
                file.toString(),
                "-map",
                "0:v:0",
                "-c",
                "copy",
                "-f",
                "framecrc",
                "-"));
    assertEquals(0, r.exitCode(), r.output());
    long num = 1;
    long den = 1;
    List<Packet> packets = new ArrayList<>();
    for (String line : r.output().split("\\R")) {
      if (line.startsWith("#tb 0:")) {
        String[] tb = line.substring(6).trim().split("/");
        num = Long.parseLong(tb[0].trim());
        den = Long.parseLong(tb[1].trim());
      } else if (!line.isBlank() && !line.startsWith("#")) {
        String[] f = line.split(",");
        packets.add(
            new Packet(
                Long.parseLong(f[1].trim()) * (double) num / den,
                Integer.parseInt(f[4].trim()),
                f[5].trim()));
      }
    }
    return packets;
  }

  private static List<Path> segments(Path dir) throws Exception {
    try (Stream<Path> s = Files.list(dir)) {
      return s.filter(p -> p.getFileName().toString().matches("seg_\\d{5}\\.mkv"))
          .sorted()
          .toList();
    }
  }

  private static void assertDecodesCleanly(Path segment) throws Exception {
    CommandRunner.Result r =
        run(
            List.of(
                ffmpeg.executable(),
                "-hide_banner",
                "-v",
                "error",
                "-i",
                segment.toString(),
                "-f",
                "null",
                "-"));
    assertEquals(0, r.exitCode(), segment + ": " + r.output());
    assertTrue(r.output().isBlank(), segment + " decode errors: " + r.output());
  }

  private static String streamInfo(Path file) throws Exception {
    return run(List.of(ffmpeg.executable(), "-hide_banner", "-i", file.toString())).output();
  }

  private static double maxGap(List<Packet> packets) {
    double max = 0;
    for (int i = 1; i < packets.size(); i++) {
      max = Math.max(max, packets.get(i).ptsSeconds() - packets.get(i - 1).ptsSeconds());
    }
    return max;
  }

  private VideoRecorderConfig config(Map<String, String> extra) throws Exception {
    Map<String, String> m = new HashMap<>(extra);
    m.put("outputDirectory", tmp.resolve("videos").toString());
    m.put("ffmpegPath", ffmpeg.executable());
    m.putIfAbsent("segmentSeconds", "1");
    m.putIfAbsent("maxSessionSeconds", "60");
    m.put("maxSessionMiB", "64");
    m.put("poolMiB", "256");
    m.put("minFreeMiB", "0");
    return VideoRecorderConfig.parse(m);
  }

  /** Runs one recorder command to completion against a finite file input. */
  private FfmpegOutputMonitor record(VideoRecorderConfig config, Path input, Path sessionDir)
      throws Exception {
    Files.createDirectories(sessionDir);
    List<String> cmd =
        new FileInputBuilder(List.of("-i", input.toString()))
            .build(ffmpeg, config, "synthetic", sessionDir);
    ManagedProcess p = new JavaProcessLauncher().launch(cmd, sessionDir.resolve("ffmpeg.log"));
    assertTrue(p.waitForExit(120_000_000_000L), "FFmpeg did not finish");
    p.awaitOutputDrained(5000);
    String log = Files.readString(sessionDir.resolve("ffmpeg.log"), StandardCharsets.UTF_8);
    assertEquals(0, p.exitCode().getAsInt(), log);
    assertTrue(p.monitor().progressEnded(), log);
    return p.monitor();
  }

  @Test
  void mjpegStreamCopyIsBitExactWithTimestampsAndGapsPreserved() throws Exception {
    Path input = syntheticMjpeg();
    List<Packet> in = packets(input);
    assertEquals(120, in.size());
    assertTrue(maxGap(in) > 0.7, "synthetic input must contain the deliberate gap");

    Path session = tmp.resolve("session-copy");
    FfmpegOutputMonitor m = record(config(Map.of()), input, session);
    assertEquals(120, m.frames());
    assertEquals(0, m.dupFrames(), "no duplicated frames");
    assertEquals(0, m.dropFrames(), "no dropped frames");

    List<Path> segs = segments(session);
    assertTrue(segs.size() >= 4, "1 s segments expected, got " + segs);
    List<String> csv = Files.readAllLines(session.resolve(FfmpegCommandBuilder.SEGMENT_LIST));
    assertEquals(segs.size(), csv.size(), "every segment finalized and listed: " + csv);
    List<Packet> out = new ArrayList<>();
    for (Path seg : segs) {
      assertDecodesCleanly(seg);
      String info = streamInfo(seg);
      assertTrue(info.contains("Video: mjpeg"), info);
      assertTrue(info.contains("yuvj420p"), "colour (YUV 4:2:0) preserved: " + info);
      assertTrue(info.contains("640x480"), info);
      out.addAll(packets(seg));
    }
    // Same packets, same sizes, same checksums, same timestamps (incl. the gap) across segments.
    assertEquals(in, out);
  }

  @Test
  void rawYuyvFallbackEncodesMjpegWithoutDuplicatingFrames() throws Exception {
    Path input = syntheticYuyv();
    List<Packet> in = packets(input);
    assertEquals(60, in.size());

    Path session = tmp.resolve("session-raw");
    FfmpegOutputMonitor m = record(config(Map.of("inputFormat", "YUYV")), input, session);
    assertEquals(60, m.frames());
    assertEquals(0, m.dupFrames());
    assertEquals(0, m.dropFrames());

    List<Packet> out = new ArrayList<>();
    for (Path seg : segments(session)) {
      assertDecodesCleanly(seg);
      String info = streamInfo(seg);
      assertTrue(info.contains("Video: mjpeg"), info);
      assertTrue(info.contains("yuvj422p"), info);
      out.addAll(packets(seg));
    }
    assertEquals(in.size(), out.size());
    for (int i = 0; i < in.size(); i++) {
      assertEquals(in.get(i).ptsSeconds(), out.get(i).ptsSeconds(), 0.0011, "pts of frame " + i);
    }
    assertTrue(maxGap(out) > 0.5, "gap preserved");
  }

  @Test
  void controllerEndToEndRecordsAndFinalizesOnDisable() throws Exception {
    Path input = syntheticMjpeg();
    VideoRecorderConfig config = config(Map.of());
    SessionStore store = new SessionStore(config.outputPath());
    RecorderController controller =
        new RecorderController(
            config,
            System::nanoTime,
            Instant::now,
            store,
            device -> CameraLocator.Lookup.found("synthetic-camera", "synthetic input"),
            new FfmpegProbe(),
            new JavaProcessLauncher(),
            new FileInputBuilder(List.of("-re", "-stream_loop", "-1", "-i", input.toString())));
    try {
      long deadline = System.nanoTime() + 20_000_000_000L;
      while (!controller.status().recording() && System.nanoTime() < deadline) {
        controller.submitRequest(
            new RecorderRequest(true, false, false, false, System.nanoTime(), 0, true));
        controller.step();
        Thread.sleep(50);
      }
      assertTrue(controller.status().recording(), controller.status().toString());
      String sessionId = controller.status().session();

      long until = System.nanoTime() + 2_500_000_000L;
      while (System.nanoTime() < until) {
        controller.submitRequest(
            new RecorderRequest(true, false, false, false, System.nanoTime(), 0, true));
        controller.step();
        assertEquals(RecorderState.RECORDING, controller.status().state());
        Thread.sleep(50);
      }

      controller.submitRequest(
          new RecorderRequest(false, false, false, false, System.nanoTime(), 0, true));
      controller.step();
      assertEquals(RecorderState.IDLE, controller.status().state());
      assertFalse(controller.status().recording());

      Path dir = config.outputPath().resolve(sessionId);
      String json = Files.readString(dir.resolve(SessionStore.METADATA_FILE));
      assertTrue(json.contains("\"status\": \"complete\""), json);
      assertTrue(json.contains("\"stopMethod\": \"q\""), "graceful quit via stdin: " + json);
      assertTrue(json.contains("\"exitCode\": 0"), json);

      List<Path> segs = segments(dir);
      assertTrue(segs.size() >= 2, segs.toString());
      double lastPts = -1;
      for (Path seg : segs) {
        assertDecodesCleanly(seg); // includes the final segment finalized by 'q'
        for (Packet p : packets(seg)) {
          assertTrue(p.ptsSeconds() > lastPts, "timestamps strictly increase across segments");
          lastPts = p.ptsSeconds();
        }
      }
      assertTrue(lastPts > 2.0, "recorded at least ~2 s of media, got " + lastPts);
    } finally {
      controller.shutdown();
    }
  }
}
