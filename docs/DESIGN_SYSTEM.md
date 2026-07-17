# Design System

## Product Feel

runway is a private training instrument. It should feel quiet, exact, and useful under repeated daily use.

The interface is not a generic admin dashboard, calendar skin, fitness social product, or motivational coach. Its visual identity comes from runway rails, centerlines, distance ticks, plan traces, tabular measurements, and visible decision history.

## Canvas And Geometry

- Light mode uses a flat cool porcelain/steel canvas.
- Dark mode uses graphite and blue-black surfaces rather than flat black.
- The body has no ruled-paper pattern, gradient wash, or decorative texture.
- Structural surfaces use a `10px` radius.
- Controls use a `6px` radius.
- Pills are reserved for statuses and switches.
- Shadows are reserved for dialogs, mobile sheets, and transient notices.
- Hover can change outline or fill, but does not lift the control.

Avoid floating navigation shells, boxes inside boxes, repeated card borders, oversized rounding, decorative depth, and large low-information containers.

## Semantic Tokens

The app uses one token set, named by job:

| Token              | Role                                         |
| ------------------ | -------------------------------------------- |
| `--canvas`         | page background                              |
| `--surface`        | standard working surface                     |
| `--surface-strong` | focused/raised surface                       |
| `--text`           | primary text                                 |
| `--muted`          | secondary text                               |
| `--line`           | outlines and separators                      |
| `--rail`           | structural rail                              |
| `--tick`           | ticks and inactive trace structure           |
| `--accent`         | current/selected/generated-plan emphasis     |
| `--accent-strong`  | keyboard focus and strong interactive accent |
| `--on-accent`      | text/icons on filled accent controls         |
| `--completed`      | recorded/stable work                         |
| `--review`         | unresolved, missed, or elevated attention    |
| `--rest`           | recovery state                               |
| `--danger`         | destructive actions and serious risk         |

Do not introduce page-local aliases such as `--background`, `--panel`, `--good`, `--warn`, or `--bad`. State must remain legible without color.

## Typography And Measurement

Use the native system sans-serif stack. Do not load a branding font.

Use `ui-monospace` (with system monospace fallbacks) for dates, duration, distance, pace, heart rate, load, chart axes, and exact tables. Use tabular numerals throughout. Headings can be strong, but should not turn every route into a marketing page.

Copy is calm, factual, and compact:

- state what happened;
- show the exact difference;
- show the plan effect;
- offer the next decision.

Do not praise, shame, reassure, or make runway speak as a coach. Keep safety guidance separate from training arithmetic. Avoid vague terms such as “signal,” “shape,” “smarter,” “adaptive,” and “optimized” in user-facing copy.

## Shared Visual Components

- **Runway mark** — inline SVG with parallel rails, inner edges, and a dashed centerline.
- **Plan trace** — accessible SVG and exact table for generated, current, and actual work.
- **State marker** — status pill with a visible symbol and text, never color alone.
- **Measurement readout** — label plus tabular value/unit on a structural rail.
- **Section rail** — flat section header/content layout for wide settings or records.
- **Choice track** — connected choice surface for a small set of explicit alternatives.
- **Ledger row** — flat label/value/action row with mobile reflow.

These are Svelte components, not a UI framework. Add a dependency only when it solves a real accessibility, protocol, or maintenance problem.

## Core Layout Rules

1. **One main canvas.** Use separators, rails, and alignment before adding another container.
2. **Calendar as operating surface.** Month, weekly load, today, next workout, and review state belong together.
3. **Persistent desktop inspector.** At wide viewports, selected-day detail stays beside the calendar.
4. **Focused mobile sheet.** On smaller screens, the same detail becomes a modal sheet with a clear first action.
5. **State before helper copy.** Visual structure and exact labels should explain the screen.
6. **Progressive disclosure.** Advanced setup, exact long tables, destructive controls, and security enrollment can use `details` when their summary remains informative.
7. **Bounded width.** Wide screens gain useful context; they do not stretch text and forms without limit.

## Route Treatments

### Application shell

Desktop navigation is a flat application rail with tab-like links and a current-position underline. Mobile keeps five destinations with restrained line icons and text labels. The mark is an inline SVG, not a capsule logo or lone brand dot.

### Onboarding

Four steps only: Goal, Starting point, Schedule, Review. Each step has one job. Choice tracks make race/foundation and start-mode decisions explicit. Review must state whether workouts are created now or the goal remains pending.

### Calendar

