# Product

## Purpose

runway helps runners who coach themselves make a conservative plan, record what actually happened, and decide what—if anything—should change next. It can guide someone toward a race or help them keep a regular weekly running habit.

## Product boundary

runway is not live GPS tracking, route discovery, a wearable replacement, a generic activity log, a social fitness product, or a medical coach. A watch, phone, or another app can record a run; runway helps fit that run back into a training plan or weekly routine. Plans, runs, imports, and notes stay on one Android device unless the runner explicitly creates a backup or export.

The product must not silently change future training because a runner missed, shortened, extended, moved, or imported an activity. It may offer conservative next steps, but it applies one only after the runner confirms it.

## Planning model

Setup has two goal paths:

| Goal | Starting point |
| --- | --- |
| Train for a distance | Choose 5K, 10K, half marathon, or marathon. Enter one repeatable recent week, or let runway observe two weeks of open runs before confirming a baseline. |
| Run consistently each week | Choose a weekly distance or running-time goal and the days you intend to run. Runway divides that goal across the selected days as concrete distance or duration prescriptions. Enter a repeatable recent week as context, or let recorded work provide that context. |

The evidence behind these conservative rules, and the limits of what each source supports, are recorded in [Training sources](TRAINING_SOURCES.md).

Defaults are recommendations, not constraints. The runner can change available days, timing, distance, duration, and individual future workouts within explicit guardrails. A weekly routine is runner-chosen, not a generated training recommendation. Its distance or time goal is divided across the scheduled runs. Changing a future week's goal recalculates only that week's prescriptions. Reducing, adding, removing, or moving one run is an explicit deviation and does not silently change the weekly goal.

Before an established race plan is created, Setup shows its first week, peak week, longest planned run, required ramp, default ramp, assessment, and warnings. A plan outside the usual recommendation remains available only after the runner confirms that exact workout schedule. A plan outside the generation limit is not created; Setup directs the runner to move the race, choose a shorter goal, or change the starting point. If the local training date changes while Setup is open, the candidate is regenerated before it can be created.

Setup does not collect health or pain limits. A result can record that the effort was unusually hard, which may offer a smaller next run. The runner can also move the next planned run later in Calendar. Neither action happens automatically.

Finishing or archiving a plan leaves the training profile and recorded history intact. A weekly routine has no completion target; the runner explicitly archives it to end future slots. Either is a deliberate no-active-plan state, not unfinished onboarding: Calendar and Stats keep the record visible and offer a new plan or routine.

## Generated, current, actual

- **Generated** is the original conservative recommendation.
- **Current** is generated work plus deliberate future edits and applied decisions.
- **Actual** is accepted completed work; review-only candidates do not count.

For a weekly routine, **current** is the runner's chosen weekly distance or time goal and the prescriptions assigned to its scheduled days; **actual** is recorded distance or duration, skipped runs, and extra runs. A passed scheduled run is factual, not a debt: it remains not recorded unless the runner marks it skipped or moves it. A run on another day counts toward that week's matching metric and frequency without completing a particular scheduled run. Routine views compare the selected weekly metric with recorded work and separately show scheduled-day and other-day run counts. Heart rate remains an optional observation. Stats and routine detail show at most the most recent 52 weeks and label that scope; older rows remain in the local ledger and backup.

This distinction must remain visible in Calendar, Inbox, Stats, and History. Rest remains an intentional state in the plan record, but Calendar does not render recovery-only dates as entries because they add no run action.

## Surfaces

1. **Calendar** — the month schedule, completed runs, day detail, edits, feedback, and a floating action for recording a run.
2. **Inbox** — activity review, links, extra work, corrections, and unresolved plan decisions.
3. **Stats** — generated/current/actual traces for prescriptions, or weekly distance-or-time goal/actual traces plus scheduled/recorded/skipped counts for a routine, with plain-language context.
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
