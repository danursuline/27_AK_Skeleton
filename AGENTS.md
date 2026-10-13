# FRC Robot Codebase - AI Operating Guidelines

## Core Stack & Target Runtimes
- Language: Java 25 (Preview features disabled unless explicitly configured in build.gradle)
- Framework: WPILib 2027 (Alpha/Beta Build)
- Architecture Paradigm: Commands V2 + AdvantageKit (Log-Replay Hardware Abstraction)

## Subsystem Architecture & Naming Conventions (AdvantageKit)
The information contained in this section establishes the official team standard for subsystem architecture utilizing the **AdvantageKit** logging framework. Rather than forcing a rigid "one subsystem, one interface" structure, our codebase leverages a **component-driven architecture** to cleanly handle complex mechanisms like Swerve Drive.

1. Subsystem Isolation:
   - Every subsystem MUST reside inside its own dedicated subfolder under `src/main/java/first/robot/subsystems/<subsystem_name>/`.
   - Never place `.java` files directly inside `src/main/java/first/robot/subsystems/`.
   - All classes within a subsystem directory must belong to the package `first.robot.subsystems.<subsystem_name>;`.

Complex subsystems are broken down into sub-components, hardware abstraction interfaces, and performance optimization threads. 

2. Component-Driven Breakdown
| Class / File | Architectural Role & Purpose |
| :--- | :--- |
| **`Drive.java`** | The master WPILib `SubsystemBase` class. It manages high-level state, runs kinematics/odometry, coordinates sub-components, and schedules commands. |
| **`Module.java`** | A logical state handler for a single swerve module. It coordinates a localized `ModuleIO` instance and handles unit conversions or specific closed-loop control states. |
| **`ModuleIO.java`** | The hardware abstraction interface for a single module. Defines the `@AutoLog` data structure (`ModuleIOInputs`) for motors and encoders. |
| **`ModuleIOTalonFX.java`** | Concrete hardware implementation of `ModuleIO` utilizing the CTRE Phoenix 6 API. |
| **`GyroIO.java`** | The hardware abstraction interface for the robot's IMU/gyro telemetry. |
| **`GyroIOPigeon2.java`** | Concrete hardware implementation of `GyroIO` for a CTRE Pigeon 2. |
| **`PhoenixOdometryThread.java`** | A high-frequency optimization thread that samples Phoenix 6 CAN signals at 250Hz+ for ultra-precise odometry. |

---

3. Directory Layout

Keep all files associated with a subsystem grouped inside a single package folder. This makes code reviews, hardware isolation, and maintenance highly efficient.

```text
src/main/java/first/robot/subsystems/drive/
├── Drive.java                 # Master Subsystem coordinating all components
├── GyroIO.java                # Gyro Hardware Interface
├── GyroIOPigeon2.java         # Physical Gyro Implementation
├── Module.java                # Module Coordinator (Logic & Closed-Loops)
├── ModuleIO.java              # Module Hardware Interface
├── ModuleIOTalonFX.java       # Physical Module Implementation (CTRE)
└── PhoenixOdometryThread.java  # High-frequency signal sampling thread
```

## Dependencies & Target APIs
1. External Dependencies & Target APIs:
   - Consult `vendordeps/*.json` and `build.gradle`
   - Consult `.agents/docs/wpilib-2027-alpha.md` for breaking changes in WPILib 2027 before using 2024–2026 legacy patterns.

## Definition of Done & Context Maintenance
1. New Subsystems: Whenever a prompt requests adding a new subsystem, create a dedicated folder at `src/main/java/first/robot/subsystems/<name>/` containing the , and add the subsystem entry to `.agents/architecture.md`.
