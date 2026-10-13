# Architecture & Subsystem Layout

Registry of active subsystems and controller bindings. Update this file when adding, removing,
or rebinding anything. Keep entries short; the code is the detail.

## Subsystems

| Subsystem | Folder | Hardware IO | Sim IO | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Drive | `subsystems/drive/` | `ModuleIOTalonFX`, `GyroIOPigeon2` | none | CTRE swerve, Phoenix odometry thread |

## Controller Bindings

| Controller | Port | Input | Action |
| :--- | :--- | :--- | :--- |
| Driver (`drv`) | 0 | Left stick / right stick X | Field-relative drive |
| Driver (`drv`) | 0 | A (hold) | Drive with heading locked to 0° |
| Driver (`drv`) | 0 | X | Stop with modules in X pattern |
| Driver (`drv`) | 0 | Menu | Reset gyro to 0° |
| Operator (`op`) | 1 | — | Unbound |
