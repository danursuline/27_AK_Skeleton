package first.robot.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Locates USB V4L2 capture devices using {@code /dev/v4l/by-id} (preferred, stable across reboots
 * and port changes) with a sysfs fallback. Automatic selection succeeds only when exactly one USB
 * capture node is present; otherwise an explicit device path is required.
 *
 * <p>Note: Linux {@code /dev/videoN} numbers are assigned in enumeration order and have no fixed
 * relationship to physical USB port numbers.
 */
final class LinuxCameraLocator implements CameraLocator {
  /** UVC cameras expose "-video-index0" for capture and "-video-index1" for metadata. */
  private static final Pattern USB_CAPTURE_BY_ID = Pattern.compile("^usb-.+-video-index0$");

  private static final Pattern VIDEO_NODE = Pattern.compile("^video\\d+$");

  private final Path devRoot;
  private final Path sysRoot;

  LinuxCameraLocator() {
    this(Path.of("/dev"), Path.of("/sys"));
  }

  /** Test constructor with substitute /dev and /sys roots. */
  LinuxCameraLocator(Path devRoot, Path sysRoot) {
    this.devRoot = devRoot;
    this.sysRoot = sysRoot;
  }

  @Override
  public Lookup locate(String configuredDevice) {
    if (!"auto".equals(configuredDevice)) {
      return locateExplicit(configuredDevice);
    }
    Map<String, String> byId = usbCaptureNodesById();
    if (byId.size() == 1) {
      String path = byId.values().iterator().next();
      return Lookup.found(path, "auto-selected the only USB camera: " + path);
    }
    if (byId.size() > 1) {
      return ambiguous(new ArrayList<>(byId.values()));
    }
    List<String> sysfs = usbCaptureNodesFromSysfs();
    if (sysfs.size() == 1) {
      String path = sysfs.get(0);
      return Lookup.found(
          path,
          "auto-selected the only USB camera: "
              + path
              + " (no /dev/v4l/by-id link; node number may change after replug)");
    }
    if (sysfs.size() > 1) {
      return ambiguous(sysfs);
    }
    return Lookup.missing(
        "NO_CAMERA: no USB video capture device found (checked /dev/v4l/by-id and sysfs)");
  }

  private Lookup ambiguous(List<String> candidates) {
    return new Lookup(
        Kind.AMBIGUOUS,
        null,
        "AMBIGUOUS_CAMERA: "
            + candidates.size()
            + " USB cameras found; set device= in video-recorder.properties to one of "
            + String.join(", ", candidates),
        List.copyOf(candidates));
  }

  private Lookup locateExplicit(String configured) {
    Path path = mapDevPath(configured);
    if (!Files.exists(path)) {
      return Lookup.missing("NO_CAMERA: configured device " + configured + " is not present");
    }
    if (!Files.isReadable(path) || !Files.isWritable(path)) {
      return new Lookup(
          Kind.NO_PERMISSION,
          configured,
          "PERMISSION_DENIED: robot program user cannot open "
              + configured
              + " for read/write (check the device's group, e.g. 'video')",
          List.of(configured));
    }
    return Lookup.found(configured, "configured device " + configured);
  }

  /** Maps an absolute /dev/... path onto the (possibly substituted) dev root. */
  private Path mapDevPath(String devPath) {
    String rest = devPath.substring("/dev/".length());
    return devRoot.resolve(rest);
  }

  private String devPathString(String relative) {
    return "/dev/" + relative;
  }

  /** Returns real-node -> by-id path for each distinct USB capture node, sorted by name. */
  private Map<String, String> usbCaptureNodesById() {
    Path dir = devRoot.resolve("v4l").resolve("by-id");
    Map<String, String> result = new LinkedHashMap<>();
    if (!Files.isDirectory(dir)) {
      return result;
    }
    TreeMap<String, Path> sorted = new TreeMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (USB_CAPTURE_BY_ID.matcher(name).matches()) {
          sorted.put(name, entry);
        }
      }
    } catch (IOException e) {
      return result;
    }
    for (Map.Entry<String, Path> e : sorted.entrySet()) {
      String real;
      try {
        real = e.getValue().toRealPath().toString();
      } catch (IOException ex) {
        continue; // dangling link: camera unplugged mid-scan
      }
      result.putIfAbsent(real, devPathString("v4l/by-id/" + e.getKey()));
    }
    return result;
  }

  private List<String> usbCaptureNodesFromSysfs() {
    Path dir = sysRoot.resolve("class").resolve("video4linux");
    List<String> result = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return result;
    }
    TreeMap<Integer, String> sorted = new TreeMap<>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        if (!VIDEO_NODE.matcher(name).matches()) {
          continue;
        }
        if (!"0".equals(readTrimmed(entry.resolve("index")))) {
          continue; // metadata or secondary node
        }
        try {
          String devicePath = entry.resolve("device").toRealPath().toString().replace('\\', '/');
          if (!devicePath.contains("/usb")) {
            continue; // not a USB device (e.g. CSI/ISP nodes)
          }
        } catch (IOException e) {
          continue;
        }
        sorted.put(Integer.parseInt(name.substring(5)), devPathString(name));
      }
    } catch (IOException e) {
      return result;
    }
    result.addAll(sorted.values());
    return result;
  }

  private static String readTrimmed(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      return "";
    }
  }
}
