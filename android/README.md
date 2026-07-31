# Android application

This directory contains runway's standalone native Android app. The product has no account, server, browser surface, or remote dependency.

## Modules

| Module | Responsibility |
| --- | --- |
| `:domain` | Pure planning, feedback, and consequence rules. |
| `:data` | Room, imports, privacy controls, backup/restore, and Health Connect persistence. |
| `:app` | Compose UI, navigation, Android capabilities, and local orchestration. |

Start with [Android development](../docs/ANDROID.md) for prerequisites and test commands, then read [Architecture](../docs/ARCHITECTURE.md) before changing module boundaries.

Release signing and personal F-Droid distribution are documented separately in the [Android release guide](docs/RELEASE.md). Keep SDK paths, signing properties, keystores, real activity files, backups, exports, and health data out of Git.
