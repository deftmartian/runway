# runway

[![Android CI](https://github.com/deftmartian/runway/actions/workflows/android.yml/badge.svg)](https://github.com/deftmartian/runway/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/deftmartian/runway?display_name=tag&sort=semver&color=1f758f)](https://github.com/deftmartian/runway/releases/latest)
[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-1f758f.svg)](LICENSE)

**Make a running plan, track what actually happens, and decide what comes next.**

runway is for runners who coach themselves. Use it to build a conservative race plan or set a few regular running days each week. When a run does not go to plan, runway records what happened and lets you decide whether the rest of the schedule should change.

runway stores and processes your training data on your Android phone. It needs no account, server, subscription, or cloud sync. It is a native Kotlin and Jetpack Compose app for Android 8.0 and newer.

| Calendar | Inbox |
| --- | --- |
| ![Calendar showing today's plan, the next run, and waiting decisions](docs/images/runway-android-calendar-dark.png) | ![Inbox showing a GPX run awaiting review and an accepted result](docs/images/runway-android-inbox-light.png) |

## Plan, record, decide

1. Choose a race goal or regular running days. For a race, start from a recent week of running, a run/walk foundation, or two short calibration weeks.
2. When life changes, move, edit, add, remove, reset, or undo future runs.
3. Record a run manually, share a GPX file, scan an approved folder, or import an optional Health Connect activity for review.
4. Compare the planned run with what happened, then keep the schedule, ease back, rest, repeat, or rebalance. Weekly routines simply record whether you ran; they never change future days on their own.

Calendar shows what is coming up. Inbox is where you review imported runs and schedule changes. Stats and History show how the plan changed and what you actually did. Settings keeps imports, privacy, backups, and app details together. Rest and recovery are part of the plan, not missing data. runway supports your choices; it does not give medical advice.

## Private by design

- runway parses GPX files on your phone and does not keep a copy of the original file.
- You review every GPX or optional read-only Health Connect import in Inbox before it counts toward your training or offers a schedule change.
- A selected GPX folder is scanned when runway returns to the foreground; Android background scans are best-effort, not a continuous folder watch.
- You choose separately whether runway keeps route and imported heart-rate details. New profiles discard both. Switching either control back to discard permanently clears only that type of saved and pending import data.
- Backup and export are explicit plaintext files written to the location chosen in Android's document picker. Create a backup before moving to or resetting a phone, then restore it into runway on the replacement device. These files can contain sensitive training history, notes, routes, and heart-rate data; a cloud-backed document provider can move them off the device.

See [Security and privacy](docs/SECURITY.md) for the full trust boundary and data controls.

<details>
<summary><strong>More screens</strong></summary>

| Stats | History |
| --- | --- |
| ![Stats comparing planned and completed training](docs/images/runway-android-stats-light.png) | ![History showing current and past plan records](docs/images/runway-android-history-light.png) |
| Settings | Larger screens |
| ![Settings for training, imports, privacy, and local data](docs/images/runway-android-settings-dark.png) | ![Calendar using the adaptive navigation rail at 600 dp and wider](docs/images/runway-android-calendar-expanded-light.png) |

</details>

## Install

Download the APK with its matching `.sha256` and `.signer.txt` files from the [latest release](https://github.com/deftmartian/runway/releases/latest). Verify the checksum and signing certificate using the [Android release guide](android/docs/RELEASE.md), then open the APK on the device. Releases signed with the same `dev.deftmartian.runway` certificate install as in-place updates.

Personal F-Droid repository operators should follow the [Android release guide](android/docs/RELEASE.md) so GitHub and F-Droid builds preserve the same application identity and update path.

## Build from source

Use JDK 17, Android SDK Platform 36 with matching build tools, and the checked-in Gradle wrapper:

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
```

See [Android development](docs/ANDROID.md) for device tests and module responsibilities, or [Contributing](CONTRIBUTING.md) before changing product behavior.

## Documentation

| Topic | Start here |
| --- | --- |
| Product intent and boundaries | [Product](docs/PRODUCT.md) |
| Interface and copy decisions | [Design system](docs/DESIGN_SYSTEM.md) |
| Modules and local data flow | [Architecture](docs/ARCHITECTURE.md) |
| Data sensitivity and trust boundary | [Security and privacy](docs/SECURITY.md) |
| Training evidence and limits | [Training sources](docs/TRAINING_SOURCES.md) |
| Building and testing | [Android development](docs/ANDROID.md) |
| Signing and distribution | [Android release guide](android/docs/RELEASE.md) |

## License

Copyright © 2026 runway contributors.

runway is licensed under the [GNU Affero General Public License v3.0 only](LICENSE) (`AGPL-3.0-only`).
