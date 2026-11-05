# Android development

runway is a standalone native Compose app with local Room storage and no account or remote dependency.

Related: [Architecture](ARCHITECTURE.md) · [Contributing](../CONTRIBUTING.md) · [Release guide](../android/docs/RELEASE.md)

## Prerequisites

Use JDK 17, Android SDK Platform 36, matching build tools, and the checked-in Gradle wrapper. Keep the local SDK path in ignored `android/local.properties` or the standard Android SDK environment variables.

## Build and test

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

The second command needs an emulator or connected device. Run only one Gradle or emulator-heavy workload at a time on constrained development hosts.

CI runs on the Forgejo repository (`.forgejo/workflows/android.yml`); the GitHub repository is a push mirror with Actions off. Pull requests and main-branch pushes that change Android source, build tooling, packaging metadata, or the Android workflow run the full verification: one job runs unit tests, lint, and the complexity ceiling; the package job builds the debug app and test APKs once; the instrumentation job installs those exact artifacts on an x86_64 emulator for device tests and current native screenshots. Documentation-only changes keep a lightweight classification check; release tags always run the complete build, device-test, signing, and publication path.

The runner VM has 16 GiB and runs two daemons. The general one takes two jobs at once with a 5 GiB memory cap per job. A dedicated emulator daemon takes one job at a time, with a 6 GiB cap, and serves the `android-emulator` label (the same image as `ubuntu-latest`). An emulator job needs about 4 GiB for the emulator plus QEMU overhead, so only one may run at a time. Forgejo 17 ignores job-level `concurrency`, so `instrumentation` and `release-device-smoke` use `runs-on: android-emulator`, and the runner enforces the limit. The runner side is in `homelab-infrastructure` (`forgejo_runner` role). Job containers share the runner's host network, so each emulator job also picks its own emulator and adb server ports from the run number: an emulator left running by a cancelled job otherwise holds the fixed ports, and adb reports "device offline" until the next job times out.

Review the `native-ui-screenshots` artifact from that same run before merging presentation changes. A source preview or screenshots from another commit are not device evidence for the change under review.

## Android capabilities

- Native Compose Calendar, Inbox, Stats, History, and Settings.
- Optional Health Connect read access for supported running records; no writes.
- GPX share intake and selected-folder scanning through the Storage Access Framework.
- Optional planned-run reminders and folder-import review alerts. They use local notification channels, Android 13+ runtime permission, and best-effort WorkManager scheduling rather than exact alarms.
- Separate, destructive route and imported-heart-rate retention controls.
- Material You dynamic colour on supported Android versions, with accessible fallback themes.
- Explicit plaintext backup/export through Android's document picker, plus local erase.

Do not claim device or screenshot evidence until it has been collected from the current standalone build.
