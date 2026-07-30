# runway for Android

runway is a full native Kotlin + Jetpack Compose running decision ledger. Plans, activities, imports, preferences, and backups are owned by the app on the phone.

The app has five destinations—Calendar, Inbox, Stats, History, and Settings—and keeps generated, current, and actual training separate. It supports explicit workout changes, manual results, GPX sharing, approved-folder scans, optional read-only Health Connect imports, privacy controls, and plaintext local backup/export.

On Android 12 and newer the interface follows the phone's Material You palette and light/dark mode. Android 8–11 use the app fallback scheme. Colour never carries state alone.

## Build

Use JDK 17, Android SDK Platform 36, matching build tools, and the checked-in wrapper.

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
```

For device tests:

```sh
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

Keep `local.properties`, `signing.properties`, keystores, passwords, real GPX/FIT/TCX files, and backups out of commits. Run heavy Gradle and emulator tasks serially on constrained hosts.

## Privacy

Folder import retains only a selected Storage Access Framework tree grant. GPX bytes are consumed locally and discarded after parsing. Health Connect is optional, read-only, and asks for route data separately. Backup/export files are plaintext and may contain sensitive training information; handle them accordingly.

## Release

The application id `dev.deftmartian.runway` and the APK signing certificate are stable update identity. See [release instructions](docs/RELEASE.md) before distributing a build. Release screenshots must come from the current native build on an emulator or device.
