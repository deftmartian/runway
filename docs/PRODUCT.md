# Product

## Purpose

runway is a private Android running decision ledger. It helps a self-coached runner understand a conservative plan, keep intentional edits separate from the generated recommendation, record actual work, and choose a visible next step when those differ.

It runs locally on one device. It has no account, server, subscription, social graph, web client, or cloud sync.

## Product boundary

runway is not live GPS tracking, route discovery, a wearable replacement, a generic activity log, a social fitness product, or a medical coach. A watch, phone, or another app can record a run; runway reconciles that record with a training plan or weekly routine.

The product must not silently change future training because a runner missed, shortened, extended, moved, or imported an activity. It may offer conservative consequences. The runner chooses whether to apply one.

## Planning model

The entry paths are:

| Path | Starting point |
| --- | --- |
| Established baseline | A repeatable recent week with at least 3 km, two runs, and a completed longest run. |
| Foundation then goal | The nine-week NHS Couch to 5K foundation before a distance plan. |
| Foundation only | A route to 30 continuous minutes without inventing a distance goal. |
| Timed calibration | Two repeatable easy run/walk sessions each week for two weeks when distance inputs would be guesswork. |
| Weekly routine | Chosen weekdays for running regularly, with open run slots and no baseline, race, distance, duration, pace, or load target. |

The evidence behind these conservative rules, and the limits of what each source supports, are recorded in [Training sources](TRAINING_SOURCES.md).

Defaults are recommendations, not constraints. The runner can change available days, timing, distance, duration, and individual future workouts within explicit guardrails. A weekly routine is a runner-chosen schedule, not a recommendation: its open runs have no prescribed amount.

Setup includes an optional running check-in with explicit effects. Current pain or a clinician's running limit saves a new plan or routine goal without scheduling workouts. A recent injury or recurring pain makes distance-plan ramps and workout-edit checks more cautious; it does not alter fixed foundation, calibration, or open routine slots. A private reminder is stored but never interpreted. These choices can be reviewed in Settings, and changing them does not silently rewrite existing workouts.

When the running check-in blocks scheduling, runway keeps the pending goal visible so the runner can return to it or explicitly replace it. A pending or active goal is never archived as a side effect of merely opening setup.

Finishing or archiving a plan leaves the training profile and recorded history intact. A weekly routine has no completion target; the runner explicitly archives it to end future slots. Either is a deliberate no-active-plan state, not unfinished onboarding: Calendar and Stats keep the record visible and offer a new plan or routine.

## Generated, current, actual

- **Generated** is the original conservative recommendation.
- **Current** is generated work plus deliberate future edits and applied decisions.
- **Actual** is accepted completed work; review-only candidates do not count.

Timed plan headlines show a coarse five-minute estimate for scheduling. The stored prescription and expanded run/walk steps retain their exact source or runner-selected durations.

For a weekly routine, **current** is the runner's chosen schedule and individual future changes; **actual** is recorded, skipped, and extra runs. A passed open slot is factual, not a debt: it remains not recorded unless the runner marks it skipped or moves it. A run on another day counts toward that week's frequency without changing a future slot. Routine counts show the weekly recorded total and, when they differ, separate scheduled-day and other-day runs; distance, duration, and heart rate remain optional observations rather than targets. Stats and routine detail show at most the most recent 52 weeks and label that scope; older rows remain in the local ledger and backup.

This distinction must remain visible in Calendar, Inbox, Stats, and History. Rest is a planned state, not the absence of data.

## Surfaces

1. **Calendar** — today, next workout or routine slot, unresolved Inbox decisions, month/day detail, edits, feedback, and results.
2. **Inbox** — activity review, links, extra work, corrections, and unresolved plan decisions.
3. **Stats** — generated/current/actual traces for prescriptions, or weekly scheduled/recorded/skipped counts for a routine, with plain-language context.
4. **History** — plan or routine lifecycle, archived records, and auditable decisions.
5. **Settings** — training profile, privacy, imports, backup/export, erase, and local build information.

Onboarding is a focused setup flow, not a sixth destination.

## Imports and privacy

Manual results, GPX shares, approved Storage Access Framework folders, and optional Health Connect readings all become local activity candidates. Intake is review-first. For a prescription, a candidate can be linked to a planned workout within three calendar days, counted as extra work, have its feedback recorded with that choice, or be deleted. An accepted unlinked run can return to review while its plan consequence remains unapplied; once a plan decision has been applied, that decision must be reversed through its own visible boundary first. For a weekly routine, accepted work is recorded against that week without a load consequence or automatic schedule change.

GPX parsing is local and bounded. An approved folder is scanned when runway has access; background work is best-effort and is not a promise to watch a filesystem continuously. Health Connect is optional, read-only, and requests routes separately. Fresh profiles discard imported route and heart-rate detail. The runner can opt into private on-device retention; switching back to discard permanently removes the corresponding retained and pending import data. When route retention is enabled, activity detail may draw a bounded, private trace directly from retained points. It uses no basemap, tile service, or network request and does not expose coordinates through accessibility text.

Backup/export is explicit and plaintext. It is a user-owned recovery and portability tool, not sync.

## Non-goals

- Live GPS capture, basemaps, route discovery, navigation, route sharing, leaderboards, social feeds, or coaching chat.
- Medical diagnosis, injury treatment, readiness clearance, or individualized physiological prescription.
- Automatic plan changes without review and confirmation.
- Accounts, sign-in, remote API, web/PWA, browser wrapper, cloud backup, or multi-device synchronization.
- A generic multi-sport tracker. Running and treadmill-running remain the supported activity scope.
