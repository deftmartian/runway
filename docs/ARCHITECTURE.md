# Architecture

## Boundary

runway is a self-hosted responsive-web and native-Android planning, activity-review, and decision-ledger product. It does not record GPS live. Server code accepts goals, prescriptions, results, and imported activity data, then presents editable recommendations and explicit decisions.

## Stack

- SvelteKit and TypeScript for the responsive web client, routes, and server actions.
- Jetpack Compose and Kotlin for the native Android client.
- PostgreSQL for users, goals, plans, workouts, activities, feedback, adjustments, imports, and audit events.
- Drizzle for typed schema and explicit migrations.
- Better Auth for local password accounts, sessions, TOTP/recovery codes, OIDC, and WebAuthn/passkeys.
- Vitest for domain/unit tests and Playwright for browser/accessibility/visual checks.
- Docker Compose for local PostgreSQL and the self-hosted web/worker/migration processes.

The app and preview bind to `0.0.0.0:4100`; the default local review URL is `http://localhost:4100/`. Set the public origin variables when reviewing from another host.

## Route Shape

- `/` — public product boundary and plan-trace identity.
- `/login` plus recovery/two-factor routes — OIDC, local, passkey, reset, and TOTP flows.
- `/app/onboarding` — Goal, Starting point, Schedule, Review.
- `/app` — calendar and bounded day inspector/sheet actions.
- `/app/import` — activity ledger, manual GPX upload, and Nextcloud sources.
- `/app/stats` — ramp assessments, generated/current/actual traces, exact values, and descriptive trends.
- `/app/history` — plan lifecycle and plan records.
- `/app/history/[planId]` — phase, adjustment, reversal, workout, and result timeline.
- `/app/settings` — training profile, time zone, security, appearance, export, and privacy controls.

`/app/plan` is a compatibility redirect to the calendar; History remains a primary destination.

## Domain Model

The current schema is a hard cutover; there is no compatibility shim for the earlier undeployed distance-only shape.

Core discriminants:

```ts
type GoalKind = 'race' | 'foundation';
type GoalState = 'pending' | 'active' | 'completed' | 'archived';
type PlanPhase = 'distance' | 'foundation' | 'calibration';
type StartMode = 'established' | 'foundation_to_goal' | 'foundation_only' | 'calibration';
type WorkoutPrescription = DistancePrescription | TimedPrescription | RestPrescription;
type PlanSummary = DistanceSummary | FoundationSummary | CalibrationSummary;
```

Goals can omit race distance only when `kind = 'foundation'`. One current goal and one active plan are allowed per user. Plans record their phase and a discriminated summary. Training weeks carry both target distance and target duration. Workouts carry a prescription kind, indexed distance/duration aggregates, and structured run/walk interval JSON.

Database checks enforce valid shapes:

- distance prescriptions have positive distance and no timed fields;
- timed prescriptions have positive duration, zero planned distance, and structured intervals;
- rest prescriptions have no distance, duration, or intervals;
- race workouts remain goal-owned and cannot be edited like ordinary future workouts.

## Planning Modules

Training rules live under typed domain modules, never in Svelte components:

- intake validation and start-mode selection;
- distance plan generation and ramp/risk classification;
- exact NHS nine-week foundation generation;
- two-week duration calibration generation;
- phase-transition baseline derivation and confirmation;
- material-deviation classification;
- consequence option generation;
- workout edit preview, risk, spacing, and explicit rebalancing;
- adjustment replay.

Zero distance is valid for timed foundation/calibration work. Domain and database code must never divide by an unchecked baseline, invent distance, or interpret a timed prescription as rest.

## Recommendation, Current Plan, And Actual

The three product traces come from different records:

1. **Generated recommendation** — reconstructed from the first applicable `plan_adjustment.previousState`; runner-added workouts have no generated recommendation.
2. **Current plan** — the current workout row after active adjustments, excluding removal tombstones.
3. **Actual** — accepted manual/import activity aggregates or saved workout feedback.

Calendar workout, activity, and result rows are bounded to the requested month plus complete boundary weeks. Supporting plan context is bounded by the 52-week plan invariant rather than by the visible month. Stats trace queries load weeks, workouts, and adjustments in fixed batches. History loads a bounded plan record and adjustment ledger. No route performs a query per workout.

