#!/usr/bin/env bash
set -euo pipefail

serial="emulator-${EMULATOR_PORT:-5554}"

run_bounded() {
  python3 android/ci/run-with-timeout.py "$@"
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
    run_bounded 20 adb -s "$serial" logcat -d -t 500 2>/dev/null |
      grep -E \
        'AndroidRuntime|FATAL EXCEPTION|Fatal signal|crash_dump|TestRunner|dev\.deftmartian\.runway' |
      tail -n 24 |
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
    adb -s "$serial" shell pm list packages >"$RUNNER_TEMP/emulator-packages.txt" 2>&1 || true
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

set +e
run_bounded 300 adb -s "$serial" shell am instrument -w -r \
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

set +e
run_bounded 300 adb -s "$serial" shell am instrument -w -r \
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
