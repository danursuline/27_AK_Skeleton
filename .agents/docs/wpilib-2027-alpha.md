# WPILib 2027 Alpha / Beta API Specification & Guardrails

- Active Documentation: https://github.wpilib.org/allwpilib/docs/beta/java/index.html
- Target Runtime: Java 25
- Canonical Package Root: `org.wpilib.*` (Replaces legacy `edu.wpi.first.*`)

---

## 1. Zero-Tolerance Namespace Guardrail
Never import from `edu.wpi.first.*`. All WPILib components belong to `org.wpilib.*`:
- NEVER `import edu.wpi.first.wpilibj2.command.Commands;`
- OK `import org.wpilib.wpilibj2.command.Commands;`
- NEVER `import edu.wpi.first.math.geometry.Pose2d;`
- OK `import org.wpilib.math.geometry.Pose2d;`

---

## 2. Type-Safe Units (`org.wpilib.units.*`)
All physical quantities (distance, velocity, voltage, angles) must use the Units library rather than raw doubles.

- **Static Import:** `import static org.wpilib.units.Units.*;`
- **Instantiation Patterns:**
  - `Measure<Voltage> operatingVoltage = Volts.of(12.0);`
  - `Measure<Distance> wheelDiameter = Inches.of(4.0);`
  - `Measure<LinearVelocity> maxLinearVelocity = MetersPerSecond.of(4.5);`
  - `Measure<Angle> targetAngle = Radians.of(Math.PI);`
- **Extracting Primitives at Hardware Boundaries:**
  - `double motorVolts = operatingVoltage.in(Volts);`
  - `double speedMPS = maxLinearVelocity.in(MetersPerSecond);`

---

## 3. Modern Commands V2 (`org.wpilib.wpilibj2.command.*`)
Do NOT create custom Command classes inheriting `CommandBase`. Use functional factory composition.

### Command Composition Patterns
- **Continuous Action:** `Commands.run(() -> this.runAction(), this)`
- **Single Action:** `Commands.runOnce(() -> this.instantAction(), this)`
- **Deferred / Dynamic:** `Commands.defer(() -> this.buildDynamicCommand(), Set.of(this))`
- **Sequences:** `Commands.sequence(cmdA, cmdB)`
- **Parallel:** `Commands.parallel(cmdA, cmdB)`

### Triggers & Button Bindings (`org.wpilib.wpilibj2.command.button.Trigger`)
- `trigger.onTrue(command)` (NOT `whenPressed`)
- `trigger.onFalse(command)` (NOT `whenReleased`)
- `trigger.whileTrue(command)` (NOT `whileHeld`)
- `trigger.toggleOnTrue(command)` (NOT `toggleWhenPressed`)

---

## 4. Hardware System Identification (`SysIdRoutine`)
- **Package:** `org.wpilib.wpilibj2.command.sysid.SysIdRoutine`
- **Pattern:**
  ```java
  import org.wpilib.wpilibj2.command.sysid.SysIdRoutine;
  import static org.wpilib.units.Units.*;

  private final SysIdRoutine sysIdRoutine = new SysIdRoutine(
      new SysIdRoutine.Config(
          Volts.per(Second).of(1.0),
          Volts.of(7.0),
          Seconds.of(10.0),
          state -> org.littletonrobotics.junction.Logger.recordOutput("SysIdState", state.toString())
      ),
      new SysIdRoutine.Mechanism(
          volts -> io.setVoltage(volts.in(Volts)),
          log -> {}, // Handled by AdvantageKit @AutoLog
          this
      )
  );