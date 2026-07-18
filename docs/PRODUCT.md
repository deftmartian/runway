# Product

## Purpose

runway is a self-hosted decision ledger for self-coached runners.

Its central loop is:

1. Show a clear recommendation.
2. Let the runner change it.
3. Record what actually happened.
4. Explain the difference and its effect on the week.
5. Offer clear next-plan options without silently taking control.

The product earns its place by making the next training decision, its evidence, and its history easier to reason about than a paper plan.

## Product Boundary

runway is not a watch, GPS tracker, human coach, social network, medical authority, or advanced sports-science workstation. Watches and phone apps record activities; runway plans, accepts activity records, compares plan with actual work, and preserves the runner's decisions.

Route maps require authenticated ownership, explicit retention/deletion controls, and local rendering without third-party tile requests. Do not infer diagnoses, treatment, or individualized medical advice. Pain and medical restrictions can change the recommended decision, but the runner remains in control and qualified medical guidance takes precedence.

## Position

runway occupies a deliberately narrow space:

- more transparent, editable, and privacy-controlled than opaque adaptive planners;
- less dependent on proprietary health metrics or daily optimization;
- simpler and running-specific compared with expert multisport analysis tools;
- focused on plan-versus-actual consequences rather than GPS recording, audio coaching, social motivation, or workout laboratories.

The defensible niche is **editable planning, explicit consequences, and self-hosted ownership of the complete training record**.

## Audience

- New runners who need a timed run/walk foundation without fabricated distance.
- Established runners preparing for 5K, 10K, half-marathon, or marathon goals.
- Returning runners who need a short observation phase before choosing a distance baseline.
- Self-hosters and households that want control over schedule, route, pain, heart-rate, and pace data.

## Planning Model

Goals and workout phases are separate. A race goal can remain pending while a runner completes a foundation or calibration phase.

Supported starting paths:

1. **Established week** — the distance planner requires a repeatable week of at least 3 km, two runs, and a positive longest run.
2. **Foundation then goal** — the exact nine-week, three-session NHS Couch to 5K run/walk schedule precedes a race phase.
3. **Foundation only** — the same nine-week phase targets 30 minutes of continuous easy running.
4. **Short calibration** — two identical easy timed run/walk sessions per week for two weeks, using a runner-selected 10–30 minute duration.

The established-week cutoff is a limitation of the distance planner, not a health recommendation. Foundation and calibration prescriptions are duration-based; the app never invents distance from time.

A timed result can include optional observed distance. That observation is kept with the result and may later be offered as part of a baseline, but it never changes the session's duration prescription. Recent-injury and recurring-pain selections produce a visible caution on timed phases without changing their fixed schedule. Private free-text health context is not interpreted by plan logic.

An established half-marathon or marathon plan based on two runs per week preserves that reported frequency instead of silently adding a day. Because this concentrates the weekly distance into larger sessions, plan creation requires an explicit acknowledgement; adding another day, changing the goal, or moving the date remain available alternatives.

At foundation or calibration completion, runway derives completed activity count, duration, observed distance, and longest activity. Those values must be shown for confirmation before they become a baseline. A race phase is generated only after confirmation and only when the resulting ramp is supported. Otherwise the app offers another foundation week, continued calibration, a later date, or a shorter goal.

Current pain or a medical restriction can save a pending goal and profile, but creates no active workout phase.

## Ramp Assessments

runway describes its plan arithmetic with four assessment bands: `Within default`, `Above default`, `High increase`, and `Unsupported`. Every assessment must identify the measured change and the relevant runway default or explain the specific generation limit. These are product boundaries, not medical safety ratings.

`Within default` means only that the calculated ramp stays inside runway's configured planning range. `Unsupported` means that runway will not generate that distance phase as a default. Pain, injury history, and clinician restrictions remain separate health inputs and must never be collapsed into a reassuring ramp label.

Runner-controlled changes use `Within default`, `Above default`, `High change`, and `Outside default`. A valid outside-default edit remains available after its exact load effect and conflicts are shown and the runner explicitly confirms it.

Week and current-plan assessments use the effective edited workouts, not stale generated totals. Projected edit ramps use the same injury-adjusted boundaries as generated ramp assessments. A week containing both distance and timed prescriptions is identified as non-comparable instead of being collapsed into one unit.

## Recommendations And User Control

Generated recommendations are defaults, not locks.

For future non-race workouts, the runner can:

- move the workout to another future date through the goal date;
- change distance, duration, type, purpose, or run/walk blocks;
- convert a run to rest or rest to a run;
- add or remove workouts, including a second workout on one day (the current product limit is two planned workouts on a date);
- apply an edit to one workout or explicitly rebalance compatible remaining workouts;
- reset to the generated recommendation;
- undo a manual adjustment without deleting later feedback or activity links.

Race events are changed through goal setup. Past and completed prescriptions are immutable; their activity/result records can be corrected, unlinked, or deleted independently.

