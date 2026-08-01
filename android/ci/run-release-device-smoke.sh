#!/usr/bin/env bash
set -euo pipefail

readonly package_name='dev.deftmartian.runway'
readonly activity_name='dev.deftmartian.runway.MainActivity'
readonly unsigned_apk="${1:-}"
readonly serial="${ANDROID_SERIAL:-emulator-${EMULATOR_PORT:-5554}}"
readonly build_tools="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/36.0.0"
readonly runner_temp="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
readonly work_directory="$(mktemp -d "${runner_temp%/}/runway-release-smoke.XXXXXX")"
readonly keystore="$work_directory/release-smoke.p12"
readonly installable_apk="$work_directory/release-smoke.apk"
readonly store_password='runway-release-smoke-only'
stage='initialization'

run_bounded() {
  python3 android/ci/run-with-timeout.py "$@"
}

compile_package() {
  local target_package="$1"
  local timeout_seconds="${2:-180}"
  local output_file="$runner_temp/release-smoke-package-compile.txt"
  local compile_status
  local attempt=1

  : >"$output_file"
  while [ "$attempt" -le 2 ]; do
    printf 'attempt %s\n' "$attempt" | tee -a "$output_file"
    set +e
    run_bounded "$timeout_seconds" adb -s "$serial" shell cmd package compile \
      -m speed -f "$target_package" 2>&1 | tee -a "$output_file"
    compile_status="${PIPESTATUS[0]}"
    set -e
    if [ "$compile_status" -eq 0 ]; then
      return 0
    fi
    if [ "$attempt" -eq 1 ] && grep -Fq 'Failure calling service package: Broken pipe' "$output_file"; then
      echo "::warning title=Package service restarted::$target_package compilation will retry once after package-manager readiness."
      local package_service_ready=false
      for _ in {1..15}; do
        if run_bounded 10 adb -s "$serial" shell pm path "$target_package" >/dev/null 2>&1; then
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
    echo "::error title=Package compilation failed::$target_package exited with status $compile_status. Output: $output_tail"
    return "$compile_status"
  done
}

sha256_file() {
  local file="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    echo 'no SHA-256 command is available (expected sha256sum or shasum)' >&2
    return 1
  fi
}

collect_diagnostics() {
  local status="$?"
  if [ "$status" -ne 0 ]; then
    printf 'stage=%s\nexit_status=%s\n' "$stage" "$status" \
      >"$runner_temp/release-smoke-summary.txt"
    echo "::error title=Release candidate smoke failed::Stage: $stage (exit $status)."
    adb devices -l >"$runner_temp/release-smoke-adb-devices.txt" 2>&1 || true
    adb -s "$serial" logcat -d >"$runner_temp/release-smoke-logcat.txt" 2>&1 || true
    adb -s "$serial" logcat -b events -d >"$runner_temp/release-smoke-events.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys activity activities \
      >"$runner_temp/release-smoke-activities.txt" 2>&1 || true
    adb -s "$serial" shell dumpsys package "$package_name" \
      >"$runner_temp/release-smoke-package.txt" 2>&1 || true
  fi
  rm -rf "$work_directory"
  exit "$status"
}
trap collect_diagnostics EXIT

test -n "$unsigned_apk" || {
  echo 'usage: run-release-device-smoke.sh <unsigned-release.apk>' >&2
  exit 2
}
test -s "$unsigned_apk" || {
  echo "release candidate is missing or empty: $unsigned_apk" >&2
  exit 1
}
for tool in "$build_tools/aapt" "$build_tools/apksigner" "$build_tools/zipalign"; do
  test -x "$tool" || { echo "required Android build tool is missing: $tool" >&2; exit 1; }
done
command -v adb >/dev/null
command -v keytool >/dev/null

stage='inspect candidate identity'
readonly unsigned_hash="$(sha256_file "$unsigned_apk")"
readonly badging="$("$build_tools/aapt" dump badging "$unsigned_apk")"
printf '%s\n' "$badging" | grep -E '^(package:|launchable-activity:)'
readonly actual_package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging")"
readonly actual_version_code="$(sed -n "s/^package:.* versionCode='\([^']*\)'.*/\1/p" <<< "$badging")"
readonly actual_version_name="$(sed -n "s/^package:.* versionName='\([^']*\)'.*/\1/p" <<< "$badging")"
readonly expected_version_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' android/app/build.gradle.kts)"
readonly expected_version_name="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' android/app/build.gradle.kts)"
[ "$actual_package" = "$package_name" ] || { echo 'release candidate has the wrong package id' >&2; exit 1; }
[ "$actual_version_code" = "$expected_version_code" ] || { echo 'release candidate has the wrong versionCode' >&2; exit 1; }
[ "$actual_version_name" = "$expected_version_name" ] || { echo 'release candidate has the wrong versionName' >&2; exit 1; }
if [ -n "${GITHUB_REF_NAME:-}" ] && [ "${GITHUB_REF_NAME#v}" != "$actual_version_name" ]; then
  echo 'release candidate versionName does not match the release tag' >&2
  exit 1
