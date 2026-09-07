# BLE Finder 2.0

Pick a Bluetooth LE device and follow Geiger-style clicks to find it.

- Generic BLE device picker, search by name or address, and a remembered recent device.
- Large signal dial, warmer/colder trend, and a 30-second history.
- Sound and phone vibration toggles, plus a single **Found it** button.
- Phone pulses use accessibility feedback, so disabling keyboard/touch haptics does not suppress the locator's guidance.
- Broadcast signal tracking and read-only connected RSSI, with no Zepp key or account.
- Android 6.0+ support, compact-screen layout, and no Internet permission.
- Stale signal protection: clicks pause and the live reading clears after three seconds without data.

Install **ble-finder.apk** below. The same APK is committed under `downloads/` in the repository. `SHA256SUMS` lets you verify the download.

Compatibility checks exercise the app on Android 6, 8, and 15 emulators. Physical BLE behavior depends on your phone and accessory: the accessory must advertise BLE or accept a BLE connection. Signal strength is not a precise distance or direction.

The non-debuggable APK uses the maintainer's existing local development signing certificate, allowing an in-place update of the original personal BLE Finder app.
