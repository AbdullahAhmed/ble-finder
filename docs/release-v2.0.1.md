# BLE Finder 2.0.1

- Automatically asks to turn on Bluetooth when it is off, using Android's standard confirmation dialog.
- Resumes discovery or locating after Bluetooth turns on.
- Respects cancellation: tap the status message or Refresh to retry, without repeated automatic dialogs.
- Handles Bluetooth being switched off during use and preserves prompt state across activity recreation.

Android 6.0+ support and the existing layout are unchanged. Updates install over 2.0.0 without clearing preferences.

Validation: release build, lint, and all 13 unit tests passed (including five Bluetooth prompt regression tests). Installed on Samsung SM-G781W and confirmed Android launched its Bluetooth-enable activity. The phone's PIN lock prevented completing the interactive acceptance/cancellation check; Bluetooth was restored after the test. Current emulator CI results are available in Actions.
