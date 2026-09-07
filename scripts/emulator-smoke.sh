#!/usr/bin/env bash
set -euo pipefail
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.afahm.blefinder android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.afahm.blefinder android.permission.ACCESS_FINE_LOCATION
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ "$api" -ge 31 ]; then
  adb shell pm grant com.afahm.blefinder android.permission.BLUETOOTH_SCAN
  adb shell pm grant com.afahm.blefinder android.permission.BLUETOOTH_CONNECT
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
output=$(adb shell am instrument -w com.afahm.blefinder.test/com.afahm.blefinder.FinderSmokeTest)
printf '%s\n' "$output"
grep -q BLE_FINDER_SMOKE_PASS <<< "$output"
mkdir -p screenshots
adb pull /sdcard/Android/data/com.afahm.blefinder/files/. screenshots/