The calendar is the product center. It shows planned, edited, actual, missed, skipped, review, rest, removed, and multi-activity states. Weekly load rails distinguish recommendation, current plan, and completion. Empty future dates retain a quiet add-workout affordance. Desktop uses the inspector; mobile uses a focused sheet.

### Activity inbox

The page is an activity ledger first and import-source setup second. Every unresolved record has one obvious `Review` action. Inside review, linking, counting as extra, feedback, and deletion remain explicit separate decisions.

### Stats

Use an accessible SVG for generated/current/actual traces and a disclosure containing exact tabular values. Do not repeat generic progress bars. Descriptive pace and heart-rate values never imply diagnosis or automatic plan mutation.

### History

History is a timeline, not an archive list alone. It includes phase start, user edits, manual additions/removals, explicit rebalancing, activity/feedback decisions, reversals, results, and plan lifecycle state.

### Settings

Use flat section rails and label/value/action rows. Keep complex enrollment or profile editing behind informative section labels and disclosures. Privacy effects and irreversible actions must be stated next to the control.

### Public and authentication

Use the same control geometry, SVG mark, and trace identity. Do not wrap every page in a large decorative card. Public copy explains the artifact and its boundary; auth pages stay focused on completing authentication.

## Semantic States

| State         | Meaning                                  | Non-color treatment                                       |
| ------------- | ---------------------------------------- | --------------------------------------------------------- |
| planned       | Future recommendation/current plan       | plan rail and `Planned` label                             |
| edited        | Current plan differs from generated      | `↺ Edited` marker and generated/current comparison        |
| completed     | Recorded work attached to plan           | check marker and actual measurement                       |
| near plan     | Actual within material threshold         | exact difference plus `Completed near plan`               |
| short         | Actual materially below prescription     | partial/short label and exact deficit                     |
| over          | Actual materially above prescription     | over label and exact surplus                              |
| skipped       | Explicit saved skip                      | skipped label and next-decision action                    |
| missed        | Past prescription without a result       | review marker and direct record/decision action           |
| rest          | Recovery prescription                    | rest marker and dash treatment                            |
| needs review  | Imported record without a confirmed role | exclamation marker and primary `Review` action            |
| counted extra | Unmatched run included in actual load    | completed marker plus extra-work wording                  |
| removed       | Current plan hides a generated workout   | removed marker with restore path                          |
| destructive   | Irreversible deletion                    | danger outline plus explicit consequence and confirmation |
| current day   | Today in calendar                        | strong edge/position marker, not color alone              |

## Interaction And Accessibility

- Minimum interactive target is `44px`; mobile navigation targets are at least `48px`.
- Every interactive state includes keyboard focus, selected/current state, disabled state, and pending text where needed.
- Calendar supports arrow-key movement and preserves focus after the inspector/sheet closes.
- SVG charts have a title/description and exact values in a table.
- Status is never communicated by color alone.
- At 200% zoom and enlarged text, content reflows without horizontal page overflow.
- Reduced-motion users receive instant state changes without losing information.
- Dialogs and sheets trap focus, close with Escape, restore focus, and use real elevation.

## Canonical Terms

- **Goal:** race target or foundation outcome.
- **Plan:** an active or archived schedule.
- **Phase:** distance, foundation, or calibration schedule within a plan.
- **Workout:** scheduled prescription.
- **Activity:** imported or manually recorded work.
- **Result:** recorded relationship to a prescription.
- **Generated:** original recommendation retained in the ledger.
- **Current:** runner-edited plan after active adjustments.
- **Actual:** accepted recorded work.
- **Plan risk:** Conservative, Moderate, Aggressive, or Unsafe.
- **Plan change:** explicit stored change to future workouts.

## Visual Verification

Run:

```sh
corepack pnpm test:visual
```

Refresh snapshots only after browser interaction and screenshot-diff inspection:

```sh
corepack pnpm test:visual:update
```

The checked-in Linux baselines use Playwright Chromium on Debian 13 with DejaVu system fonts. The
browser CI job pins that rendering environment; do not refresh baselines from a different font
stack and accept the resulting diff without inspecting it.

Deterministic coverage should include public home, login/recovery, all onboarding modes, empty/active/edited calendars, day inspector/sheet, activity review, generated/current/actual stats, history ledger, settings/security, mobile/desktop/wide viewports, and dark mode.

Before accepting a browser-facing pass, verify keyboard navigation, screen-reader names, Axe results, 200% zoom, text enlargement, reduced motion, and no horizontal overflow.