fi

stage='verify standalone permissions and alignment'
readonly permissions="$("$build_tools/aapt" dump permissions "$unsigned_apk")"
printf '%s\n' "$permissions"
if grep -Eq 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)' <<< "$permissions"; then
  echo 'standalone release candidate unexpectedly requests a network permission' >&2
  exit 1
fi
"$build_tools/zipalign" -c -P 16 4 "$unsigned_apk"

stage='create ephemeral signing identity'
keytool -genkeypair -noprompt \
  -storetype PKCS12 \
  -keystore "$keystore" \
  -storepass "$store_password" \
  -keypass "$store_password" \
  -alias runway-release-smoke \
  -keyalg RSA \
  -keysize 2048 \
  -validity 2 \
  -dname 'CN=Runway release smoke, O=Local CI, C=CA' >/dev/null
chmod 600 "$keystore"
stage='sign ephemeral install copy'
"$build_tools/apksigner" sign \
  --ks "$keystore" \
  --ks-type PKCS12 \
  --ks-key-alias runway-release-smoke \
  --ks-pass "pass:$store_password" \
  --key-pass "pass:$store_password" \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$installable_apk" \
  "$unsigned_apk"
stage='verify ephemeral signature and candidate integrity'
readonly verification="$("$build_tools/apksigner" verify --verbose --print-certs "$installable_apk")"
printf '%s\n' "$verification"
grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true$' <<< "$verification"
grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true$' <<< "$verification"
[ "$(sha256_file "$unsigned_apk")" = "$unsigned_hash" ] || {
  echo 'smoke signing changed the canonical unsigned candidate' >&2
  exit 1
}

stage='wait for Android device'
run_bounded 60 adb -s "$serial" wait-for-device
run_bounded 30 adb -s "$serial" shell getprop sys.boot_completed | grep -q '^1'
run_bounded 30 adb -s "$serial" logcat -c
stage='install ephemeral-signed candidate'
run_bounded 180 adb -s "$serial" install -r "$installable_apk"
stage='compile installed candidate'
# Hosted emulators can hit a first-start ANR while compiling Compose and release dex. Compile the
# installed production APK up front; the following force-stop still makes the launch itself cold.
compile_package "$package_name" 600
run_bounded 30 adb -s "$serial" shell am force-stop "$package_name"
stage='cold-launch main activity'
run_bounded 60 adb -s "$serial" shell am start -W \
  -n "$package_name/$activity_name" | tee "$runner_temp/release-smoke-start.txt"
if ! grep -q '^Status: ok$' "$runner_temp/release-smoke-start.txt"; then
  launch_tail="$(
    tail -n 20 "$runner_temp/release-smoke-start.txt" |
      tr '\r\n' '  ' |
      cut -c 1-1800 |
      sed 's/%/%25/g'
  )"
  echo "::error title=Release candidate did not launch::Unexpected am start result: $launch_tail"
  exit 1
fi

sleep 2
stage='verify foreground process and crash state'
readonly pid="$(run_bounded 20 adb -s "$serial" shell pidof "$package_name" | tr -d '\r')"
test -n "$pid" || { echo 'release candidate process did not remain running' >&2; exit 1; }
run_bounded 30 adb -s "$serial" shell dumpsys activity activities \
  >"$runner_temp/release-smoke-activities.txt"
grep -E 'mResumedActivity|topResumedActivity' "$runner_temp/release-smoke-activities.txt" |
  grep -Eq "${package_name}/(\.|${package_name}\.)MainActivity"
if run_bounded 30 adb -s "$serial" logcat -b events -d |
  grep -E "am_(crash|anr).*${package_name}"; then
  echo 'release candidate emitted a startup crash or ANR event' >&2
  exit 1
fi

stage='complete'
printf 'Release candidate %s (%s) installed and launched on %s; unsigned SHA-256 %s\n' \
  "$actual_version_name" "$actual_version_code" "$serial" "$unsigned_hash"
