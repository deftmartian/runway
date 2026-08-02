# Design system

## Product feel

runway should feel calm, clear, and comfortable to return to. It shows what was planned, what happened, and what needs attention next. It does not perform optimism, shame, or medical certainty.

## Platform

Use native Jetpack Compose and Material 3. On Android 12+ use the device's dynamic Material You scheme and system light/dark mode. On Android 8–11 use the app fallback scheme. Never make meaning depend on a particular wallpaper color: labels, icons, measurements, and accessible contrast carry state.

Use the Android app bar for the current destination title. Keep the five primary destinations in a bottom navigation bar on compact phones and an adaptive navigation rail at 600 dp and wider. Setup remains a focused flow without primary navigation.

## Hierarchy

- One primary decision per surface before secondary detail.
- Keep Calendar focused on the schedule and completed runs by date. Do not repeat today, next work, or Inbox counts above information already visible in the calendar and primary navigation.
- Keep Inbox focused on unresolved review work; an imported activity must show what it will and will not change, and every decision counted from Calendar must remain reachable.
- In Stats, lead with the comparison and plain meaning; charts support the decision rather than becoming the page.
- In History, preserve the difference between generated, current, actual, and archived state.
- In Settings, group Training, Notifications, Imports, Privacy, Data, Reset and removal, and About. About shows concise local app/build information, never network or account controls.

## Components and interaction

- Prefer cards only when they separate decisions; avoid a wall of rounded containers.
- On compact and medium widths, Calendar uses chronological day rows so the full run purpose and state can wrap. Calendar may use a wider centered canvas than other destinations. Use the seven-column month grid only at expanded widths where every cell remains readable. Day actions belong in the selected-day sheet.
- Keep the record-run action fixed at the lower right of Calendar and hide it while scrolling down; reveal it at the top or when scrolling back up. General goal replacement belongs in Settings, not Calendar or History.
- Do not render recovery-only dates as Calendar entries. Keep planned rest in the underlying plan and history so this remains a presentation choice rather than a training change.
- Forms use clear labels, units, sensible defaults, inline validation, and a visible save/cancel boundary. Invalid fields explain what must change; a disabled action alone is not validation.
- Destructive actions say what local data will be removed and require confirmation.
- Consequence choices show their precise affected workouts and remain reversible when safe.
- Open routine runs show no distance, duration, pace, or implied load target. Recording, skipping, moving, or adding one must state that future routine slots stay unchanged.
- Use progressive disclosure for technical import detail, retained route and heart-rate evidence, old plans, and raw metrics; do not hide the next decision.
- Ask for Android notification permission only after the runner enables an alert. Describe reminders as best-effort, keep lock-screen text generic, and show when Android has blocked the relevant channel.
- Support touch targets, keyboard navigation, screen readers, system font scaling, and both contrast modes.

## Copy

Write calmly, directly, and without hurry. Be concise without becoming terse or mechanical. Product copy should sound natural when read aloud.

- Lead with what the runner wants to know or do, not the architecture behind it.
- Address the runner as “you” when that makes a sentence clearer. Name runway when the app performs an action.
- Prefer everyday words. In product copy, use “history,” “planned run,” “result,” and “change” instead of internal terms such as “ledger,” “prescription,” “consequence,” “provenance,” or “surface.” Keep a technical term only when it is the clearest accurate name, and explain it on first use.
- Put the condition first, then the effect or action: “If you discard routes, runway permanently removes saved route points.”
- Give each message one primary job. State the important fact first and make the next action obvious.
- Keep onboarding and empty states practical and welcoming. Make errors, pain guidance, privacy choices, and destructive confirmations more serious and explicit.
- Avoid slogans, fake warmth, motivational pressure, jokes in errors, exclamation marks, and claims that something is “smart,” “adaptive,” “optimized,” “easy,” or “simple.”

Every sentence should earn its place by stating a condition, fact, action, effect, or next step. Prefer “This run was shorter than planned. Choose whether the next run changes.” to vague or performative language.

Examples:

| Avoid | Prefer |
| --- | --- |
| “A private Android running decision ledger.” | “Make a running plan, track what actually happens, and decide what comes next.” |
| “This addition stays in the adjustment ledger.” | “History keeps a record of this change, so you can undo it later.” |
| “No routine week is available yet.” | “Your first routine week starts Monday. Open Calendar to see the runs.” |

Review new copy in its actual screen. Read it aloud, check it at larger system text sizes, and ask whether someone unfamiliar with runway can explain what happens next. For important onboarding, health, privacy, or destructive messages, test comprehension rather than judging tone from source alone.

This guidance adapts [Material's communication principles](https://codelabs.developers.google.com/codelabs/material-communication-guidance), the [Google voice and tone guide](https://developers.google.com/style/tone), and the [NHS guidance for effective app messages](https://service-manual.nhs.uk/content/writing-nhs-messages).

Pain and hard-effort labels describe a runner's report, not a diagnosis. Use conservative, clear language and distinguish an offered plan change from one already applied.

For routines, use factual count language: “2 of 3 runs recorded this week” and “One scheduled run was not recorded.” If a run happened on another day, include it in the weekly total and separately show scheduled-day versus other-day counts; never imply that an extra run completed a particular scheduled slot. Do not call an open slot short, behind, deficient, or a missed load target. A pain or hard-effort report remains visible context; it does not imply that runway changed the routine.

## Visual verification

Native UI changes require emulator or device inspection at a compact phone size, a larger phone size, system light/dark schemes, increased font scale, and TalkBack where the changed flow warrants it. Screenshot updates are evidence only when they come from the current native build.
