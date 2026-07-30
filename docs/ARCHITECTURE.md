# Android architecture

## Boundary

runway is a standalone Android application. Its persisted state, planning, activity review, imports, privacy controls, and backup/export all execute locally. The local ledger is the only product authority.

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

Released Room schemas require explicit migration and upgrade tests. The v1-to-v2 migration preserves heart-rate data already stored by a released build under explicit private retention; fresh profiles still default to discard. Restore accepts exact released schema identities, upgrades a v1 backup through that same immutable migration, and is tested for a second no-op preparation. New unreleased schema work may be corrected before release; never invent a timestamp-based migration lineage.

## Imports

GPX share and folder imports use a bounded local parser. Raw files are consumed for parsing and not stored. Content-derived records and tombstones prevent accidental re-import after deletion. Folder access uses the Android Storage Access Framework, so the user grants only a selected tree and may revoke it in system settings. Scans run on foreground return and optional bounded WorkManager work; Android does not provide a reliable directory watch.

Health Connect is an optional read-only source for supported running and treadmill-running records. The app requests only the permissions it needs, treats route access separately, and brings additions, corrections, and deletions through the same review/confirmation boundary. A denied or revoked permission leaves the local ledger intact.

Folder scans, one-off GPX intake, foreground/background Health Connect sync, destructive erase, and restore share one process-wide import-data boundary. Scheduler cancellation is not treated as proof that an active Room write has finished.

## Privacy and data management

Route and imported-heart-rate retention are separate local profile choices. Discarding either removes its retained summaries/samples and pending import evidence in the same local operation. Heart-rate profile values are optional display context and never change a plan by themselves.

Auto Backup is disabled. Backup/export is an explicit user action. The app warns that its local artifacts are plaintext and sensitive; it does not claim encryption it does not provide. Restore validates a bounded candidate before replacing the current ledger, disconnects import sources before installation, and leaves the old ledger untouched on failed validation.

## Native delivery

The app is Kotlin + Jetpack Compose with Material 3. It supports Android 8.0+ (`minSdk 26`) and uses Material You dynamic colour where the platform supports it. `dev.deftmartian.runway` and its signing certificate are stable release identity, not runtime configuration.

Run static/unit/build work serially on constrained hosts. Device acceptance still needs a real emulator or device run; Gradle compilation is not UI evidence.
