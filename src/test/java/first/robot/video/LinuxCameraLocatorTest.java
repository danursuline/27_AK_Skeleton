package first.robot.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses fake /dev and /sys trees so camera selection is testable without hardware. */
class LinuxCameraLocatorTest {
  @TempDir Path tmp;
  private Path dev;
  private Path sys;
  private LinuxCameraLocator locator;

  @BeforeEach
  void setUp() throws IOException {
    dev = Files.createDirectories(tmp.resolve("dev"));
    sys = Files.createDirectories(tmp.resolve("sys"));
    locator = new LinuxCameraLocator(dev, sys);
  }

  private void videoNode(int n) throws IOException {
    Files.writeString(dev.resolve("video" + n), "");
  }

  /** Creates a by-id entry. Uses a symlink when possible, else a stand-in regular file. */
  private void byId(String name, int node) throws IOException {
    Path byId = Files.createDirectories(dev.resolve("v4l").resolve("by-id"));
    try {
      Files.createSymbolicLink(byId.resolve(name), dev.resolve("video" + node));
    } catch (IOException | UnsupportedOperationException e) {
      Files.writeString(byId.resolve(name), "");
    }
  }

  @Test
  void singleUsbCameraIsAutoSelectedByStableId() throws Exception {
    videoNode(0);
    videoNode(1);
    byId("usb-Arducam_USB_Camera_SN1-video-index0", 0);
    byId("usb-Arducam_USB_Camera_SN1-video-index1", 1); // metadata node
    CameraLocator.Lookup r = locator.locate("auto");
    assertEquals(CameraLocator.Kind.FOUND, r.kind());
    assertEquals("/dev/v4l/by-id/usb-Arducam_USB_Camera_SN1-video-index0", r.devicePath());
  }

  @Test
  void twoUsbCamerasRequireExplicitDevice() throws Exception {
    videoNode(0);
    videoNode(2);
    byId("usb-CamA_1-video-index0", 0);
    byId("usb-CamB_2-video-index0", 2);
    CameraLocator.Lookup r = locator.locate("auto");
    assertEquals(CameraLocator.Kind.AMBIGUOUS, r.kind());
    assertEquals(2, r.candidates().size());
    assertTrue(r.detail().contains("set device="), r.detail());
  }

  @Test
  void noCameraIsMissing() {
    CameraLocator.Lookup r = locator.locate("auto");
    assertEquals(CameraLocator.Kind.MISSING, r.kind());
    assertTrue(r.detail().startsWith("NO_CAMERA"), r.detail());
  }

  @Test
  void nonUsbByIdEntriesAreIgnored() throws Exception {
    videoNode(0);
    byId("platform-xhci-video-index0", 0);
    assertEquals(CameraLocator.Kind.MISSING, locator.locate("auto").kind());
  }

  @Test
  void explicitDeviceMustExist() throws Exception {
    CameraLocator.Lookup missing = locator.locate("/dev/v4l/by-id/usb-Nope-video-index0");
    assertEquals(CameraLocator.Kind.MISSING, missing.kind());
    videoNode(4);
    CameraLocator.Lookup found = locator.locate("/dev/video4");
    assertEquals(CameraLocator.Kind.FOUND, found.kind());
    assertEquals("/dev/video4", found.devicePath());
  }

  @Test
  void explicitDeviceIsUsedEvenWhenSeveralCamerasExist() throws Exception {
    videoNode(0);
    videoNode(2);
    byId("usb-CamA_1-video-index0", 0);
    byId("usb-CamB_2-video-index0", 2);
    CameraLocator.Lookup r = locator.locate("/dev/v4l/by-id/usb-CamB_2-video-index0");
    assertEquals(CameraLocator.Kind.FOUND, r.kind());
  }

  @Test
  void sysfsFallbackSelectsOnlyUsbCaptureNodes() throws Exception {
    Path usbDevice = Files.createDirectories(sys.resolve("devices/platform/xhci/usb1/1-1/1-1.0"));
    Path csiDevice = Files.createDirectories(sys.resolve("devices/platform/csi0"));
    Path cls = Files.createDirectories(sys.resolve("class/video4linux"));
    try {
      for (int n : new int[] {0, 1, 2}) {
        Path node = Files.createDirectories(cls.resolve("video" + n));
        Files.writeString(node.resolve("index"), n == 1 ? "1\n" : "0\n");
        Files.createSymbolicLink(node.resolve("device"), n == 2 ? csiDevice : usbDevice);
      }
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "symlinks not supported: " + e);
    }
    CameraLocator.Lookup r = locator.locate("auto");
    assertEquals(CameraLocator.Kind.FOUND, r.kind(), r.detail());
    assertEquals("/dev/video0", r.devicePath());
    assertTrue(r.detail().contains("no /dev/v4l/by-id link"), r.detail());
  }
}
