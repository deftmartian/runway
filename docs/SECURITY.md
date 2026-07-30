# Security and privacy

## Data sensitivity

Training history, schedule patterns, route coordinates, heart-rate samples, pace, feedback, pain/load notes, GPX files, backups, and exports are private. Treat them as sensitive in code, logs, screenshots, issues, and release evidence.

## Local trust boundary

runway intentionally has no account or network service. It relies on Android's application sandbox, the device lock state, and the runner's control of their device and exported files. It cannot protect local data from a fully compromised or unlocked device, from another app granted broad device access, or from a user sharing a plaintext backup.

The app requests no broad storage permission. A folder import uses a read-only Storage Access Framework tree grant selected by the runner. The grant can be revoked in Android settings. Share intake consumes a supplied file; it does not retain original raw GPX bytes.

## Health Connect

Health Connect access is optional and read-only. Permissions are scoped to the records runway needs. Route permission is separate and route samples are retained only when the profile permits it. Permission denial or revocation must fail safely without deleting unrelated local history.

## Data controls

- Imported activities begin in Review; no import automatically changes a plan.
- Content markers and tombstones prevent a deleted activity from being recreated by a later folder or Health Connect pass.
- Route discard clears retained and pending route samples together.
- Backup and export are user-initiated plaintext files. The product must warn before writing them and never present them as encrypted.
- Removing imported runs and resetting runway first stop import work, release the selected-folder grant, and revoke Health Connect access. If Android cannot confirm those changes, data deletion does not proceed. A later database failure is reported as a partial result because platform permissions and Room cannot be rolled back atomically.

## Secure engineering

- Bound parser size, track points, values, and work per import. Reject document type declarations and entities; do not log raw activity content.
- Validate restore candidates before replacement; keep operations transactional and rerun-safe.
- Keep Room queries bounded. Do not introduce unbounded history, route, or Health Connect reads into a primary surface.
- Do not hand-roll cryptography. Signing material stays outside the repository and is supplied only to a protected release environment.
- Do not commit real activity files, backups, coordinates, signing keys, passwords, or `local.properties`.

## Reporting a vulnerability

Use the repository's [private vulnerability-reporting form](https://github.com/deftmartian/runway/security/advisories/new). If it is unavailable, open a public issue asking for a private reporting channel without including sensitive evidence.

## Release checks

Before a public APK, verify the current build on an emulator and at least one practical device: first launch/onboarding, all five destinations, data erase/backup warning, GPX share and folder grant/revocation, Health Connect permission/revocation, route discard, upgrade preservation, large text, and TalkBack. Keep private activity samples out of the repository and release record.