## Adjustment Ledger

`plan_adjustment` is the reversible source of truth for plan mutations. Each entry is user/plan/workout scoped and stores:

- an adjustment identity and optional trigger identity;
- trigger type (`feedback`, activity link/import, explicit decision, manual edit/add/remove, or rebalance);
- before and after workout state;
- reason and timestamp;
- reversal metadata.

Manual rebalancing records every affected workout under one adjustment identity. Undoing that identity replays the group. Resetting one workout reverses its active manual changes while preserving later feedback/activity changes. Deleting or unlinking an activity reverses only changes derived from that activity.

Removed workouts remain as `isRemoved` tombstones so recommendation, history, restoration, ownership checks, and later non-manual records survive.

Finite product limits keep an editable plan reviewable and prevent a single account from turning normal plan reads into unbounded work:

- at most 52 weeks and 14 stored workout rows per week (728 per plan);
- at most two current planned workouts on one date;
- at most 100 adjustment entries for one workout and 10,000 for one plan.

These are storage and interaction bounds, not training advice. Raising them requires reviewing query, ledger-replay, history, and browser behavior together.

## Activity And Consequence Flow

Activities are accepted before future-plan decisions:

- manual unplanned runs immediately count in actual load;
- imported runs start in Review;
- candidates within three days are suggested without ambiguous auto-linking;
- linking preserves the original recommendation and moves current plan context to the activity date;
- multiple activities remain separate;
- a rest prescription is not deleted when activity occurs on that date.

Only accepted activities participate in actual totals, traces, history results, heart-rate summaries, and current training signals. Review-only records stay isolated in the activity ledger until the runner confirms a match or counts them as extra work.

Distance and duration deviation classification is pure domain logic. Consequence proposals can recommend keep, reduce, rest, repeat, or rebalance, but no future workout changes until `applyConsequenceDecision` receives the explicit user choice. The selected decision is persisted with the consequence.

## Ownership And Query Discipline

Every runway-owned table carries `userId`, and relational writes use user-scoped foreign keys or explicit ownership predicates. Client input never supplies a trusted user id. Form actions derive ownership from `event.locals.user`.

Calendar, import, stats, history, and settings use bounded selects/aggregates and small fixed query batches. New list routes require pagination or a documented finite bound. Remote I/O never remains inside a long database transaction.

Training-data export uses a read-only, repeatable-read snapshot and serializes each ordered table in
250-row pages. The snapshot is staged to an owner-only (`0600`) temporary file before the HTTP body is
opened, so neither the full relational graph nor the completed JSON document is held in memory. The
response removes the artifact on completion, cancellation, or read error; a conservative 24-hour
reaper runs in the web process at startup and every five minutes, removing only stale, real
`runway-training-export-*` directories left by a process crash. Running it in the web process is
required because the dedicated staging tmpfs is not shared with the worker container.
Application-level byte, staging-quota, and concurrency reservations bound this sensitive scratch
space independently from general `/tmp`; the Compose deployment supplies a dedicated owner-only
tmpfs with additional filesystem overhead. Operators can raise the documented limits together for a
larger installation. The `account.export` success audit is written only after staging finishes and is
not part of the snapshot that it records.

## Authentication And Email

Better Auth owns auth protocol and persistence. runway does not implement password hashing, session signing, TOTP, OIDC validation, WebAuthn, or cryptographic sealing primitives.

Local accounts, OIDC, TOTP/recovery codes, and passkeys are product requirements. Password reset email is provider-neutral SMTP configuration. Training reminders are out of scope unless they become explicit, private opt-in behavior.

## Import Architecture

All GPX entry points use the same bounded parser and persist activity date/time, duration, distance,
point count, optional heart-rate/cadence/speed aggregates, a heart-rate series of at most 600 points,
and—when enabled—a representative route trace of at most 600 points that retains the first and last
track points. Series timestamps are stored as elapsed seconds from the retained activity start time;
the original per-point timestamps are not retained separately. Route retention defaults to `private`
for the self-hosted database and can be changed to `discard`; changing it to `discard` also clears
existing route traces while leaving activity totals and heart-rate data intact. Raw GPX bytes are
discarded after validation. Coordinates and metadata are never logged.

