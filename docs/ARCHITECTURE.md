# Android architecture

## Boundary

runway is a standalone Android application. Its persisted state, planning, activity review, imports, privacy controls, and backup/export all execute locally. There is no remote authority and no compatibility layer for the retired server product.

## Modules

| Module | Responsibility |
| --- | --- |
| `:domain` | Pure planning, eligibility, feedback, and consequence rules with deterministic tests. |
| `:data` | Room schema, transactions, typed repositories, local import persistence, backup/export validation, and Health Connect persistence. |
| `:app` | Compose UI, navigation, lifecycle, Storage Access Framework, share intents, Health Connect permission/sync orchestration, and WorkManager scheduling. |

The application service wiring is intentionally small and explicit. UI code consumes typed local read models and calls typed commands; it must not depend on JSON payloads or network-shaped state.

## Ledger model

Room persists the profile, plans, generated/current workouts, actual activity ledger, feedback, decisions, adjustment/undo records, imports, privacy choices, and metadata. The database is the local source of truth.

Generated, current, and actual remain distinct. An imported activity starts in review and cannot affect actual traces, statistics, or training decisions until the runner resolves it. Operations that change several related records use a Room transaction. Deletion and route-discard paths must also clear pending imported-route state so it cannot reappear later.

Released Room schemas require explicit migration and upgrade tests. New unreleased schema work may be corrected before release; never invent a timestamp-based migration lineage.

## Imports

GPX share and folder imports use a bounded local parser. Raw files are consumed for parsing and not stored. Content-derived records and tombstones prevent accidental re-import after deletion. Folder access uses the Android Storage Access Framework, so the user grants only a selected tree and may revoke it in system settings. Scans run on foreground return and optional bounded WorkManager work; Android does not provide a reliable directory watch.

Health Connect is an optional read-only source for supported running and treadmill-running records. The app requests only the permissions it needs, treats route access separately, and brings additions, corrections, and deletions through the same review/confirmation boundary. A denied or revoked permission leaves the local ledger intact.

## Privacy and data management

Route retention is an explicit local profile choice. Discarding routes removes retained samples and pending route samples in the same local operation. Heart-rate data is descriptive context and never changes a plan by itself.

Auto Backup is disabled. Backup/export is an explicit user action. The app warns that its local artifacts are plaintext and sensitive; it does not claim encryption it does not provide. Restore must validate a bounded candidate before replacing the current ledger and must leave the old state untouched on a failed validation.

## Native delivery

The app is Kotlin + Jetpack Compose with Material 3. It supports Android 8.0+ (`minSdk 26`) and uses Material You dynamic colour where the platform supports it. `dev.deftmartian.runway` and its signing certificate are stable release identity, not runtime configuration.

Run static/unit/build work serially on constrained hosts. Device acceptance still needs a real emulator or device run; Gradle compilation is not UI evidence.
