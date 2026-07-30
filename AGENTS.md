# Agent Instructions

## Product boundary

This repository is runway: an offline/local-first native Android app for conservative running-goal planning, workout feedback, activity imports, history, and stats. It is a private training decision ledger, not a GPS tracker, social fitness app, generic dashboard, medical coach, account service, or web application.

The value is making the training ramp, missed work, completed work, rest, and next decision easier to reason about than a paper plan. Do not let implementation convenience redefine that product.

Canonical direction:

- [docs/PRODUCT.md](docs/PRODUCT.md)
- [docs/DESIGN_SYSTEM.md](docs/DESIGN_SYSTEM.md)
- [docs/TRAINING_SOURCES.md](docs/TRAINING_SOURCES.md)

## Startup context

Work from the repository root. Do not read or modify outside it unless explicitly authorized. Preserve unrelated worktree changes.

For long work, run `date '+%Y-%m-%d %H:%M:%S %Z (%z)'` at the start, after major phases, before scope changes, and at least hourly.

## Engineering

- Use Kotlin, Jetpack Compose, Room, and Gradle. Keep `:domain` pure, `:data` local and typed, and `:app` focused on presentation and Android capabilities.
- Prefer boring, inspectable architecture. Do not add a server, network client, account system, browser surface, WebView, PWA, compatibility shim, or cloud dependency.
- Keep data-model changes explicit and testable. Preserve released local data with migration and rerun/idempotence evidence; do not infer schema state.
- Run focused checks for small changes and broader Gradle, instrumentation, privacy, and device checks for shared, import, data, or visual behavior.
- On constrained hosts, run one Gradle/emulator workload at a time and use `--no-daemon --max-workers=1` unless there is measured capacity.

Typical commands:

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

Do not call Android UI work complete without emulator or device evidence. Screenshot-only or source-only review is insufficient.

## Privacy and training

- Never commit or log real GPX, FIT, TCX, backups, coordinates, route metadata, heart-rate data, schedule patterns, pain/load notes, signing keys, or passwords.
- Original input bytes are discarded after local parsing. Respect route and heart-rate retention choices; route discard is destructive and must not be silently reversed.
- Backup/export is user-controlled plaintext. Explain its sensitivity; never hand-roll cryptography.
- Training rules require reliable sources recorded in `docs/TRAINING_SOURCES.md`.
- Do not diagnose, treat, or make medical-clearance claims. The app can flag conservative options and advise professional guidance.

## UX and copy

- Mobile-first native UI. Use Material You when available and accessible fallback tokens when it is not.
- Keep the interface quiet, useful, and human. No fake warmth, generated-sounding helper text, motivational pressure, or clever slogans.
- Every sentence must state a condition, action, consequence, or next decision.
- Make deviations visible; do not silently alter future training.
- Treat rest days and recovery as intentional parts of a plan.
- Do not build maps before route privacy controls exist.

## Reviews and safety

Reviews are read-only unless edits are explicitly requested. Findings come first, ordered by severity, with concrete reproduction or file references and residual risk.

Before calling major work complete, perform proportionate UX, accessibility, security/privacy, performance/native delivery, architecture/code-quality, and training-truthfulness passes. When time remains, only expand scope when it strengthens the core decision loop.

Never reset hard, force-push, wipe, or discard work unless explicitly asked for that exact action. Keep release signing material outside the repository and preserve a reviewable commit boundary.
