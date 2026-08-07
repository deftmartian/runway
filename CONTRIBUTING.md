# Contributing to runway

runway is a local Android decision ledger for self-coached runners. A change should strengthen this loop: show a conservative recommendation, preserve deliberate edits, record actual work, explain the difference, and leave the next decision with the runner.

Read [Product](docs/PRODUCT.md), [Design system](docs/DESIGN_SYSTEM.md), [Architecture](docs/ARCHITECTURE.md), [Security and privacy](docs/SECURITY.md), and [Training sources](docs/TRAINING_SOURCES.md) before changing product behavior.

## Development

Use JDK 17, Android SDK Platform 36, matching build tools, and the checked-in Gradle wrapper. Keep local SDK configuration in ignored `local.properties`.

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
```

Run connected tests when an emulator or device is available:

```sh
android/gradlew -p android --no-daemon --max-workers=1 connectedDebugAndroidTest
```

Use one Gradle build at a time on constrained hosts. A green compile or unit suite is not acceptance evidence for Compose changes: inspect the affected native flow at practical phone sizes, large text, and TalkBack where applicable.

## Change expectations

- Keep future workout changes previewed and explicit. Never silently mutate a plan after feedback or import.
- Treat rest and recovery as first-class planned work.
- Keep imported activities in Review until the runner accepts, links, changes, or deletes them.
- Keep Room schema changes explicit. A new unreleased schema may be corrected; released schemas need an upgrade path and an idempotence test.
- Keep repository boundaries typed and bounded. Do not route new UI behavior through JSON payloads or network-shaped models.
- Name each instrumentation source and its matching top-level class `*Test.kt`. CI passes an explicit class list to the runner and rejects Java, helper-only, or differently named instrumentation sources instead of silently skipping them.
- Back new training behavior with reliable sources in `docs/TRAINING_SOURCES.md`; do not add medical claims.
- Do not commit private GPX/FIT/TCX files, backups, route coordinates, health data, signing keys, passwords, or machine-specific paths.
- Treat plaintext backup and export files as sensitive. The default `runway-training-export*.json` name is ignored, but renamed exports are still private and must not be committed. Do not invent cryptography.
- Update tests and documentation with behavior changes.

## Security reports

Do not include private activity data, backups, credentials, or reproduction files containing them in a public issue. Use the repository's [private vulnerability-reporting form](https://github.com/deftmartian/runway/security/advisories/new). If it is unavailable, open a public issue requesting a private contact channel only.

## License

By contributing, you agree that your contribution is licensed under the repository's [MIT](LICENSE) terms.
