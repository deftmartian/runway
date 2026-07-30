#!/usr/bin/env bash
set -euo pipefail

serial="emulator-${EMULATOR_PORT:-5554}"
app_test_classes="$(python3 android/ci/discover-instrumentation-tests.py app)"
data_test_classes="$(python3 android/ci/discover-instrumentation-tests.py data)"

run_bounded() {
  python3 android/ci/run-with-timeout.py "$@"
}

compile_package() {
  local package_name="$1"
  local output_file="$RUNNER_TEMP/package-compile-${package_name//./-}.txt"
  local compile_status

  set +e
  run_bounded 180 adb -s "$serial" shell cmd package compile \
    -m speed -f "$package_name" 2>&1 | tee "$output_file"
  compile_status="${PIPESTATUS[0]}"
  set -e
  if [ "$compile_status" -ne 0 ]; then
    local output_tail
    output_tail="$(
      tail -n 12 "$output_file" |
        tr '\r\n' '  ' |
        cut -c 1-1800 |
        sed 's/%/%25/g'
    )"
    echo "::error title=Package compilation failed::$package_name exited with status $compile_status. Output: $output_tail"
    return "$compile_status"
  fi
}

report_instrumentation_failure() {
  local title="$1"
  local output_file="$2"
  local reason="$3"
  local output_tail
  local device_tail
  output_tail="$(
    tail -n 24 "$output_file" |
      tr '\r\n' '  ' |
      cut -c 1-1400 |
      sed 's/%/%25/g'
  )"
  device_tail="$(
    run_bounded 20 adb -s "$serial" logcat -b all -d -t 2000 2>/dev/null |
      grep -E \
        'ANR in|am_anr|failed to complete startup|ActivityManager.*(ANR|Killing|crash)|AndroidRuntime|FATAL EXCEPTION|Fatal signal|crash_dump|TestRunner|lowmemorykiller|lmkd' |
      tail -n 30 |
      tr '\r\n' '  ' |
      cut -c 1-1800 |
      sed 's/%/%25/g'
  )" || true
  if [ -n "$device_tail" ]; then
    echo "::error title=$title::$reason Output: $output_tail Device log: $device_tail"
  else
    echo "::error title=$title::$reason Output: $output_tail Device log: no matching lines."
  fi
}

collect_diagnostics() {
  status="$?"
  if [ "$status" -ne 0 ]; then
    adb devices -l >"$RUNNER_TEMP/adb-devices.txt" 2>&1 || true
    adb -s "$serial" logcat -d >"$RUNNER_TEMP/emulator-logcat.txt" 2>&1 || true
    adb -s "$serial" logcat -b events -d >"$RUNNER_TEMP/emulator-events.txt" 2>&1 || true
    adb -s "$serial" shell pm list packages >"$RUNNER_TEMP/emulator-packages.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys activity lastanr \
      >"$RUNNER_TEMP/emulator-last-anr.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys meminfo dev.deftmartian.runway.debug \
      >"$RUNNER_TEMP/emulator-app-meminfo.txt" 2>&1 || true
  fi
  exit "$status"
}
trap collect_diagnostics EXIT

for package_name in \
  dev.deftmartian.runway.debug \
  dev.deftmartian.runway.debug.test \
  dev.deftmartian.runway.data.test
do
  run_bounded 30 adb -s "$serial" uninstall "$package_name" >/dev/null 2>&1 || true
done

run_bounded 120 adb -s "$serial" install -r -t \
  android/app/build/outputs/apk/debug/app-debug.apk
run_bounded 120 adb -s "$serial" install -r -t \
  android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Hosted emulators can spend long enough compiling Compose and test dex on first process start to
# trigger Android's startup ANR before AndroidJUnitRunner reaches a test. Compile the installed
# packages up front; the device tests still execute the production APK and fail on test errors.
compile_package dev.deftmartian.runway.debug
compile_package dev.deftmartian.runway.debug.test

set +e
run_bounded 300 adb -s "$serial" shell am instrument -w -r \
  -e class "$app_test_classes" \
  dev.deftmartian.runway.debug.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$RUNNER_TEMP/app-instrumentation.txt"
app_status="${PIPESTATUS[0]}"
set -e
if [ "$app_status" -ne 0 ]; then
  echo "::error title=App instrumentation failed::Runner exited with status $app_status."
  exit "$app_status"
fi
failure_pattern='FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|Process crashed|Unable to find instrumentation'
app_failure_marker="$(
  grep -Eo "$failure_pattern" "$RUNNER_TEMP/app-instrumentation.txt" |
    head -n 1 ||
    true
)"
if [ -n "$app_failure_marker" ]; then
  report_instrumentation_failure \
    "App instrumentation failed" \
    "$RUNNER_TEMP/app-instrumentation.txt" \
    "Runner output contained $app_failure_marker."
  exit 1
fi
if ! grep -Eq 'OK[[:space:]]+\([0-9]+[[:space:]]+tests?\)' \
  "$RUNNER_TEMP/app-instrumentation.txt"
then
  report_instrumentation_failure \
    "App instrumentation failed" \
    "$RUNNER_TEMP/app-instrumentation.txt" \
    "Runner output did not contain a success marker."
  exit 1
fi

run_bounded 120 adb -s "$serial" install -r -t \
  android/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
compile_package dev.deftmartian.runway.data.test

set +e
run_bounded 300 adb -s "$serial" shell am instrument -w -r \
  -e class "$data_test_classes" \
  dev.deftmartian.runway.data.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$RUNNER_TEMP/data-instrumentation.txt"
data_status="${PIPESTATUS[0]}"
set -e
if [ "$data_status" -ne 0 ]; then
  echo "::error title=Data instrumentation failed::Runner exited with status $data_status."
  exit "$data_status"
fi
data_failure_marker="$(
  grep -Eo "$failure_pattern" "$RUNNER_TEMP/data-instrumentation.txt" |
    head -n 1 ||
    true
)"
if [ -n "$data_failure_marker" ]; then
  report_instrumentation_failure \
    "Data instrumentation failed" \
    "$RUNNER_TEMP/data-instrumentation.txt" \
    "Runner output contained $data_failure_marker."
  exit 1
fi
if ! grep -Eq 'OK[[:space:]]+\([0-9]+[[:space:]]+tests?\)' \
  "$RUNNER_TEMP/data-instrumentation.txt"
then
  report_instrumentation_failure \
    "Data instrumentation failed" \
    "$RUNNER_TEMP/data-instrumentation.txt" \
    "Runner output did not contain a success marker."
  exit 1
fi
