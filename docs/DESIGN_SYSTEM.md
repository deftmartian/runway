# Design system

## Product feel

runway should feel like a private training instrument: calm, legible, and warm enough to invite regular use without becoming decorative or motivational. It reports the plan, the facts, and the next decision. It does not perform optimism, shame, or medical certainty.

## Platform

Use native Jetpack Compose and Material 3. On Android 12+ use the device's dynamic Material You scheme and system light/dark mode. On Android 8–11 use the app fallback scheme. Never make meaning depend on a particular wallpaper color: labels, icons, measurements, and accessible contrast carry state.

Use the Android app bar for the current destination title. Keep the five primary destinations in a bottom navigation bar on compact phones and an adaptive navigation rail at 600 dp and wider. Setup remains a focused flow without primary navigation.

## Hierarchy

- One primary decision per surface before secondary detail.
- Keep Calendar focused on today, next work, missed runs, and the exact count of unresolved Inbox decisions. For a weekly routine, lead with this week's scheduled, recorded, and skipped run count; an unrecorded passed slot is visible but not an alarm. Collapse secondary metrics behind a deliberate expansion.
- Keep Inbox focused on unresolved review work; an imported activity must show what it will and will not change, and every decision counted from Calendar must remain reachable.
- In Stats, lead with the comparison and plain meaning; charts support the decision rather than becoming the page.
- In History, preserve the difference between generated, current, actual, and archived state.
- In Settings, group Training, Imports, Privacy, Data, Reset and removal, and About. About shows concise local app/build information, never network or account controls.

## Components and interaction

- Prefer cards only when they separate decisions; avoid a wall of rounded containers.
- Forms use clear labels, units, sensible defaults, inline validation, and a visible save/cancel boundary. Invalid fields explain what must change; a disabled action alone is not validation.
- Destructive actions say what local data will be removed and require confirmation.
- Consequence choices show their precise affected workouts and remain reversible when safe.
- Open routine runs show no distance, duration, pace, or implied load target. Recording, skipping, moving, or adding one must state that future routine slots stay unchanged.
- Use progressive disclosure for technical import detail, retained route and heart-rate evidence, old plans, and raw metrics; do not hide the next decision.
- Support touch targets, keyboard navigation, screen readers, system font scaling, and both contrast modes.

## Copy

Every sentence must state a condition, action, consequence, or next decision. Prefer “This run is shorter than planned. Choose what changes next.” to vague or performative language. Avoid “smart,” “adaptive,” “optimized,” “signal,” fake warmth, and motivational pressure.

Pain and hard-effort labels describe a runner's report, not a diagnosis. Use conservative, clear language and distinguish an offered plan change from one already applied.

For routines, use factual count language: “2 of 3 runs recorded this week” and “One scheduled run was not recorded.” If a run happened on another day, include it in the weekly total and separately show scheduled-day versus other-day counts; never imply that an extra run completed a particular scheduled slot. Do not call an open slot short, behind, deficient, or a missed load target. A pain or hard-effort report remains visible context; it does not imply that runway changed the routine.

## Visual verification

Native UI changes require emulator or device inspection at a compact phone size, a larger phone size, system light/dark schemes, increased font scale, and TalkBack where the changed flow warrants it. Screenshot updates are evidence only when they come from the current native build.
