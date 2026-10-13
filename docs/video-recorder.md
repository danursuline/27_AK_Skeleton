# USB Video Recorder (SystemCore)

Records **unannotated** color video from one USB camera plugged into SystemCore,
writing it straight to SystemCore storage so it can be replayed on a computer
later (for example, to develop a Limelight SnapScript bumper detector and an
opponent-motion tracker).

This is only a recorder. It does not detect bumpers, track opponents, replay
video, or reproduce Limelight Rewind, and it does not use any Limelight Rewind
APIs.

- Code: `src/main/java/first/robot/video/`
- Robot integration: `Robot.java` (constructor, `robotPeriodic()`, `close()`)
- Configuration: `src/main/deploy/video-recorder.properties`
- Tests: `src/test/java/first/robot/video/`

## How it works

- The recorder is created **only in AdvantageKit `REAL` mode**
  (`Constants.currentMode`). In `SIM` and `REPLAY` it is never constructed, so
  no camera process starts, no recording directory is created, and nothing is
  pruned.
- All slow or blocking work runs on a dedicated daemon thread named
  `VideoRecorder`: camera discovery, FFmpeg probing, starting and stopping
  FFmpeg, disk checks, pruning, and metadata writes.
- `robotPeriodic()` calls `videoRecorder.periodic()`, which only reads two
  NetworkTables booleans, hands the worker a small immutable snapshot
  (enabled, FMS, controls, timestamps), and publishes the latest status. It
  never waits on the worker, a process, or the disk. The snapshot is also the
  **heartbeat**: if the robot loop stops providing it for
  `heartbeatTimeoutSeconds`, recording stops.
- FFmpeg is launched with `ProcessBuilder` and separate arguments (no shell).
  Its combined output is drained continuously by a reader thread, so FFmpeg can
  never block on a full pipe. Only a bounded copy of the log is kept (512 KiB per
  session, plus a 40-line in-memory tail).
- Stopping is bounded: FFmpeg receives `q` on stdin (graceful finalize), then
  SIGTERM after `stopTimeoutSeconds`, then SIGKILL after 2 more seconds.
  Robot-program shutdown (`Robot.close()` and a JVM shutdown hook) uses the
  same sequence, then calls the normal `LoggedRobot.close()`.
- The recorder does not touch the drivetrain, commands, or controller
  bindings. `Inhibit` only affects recording.

## Recording controls

Default behavior:

| Situation | Recording |
|---|---|
| Robot enabled | Starts automatically (`autoRecordWhenEnabled=true`) |
| Robot disabled | Stops and finalizes, unless `ManualRecord` is true |
| `ManualRecord` = true | Records even while disabled; keeps recording through disable |
| `Inhibit` = true | Stops / prevents recording, whatever else is requested |
| FMS attached | Blocked unless `allowRecordingWithFms=true` (opt in only after hardware validation) |
| Robot loop heartbeat stale | Stops |
| Session reaches `maxSessionSeconds` or `maxSessionMiB` | Stops and **latches** |

**Session limit latch:** when a session hits its limit, recording stays off.
Nothing restarts while the robot stays enabled or `ManualRecord` stays true.
To start a new session, release the request (robot disabled **and**
`ManualRecord` false), then request recording again.

### NetworkTables (`/VideoRecorder`)

| Topic | Type | Access | Meaning |
|---|---|---|---|
| `/VideoRecorder/ManualRecord` | boolean | write | Practice recording while disabled; keeps recording through disable |
| `/VideoRecorder/Inhibit` | boolean | write | Stop/prevent recording |
| `/VideoRecorder/State` | string | read | One of the states below |
| `/VideoRecorder/Detail` | string | read | Explanation, prefixed with an error code when relevant |
| `/VideoRecorder/Session` | string | read | Current or most recent session directory name |
| `/VideoRecorder/Frames` | integer | read | Frames written in that session |
| `/VideoRecorder/Recording` | boolean | read | True **only while frames are arriving**, not merely when FFmpeg is running |

