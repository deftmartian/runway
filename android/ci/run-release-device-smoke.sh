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

run_bounded() {
  python3 android/ci/run-with-timeout.py "$@"
}

collect_diagnostics() {
  local status="$?"
  if [ "$status" -ne 0 ]; then
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

readonly unsigned_hash="$(sha256sum "$unsigned_apk" | awk '{print $1}')"
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

readonly permissions="$("$build_tools/aapt" dump permissions "$unsigned_apk")"
printf '%s\n' "$permissions"
if grep -Eq 'android\.permission\.(INTERNET|ACCESS_NETWORK_STATE)' <<< "$permissions"; then
  echo 'standalone release candidate unexpectedly requests a network permission' >&2
  exit 1
fi
"$build_tools/zipalign" -c -P 16 4 "$unsigned_apk"

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
readonly verification="$("$build_tools/apksigner" verify --verbose --print-certs "$installable_apk")"
printf '%s\n' "$verification"
grep -q '^Verified using v2 scheme (APK Signature Scheme v2): true$' <<< "$verification"
grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true$' <<< "$verification"
[ "$(sha256sum "$unsigned_apk" | awk '{print $1}')" = "$unsigned_hash" ] || {
  echo 'smoke signing changed the canonical unsigned candidate' >&2
  exit 1
}

run_bounded 60 adb -s "$serial" wait-for-device
run_bounded 30 adb -s "$serial" shell getprop sys.boot_completed | grep -q '^1'
run_bounded 30 adb -s "$serial" logcat -c
run_bounded 180 adb -s "$serial" install -r "$installable_apk"
run_bounded 30 adb -s "$serial" shell am force-stop "$package_name"
run_bounded 60 adb -s "$serial" shell am start -W \
  -n "$package_name/$activity_name" | tee "$runner_temp/release-smoke-start.txt"
grep -q '^Status: ok$' "$runner_temp/release-smoke-start.txt"

sleep 2
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

printf 'Release candidate %s (%s) installed and launched on %s; unsigned SHA-256 %s\n' \
  "$actual_version_name" "$actual_version_code" "$serial" "$unsigned_hash"
