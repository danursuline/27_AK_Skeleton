# WPILib 2027 Alpha / Beta API Specification & Guardrails

- Active Documentation: https://github.wpilib.org/allwpilib/docs/beta/java/index.html
- Target Runtime: Java 25
- Canonical Package Root: `org.wpilib.*` (Replaces legacy `edu.wpi.first.*`)
- Version in use: see the GradleRIO version in `build.gradle` and `vendordeps/*.json`.

---

## 0. Source of Truth: Existing Code
The 2027 package layout is not a simple rename of the old one. When unsure of an import path,
copy it from an existing file in `src/main/java/` (e.g. `RobotContainer.java`,
`subsystems/drive/Drive.java`) instead of guessing. Run `./gradlew build` to confirm.

---

## 1. Zero-Tolerance Namespace Guardrail
Never import from `edu.wpi.first.*`. All WPILib components belong to `org.wpilib.*`:
- Robot base package is strictly `first.robot.*` (NEVER `frc.robot.*`).
- There is NO `org.wpilib.wpilibj.*` package! Classes previously in `wpilibj` are now divided into dedicated packages (`driverstation`, `framework`, `system`, `smartdashboard`, `hardware.*`, `util`).
- NEVER `import edu.wpi.first.wpilibj2.command.Commands;`
- NEVER `import org.wpilib.wpilibj2.command.Commands;` (does not exist)
- OK `import org.wpilib.command2.Commands;`
- NEVER `import edu.wpi.first.math.geometry.Pose2d;`
- OK `import org.wpilib.math.geometry.Pose2d;`

Common mappings used in this repo:

| Legacy (2024–2026) | 2027 |
| :--- | :--- |
| `edu.wpi.first.wpilibj2.command.*` | `org.wpilib.command2.*` |
| `edu.wpi.first.wpilibj2.command.button.*` | `org.wpilib.command2.button.*` |
| `edu.wpi.first.wpilibj2.command.sysid.*` | `org.wpilib.command2.sysid.*` |
| `edu.wpi.first.math.*` | `org.wpilib.math.*` |
| `edu.wpi.first.math.util.MathUtil` | `org.wpilib.math.util.MathUtil` |
| `edu.wpi.first.units.*` | `org.wpilib.units.*` |
| `edu.wpi.first.wpilibj.DriverStation` | `org.wpilib.driverstation.DriverStation` |
| `edu.wpi.first.wpilibj.XboxController` | `org.wpilib.driverstation.XboxController` |
| `edu.wpi.first.wpilibj.GenericHID` | `org.wpilib.driverstation.GenericHID` |
| `edu.wpi.first.wpilibj.Joystick` | `org.wpilib.driverstation.Joystick` |
| `edu.wpi.first.wpilibj.RobotState` | `org.wpilib.driverstation.RobotState` |
| `edu.wpi.first.wpilibj.DriverStation.Alliance` | `org.wpilib.driverstation.Alliance` |
| `edu.wpi.first.wpilibj.RobotBase` | `org.wpilib.framework.RobotBase` |
| `edu.wpi.first.wpilibj.TimedRobot` | `org.wpilib.framework.TimedRobot` |
| `edu.wpi.first.wpilibj.Timer` | `org.wpilib.system.Timer` |
| `edu.wpi.first.wpilibj.RobotController` | `org.wpilib.system.RobotController` |
| `edu.wpi.first.wpilibj.Alert` | `org.wpilib.util.Alert` |
| `edu.wpi.first.wpilibj.smartdashboard.*` | `org.wpilib.smartdashboard.*` |
| `edu.wpi.first.wpilibj.motorcontrol.*` | `org.wpilib.hardware.motor.*` |

---

## 2. Type-Safe Units (`org.wpilib.units.*`)
Use the Units library for constants and public method parameters. AdvantageKit `@AutoLog`
inputs and hardware boundaries use plain `double`s with the unit in the field name
(e.g. `driveVelocityRadPerSec`); do not convert those to `Measure` types.

- **Static Import:** `import static org.wpilib.units.Units.*;`
- **Measure Types:** `import org.wpilib.units.measure.*;` (`Voltage`, `Distance`, `LinearVelocity`, `Angle`, ...)
- **Instantiation Patterns:**
  - `Voltage operatingVoltage = Volts.of(12.0);`
  - `Distance wheelDiameter = Inches.of(4.0);`
  - `LinearVelocity maxLinearVelocity = MetersPerSecond.of(4.5);`
  - `Angle targetAngle = Radians.of(Math.PI);`
- **Do not** write `Measure<Voltage>`. `Measure` is parameterized by a unit type (`Measure<VoltageUnit>`); prefer the concrete measure types above.
- **Extracting Primitives at Hardware Boundaries:**
  - `double motorVolts = operatingVoltage.in(Volts);`
  - `double speedMPS = maxLinearVelocity.in(MetersPerSecond);`

---

## 3. Modern Commands V2 (`org.wpilib.command2.*`)
Do NOT create custom Command classes inheriting `CommandBase`. Use functional factory composition.

### Command Composition Patterns
- **Continuous Action:** `Commands.run(() -> this.runAction(), this)`
- **Single Action:** `Commands.runOnce(() -> this.instantAction(), this)`
- **Deferred / Dynamic:** `Commands.defer(() -> this.buildDynamicCommand(), Set.of(this))`
- **Sequences:** `Commands.sequence(cmdA, cmdB)`
- **Parallel:** `Commands.parallel(cmdA, cmdB)`

### Triggers & Button Bindings (`org.wpilib.command2.button.Trigger`)
- `trigger.onTrue(command)` (NOT `whenPressed`)
- `trigger.onFalse(command)` (NOT `whenReleased`)
- `trigger.whileTrue(command)` (NOT `whileHeld`)
- `trigger.toggleOnTrue(command)` (NOT `toggleWhenPressed`)

---

## 4. Hardware System Identification (`SysIdRoutine`)
- **Package:** `org.wpilib.command2.sysid.SysIdRoutine`
- **Reference:** `subsystems/drive/Drive.java`
- **Pattern:**
  ```java
  import org.wpilib.command2.sysid.SysIdRoutine;
  import static org.wpilib.units.Units.*;

  private final SysIdRoutine sysIdRoutine = new SysIdRoutine(
      new SysIdRoutine.Config(
          Volts.of(1.0).per(Second),
          Volts.of(7.0),
          Seconds.of(10.0),
          state -> Logger.recordOutput("<Name>/SysIdState", state.toString())
      ),
      new SysIdRoutine.Mechanism(
          volts -> io.setVoltage(volts.in(Volts)),
          null, // Handled by AdvantageKit @AutoLog
          this
      )
  );
  ```