`ManualRecord` and `Inhibit` are reset to `false` every time robot code starts.
Controls and status are also logged through AdvantageKit under
`RealOutputs/VideoRecorder/...`.

States: `IDLE`, `INHIBITED`, `FMS_BLOCKED`, `HEARTBEAT_LOST`, `SESSION_LIMIT`,
`STARTING`, `RECORDING`, `STOPPING`, `FFMPEG_ERROR`, `WAITING_FOR_CAMERA`,
`CAMERA_ERROR`, `STORAGE_ERROR`, `PROCESS_ERROR`, `CONFIG_ERROR`, `DISABLED`,
`INTERNAL_ERROR`, `CLOSED`.

While idle, `Detail` also reports readiness (for example
`Idle; ready: camera /dev/v4l/by-id/..., FFmpeg 6.1.1` or
`Idle; not ready: FFMPEG_MISSING: ...`), so you can check setup in the pits
without enabling.

## Camera and video defaults

All of these are set in `video-recorder.properties`:

| Key | Default | Notes |
|---|---|---|
| `device` | `auto` | Auto-select only if exactly one USB capture device exists |
| `ffmpegPath` | `ffmpeg` | Name on `PATH` or absolute path |
| `inputFormat` | `MJPEG` | `MJPEG` (stream copy) or `YUYV` (software encode, explicit only) |
| `width` x `height` @ `fps` | 640 x 480 @ 30 | Must be supported by the camera |
| `segmentSeconds` | 10 | Matroska segment length |
| `maxSessionSeconds` | 180 | Session limit (latched) |
| `maxSessionMiB` | 512 | Per-session size limit and reservation (latched) |
| `outputDirectory` | `/home/systemcore/videos` | |
| `poolMiB` | 2048 | Max total size of recorder-owned sessions |
| `minFreeMiB` | 1024 | Minimum filesystem free space to keep |
| `retryIntervalSeconds` | 5 | Retry for missing camera/FFmpeg; base for backoff |
| `maxRetryBackoffSeconds` | 60 | Cap for exponential backoff after process failures |
| `startupTimeoutSeconds` | 10 | First frame must arrive within this time |
| `stallTimeoutSeconds` | 5 | Stop if frames stop advancing for this long |
| `heartbeatTimeoutSeconds` | 1.0 | Robot-loop heartbeat timeout |
| `stopTimeoutSeconds` | 3 | Graceful-stop wait before SIGTERM |
| `allowRecordingWithFms` | `false` | Opt-in after hardware validation |
| `autoRecordWhenEnabled` | `true` | |
| `rawEncodeQuality` / `rawEncodePixelFormat` | 5 / `yuvj422p` | YUYV mode only |

Unknown keys and invalid values are rejected. The state becomes `CONFIG_ERROR`
with every problem listed in `Detail`. Robot code keeps running.

**MJPEG (default):** compressed frames from the camera are **stream-copied**
(`-c:v copy`) into MJPEG-in-Matroska `.mkv` segments, with no decode and no
re-encode. The JPEG data, including its color, is stored byte-for-byte.

