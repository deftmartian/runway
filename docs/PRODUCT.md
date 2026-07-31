# Product

## Purpose

runway is a private Android running decision ledger. It helps a self-coached runner understand a conservative plan, keep intentional edits separate from the generated recommendation, record actual work, and choose a visible next step when those differ.

It runs locally on one device. It has no account, server, subscription, social graph, web client, or cloud sync.

## Product boundary

runway is not live GPS tracking, route discovery, a wearable replacement, a generic activity log, a social fitness product, or a medical coach. A watch, phone, or another app can record a run; runway reconciles that record with a training plan.

The product must not silently change future training because a runner missed, shortened, extended, moved, or imported an activity. It may offer conservative consequences. The runner chooses whether to apply one.

## Planning model

The entry paths are:

| Path | Starting point |
| --- | --- |
| Established baseline | A repeatable recent week with at least 3 km, two runs, and a completed longest run. |
| Foundation then goal | The nine-week NHS Couch to 5K foundation before a distance plan. |
| Foundation only | A route to 30 continuous minutes without inventing a distance goal. |
| Timed calibration | Two repeatable easy run/walk sessions each week for two weeks when distance inputs would be guesswork. |

The evidence behind these conservative rules, and the limits of what each source supports, are recorded in [Training sources](TRAINING_SOURCES.md).

Defaults are recommendations, not constraints. The runner can change available days, timing, distance, duration, and individual future workouts within explicit guardrails.

If health context blocks plan generation, runway keeps the pending goal visible so the runner can return to it or explicitly replace it. A pending or active goal is never archived as a side effect of merely opening setup.

## Generated, current, actual

- **Generated** is the original conservative recommendation.
- **Current** is generated work plus deliberate future edits and applied decisions.
- **Actual** is accepted completed work; review-only candidates do not count.

This distinction must remain visible in Calendar, Inbox, Stats, and History. Rest is a planned state, not the absence of data.

## Surfaces

1. **Calendar** — today, next workout, unresolved Inbox decisions, month/day detail, edits, feedback, and results.
2. **Inbox** — activity review, links, extra work, corrections, and unresolved plan decisions.
3. **Stats** — generated/current/actual traces and plain-language training context.
4. **History** — plan lifecycle, archived plans, and auditable decisions.
5. **Settings** — training profile, privacy, imports, backup/export, erase, and local build information.

Onboarding is a focused setup flow, not a sixth destination.

## Imports and privacy

Manual results, GPX shares, approved Storage Access Framework folders, and optional Health Connect readings all become local activity candidates. Intake is review-first. A candidate can be linked to a planned workout within three calendar days, counted as extra work, have its feedback corrected, or be deleted before it affects actual totals or future decisions.

GPX parsing is local and bounded. An approved folder is scanned when runway has access; background work is best-effort and is not a promise to watch a filesystem continuously. Health Connect is optional, read-only, and requests routes separately. Fresh profiles discard imported route and heart-rate detail. The runner can opt into private on-device retention; switching back to discard permanently removes the corresponding retained and pending import data.

Backup/export is explicit and plaintext. It is a user-owned recovery and portability tool, not sync.

## Non-goals

- Live GPS capture, navigation, maps, route sharing, leaderboards, social feeds, or coaching chat.
- Medical diagnosis, injury treatment, readiness clearance, or individualized physiological prescription.
- Automatic plan changes without review and confirmation.
- Accounts, sign-in, remote API, web/PWA, browser wrapper, cloud backup, or multi-device synchronization.
- A generic multi-sport tracker. Running and treadmill-running remain the supported activity scope.
