# Architecture & Subsystem Layout

Registry of active subsystems and controller bindings. Update this file when adding, removing,
or rebinding anything. Keep entries short; the code is the detail.

## Subsystems

| Subsystem | Folder | Hardware IO | Sim IO | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Drive | `subsystems/drive/` | `ModuleIOTalonFX`, `GyroIOPigeon2` | none | CTRE swerve, Phoenix odometry thread |
| Elevator | `subsystems/elevator/` | `ElevatorIOTalonFX` | `ElevatorIOSim` | Trap elevator modeled on 1706's 2024 robot: 2 TalonFX (opposed follower), Motion Magic, stall-current homing |

## Controller Bindings

| Controller | Port | Input | Action |
| :--- | :--- | :--- | :--- |
| Driver (`drv`) | 0 | Left stick / right stick X | Field-relative drive |
| Driver (`drv`) | 0 | A (hold) | Drive with heading locked to 0° |
| Driver (`drv`) | 0 | X | Stop with modules in X pattern |
| Driver (`drv`) | 0 | Menu | Reset gyro to 0° |
| Operator (`op`) | 1 | A (hold) | Elevator up to trap; on release, stow then re-home |
| Operator (`op`) | 1 | View | Home elevator on bottom hard stop |
