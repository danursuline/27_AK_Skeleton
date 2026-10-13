# FRC Robot Codebase - AI Operating Guidelines

## Core Stack & Target Runtimes
- Language: Java 25 (Preview features disabled unless explicitly configured in build.gradle)
- Framework: WPILib 2027 (Alpha/Beta Build)
- Architecture Paradigm: Commands V2 + AdvantageKit (Log-Replay Hardware Abstraction)

## Structural Invariants & Organization Rules
1. Subsystem Isolation:
   - Every subsystem MUST reside inside its own dedicated subfolder under `src/main/java/first/robot/subsystems/<subsystem_name>/`.
   - Never place `.java` files directly inside `src/main/java/first/robot/subsystems/`.
   - All classes within a subsystem directory must belong to the package `first.robot.subsystems.<subsystem_name>;`.

2. AdvantageKit IO Pattern:
   Every subsystem folder must strictly implement the AdvantageKit boundary:
   - `<Name>IO.java`: Public interface defining inputs (`@AutoLog public static class <Name>IOInputs`) and control methods.
   - `<Name>IOSim.java`: Physics simulation implementation.
   - `<Name>IOTalonFX.java` (or vendor equivalent): Physical hardware implementation using CTRE Phoenix v6.
   - `<Name>.java`: Extends `SubsystemBase`. Consumes inputs via `io.updateInputs(inputs)`, logs telemetry via `Logger.processInputs(...)`, and exposes command factories.

3. External Dependencies & Target APIs:
   - Consult `./.context/dependencies.json` before generating or modifying vendor-specific code.
   - Consult `./.context/docs/wpilib-2027-alpha.md` for breaking changes in WPILib 2027 before using 2024–2026 legacy patterns.

   ## Definition of Done & Context Maintenance
   ## Automated Context Maintenance Protocol
1. New Subsystems: Whenever a prompt requests adding a new subsystem, create a dedicated folder at `src/main/java/first/robot/subsystems/<name>/` containing the full 4-file AdvantageKit IO suite, and add the subsystem entry to `./.context/architecture.md`.
2. Vendor Library Updates: If `vendordeps/*.json` or dependencies in `build.gradle` are modified, update the versions and notes in `./.context/dependencies.json`.
3. WPILib Release Transition: When upgrading from 2027-alpha to the official 2027 release, update the `wpilib_target` object in `./.context/dependencies.json` and replace the beta URL in `./.context/docs/wpilib-2027-alpha.md`.