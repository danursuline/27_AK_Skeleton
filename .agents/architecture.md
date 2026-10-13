# Architecture & Subsystem Layout

## Base Package
`src/main/java/first/robot/subsystems/`

## Subsystem Directory Standard
Each functional mechanism on the robot must have its own isolated package folder.

### File Responsibilities Within Each Subsystem Directory

| File Name Pattern | Package | Responsibility |
| :--- | :--- | :--- |
| `<Name>IO.java` | `first.robot.subsystems.<name>` | Declares `@AutoLog public static class <Name>IOInputs` and hardware control method stubs. |
| `<Name>IOTalonFX.java` | `first.robot.subsystems.<name>` | Instantiates Phoenix v6 `TalonFX` motors, sets configurations, and assigns hardware readings to inputs. |
| `<Name>IOSim.java` | `first.robot.subsystems.<name>` | Implements WPILib/AdvantageKit physics simulation (`DCMotorSim`, `SingleJointedArmSim`, etc.). |
| `<Name>.java` | `first.robot.subsystems.<name>` | Extends `SubsystemBase`. Updates inputs in `periodic()`, records to Logger, and exposes public command factories. |

### Subsystem Boundaries & Interaction Rules
1. Encapsulation: Subsystems never import other subsystems' `IO` classes.
2. Cross-Subsystem Communication: Triggers, Command composition (`RobotContainer.java`), or centralized state models handle coordination.
3. Telemetry: Standardize all debug fields into AdvantageKit outputs using `Logger.recordOutput("SubsystemName/Key", value)`.