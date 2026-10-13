package org.littletonrobotics.junction;

import first.robot.Robot;

/** Test-only access to the same AdvantageKit hooks used by LoggedRobot's main loop. */
public final class SteppedRobot extends Robot {
  public SteppedRobot() {
    super();
  }

  public SteppedRobot(java.util.function.Supplier<first.robot.RobotContainer> factory) {
    super(factory);
  }

  public void step() {
    Logger.periodicBeforeUser();
    try {
      loopFunc();
    } finally {
      Logger.periodicAfterUser(0, 0);
    }
  }
}
