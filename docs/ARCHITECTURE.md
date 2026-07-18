# Architecture

## Boundary

runway is a self-hosted planning, activity-review, and decision-ledger PWA. It does not record GPS live. Server code accepts goals, prescriptions, results, and imported activity data, then presents editable recommendations and explicit decisions.

## Stack

- SvelteKit and TypeScript for routes, server actions, and the PWA.
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
- `/app/import` — activity ledger, manual GPX upload, share/device-folder/Nextcloud sources.
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

## Authentication And Email

Better Auth owns auth protocol and persistence. runway does not implement password hashing, session signing, TOTP, OIDC validation, WebAuthn, or cryptographic sealing primitives.

Local accounts, OIDC, TOTP/recovery codes, and passkeys are product requirements. Password reset email is provider-neutral SMTP configuration. Training reminders are out of scope unless they become explicit, private opt-in behavior.

## Import Architecture

All GPX entry points use the same bounded parser and persist activity date/time, duration, distance,
point count, optional heart-rate/cadence/speed aggregates, a heart-rate series of at most 600 points,
and—when enabled—a route trace of at most 600 points. Route retention defaults to `private` for the
self-hosted database and can be changed to `discard`; changing it to `discard` also clears existing
route traces while leaving activity totals and heart-rate data intact. Raw GPX bytes are discarded
after validation. Coordinates and metadata are never logged.

Authenticated activity records render the retained route as a local SVG with relative-speed
segments, start/finish markers, and no external tile request. Heart rate renders as an accessible
elapsed-time chart, a zone-duration summary when zones were configured at import, and an exact
retained-sample table. Aggregate Stats remain available when no plan is active.

### Manual and share target

Manual upload can use an explicit match choice. The installed-PWA share target always creates an authenticated unlinked Review record; signed-out shares are discarded and must be shared again after login.

### Browser device folder

Supported Chromium PWAs can approve a Gadgetbridge export directory through File System Access. The handle and handled-file hashes remain in browser IndexedDB, keyed by runway user id. The app scans only while visible, reads bounded direct-child metadata, submits at most one newest unhandled GPX per check, quarantines terminal rejects, never modifies the folder, and clears browser-local access at account handoff/sign-out.

### Android app

The Android app launches the complete instance-bound PWA as a Digital Asset Links-verified
Trusted Web Activity, falling back to a browser Custom Tab rather than WebView. Native code owns the
persisted Storage Access Framework read grant, bounded shares, folder settings, and inexact WorkManager
reconciliation. An authenticated PWA session creates a ten-minute, single-use pairing code. Android
exchanges it for a one-year, revocable credential limited to `/api/android/status` and
`/api/android/import`; the server stores only its hash and Android encrypts it with a Keystore-backed
AES-GCM key. Each GPX request has a stable UUID receipt and user-scoped content key, enters Review,
and uses the same parser, import-generation barrier, duplicate checks, and privacy rules as browser
imports. Receipt claims lock the account and revalidate device revocation and expiry, closing the race
between initial bearer authentication and privacy deletion. The boundary and remaining production
gates are documented in [ANDROID.md](ANDROID.md).

### Nextcloud folder share

The server uses a password-protected public folder share, exact-origin allowlisting, and WebDAV `PROPFIND`/`GET`. Tokens/passwords are sealed with `@hapi/iron`; deterministic keyed blind indexes support uniqueness without storing raw remote paths. The worker imports at most one eligible revision per source per pass and backfills older unhandled revisions over later passes.

Source-item claims, user-scoped content hashes, keyed revision constraints, and deletion tombstones make sync idempotent across processes. Every import also captures `athlete_profile.activity_import_generation`; the recording transaction locks and rechecks that generation. Deleting imported activity increments it, so an upload or remote fetch that began earlier cannot recreate data after deletion. Remote listing/download occurs outside long transactions.

## PWA And Cache Boundary

The service worker caches only the offline shell and immutable public application assets. It never caches authenticated HTML, auth endpoints, private training data, mutation responses, or GPX content. Navigation preload reduces startup delay. Install, share-target, passkey, and full service-worker checks require a secure origin; LAN HTTP is only a visual/product preview.

## Deployment Shape

The adapter-node image runs on port `4100` and expects PostgreSQL through `DATABASE_URL`. Production
Compose pulls one explicitly selected image for web, worker, and migration roles; it contains the SQL
journal and production migration runner but not the development toolchain. Migrations complete before
web/worker cutover. Web and worker use separately bounded connection pools with validated connect,
idle, lifetime, statement, and idle-transaction limits. Health responses identify the semantic release
and exact build; worker readiness also rejects stale successful work and overlong in-flight passes.

The intended edge is Cloudflare, OPNsense Caddy, Authentik, runway, and PostgreSQL. Caddy owns the outer security header/TLS policy; SvelteKit retains defensive baseline headers and `private, no-store` for authenticated responses. Exact deployment, rotation, backup, and recovery steps live in [DEPLOYMENT.md](DEPLOYMENT.md).
