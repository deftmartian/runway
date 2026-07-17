# Agent Instructions

## Product Boundary

This repo is for runway, a self-hosted PWA for conservative running-goal planning, workout feedback, activity imports, history, and stats.

Do not let implementation convenience redefine the product. runway is not a GPS tracker, a social fitness app, a generic admin dashboard, or a medical coach. The app earns its keep by making the training ramp, missed work, completed work, rest, and next decision easier to reason about than a paper plan.

Canonical product and design direction lives in:

- [docs/PRODUCT.md](docs/PRODUCT.md)
- [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md)
- [docs/TRAINING_SOURCES.md](docs/TRAINING_SOURCES.md)

## Startup Context

Work from the repository root for code, docs, tests, builds, and git operations. Do not read or
modify files outside the repository unless the user explicitly places them in scope.

## Time And Pacing

For long-running goal work, run:

```sh
date '+%Y-%m-%d %H:%M:%S %Z (%z)'
```

Use it at the start, after each major phase, before changing scope, and at least every 60 minutes. For any session that continues beyond a quick answer, repeat this command periodically to keep real elapsed time visible instead of estimating from memory.

If the user gives a deadline, use the date output to decide when to build, audit, polish, or stop
expanding scope. If no deadline is supplied, work in complete, reviewable passes.

Keep runway live and current during UI and product work at:

```text
http://localhost:4100/
```

From inside this VM, bind dev or preview servers to:

```text
0.0.0.0:4100
```

Do not call a browser-facing pass complete until the current implementation has been checked in the browser.

## Engineering Rules

- Use TypeScript and pnpm.
- Prefer boring, typed, inspectable architecture.
- Target public deployment behind Authentik, Caddy, Cloudflare, and PostgreSQL.
- Keep pnpm, TypeScript, linting, browser checks, deployment verification, and code-quality tooling
  explicit and reviewable.
- Do not hand-roll password hashing, passkey/WebAuthn protocol, session signing, OAuth/OIDC validation, token sealing, or cryptographic primitives.
- Auth is not optional for a complete product state: OIDC, local username/password, 2FA, and passkeys/WebAuthn need vetted libraries and reviewable tests.
- Avoid N+1 query patterns. Dashboard, calendar, import, stats, and settings routes should load with bounded, intentional queries.
- Keep data model migrations explicit and reviewable.
- Use focused verification for small changes and full checks for shared behavior, auth, privacy, visual regressions, deployment, or launch readiness.

Expected commands:

```sh
corepack pnpm install
corepack pnpm dev
corepack pnpm build
corepack pnpm check
corepack pnpm lint
corepack pnpm test:unit
corepack pnpm test:e2e
corepack pnpm test:visual
SITE_URL=http://localhost:4100 corepack pnpm verify:preview
```

## Privacy And Training Rules

- Do not commit sample GPX, FIT, or TCX files.
- Private activity samples may exist locally for importer development. Never commit them or print
  coordinates, author metadata, or raw route content in logs or final summaries.
- Treat route data, injury/load-risk notes, schedule patterns, pace history, heart-rate data, Nextcloud share URLs, share tokens, and share passwords as sensitive.
- Do not log raw coordinates, GPX contents, secrets, reset tokens, SMTP credentials, OIDC secrets, or private sample details.
- Training rules must be backed by reliable sources before they become product behavior. Record those sources in [docs/TRAINING_SOURCES.md](docs/TRAINING_SOURCES.md).
- Do not claim medical authority. The app can flag risk and suggest conservative adjustments; it must not diagnose, treat, or override a clinician.

Security and deployment direction lives in:

- [docs/SECURITY.md](docs/SECURITY.md)
- [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## UX And Copy Rules

- Mobile-first, but desktop must be polished and use the viewport intentionally.
- Keep the product quiet and utilitarian without making it bland.
- The interface should feel like a private training instrument, not a pile of generated controls.
- Copy should sound like useful product language written by a person. Avoid robotic helper paragraphs, fake warmth, clever startup phrasing, motivational pressure, and clipped slogans.
- Every sentence in the UI needs a job: state, action, consequence, or next decision.
- Make consequences visible when the user skips, shortens, over-runs, or reports pain on a run.
- Treat rest days and recovery as first-class parts of the plan.
- Do not build maps before route privacy controls exist.

## Reviews

When the user asks for a review, default to read-only unless they explicitly ask for edits.

Review output should be:

- findings first;
- ordered by severity;
- grounded in file/line references or concrete reproduction steps;
- focused on actionable issues, not compliments or generic advice;
- clear about residual risk when no findings are found.

Use independent review passes when available. If subagents are unreliable, use separate exec-launched review agents or separate read-only review sessions.

Visual and UX review must use a browser tool and interact with the PWA. Review agents should navigate public home, auth flows, onboarding, calendar/day detail, import, stats, settings, and responsive mobile/desktop viewports where practical. Screenshot-only or source-only visual review is not enough.

Before calling a major implementation complete, perform and record:

- UX audit.
- Accessibility audit.
- Security audit.
- Personal data handling audit.
- Performance/PWA audit.
- Architecture/code-quality audit.
- Training-logic truthfulness audit against recorded sources.

When the implementation appears complete and time remains, ask:

- How could this be more useful?
- How could this add more value?
- Would this degrade the user experience?
- Would this dilute the product?

Use the answers to guide another focused pass only when it strengthens the core product.

## Git And Worktree Safety

The worktree may already be dirty. Do not revert unrelated changes.

Never wipe, force-push, reset hard, or discard work unless the user explicitly requests that exact
operation.

When changing git setup or deployment flow, preserve the option to review through a PR or separate worktree.
