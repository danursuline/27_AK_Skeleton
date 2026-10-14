package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import first.robot.video.DependencyProbe.ModeResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegProbeTest {
  @TempDir Path tmp;

  private static final String LIST_FORMATS =
      String.join(
          "\n",
          "[video4linux2,v4l2 @ 0x55] Compressed:       mjpeg :          Motion-JPEG :"
              + " 1280x720 640x480 320x240",
          "[video4linux2,v4l2 @ 0x55] Raw       :     yuyv422 :           YUYV 4:2:2 :"
              + " 640x480 320x240",
          "/dev/video0: Immediate exit requested");

  @Test
  void parsesVersionsAndSelectsTimestampPassthroughOption() {
    FfmpegInfo gyan =
        FfmpegInfo.fromVersionOutput(
            "ffmpeg", "ffmpeg version 5.0.1-essentials_build-www.gyan.dev Copyright (c)");
    assertEquals(5, gyan.major());
    assertFalse(gyan.supportsFpsMode());
    assertTrue(
        FfmpegInfo.fromVersionOutput("f", "ffmpeg version 6.1.1-3ubuntu5 Copyright")
            .supportsFpsMode());
    assertTrue(
        FfmpegInfo.fromVersionOutput("f", "ffmpeg version 5.1.2 Copyright").supportsFpsMode());
    assertFalse(
        FfmpegInfo.fromVersionOutput("f", "ffmpeg version n4.4-7-g023 C").supportsFpsMode());
    FfmpegInfo git = FfmpegInfo.fromVersionOutput("f", "ffmpeg version N-113000-gabc Copyright");
    assertEquals(-1, git.major());
    assertTrue(git.supportsFpsMode());
    assertNull(FfmpegInfo.fromVersionOutput("f", "bash: ffmpeg: command not found"));
  }

  @Test
  void modeCheckAcceptsAdvertisedFormatAndSize() {
    VideoRecorderConfig c = VideoRecorderConfig.defaults();
    assertEquals(ModeResult.SUPPORTED, FfmpegProbe.parseListFormats(LIST_FORMATS, c).result());
  }

  @Test
  void modeCheckRejectsUnadvertisedSizeOrFormat() throws Exception {
    VideoRecorderConfig big = VideoRecorderConfig.parse(Map.of("width", "1920", "height", "1080"));
    DependencyProbe.ModeCheck r = FfmpegProbe.parseListFormats(LIST_FORMATS, big);
    assertEquals(ModeResult.UNSUPPORTED, r.result());
    assertTrue(r.detail().startsWith("UNSUPPORTED_MODE"), r.detail());
    assertTrue(r.detail().contains("1280x720"), "lists what the camera supports: " + r.detail());

    String yuyvOnly =
        "[video4linux2,v4l2 @ 0x1] Raw       :     yuyv422 :           YUYV 4:2:2 : 640x480";
    DependencyProbe.ModeCheck noMjpeg =
        FfmpegProbe.parseListFormats(yuyvOnly, VideoRecorderConfig.defaults());
    assertEquals(ModeResult.UNSUPPORTED, noMjpeg.result());
    assertTrue(noMjpeg.detail().contains("format not advertised"));
  }

  @Test
  void modeCheckStepwiseAndUnparseableAreUnknown() {
    String stepwise =
        "[video4linux2,v4l2 @ 0x1] Compressed: mjpeg : Motion-JPEG : {32-1920, 2}x{32-1080, 2}";
    VideoRecorderConfig c = VideoRecorderConfig.defaults();
    assertEquals(ModeResult.UNKNOWN, FfmpegProbe.parseListFormats(stepwise, c).result());
    assertEquals(ModeResult.UNKNOWN, FfmpegProbe.parseListFormats("garbage", c).result());
  }

  @Test
  void modeCheckReportsBusyAndPermission() {
    VideoRecorderConfig c = VideoRecorderConfig.defaults();
    assertEquals(
        ModeResult.BUSY,
        FfmpegProbe.parseListFormats(
                "[video4linux2,v4l2 @ 0x1] Cannot open video device /dev/video0: Device or"
                    + " resource busy",
                c)
            .result());
    assertEquals(
        ModeResult.PERMISSION,
        FfmpegProbe.parseListFormats("/dev/video0: Permission denied", c).result());
  }

  @Test
  void missingExecutableIsReportedActionably() {
    DependencyProbe.ProbeException onPath =
        assertThrows(
            DependencyProbe.ProbeException.class,
            () ->
                FfmpegProbe.resolveExecutable(
                    "ffmpeg-does-not-exist", Map.of("PATH", tmp.toString())));
    assertTrue(onPath.getMessage().startsWith("FFMPEG_MISSING"), onPath.getMessage());
    assertTrue(onPath.getMessage().contains("ffmpegPath"), onPath.getMessage());

    DependencyProbe.ProbeException explicit =
        assertThrows(
            DependencyProbe.ProbeException.class,
            () ->
                FfmpegProbe.resolveExecutable(
                    tmp.resolve("opt/ffmpeg/bin/ffmpeg").toString(), Map.of()));
    assertTrue(explicit.getMessage().contains("does not exist"), explicit.getMessage());
  }

  @Test
  void probeFailsCleanlyWhenFfmpegMissing() {
    FfmpegProbe probe = new FfmpegProbe(Map.of("PATH", tmp.toString()));
    assertThrows(
        DependencyProbe.ProbeException.class, () -> probe.probeFfmpeg("ffmpeg", true, false));
  }

  @Test
  void recognizesMuxerListingRows() {
    String muxers =
        " E matroska        Matroska\n"
            + " E segment         segment\n"
            + " E stream_segment,ssegment streaming segment muxer\n";
    assertTrue(FfmpegProbe.hasListedName(muxers, "matroska"));
    assertTrue(FfmpegProbe.hasListedName(muxers, "segment"));
    assertFalse(FfmpegProbe.hasListedName(" E stream_segment,ssegment x\n", "segment"));
    assertFalse(FfmpegProbe.hasListedName(" D  matroska,webm  Matroska / WebM\n", "matroska"));
  }
}
