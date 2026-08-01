#!/usr/bin/env bash
set -euo pipefail

serial="emulator-${EMULATOR_PORT:-5554}"
app_test_classes="$(python3 android/ci/discover-instrumentation-tests.py app)"
data_test_classes="$(python3 android/ci/discover-instrumentation-tests.py data)"

run_bounded() {
  python3 android/ci/run-with-timeout.py "$@"
}

stop_instrumentation_packages() {
  local package_name
  for package_name in "$@"; do
    run_bounded 20 adb -s "$serial" shell am force-stop "$package_name" \
      >/dev/null 2>&1 || true
  done
}

compile_package() {
  local package_name="$1"
  local timeout_seconds="${2:-180}"
  local output_file="$RUNNER_TEMP/package-compile-${package_name//./-}.txt"
  local compile_status
  local attempt=1

  : >"$output_file"
  while [ "$attempt" -le 2 ]; do
    echo "attempt $attempt" | tee -a "$output_file"
    set +e
    run_bounded "$timeout_seconds" adb -s "$serial" shell cmd package compile \
      -m speed -f "$package_name" 2>&1 | tee -a "$output_file"
    compile_status="${PIPESTATUS[0]}"
    set -e
    if [ "$compile_status" -eq 0 ]; then
      return 0
    fi
    if [ "$attempt" -eq 1 ] && grep -Fq 'Failure calling service package: Broken pipe' "$output_file"; then
      echo "::warning title=Package service restarted::$package_name compilation will retry once after package-manager readiness."
      local package_service_ready=false
      for _ in {1..15}; do
        if run_bounded 10 adb -s "$serial" shell pm path "$package_name" >/dev/null 2>&1; then
          package_service_ready=true
          break
        fi
        sleep 2
      done
      if [ "$package_service_ready" = true ]; then
        attempt=$((attempt + 1))
        continue
      fi
    fi

    local output_tail
    output_tail="$(
      tail -n 12 "$output_file" |
        tr '\r\n' '  ' |
        cut -c 1-1800 |
        sed 's/%/%25/g'
    )"
    echo "::error title=Package compilation failed::$package_name exited with status $compile_status. Output: $output_tail"
    return "$compile_status"
  done
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
compile_package dev.deftmartian.runway.debug 600
compile_package dev.deftmartian.runway.debug.test

failure_pattern='FAILURES!!!|INSTRUMENTATION_(FAILED|ABORTED)|Process crashed|Unable to find instrumentation'
app_output="$RUNNER_TEMP/app-instrumentation.txt"
app_class_output="$RUNNER_TEMP/app-instrumentation-current.txt"
: >"$app_output"
# Keep each Compose class independently bounded. A single stalled class must identify itself
# instead of consuming the aggregate runner timeout and hiding behind a generic exit 124.
while IFS= read -r app_test_class; do
  test -n "$app_test_class" || continue
  printf 'Running %s\n' "$app_test_class" | tee -a "$app_output"
  set +e
  run_bounded 240 adb -s "$serial" shell am instrument -w -r \
    -e class "$app_test_class" \
    dev.deftmartian.runway.debug.test/androidx.test.runner.AndroidJUnitRunner </dev/null \
    2>&1 | tee "$app_class_output"
  app_status="${PIPESTATUS[0]}"
  set -e
  cat "$app_class_output" >>"$app_output"
  if [ "$app_status" -ne 0 ]; then
    report_instrumentation_failure \
      "App instrumentation failed" \
      "$app_class_output" \
      "$app_test_class exited with status $app_status."
    stop_instrumentation_packages \
      dev.deftmartian.runway.debug \
      dev.deftmartian.runway.debug.test
    exit "$app_status"
  fi
  app_failure_marker="$(
    grep -Eo "$failure_pattern" "$app_class_output" |
      head -n 1 ||
      true
  )"
  if [ -n "$app_failure_marker" ]; then
    report_instrumentation_failure \
      "App instrumentation failed" \
      "$app_class_output" \
      "$app_test_class output contained $app_failure_marker."
    stop_instrumentation_packages \
      dev.deftmartian.runway.debug \
      dev.deftmartian.runway.debug.test
    exit 1
  fi
  if ! grep -Eq 'OK[[:space:]]+\([0-9]+[[:space:]]+tests?\)' "$app_class_output"; then
    report_instrumentation_failure \
      "App instrumentation failed" \
      "$app_class_output" \
      "$app_test_class did not report a success marker."
    stop_instrumentation_packages \
      dev.deftmartian.runway.debug \
      dev.deftmartian.runway.debug.test
    exit 1
  fi
done < <(printf '%s\n' "$app_test_classes" | tr ',' '\n')
rm -f "$app_class_output"

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
  stop_instrumentation_packages \
    dev.deftmartian.runway.debug \
    dev.deftmartian.runway.data.test
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
  stop_instrumentation_packages \
    dev.deftmartian.runway.debug \
    dev.deftmartian.runway.data.test
  exit 1
fi
if ! grep -Eq 'OK[[:space:]]+\([0-9]+[[:space:]]+tests?\)' \
  "$RUNNER_TEMP/data-instrumentation.txt"
then
  report_instrumentation_failure \
    "Data instrumentation failed" \
    "$RUNNER_TEMP/data-instrumentation.txt" \
    "Runner output did not contain a success marker."
  stop_instrumentation_packages \
    dev.deftmartian.runway.debug \
    dev.deftmartian.runway.data.test
  exit 1
fi

screenshot_directory="$RUNNER_TEMP/runway-native-screenshots"
mkdir -p "$screenshot_directory"
for screenshot in calendar-dark calendar-large-text calendar-expanded-light inbox-light history-light stats-light settings-dark settings-about-dark; do
  output="$screenshot_directory/$screenshot.png"
  run_bounded 30 adb -s "$serial" exec-out run-as dev.deftmartian.runway.debug \
    cat "files/documentation-screenshots/$screenshot.png" >"$output"
  test -s "$output" || {
    echo "::error title=Native screenshot missing::$output was not captured by instrumentation."
    exit 1
  }
done
