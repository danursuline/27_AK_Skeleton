# Architecture & Subsystem Layout

Registry of active subsystems and controller bindings. Update this file when adding, removing,
or rebinding anything. Keep entries short; the code is the detail.

## Subsystems

| Subsystem | Folder | Hardware IO | Sim IO | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Drive | `subsystems/drive/` | `ModuleIOTalonFX`, `GyroIOPigeon2` | none | CTRE swerve, Phoenix odometry thread |
| Elevator | `subsystems/elevator/` | `ElevatorIOTalonFX` | `ElevatorIOSim` | Dual Talon FX trap elevator (1706 style) on SystemCore CAN bus |

## Controller Bindings

| Controller | Port | Input | Action |
| :--- | :--- | :--- | :--- |
| Driver (`drv`) | 0 | Left stick / right stick X | Field-relative drive |
| Driver (`drv`) | 0 | A (hold) | Drive with heading locked to 0° |
| Driver (`drv`) | 0 | X | Stop with modules in X pattern |
| Driver (`drv`) | 0 | Menu | Reset gyro to 0° |
| Operator (`op`) | 1 | A (hold) | Move elevator to Trap position |
| Operator (`op`) | 1 | B (hold) | Move elevator to Rest position |
| Operator (`op`) | 1 | X (hold) | Move elevator to Intake position |
| Operator (`op`) | 1 | Y | Run elevator zeroing sequence (current-spike homing) |
