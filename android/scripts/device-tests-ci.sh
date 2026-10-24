#!/usr/bin/env bash
# Disposable CI emulator only. Preserve logs before the emulator action shuts it down.
set -uo pipefail
mkdir -p device-diagnostics
adb logcat -c
adb logcat -v threadtime > device-diagnostics/logcat.txt 2>&1 &
logcat_pid=$!
collect() {
  adb shell dumpsys activity activities > device-diagnostics/activities.txt 2>&1 || true
  adb shell dumpsys window > device-diagnostics/windows.txt 2>&1 || true
  adb shell dumpsys input > device-diagnostics/input.txt 2>&1 || true
  adb exec-out screencap -p > device-diagnostics/final-screen.png 2>/dev/null || true
  adb pull /sdcard/Download/rabta-interface interface-screenshots || true
  kill "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
}
trap collect EXIT
result=0
gradle --project-dir android :app:connectedDebugAndroidTest --no-daemon || result=$?
exit "$result"
