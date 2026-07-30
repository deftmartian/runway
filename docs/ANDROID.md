# Android development

This document is retained as the Android development entry point. The architecture is described in [ARCHITECTURE.md](ARCHITECTURE.md), and release handling is in [android/docs/RELEASE.md](../android/docs/RELEASE.md).

runway is a standalone native Compose app with local Room storage. It does not select an origin, sign in, open a browser, or contact a remote service.

## Build and test

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

Use JDK 17 and Android SDK Platform 36. The second command needs an emulator or connected device. Run only one Gradle or emulator-heavy workload at a time on constrained development hosts.

## Android capabilities

- Native Compose Calendar, Inbox, Stats, History, and Settings.
- Optional Health Connect read access for supported running records; no writes.
- GPX share intake and selected-folder scanning through the Storage Access Framework.
- Material You dynamic colour on supported Android versions, with accessible fallback themes.
- Explicit plaintext backup/export and local erase.

Do not claim device or screenshot evidence until it has been collected from the current standalone build.