Authenticated activity records render the retained route as a local SVG with relative-speed
segments, start/finish markers, and no external tile request. Heart rate renders as an accessible
elapsed-time chart, a zone-duration summary when zones were configured at import, and an exact
retained-sample table. Aggregate Stats remain available when no plan is active.

### Manual upload

Manual upload can use an explicit match choice. It creates an authenticated Review record and uses the
same bounded parser, duplicate controls, activity-deletion barrier, and per-user operation lease as
other import paths.

### Android app

Android verifies a user-selected runway server and renders the product natively with Jetpack Compose.
It uses Better Auth device authorization for the normal account session and the versioned
`/api/mobile/v1` API for product views and mutations. The system browser is only an approval and
fresh-account-security boundary; it does not render the product and no browser session is copied into
Android. There is no origin-bound build variant.

Native code owns the persisted Storage Access Framework read grant, bounded shares, folder settings,
inexact WorkManager reconciliation, and optional Health Connect ingestion. The separate `rwy1_`
import credential is limited to Android import endpoints; the signed-in native client establishes it
without a user-visible pairing code. It is distinct from the native account session and
Keystore-encrypted with its exact origin. Native mutations use user-scoped idempotency receipts. GPX
and Health Connect activities enter Review and use the same parser, privacy, duplicate, and
activity-deletion barriers as web uploads. Health Connect reads only explicitly approved running and
treadmill-running sessions and metrics; background route reads and all Health Connect writes are out
of scope. See [ANDROID.md](ANDROID.md) for the complete boundary.

The public `GET /api/android/instance` endpoint exposes only product identity, supported Android API
range, and release version. Android requires that handshake before saving a server. Credentials,
workers, browser navigation, and local import state are bound to the exact normalized origin and a
monotonic connection generation. A confirmed server change attempts server-side device revocation,
then performs a generation-checked local teardown before the new origin is stored. An explicit offline
override discloses that the old server may retain the device and an in-flight upload.

### Nextcloud folder share

The server uses a password-protected public folder share, exact-origin allowlisting, and WebDAV `PROPFIND`/`GET`. Tokens/passwords are sealed with `@hapi/iron`; deterministic keyed blind indexes support uniqueness without storing raw remote paths. The worker imports at most one eligible revision per source per pass and backfills older unhandled revisions over later passes.

Source-item claims, user-scoped content hashes, keyed revision constraints, and deletion tombstones make sync idempotent across processes. Every import also captures `athlete_profile.activity_import_generation`; the recording transaction locks and rechecks that generation. Deleting imported activity increments it, so an upload or remote fetch that began earlier cannot recreate data after deletion. Remote listing/download occurs outside long transactions.

Interactive GPX and Nextcloud operations use persistent per-user and per-client-address request
budgets before multipart parsing or remote WebDAV work. Nextcloud connect, test, and sync share the
per-user operation lease; the scheduled worker observes the same lease and retries a busy source on a
later pass.

## Web Delivery And Service-worker Retirement

The web client is responsive and online. It does not promise installation, offline navigation, a
share target, or browser-managed folder access. Authenticated responses are private and no-store.

For one release after the hard cutover, `/service-worker.js` is a narrowly scoped retirement worker:
an existing old installation may load it once, delete only old `runway-*` caches, unregister itself,
and return clients to the current page. New visitors are not registered. Remove this compatibility
endpoint after the documented retirement window; it is not a continuing PWA capability.

## Deployment Shape

The adapter-node image runs on port `4100` and expects PostgreSQL through `DATABASE_URL`. Production
Compose pulls one explicitly selected image for web, worker, and migration roles; it contains the SQL
journal and production migration runner but not the development toolchain. Migrations complete before
web/worker cutover. Web and worker use separately bounded connection pools with validated connect,
idle, lifetime, statement, and idle-transaction limits. Health responses identify the semantic release
and exact build; worker readiness also rejects stale successful work and overlong in-flight passes.

The intended edge is Cloudflare, OPNsense Caddy, Authentik, runway, and PostgreSQL. Caddy owns the outer security header/TLS policy; SvelteKit retains defensive baseline headers and `private, no-store` for authenticated responses. Exact deployment, rotation, backup, and recovery steps live in [DEPLOYMENT.md](DEPLOYMENT.md).
