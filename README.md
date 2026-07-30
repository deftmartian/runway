# runway

[![Android CI](https://github.com/deftmartian/runway/actions/workflows/check.yml/badge.svg)](https://github.com/deftmartian/runway/actions/workflows/check.yml)
[![Latest release](https://img.shields.io/github/v/release/deftmartian/runway?display_name=tag&sort=semver&color=1f758f)](https://github.com/deftmartian/runway/releases/latest)
[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-1f758f.svg)](LICENSE)

**A private, offline-first Android running decision ledger.**

runway helps a self-coached runner keep the recommendation, their edits, and the work actually completed separate. A missed day, short run, extra run, or hard effort does not quietly rewrite the plan: runway records it, explains the available next decisions, and waits for the runner to choose.

It is a native Kotlin + Jetpack Compose application. There is no account, server, web client, PWA, or hosted service.

## The loop

1. Build a conservative plan from a repeatable baseline, a foundation phase, or timed calibration.
2. Adjust future runs when life changes: move, edit, add, remove, reset, or undo them.
3. Record a run manually, share a GPX file, scan an approved folder, or read an approved Health Connect activity.
4. Review the difference between plan and reality. Keep, reduce, rest, repeat, or rebalance only after an explicit choice.

The five main surfaces are Calendar, Inbox, Stats, History, and Settings. Rest and recovery are planned work; training guidance is decision support, not medical advice.

## Local by default

- The training ledger lives in Room on the device.
- GPX input is parsed locally and the original bytes are discarded after intake.
- A Storage Access Framework folder grant can be scanned when the app returns to the foreground; Android background scheduling is best-effort, not a filesystem watch guarantee.
- Health Connect reading is optional and read-only. Imported activities enter review before they affect a plan.
- Route and heart-rate retention are controlled in Settings. Removing route retention clears retained route data.
- Backup and export are explicit, user-controlled plaintext files. They may contain private training history, notes, route data, and heart-rate data; store and share them carefully.

## Build

Requirements: JDK 17, Android SDK Platform 36 and matching build tools, and the checked-in Gradle wrapper. Keep SDK paths in ignored `local.properties` or the normal Android SDK environment variables.

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
```

The project has `:app`, `:data`, and `:domain` modules. `:domain` contains pure training rules, `:data` owns Room and import persistence, and `:app` owns Compose, Android capabilities, and local orchestration.

For an emulator or connected device:

```sh
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

Do not commit real GPX, FIT, or TCX files, device backups, route coordinates, health data, signing material, or `local.properties`.

## Releases

The stable application id is `dev.deftmartian.runway`. Android updates are tied to both that id and the signing certificate: retain the release key securely and back it up before distributing an APK.

GitHub version tags publish a signed universal APK only when the protected signing environment is available. The F-Droid source-build path is deliberately unsigned so F-Droid can sign it. See [Android release instructions](android/docs/RELEASE.md).

Current screenshots from the retired client are intentionally not shown here. Fresh screenshots must come from the standalone native app and be checked on an emulator or device before publication.

## Read more

- [Product direction](docs/PRODUCT.md)
- [Design system](docs/DESIGN_SYSTEM.md)
- [Android architecture](docs/ARCHITECTURE.md)
- [Security and privacy](docs/SECURITY.md)
- [Training sources and limits](docs/TRAINING_SOURCES.md)
- [Contributing](CONTRIBUTING.md)

## License

Copyright © 2026 runway contributors.

runway is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
