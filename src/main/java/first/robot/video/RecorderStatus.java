package first.robot.video;

/**
 * Snapshot of recorder status published to NetworkTables and AdvantageKit.
 *
 * @param state top-level state
 * @param detail human-readable detail, prefixed with an error code when relevant
 * @param session current or most recent session id ("" if none)
 * @param frames frames written in the current or most recent session
 * @param recording true only while frames are actually arriving
 */
public record RecorderStatus(
    RecorderState state, String detail, String session, long frames, boolean recording) {
  static RecorderStatus of(RecorderState state, String detail) {
    return new RecorderStatus(state, detail, "", 0, false);
  }
}
