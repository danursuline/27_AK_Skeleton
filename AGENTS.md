# FRC Robot Codebase - AI Operating Guidelines

## Core Stack & Target Runtimes
- Language: Java 25 (Preview features disabled unless explicitly configured in build.gradle)
- Framework: WPILib 2027 (Alpha/Beta Build) — **Namespace: `org.wpilib.*`**
- Architecture Paradigm: Commands V2 + AdvantageKit (Log-Replay Hardware Abstraction)

## Strict Package & Import Guardrails
- Base Package: Robot code strictly belongs to `package first.robot.*` (NEVER `frc.robot.*`).
- WPILib Namespace: NEVER use `edu.wpi.first.*`. All WPILib imports must strictly use `org.wpilib.*` (e.g., `org.wpilib.command2.Commands`, `org.wpilib.math.geometry.Pose2d`, `org.wpilib.units.*`). There is NO `org.wpilib.wpilibj.*` package. The 2027 layout is not a simple prefix swap; when unsure, copy the import from an existing file in `src/main/java/`.
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

## Git & Pull Request Policy
- **Minor Updates & Fixes:** Minor code changes, constant tuning, small bug fixes, and documentation updates may be made directly within the existing branch context.
- **New Features & Systems Changes:** Any new subsystem, major architectural modification, or substantial new feature must NEVER be committed directly to `main`. Instead:
  1. Create a dedicated branch off `main` before starting changes (e.g., `git checkout -b feature/<name>` or `pr/<name>`).
  2. Implement, format (`./gradlew spotlessApply`), and verify (`./gradlew build`).
  3. Push the branch to `origin` and provide the Pull Request link targeting `main`.
  4. Keep the local `main` branch clean and synchronized.

## Definition of Done & Context Maintenance
Before considering any task complete:

1. Zero Legacy Imports: Verify that no imports starting with `edu.wpi.first` exist in generated or modified code.

2. Correct Package: Verify that all robot classes declare `package first.robot.*` (not `frc.robot.*`).

3. Subsystem Isolation: When creating a subsystem, ensure it has its own directory at `src/main/java/first/robot/subsystems/<name>/`, implements the AdvantageKit IO pattern, and is registered in `.agents/architecture.md`.

4. Build Passes: Run `./gradlew build` and fix any compile errors or test failures before reporting the task done. If `JAVA_HOME` is unset in your environment, use the WPILib Java 25 JDK (e.g. on Windows: `$env:JAVA_HOME = "C:\Users\Public\wpilib\2027_alpha7\jdk"; ./gradlew build`).

5. Formatting Passes: Run `./gradlew spotlessApply` (or ensure `./gradlew spotlessCheck` passes) to conform to project code style.

6. Bindings Registered: When adding or changing controller bindings, update the bindings table in `.agents/architecture.md`.

7. PR Delivery for Systems/Features: If the task introduces a new subsystem, major feature, or architectural change, ensure it is delivered via a Pull Request targeting `main` rather than committed directly to `main`.
