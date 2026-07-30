#!/usr/bin/env bash
set -euo pipefail

serial="emulator-${EMULATOR_PORT:-5554}"

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
  timeout 30s adb -s "$serial" uninstall "$package_name" >/dev/null 2>&1 || true
done

timeout 2m adb -s "$serial" install -r -t \
  android/app/build/outputs/apk/debug/app-debug.apk
timeout 2m adb -s "$serial" install -r -t \
  android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

set +e
timeout 5m adb -s "$serial" shell am instrument -w -r \
  dev.deftmartian.runway.debug.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$RUNNER_TEMP/app-instrumentation.txt"
app_status="${PIPESTATUS[0]}"
set -e
if [ "$app_status" -ne 0 ]; then
  echo "::error title=App instrumentation failed::Runner exited with status $app_status."
  exit "$app_status"
fi
if ! grep -q '^OK (' "$RUNNER_TEMP/app-instrumentation.txt"; then
  echo "::error title=App instrumentation failed::Runner output did not contain a success marker."
  exit 1
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' \
  "$RUNNER_TEMP/app-instrumentation.txt"
then
  echo "::error title=App instrumentation failed::Runner output contained a failure marker."
  exit 1
fi

timeout 2m adb -s "$serial" install -r -t \
  android/data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk

set +e
timeout 5m adb -s "$serial" shell am instrument -w -r \
  dev.deftmartian.runway.data.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee "$RUNNER_TEMP/data-instrumentation.txt"
data_status="${PIPESTATUS[0]}"
set -e
if [ "$data_status" -ne 0 ]; then
  echo "::error title=Data instrumentation failed::Runner exited with status $data_status."
  exit "$data_status"
fi
if ! grep -q '^OK (' "$RUNNER_TEMP/data-instrumentation.txt"; then
  echo "::error title=Data instrumentation failed::Runner output did not contain a success marker."
  exit 1
fi
if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' \
  "$RUNNER_TEMP/data-instrumentation.txt"
then
  echo "::error title=Data instrumentation failed::Runner output contained a failure marker."
  exit 1
fi
