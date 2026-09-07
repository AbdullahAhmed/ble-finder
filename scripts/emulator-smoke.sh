#!/usr/bin/env bash
set -euo pipefail
trap 'code=$?; if [ "$code" -ne 0 ]; then adb logcat -d -s AndroidRuntime:E; fi' EXIT
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
if [ "$api" -eq 23 ]; then
  adb shell wm size 640x960
  adb shell wm density 320
  adb shell settings put system font_scale 1.3
  compact=$(adb shell am instrument -w com.afahm.blefinder.test/com.afahm.blefinder.FinderSmokeTest)
  printf '%s\n' "$compact"
  grep -q BLE_FINDER_SMOKE_PASS <<< "$compact"
  mkdir -p screenshots/compact
  adb pull /sdcard/Android/data/com.afahm.blefinder/files/. screenshots/compact/
fi
