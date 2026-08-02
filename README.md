# runway

[![Android CI](https://github.com/deftmartian/runway/actions/workflows/android.yml/badge.svg)](https://github.com/deftmartian/runway/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/deftmartian/runway?display_name=tag&sort=semver&color=1f758f)](https://github.com/deftmartian/runway/releases/latest)
[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-1f758f.svg)](LICENSE)

**Plan your running, bring in what you recorded, and decide what changes next.**

runway is an Android app for runners who plan their own training. Build toward a race or choose regular running days, then keep the plan, completed runs, recovery, and deliberate changes in one place.

Keep recording with the app or watch you already use. Share one GPX file with runway or approve an export folder, and each new activity waits in Inbox until you review it. Optional read-only Health Connect import is available too.

runway works with your existing recorder instead of replacing it. It runs locally on Android 8.0 and newer with no account, server, subscription, or cloud sync.

| Calendar | Inbox |
| --- | --- |
| ![Calendar showing today's planned run and a readable schedule by date](docs/images/runway-android-calendar-dark.png) | ![Inbox showing a GPX run awaiting review and an accepted result](docs/images/runway-android-inbox-light.png) |

## A clear next step

- Build toward a race or choose a few regular running days each week.
- Review the starting week, peak, longest run, and ramp before creating a race plan.
- See planned runs, completed work, and waiting decisions by date without filling the calendar with recovery entries.
- Move, edit, add, or remove a future run when life changes.
- Compare what was planned with what happened, then keep the schedule, ease back, rest, repeat, or rebalance.
- Look back at the original plan, every deliberate change, and the work you completed.

Weekly routines record whether you ran without changing future days on their own. Race plans can offer a schedule change, but runway never applies one until you approve it.

## Bring runs in from the apps you already use

runway reads local GPX files; it does not record your route. You can share one file from another app or approve a GPX folder under **Settings → Imports**.

- **[Gadgetbridge](https://gadgetbridge.org/internals/automations/auto-export/):** In **Settings → Automations → Auto export GPX tracks**, choose an export folder. Approve that same folder in runway. Gadgetbridge creates a GPX when a newly synced activity includes a GPS track.
- **[OpenTracks](https://github.com/OpenTracksApp/OpenTracks):** Share a recorded track as GPX 1.1, or enable automatic export after each recording and approve the output folder in runway.
- **[FitoTrack](https://codeberg.org/jannis/FitoTrack):** Set GPX export to a directory, then approve that directory in runway.
- **Another GPX recorder:** Use the same flow if it can share a `.gpx` activity or write GPX files to a folder you choose.

runway checks the approved folder when the app returns to the foreground. Optional background checks are best-effort rather than a continuous folder watch. Every imported activity goes to Inbox for review before it counts toward training or offers a schedule change.

Under **Settings → Notifications**, you can turn on reminders for planned run days and alerts when a folder import is ready in Inbox. Android may deliver background work and notifications later than requested.

## Your data

Plans, runs, and preferences stay on this phone unless you create a backup or export. You choose whether imported route and heart-rate details are saved. Backup and export files are plaintext, so store them somewhere you trust.

See [Security and privacy](docs/SECURITY.md) for exact storage, permission, retention, and deletion behavior.

<details>
<summary><strong>More screens</strong></summary>

| Stats | History |
| --- | --- |
| ![Stats comparing planned and completed training](docs/images/runway-android-stats-light.png) | ![History showing current and past plan records](docs/images/runway-android-history-light.png) |
| Settings | Larger screens |
| ![Settings for training, reminders, imports, and saved data](docs/images/runway-android-settings-dark.png) | ![Calendar using the adaptive navigation rail at 600 dp and wider](docs/images/runway-android-calendar-expanded-light.png) |

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
