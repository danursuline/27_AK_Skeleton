package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import first.robot.video.FfmpegOutputMonitor.Problem;
import org.junit.jupiter.api.Test;

class FfmpegOutputMonitorTest {
  @Test
  void parsesProgressFramesAndIgnoresFinalStatsLine() {
    FfmpegOutputMonitor m = new FfmpegOutputMonitor();
    m.accept("frame=42");
    m.accept("dup_frames=0");
    m.accept("drop_frames=0");
    m.accept("progress=continue");
    m.accept("frame=  120 fps=0.0 q=-1.0 Lsize=N/A time=00:00:04.70 bitrate=N/A");
    assertEquals(42, m.frames());
    assertFalse(m.progressEnded());
    m.accept("frame=120");
    m.accept("progress=end");
    assertEquals(120, m.frames());
    assertTrue(m.progressEnded());
    assertEquals(Problem.NONE, m.problem());
  }

  @Test
  void progressLinesAreNotKeptInTail() {
    FfmpegOutputMonitor m = new FfmpegOutputMonitor();
    m.accept("Input #0, video4linux2,v4l2, from '/dev/video0':");
    m.accept("frame=1");
    assertEquals(1, m.tail().size());
  }

  @Test
  void tailIsBounded() {
    FfmpegOutputMonitor m = new FfmpegOutputMonitor();
    for (int i = 0; i < 1000; i++) {
      m.accept("line " + i + " " + "x".repeat(2000));
    }
    assertEquals(FfmpegOutputMonitor.TAIL_LINES, m.tail().size());
    assertTrue(m.lastLine().length() <= 500);
  }

  @Test
  void classifiesCameraErrors() {
    assertEquals(
        Problem.CAMERA_BUSY,
        FfmpegOutputMonitor.classifyLine(
            "[video4linux2,v4l2 @ 0x5] ioctl(VIDIOC_STREAMON): Device or resource busy"));
    assertEquals(
        Problem.PERMISSION_DENIED,
        FfmpegOutputMonitor.classifyLine(
            "[video4linux2,v4l2 @ 0x5] Cannot open video device /dev/video0: Permission denied"));
    assertEquals(
        Problem.DEVICE_MISSING,
        FfmpegOutputMonitor.classifyLine("/dev/video9: No such file or directory"));
    assertEquals(
        Problem.DEVICE_MISSING,
        FfmpegOutputMonitor.classifyLine(
            "[video4linux2,v4l2 @ 0x5] ioctl(VIDIOC_DQBUF): No such device"));
    assertEquals(
        Problem.UNSUPPORTED_MODE,
        FfmpegOutputMonitor.classifyLine(
            "[video4linux2,v4l2 @ 0x5] The V4L2 driver changed the video from 640x480 to 320x240"));
    assertEquals(
        Problem.UNSUPPORTED_MODE,
        FfmpegOutputMonitor.classifyLine(
            "[video4linux2,v4l2 @ 0x5] Cannot find a proper format for codec 'mjpeg' (id 7)"));
    assertEquals(
        Problem.NOT_CAPTURE_DEVICE,
        FfmpegOutputMonitor.classifyLine("[video4linux2,v4l2 @ 0x5] Not a video capture device."));
    assertEquals(
        Problem.NO_SPACE,
        FfmpegOutputMonitor.classifyLine("Error writing trailer: No space left on device"));
    assertEquals(
        Problem.FFMPEG_FEATURE_MISSING,
        FfmpegOutputMonitor.classifyLine("Unknown input format: 'v4l2'"));
    assertEquals(Problem.NONE, FfmpegOutputMonitor.classifyLine("Stream #0:0: Video: mjpeg"));
  }

  @Test
  void firstProblemWinsAndFpsChangeIsOnlyAWarning() {
    FfmpegOutputMonitor m = new FfmpegOutputMonitor();
    m.accept("[video4linux2,v4l2 @ 0x5] The driver changed the time per frame from 1/30 to 1/15");
    assertEquals(Problem.NONE, m.problem());
    assertEquals(1, m.warnings().size());
    assertTrue(m.warnings().get(0).startsWith("FPS_CHANGED"));
    m.accept("x: Device or resource busy");
    m.accept("y: Permission denied");
    assertEquals(Problem.CAMERA_BUSY, m.problem());
    assertTrue(m.problemLine().contains("busy"));
  }
}
