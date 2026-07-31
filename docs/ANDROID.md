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

Pull requests and main-branch pushes that change Android source, build tooling, packaging metadata, or the Android workflow run the full hosted verification matrix. The Linux package job builds the debug app and test APKs once; the macOS emulator job installs those exact artifacts for device tests and current native screenshots. Documentation-only changes keep a lightweight classification check; release tags always run the complete build, device-test, signing, and publication path.

Review the `native-ui-screenshots` artifact from that same run before merging presentation changes. A source preview or screenshots from another commit are not device evidence for the change under review.

## Android capabilities

- Native Compose Calendar, Inbox, Stats, History, and Settings.
- Optional Health Connect read access for supported running records; no writes.
- GPX share intake and selected-folder scanning through the Storage Access Framework.
- Separate, destructive route and imported-heart-rate retention controls.
- Material You dynamic colour on supported Android versions, with accessible fallback themes.
- Explicit plaintext backup/export through Android's document picker, plus local erase.

Do not claim device or screenshot evidence until it has been collected from the current standalone build.
