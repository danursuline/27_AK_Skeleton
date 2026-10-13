package first.robot.video;

/**
 * Small immutable snapshot passed from the robot loop to the recorder worker every cycle. It
 * doubles as the heartbeat: the worker stops recording if {@code heartbeatNanos} becomes stale.
 *
 * @param robotEnabled robot is enabled
 * @param fmsAttached connected to the field management system
 * @param manualRecord value of /VideoRecorder/ManualRecord
 * @param inhibit value of /VideoRecorder/Inhibit
 * @param heartbeatNanos {@link System#nanoTime()} when this snapshot was taken
 * @param robotTimestampSeconds robot (AdvantageKit/FPGA) timestamp when this snapshot was taken
 * @param systemTimeValid whether the system wall clock has been set (e.g. by the DS)
 */
public record RecorderRequest(
    boolean robotEnabled,
    boolean fmsAttached,
    boolean manualRecord,
    boolean inhibit,
    long heartbeatNanos,
    double robotTimestampSeconds,
    boolean systemTimeValid) {}