**YUYV fallback (`inputFormat=YUYV`):** for cameras without MJPEG. Raw frames
are software-encoded to MJPEG (`-c:v mjpeg -q:v <rawEncodeQuality>`). This has a
**much higher, unmeasured CPU cost** on SystemCore. Raw 640x480 YUYV at 30 FPS is
also about 18 MB/s of USB bandwidth, which many cameras cannot deliver at that
rate. It is never selected automatically. Benchmark it on the robot first
(see [Physical validation](#physical-validation-on-systemcore)).

Color: the recorder keeps whatever the camera delivers (for example,
YUV 4:2:0 or 4:2:2 JPEG). It cannot tell whether a camera has a color sensor.
A monochrome camera records grayscale.

## SystemCore setup

### 1. Release the USB port from built-in vision

SystemCore's built-in Limelight vision runs up to four USB vision instances
(`limelightsc0` to `limelightsc3`), one per USB port. Plug in a USB camera and,
by default, built-in vision captures from it. If built-in vision and FFmpeg both
try to open the same camera, one of them fails (FFmpeg reports
`Device or resource busy`, which the recorder shows as `CAMERA_BUSY`).

This recorder uses the camera **for recording only**. For the port the recording
camera is plugged into:

1. Open the SystemCore web UI (`http://robot.local`) and go to the **Config**
   tab.
2. Disable **USB camera capture** for that one port. SystemCore OS
   Alpha/Beta 14 added a per-port option for this. Labels can change between OS
   releases, so check against your OS version's release notes.
3. Leave every other port alone. The recorder never disables other vision
   cameras or changes system configuration itself.

Sources:
[SystemCore OS release notes](https://github.com/LimelightVision/systemcore-os-public)
("Config Tab: Add ability to disable USB camera capture for individual USB
ports"),
[LimelightVision.md in SystemcoreTesting](https://github.com/wpilibsuite/SystemcoreTesting/blob/main/LimelightVision.md)
(USB vision instances per port).

Note: Linux `/dev/videoN` numbers are assigned in enumeration order and are
**not** physical USB port numbers. One UVC camera usually creates two nodes:
capture (`...-video-index0`) and metadata (`...-video-index1`).

### 2. Check FFmpeg

The recorder does **not** install packages or change the OS. It checks for FFmpeg
and reports `FFMPEG_MISSING` / `FFMPEG_FEATURES_MISSING` if it is unusable. The
check repeats every `retryIntervalSeconds`, so installing FFmpeg later works
without redeploying.

SSH into SystemCore. The address and default account are in the
[WPILib SystemCore SSH docs](https://docs.wpilib.org/en/latest/docs/software/systemcore-info/systemcore-ssh.html).
From PowerShell:

```powershell
ssh systemcore@robot.local
```

Then check for FFmpeg and the features the recorder needs:

```sh
command -v ffmpeg && ffmpeg -hide_banner -version | head -n 1
ffmpeg -hide_banner -devices  | grep v4l2                  # V4L2 camera input
ffmpeg -hide_banner -muxers   | grep -E ' (matroska|segment) '
ffmpeg -hide_banner -encoders | grep ' mjpeg '               # only needed for inputFormat=YUYV
```

Required FFmpeg features: the `v4l2` input device, plus the `matroska` and
`segment` muxers. The `mjpeg` encoder is needed only for YUYV mode. Both
`-fps_mode passthrough` (FFmpeg 5.1 and later) and the older
`-vsync passthrough` are supported; the recorder picks one from the reported
version. Versions exercised by the automated tests: 5.0.1 (Windows desktop) and
the Ubuntu 24.04 package used in CI. Neither run was on SystemCore.

**Installing FFmpeg:** this guide gives no install command because none has been
verified. At the time of writing, the official SystemCore documentation does
not describe installing FFmpeg. Use a mechanism SystemCore officially supports,
or put a Linux **arm64/aarch64** FFmpeg build that you trust somewhere the robot
program can execute. Then set `ffmpegPath=/absolute/path/to/ffmpeg`.

### 3. Identify the camera and its capture modes

With the camera plugged in (and that port's built-in capture disabled):

```sh
ls -l /dev/v4l/by-id/
ffmpeg -hide_banner -f v4l2 -list_formats all -i /dev/v4l/by-id/usb-XXXX-video-index0
```

Look for a `Compressed: mjpeg` line that lists `640x480`. If `v4l2-ctl` is
installed, `v4l2-ctl -d <device> --list-formats-ext` also shows frame rates.

- With **exactly one** USB camera attached, `device=auto` selects it, using
  its `/dev/v4l/by-id` path when available.
- With **more than one** (for example, a vision camera on another port),
  `auto` refuses to guess (`AMBIGUOUS_CAMERA`). Set an explicit path, preferably
  the stable `/dev/v4l/by-id/usb-...-video-index0` link:

  ```properties
  device=/dev/v4l/by-id/usb-Arducam_USB_Camera_XXXX-video-index0
  ```

The recorder never assumes the camera supports the requested format, size, or
frame rate. Before each capture it runs `-list_formats`; if the format or size
is not advertised, you get `UNSUPPORTED_MODE` and the list of modes the camera
does advertise. If the driver substitutes a different size or format at
capture time, FFmpeg is stopped and `UNSUPPORTED_MODE` is reported. If the
driver changes only the frame interval, recording continues and an
`FPS_CHANGED` warning shows in `Detail` and `session.json`.

### 4. Configure and deploy

Edit `src/main/deploy/video-recorder.properties`, then deploy as usual from
PowerShell in the project directory:

```powershell
.\gradlew deploy
```

The file is copied to `/home/systemcore/deploy/video-recorder.properties`.

## Recordings

Each session is a directory in `outputDirectory`:

```text
/home/systemcore/videos/
  .video-recorder.lock            exclusive lock (one recorder per pool)
  last-failure.log                diagnostics from the last attempt that produced no video
  vr-000012-20261012T201533Z/     vr-<sequence>-<UTC launch time>
    .video-recorder-session       ownership marker
    session.json                  metadata (see below)
    segments.csv                  finalized segments with start/end times (s)
    seg_00000.mkv, seg_00001.mkv  MJPEG-in-Matroska, segmentSeconds each
    ffmpeg.log                    bounded FFmpeg log
```

`session.json` records: device path and resolved node, how the camera was
selected, the mode-check result, the FFmpeg path, version, and full argument
list, the capture configuration, wall-clock (UTC) and robot timestamps at
launch, first frame, and end, frame/dup/drop counts, exit code, how the
process was stopped (`q`, `SIGTERM`, `SIGKILL`, `self`), warnings, the segment
list, and `status`. Status is `recording` while active (or if the program died
mid-session), `complete` after a clean stop with frames, and `failed` for any
other ended session. Attempts that captured zero frames are removed (only
recorder-owned files) and summarized in `last-failure.log`.

The session sequence number, not the clock, decides "oldest", so pruning
order still holds if the wall clock was unset at boot.

### Copying recordings to a computer

From PowerShell on a computer connected to the robot (Windows includes the
OpenSSH `scp` client):

```powershell
scp -r systemcore@robot.local:/home/systemcore/videos/vr-000012-20261012T201533Z .
```

Copy whole session directories, and copy them while not recording. All segments
of a session share one timeline, so they can be played in order, or joined
without re-encoding:

```powershell
ffmpeg -f concat -safe 0 -i list.txt -c copy session.mkv
```

`list.txt` contains one `file 'seg_00000.mkv'` line per segment, in order.

## Storage, retention, and safety

- Before each session, the recorder reserves `maxSessionMiB`. The session may
  start only if the recorder pool stays within `poolMiB` **and** filesystem free
  space stays at least `minFreeMiB`.
- To make room, it deletes the **oldest completed sessions positively
  identified as its own**. A session must meet all of these:
  - Its name matches `vr-NNNNNN-YYYYMMDDTHHMMSSZ`.
  - It is a real directory, not a symlink.
  - It contains the exact marker file.
  - `session.json` status is `complete` or `failed`.
  - Every entry inside is a regular file with a recorder-owned name.
- It never deletes the active session, `recording`/incomplete sessions,
  sessions containing other files or symlinks, symlinks, or anything else in the
  directory. Unrelated files are not counted in the pool.
- If safe pruning cannot free enough space, it **refuses to record**
  (`STORAGE_FULL`) instead of deleting anything else. Copy off and delete
  recordings manually.
- While recording, it checks once per second. If the session exceeds
  `maxSessionMiB` it stops and latches (`SESSION_SIZE_LIMIT`). If free space
  drops below `minFreeMiB`, it stops (`LOW_DISK`).
- An OS file lock on `.video-recorder.lock` stops two recorder instances from
  managing the same pool (`LOCKED`).
- FFmpeg is also given `-t maxSessionSeconds`, so a process orphaned by a JVM
  crash still stops on its own.

## Timing guarantees and limitations

What is guaranteed (and covered by tests with synthetic video):

- No `-r`, frame-rate filter, or constant-frame-rate conversion is applied.
  Timestamps pass through unchanged (`-fps_mode passthrough`), so frames are
  never duplicated or dropped to fake a constant rate. Real gaps between frames
  (for example, a USB hiccup) stay as gaps in the timestamps.
- Each frame's timestamp is the capture timestamp FFmpeg receives from V4L2,
  rebased so the session starts near 0. Segments do not reset timestamps, so
  one session is a single continuous timeline across all `.mkv` segments.
  Matroska stores timestamps with **1 ms** resolution.
- In MJPEG mode the stored JPEG payloads are byte-identical to what the camera
  sent.

Limitations:

- **No per-frame synchronization to robot logs.** `session.json` stores
  wall-clock and robot (AdvantageKit) timestamps at launch, first frame, and
  end. These are sampled by the recorder thread when it *observed* those
  events, through FFmpeg progress reports roughly every 0.5 s. They are coarse
  (around 0.5 to 1 s) and are not frame-accurate. The nominal FPS is not a
  timing source either: use the per-frame timestamps in the file.
- AdvantageKit logs `VideoRecorder/Frames` and `VideoRecorder/Recording` every
  loop, which gives a coarse frame-count-versus-robot-time relationship. For
  precise alignment, film a visible event (for example, an LED toggled by
  robot code and logged) and align on it during analysis.
- Wall-clock times are only as good as SystemCore's clock (`systemTimeValid` is
  recorded).
- Camera-side latency (exposure to USB delivery) is not measured.

## Troubleshooting

Check `/VideoRecorder/State` and `/VideoRecorder/Detail` first, then the
session's `ffmpeg.log` or `last-failure.log`. After an error, the recorder
retries: every `retryIntervalSeconds` for missing hardware or dependencies, and
with exponential backoff up to `maxRetryBackoffSeconds` for process failures.

| Detail code | Meaning / fix |
|---|---|
| `CONFIG_MISSING`, `unknown key`, `... must be ...` | Fix `video-recorder.properties` and redeploy |
| `FFMPEG_MISSING` | FFmpeg not on `PATH` or `ffmpegPath` wrong. See [Check FFmpeg](#2-check-ffmpeg) |
| `FFMPEG_FEATURES_MISSING` | The build lacks v4l2, matroska, segment, or (YUYV) mjpeg. Use a fuller build |
| `FFMPEG_UNUSABLE`, `FFMPEG_LAUNCH_FAILED` | Binary not executable or wrong architecture (needs arm64) |
| `NO_CAMERA` | Nothing detected. Check cable and port; plugging in later is fine (auto-retry) |
| `AMBIGUOUS_CAMERA` | More than one USB camera. Set `device=` to a `/dev/v4l/by-id/...-video-index0` path |
| `CAMERA_BUSY` | Another process holds the camera, usually SystemCore built-in capture on that port. Disable it for that port (setup step 1) |
| `PERMISSION_DENIED` | The robot program user cannot open the device or write `outputDirectory`. Compare `ls -l /dev/video*` with that user's groups. Do not change system permissions without understanding the impact |
| `UNSUPPORTED_MODE` | The camera does not offer the format/size. Pick a listed mode, or use `inputFormat=YUYV` after benchmarking |
| `NOT_CAPTURE_DEVICE` | You pointed at a metadata node. Use `...-video-index0` |
| `FPS_CHANGED` (warning) | The driver chose a different frame interval. Recording continues with real timestamps |
| `NO_FRAMES` | FFmpeg started but nothing arrived within `startupTimeoutSeconds` |
| `CAMERA_STALLED` | Frames stopped for `stallTimeoutSeconds` (unplugged, USB reset, power). Retries with backoff |
| `CAMERA_DISCONNECTED` | The device disappeared. Waits for it to return |
| `PROCESS_EXITED` | FFmpeg exited unexpectedly. See `ffmpeg.log` |
| `STORAGE_FULL`, `LOW_DISK`, `NO_SPACE` | Copy off and delete old sessions, or adjust `poolMiB`/`minFreeMiB`/`maxSessionMiB` |
| `LOCKED` | Another recorder instance is using the same `outputDirectory` |
| `HEARTBEAT_LOST` | `robotPeriodic()` stopped running, for example robot code hung |
| `SESSION_LIMIT`, `SESSION_SIZE_LIMIT` | Expected. Disable and clear `ManualRecord`, then request again |

## Testing

### Desktop tests (no SystemCore, no camera)

```powershell
.\gradlew test
```

- Unit tests cover configuration validation, the start/stop policy, manual
  override, inhibit, FMS blocking, heartbeat loss, missing FFmpeg, camera
  absence and reconnection, ambiguous cameras, busy/unsupported cameras, process
  exit and stall, retry backoff, bounded shutdown escalation, the session-limit
  latch and rearm, storage refusal, locking, and safe pruning (including symlink
  cases on platforms that allow creating symlinks), plus the REAL-only factory,
  NetworkTables control reset, and a check that `periodic()` stays non-blocking
  while the worker is stuck stopping FFmpeg. They use fake processes, clocks,
  and `/dev`/`/sys` trees.
- `FfmpegIntegrationTest` runs the recorder's real FFmpeg command line against
  synthetic video. It checks that segments decode cleanly and are finalized, that
  MJPEG stream copy is bit-exact (per-packet CRC), that relative timestamps and a
  deliberate frame gap are preserved across segments, that the YUYV fallback
  encodes MJPEG without duplicating frames, and that a full controller run stops
  gracefully and finalizes on disable. It is skipped if FFmpeg is not found.
  To point it at a specific binary:

  ```powershell
  $env:VIDEO_RECORDER_TEST_FFMPEG = "C:\path\to\ffmpeg.exe"
  .\gradlew test
  ```

  CI installs FFmpeg and sets `VIDEO_RECORDER_REQUIRE_FFMPEG=true` so these tests
  fail instead of skipping.

Desktop tests prove the logic and the FFmpeg command line. They do **not**
prove SystemCore CPU usage, USB bandwidth, a particular camera's behavior, or
SystemCore permissions.

### Physical validation on SystemCore

Still required before relying on this at events:

1. Deploy, then confirm `State=IDLE` and `Detail` says `ready: camera ...`.
2. Set `ManualRecord=true` while disabled. `Recording` should become true
   within a few seconds and `Frames` should increase at about the camera rate.
3. Watch CPU while recording (`top` over SSH). Check the AdvantageKit log for
   loop overruns. Repeat with the robot enabled and driving.
4. Unplug the camera mid-recording (expect `CAMERA_STALLED`/`CAMERA_DISCONNECTED`
   and no robot-code impact), then plug it back in (recording should resume).
5. Copy a session off and confirm it plays, is in color, and `session.json` is
   `complete`.
6. Only then consider `allowRecordingWithFms=true`.

On a Linux machine with a UVC camera, `PhysicalCameraTest` records about 5 s
with the production command line. It is skipped unless
`VIDEO_RECORDER_HW_DEVICE` is set:

```sh
VIDEO_RECORDER_HW_DEVICE=/dev/v4l/by-id/usb-XXXX-video-index0 ./gradlew test --tests '*PhysicalCameraTest*'
```
