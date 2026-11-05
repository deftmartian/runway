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

The runner has 16 GiB and runs three jobs at once. Each emulator job needs about 4 GiB plus its Gradle tooling, so only one emulator job may run at a time. Forgejo 17 ignores job-level `concurrency`, so that limit belongs to the runner: register the emulator jobs' label on a runner instance with capacity 1, then point `instrumentation` and `release-device-smoke` at that label. Until then, push pull requests one at a time. Each emulator job picks its own emulator and adb server ports from the run number, because job containers share the runner's host network: fixed ports collided with concurrent or leftover emulators and left adb reporting "device offline".

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