Every edit preview shows generated, current, and proposed prescriptions, source/destination week load, recovery-spacing conflicts, the projected ramp assessment, and every affected workout. The edit assessment is based on the changed share of the affected week's load; a large percentage change to one small workout does not silently override that measure. The projected week-to-week ramp remains separate context and does not relabel a small edit. A new workout starts from “no workout scheduled,” not from a rest prescription, and is assessed against the week's existing load. Distance-to-duration conversions carry a separate comparison warning because those prescription bases are not directly interchangeable. Above-default and high changes require explicit confirmation. Invalid values, cross-user access, and dates outside the active goal are blocked; a valid outside-default choice remains available.

## Plan Versus Actual

Activities are accepted before runway judges their relationship to the plan.

Distance prescriptions use a material threshold of `max(500 m, 15% of target)`. Timed prescriptions use `max(5 minutes, 15% of target duration)`. Results are classified as near plan, short, or over plan, and the exact difference is shown.

A manually recorded unplanned run immediately counts in actual load. An imported activity stays in Review until confirmed. Selecting **Auto-match by date and distance** is an explicit standing confirmation for future unambiguous matches from that source; those matches are accepted and linked immediately. Unmatched or ambiguous records still remain in Review, and the setting remains visible and reversible. A run on a rest day appears beside the rest prescription; the rest day is not silently deleted. Multiple activities on one day remain separate records.

Unlinking an accepted imported match returns it to Review unless the runner had separately confirmed it as extra training. Unlinking a manual activity, or an import already confirmed as extra, removes only the plan match and keeps its accepted actual load.

`Review` is a data boundary, not just a label. Review-only imports remain visible in the activity ledger but do not enter calendar actuals, weekly totals, traces, history results, heart-rate summaries, or the current training assessment until the runner accepts their role.

Factual actual-load changes happen immediately. Future-plan changes do not. After a material shortfall, overrun, unplanned run, hard effort, or pain report, runway offers explicit decisions such as:

- keep the remaining plan;
- reduce the next run;
- make the next workout rest;
- repeat the prescription;
- rebalance the remaining week.

The app recommends one option and previews its effect, but applies nothing until the runner confirms. Deleting or unlinking an activity reverses only changes derived from that activity.

The current feedback/load assessment follows the latest accepted evidence by activity or workout date, not the most severe record in the recent window. Newer normal feedback can replace an older concern, and an applied decision resolves that current item without deleting it from the calendar or history.

Timed-plan comparisons require recorded duration. A distance-only activity still enters factual actual distance, but runway labels its timed load effect `Needs review` and does not invent a duration ratio or call it within default. Pain is presented as `Pain review`, separate from numeric load bands.

## Product Surfaces

1. `/app` — primary calendar, today/next/review readouts, weekly load, and persistent desktop day inspector or focused mobile sheet.
2. `/app/import` — compact activity ledger first; import source setup second; opened GPX records include route and heart-rate visuals.
3. `/app/stats` — accessible generated/current/actual traces with exact values and descriptive effort/heart-rate context, with or without an active plan.
4. `/app/history` — active and archived plan phases, user edits, feedback-driven changes, reversals, and results.
5. `/app/settings` — flat profile, account security, appearance, import, export, and privacy controls with progressive disclosure.
6. `/app/onboarding` — four focused steps: Goal, Starting point, Schedule, Review.

Public and authentication routes use the same control geometry and runway trace identity without turning into marketing or decorative container pages.

## Privacy Model

Route data, schedule patterns, pain/load-assessment notes, pace and heart-rate history, Nextcloud share credentials, and authentication secrets are sensitive.

- Authenticated pages are private and are not stored by the service worker.
- Raw GPX content and coordinates are not logged or committed.
- A bounded route trace is retained by default for authenticated activity maps; the runner can
  discard route points on future imports or clear retained traces without deleting activity totals.
- Heart-rate time series are bounded and retained for activity charts. Original GPX bytes are
  discarded after parsing, and route maps do not request external tiles.
- Import disclosure names the retained activity start time, bounded heart-rate samples, average
  cadence, and the first/last points included in a retained representative route trace.
- Import credentials are sealed and exact-origin constrained.
- Training-data export and destructive controls remain user-scoped and explicit. Full account
  deletion is separate from deleting imported activities and removes authentication and training
  records together.
- Local device-folder access stays in the approving browser and is cleared at account handoff/sign-out.
- Android folder access stays in the installed app. Its import-only credential is Keystore-encrypted,
  expires, can be revoked from Import sources, and is revoked automatically when imported activity
  data is deleted.

## Non-Goals

- A separate native product that duplicates the planning UI. The Android package presents the
  complete authenticated runway PWA and adds only Android-owned capabilities such as durable folder
  access, shares, and background reconciliation.
- Live GPS recording or watch replacement.
- Social feeds, leaderboards, streak pressure, or public routes.
- Guided audio, coaching personality, or motivation programs.
- Paid data APIs.
- Automatic plan mutation without explicit consent.
- Pace-zone speedwork or medical interpretation without reviewed source-backed behavior.
