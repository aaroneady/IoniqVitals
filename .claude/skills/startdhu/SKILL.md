---
name: startdhu
description: Launch the Android Auto Desktop Head Unit (DHU) for this project by running run_dhu.bat (it forwards adb tcp:5277 and starts desktop-head-unit.exe with ioniq5.ini). Use when the user wants to start the DHU, test the app on the head unit, or asks to "start dhu".
---

# Start the Desktop Head Unit (DHU)

Runs the project's `run_dhu.bat`, which sets up the `adb tcp:5277` forward and starts
`desktop-head-unit.exe` with the project's `ioniq5.ini`. The batch file handles
everything, so just run it.

## Steps

1. `desktop-head-unit.exe` is a long-running GUI process, so run the batch file in
   the **background** so it doesn't block the session:

   ```powershell
   .\run_dhu.bat
   ```

   Run this with the PowerShell tool's `run_in_background: true`. The DHU window
   should open and stay up; you'll be notified if the process exits.

2. Report whether the DHU started. If the background process exits almost
   immediately, surface its output — the usual cause is no connected/authorized
   device for the `5277` forward.

## Notes

- Do not redirect the executable's stderr in PowerShell 5.1 (it wraps native stderr
  lines as errors and falsely reports failure); the harness already captures it.
- The batch file paths are absolute, so the working directory doesn't matter.
