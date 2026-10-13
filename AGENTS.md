# FRC Robot Codebase - AI Operating Guidelines

## Core Stack & Target Runtimes
- Language: Java 25 (Preview features disabled unless explicitly configured in build.gradle)
- Framework: WPILib 2027 (Alpha/Beta Build) — **Namespace: `org.wpilib.*`**
- Architecture Paradigm: Commands V2 + AdvantageKit (Log-Replay Hardware Abstraction)

## Strict Package & Import Guardrails
- WPILib Namespace: NEVER use `edu.wpi.first.*`. All WPILib imports must strictly use `org.wpilib.*` (e.g., `org.wpilib.command2.Commands`, `org.wpilib.math.geometry.Pose2d`, `org.wpilib.units.*`). The 2027 layout is not a simple prefix swap; when unsure, copy the import from an existing file in `src/main/java/`.
- Detailed API Notes: Consult `./.agents/docs/wpilib-2027-alpha.md` for breaking changes and type-safe unit requirements before writing robot code.

## Subsystem Architecture & Directory Isolation
Every subsystem MUST reside inside its own dedicated subfolder under `src/main/java/first/robot/subsystems/<subsystem_name>/`. Never place `.java` files directly inside `subsystems/`. All classes within a subsystem directory belong to `package first.robot.subsystems.<subsystem_name>;`.

### 1. Standard Single-Mechanism Subsystems (Intake, Shooter, Elevator)
For single mechanisms, implement the standard 4-file AdvantageKit IO suite:
- `<Name>.java`: Master `SubsystemBase` class handling periodic updates, logging, and command factories.
- `<Name>IO.java`: Hardware abstraction interface defining `@AutoLog public static class <Name>IOInputs`.
- `<Name>IOSim.java`: Simulation physics implementation.
- `<Name>IOTalonFX.java`: Concrete CTRE Phoenix 6 hardware implementation.

### 2. Complex Component-Driven Subsystems (example: Swerve Drive)
For multi-component systems, break down subsystems into sub-components, hardware interfaces, and optimization threads:

| Class / File | Architectural Role & Purpose |
| :--- | :--- |
| **`Drive.java`** | Master `SubsystemBase`. Coordinates state, runs kinematics/odometry, coordinates sub-components, and schedules commands. |
| **`Module.java`** | Logical state handler for a single module. Coordinates a localized `ModuleIO` instance and handles unit conversions and closed-loop states. |
| **`ModuleIO.java`** | Hardware abstraction interface for a module. Defines the `@AutoLog` data structure (`ModuleIOInputs`) for drive/steer motors and encoders. |
| **`ModuleIOTalonFX.java`** | Concrete hardware implementation of `ModuleIO` using CTRE Phoenix 6. |
| **`GyroIO.java`** | Hardware abstraction interface for the robot IMU/gyro. |
| **`GyroIOPigeon2.java`** | Concrete hardware implementation of `GyroIO` for a CTRE Pigeon 2. |
| **`PhoenixOdometryThread.java`** | High-frequency signal sampling thread (250Hz+) for Phoenix 6 CAN status signals. |

## Dependencies & Target APIs
1. External Dependencies & Target APIs:
   - Always consult `vendordeps/*.json`, `build.gradle` before introducing or modifying 3rd-party vendor code.
   - Always Consult `.agents/docs/wpilib-2027-alpha.md` for breaking changes in WPILib 2027 before using 2024–2026 legacy patterns.

## Definition of Done & Context Maintenance
Before considering any task complete:

1. Zero Legacy Imports: Verify that no imports starting with `edu.wpi.first` exist in generated or modified code.

2. Subsystem Isolation: When creating a subsystem, ensure it has its own directory at `src/main/java/first/robot/subsystems/<name>/`, implements the AdvantageKit IO pattern, and is registered in `.agents/architecture.md`.

3. Build Passes: Run `./gradlew build` and fix any compile errors or test failures before reporting the task done.

4. Bindings Registered: When adding or changing controller bindings, update the bindings table in `.agents/architecture.md`.
