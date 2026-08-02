# Product

## Purpose

runway helps runners who coach themselves make a conservative plan, record what actually happened, and decide what—if anything—should change next. It can guide someone toward a race or help them keep a regular weekly running habit.

## Product boundary

runway is not live GPS tracking, route discovery, a wearable replacement, a generic activity log, a social fitness product, or a medical coach. A watch, phone, or another app can record a run; runway helps fit that run back into a training plan or weekly routine. Plans, runs, imports, and notes stay on one Android device unless the runner explicitly creates a backup or export.

The product must not silently change future training because a runner missed, shortened, extended, moved, or imported an activity. It may offer conservative next steps, but it applies one only after the runner confirms it.

## Planning model

The entry paths are:

| Path | Starting point |
| --- | --- |
| Established baseline | One repeatable recent week with at least 3 km and two runs. Its weekly distance, run count, and longest run must describe the same week. |
| Foundation then goal | The nine-week NHS Couch to 5K foundation before a distance plan. |
| Foundation only | A route to 30 continuous minutes without inventing a distance goal. |
| Timed calibration | Two repeatable easy run/walk sessions each week for two weeks when distance inputs would be guesswork. |
| Weekly routine | Chosen weekdays for running regularly, with open run slots and no baseline, race, distance, duration, pace, or load target. |

The evidence behind these conservative rules, and the limits of what each source supports, are recorded in [Training sources](TRAINING_SOURCES.md).

Defaults are recommendations, not constraints. The runner can change available days, timing, distance, duration, and individual future workouts within explicit guardrails. A weekly routine is a runner-chosen schedule, not a recommendation: its open runs have no prescribed amount.

Before an established race plan is created, Setup shows its first week, peak week, longest planned run, required ramp, default ramp, assessment, and warnings. A plan outside the usual recommendation remains available only after the runner confirms that exact workout schedule. A plan outside the generation limit is not created; Setup directs the runner to move the race, choose a shorter goal, or change the starting point. If the local training date changes while Setup is open, the candidate is regenerated before it can be created.

Setup includes optional running limits with explicit effects. Current pain or a clinician's running limit saves a new plan or routine goal without scheduling workouts. A recent injury or recurring pain makes distance-plan ramps and workout-edit checks more cautious; it does not alter fixed foundation, calibration, or open routine slots. A private reminder is stored but never interpreted. These choices can be reviewed in Settings, and changing them does not silently rewrite existing workouts.

When a running limit blocks scheduling, runway keeps the pending goal visible so the runner can return to it or explicitly replace it. A pending or active goal is never archived as a side effect of merely opening setup.

Finishing or archiving a plan leaves the training profile and recorded history intact. A weekly routine has no completion target; the runner explicitly archives it to end future slots. Either is a deliberate no-active-plan state, not unfinished onboarding: Calendar and Stats keep the record visible and offer a new plan or routine.

## Generated, current, actual

- **Generated** is the original conservative recommendation.
- **Current** is generated work plus deliberate future edits and applied decisions.
- **Actual** is accepted completed work; review-only candidates do not count.

Timed plan headlines show a coarse five-minute estimate for scheduling. The stored prescription and expanded run/walk steps retain their exact source or runner-selected durations.

For a weekly routine, **current** is the runner's chosen schedule and individual future changes; **actual** is recorded, skipped, and extra runs. A passed open slot is factual, not a debt: it remains not recorded unless the runner marks it skipped or moves it. A run on another day counts toward that week's frequency without changing a future slot. Routine counts show the weekly recorded total and, when they differ, separate scheduled-day and other-day runs; distance, duration, and heart rate remain optional observations rather than targets. Stats and routine detail show at most the most recent 52 weeks and label that scope; older rows remain in the local ledger and backup.

This distinction must remain visible in Calendar, Inbox, Stats, and History. Rest remains an intentional state in the plan record, but Calendar does not render recovery-only dates as entries because they add no run action.

## Surfaces

1. **Calendar** — the month schedule, completed runs, day detail, edits, feedback, and a floating action for recording a run.
2. **Inbox** — activity review, links, extra work, corrections, and unresolved plan decisions.
3. **Stats** — generated/current/actual traces for prescriptions, or weekly scheduled/recorded/skipped counts for a routine, with plain-language context.
4. **History** — plan or routine lifecycle, archived records, and auditable decisions.
5. **Settings** — training setup, optional notifications, imports, saved-data tools, and local build information.

Onboarding is a focused setup flow, not a sixth destination.

## Bring in runs

Manual results, shared GPX files, approved GPX folders, and optional Health Connect readings all arrive in Inbox first. For a race plan, the runner links a candidate to a planned workout within three calendar days or counts it as extra work, records feedback, and then chooses whether the result should affect the plan. An accepted extra run can return to Inbox while its proposed plan change remains unapplied; an applied plan change must be undone first. For a weekly routine, accepted work counts toward that week without changing future run days.

Gadgetbridge's automatic GPX export is the primary folder workflow. OpenTracks and other recorders can share GPX files or write them to a user-chosen directory; FitoTrack uses the directory path. This is a local file handoff, not a direct integration or continuous sync.

Folder checks and planned-run reminders use Android's best-effort background work. Exact permission, retention, notification, backup, and deletion behavior is specified in [Security and privacy](SECURITY.md).

## Non-goals

- Live GPS capture, basemaps, route discovery, navigation, route sharing, leaderboards, social feeds, or coaching chat.
- Medical diagnosis, injury treatment, readiness clearance, or individualized physiological prescription.
- Automatic plan changes without review and confirmation.
- Accounts, sign-in, remote API, web/PWA, browser wrapper, cloud backup, or multi-device synchronization.
- Android Calendar access, calendar export, or a second editable copy of the runway schedule.
- A generic multi-sport tracker. Running and treadmill-running remain the supported activity scope.
