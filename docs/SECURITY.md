# Security

## Data Sensitivity

runway handles personal data that can be more sensitive than it first appears:

- route coordinates can reveal home, school, work, and routines;
- Nextcloud share URLs, tokens, passwords, remote file names, and sync errors can reveal private storage structure and routines;
- injury flags and pain notes are health-adjacent;
- pace, heart rate, activity history, and schedule patterns can reveal fitness level and routine;
- account credentials, reset tokens, 2FA factors, and passkeys need normal account-security rigor.

Treat route and injury data as user-owned and non-public by default.

## Reporting A Vulnerability

Use the repository's
[private vulnerability-reporting form](https://github.com/deftmartian/runway/security/advisories/new).
Include the affected version, impact, and minimal reproduction steps, but do not attach real activity
exports, credentials, tokens, private URLs, or other personal data. If private reporting is unavailable,
open a public issue containing only a request for a private contact channel; do not disclose the
vulnerability in that issue.

## Auth And Authorization

The app must support:

- direct OIDC login, intended for Authentik;
- local username/password fallback;
- local 2FA;
- passkeys/WebAuthn, including hardware security keys.

Security constraints:

- no hand-rolled crypto;
- modern password hashing through vetted libraries;
- secure HTTP-only cookies;
- explicit CSRF protection;
- per-user authorization checks on every data access;
- no open registration unless configured;
- local auth can be disabled by config;
- production auth config fails closed unless public origins are explicitly HTTPS;
- password reset tokens are hashed at rest, expire quickly, are single-use, and are not logged;
- password reset request responses do not reveal whether an email address has an account.
- OAuth access and refresh tokens are encrypted at rest with Better Auth's versioned secret support;
  the generic OIDC ID token is discarded after validation instead of being stored plaintext.

State-changing requests require the exact public Origin. The only missing-Origin exception is the
installed PWA's `POST /app/import/share` navigation because the Web Share Target algorithm does not
attach an Origin header. That exception requires the exact path, multipart content, and browser-set
top-level navigation Fetch Metadata (`site=none`, `mode=navigate`, `dest=document`). The route still
requires an authenticated session before reading the body, accepts exactly one bounded GPX, and uses
the same strict parser and review-only import path as manual upload.

`BETTER_AUTH_SECRET` rotation is an operational migration, not a blind value replacement. Database
backups must have separately protected matching key material, and an old key cannot be retired until
count-only checks show no OAuth or TOTP ciphertext still references it. The staged rotation,
re-authentication, revocation, restore, and compromised-key procedures live in
[DEPLOYMENT.md](DEPLOYMENT.md#better-auth-secret-and-encrypted-provider-tokens).

The app sets baseline defensive headers on SvelteKit responses, including CSP, frame blocking, referrer policy, permissions policy, and `private, no-store` on authenticated routes. The production edge should enforce the outer security-header policy globally while preserving long-lived cache headers for hashed assets.

## Email And Password Reset

Password reset must use SMTP through configuration, not hard-coded provider logic.

Expected environment shape:

- `SMTP_HOST`
- `SMTP_PORT`
- `SMTP_USER`
- `SMTP_PASSWORD`
- `SMTP_FROM`
- `SMTP_TLS_MODE`
- `MAIL_ENABLED`
- optional `PASSWORD_RESET_RATE_LIMIT_SECRET` for HMAC-keyed rate-limit identifiers

For Proton Mail, use either Proton SMTP submission with a custom-domain SMTP token, or Proton Mail Bridge if the host provides a reachable Bridge SMTP endpoint. Never use the Proton account password as the SMTP password.

Email failures should be logged without credentials, reset tokens, or recipient enumeration. Missing global SMTP configuration can be shown as a setup problem; per-recipient delivery failures should still use the generic reset-request response.

## Personal Data Defaults

Default behavior should be private:

- no public profiles;
- no public activity feeds;
- no public maps;
- no route sharing by default;
- no telemetry;
- route traces remain inside the self-hosted database and authenticated UI;
- no external map tiles.

## Audit Event Retention

Account/security and activity audit events use one retention policy. The default is 365 days. The
dedicated worker deletes at most 500 expired rows per pass, oldest first, so cleanup remains bounded;
worker downtime or a large backlog can delay deletion beyond the configured age while later passes
catch up.

Set `AUDIT_EVENT_RETENTION_DAYS` to an integer from 1 to 3650 to override the default. Indefinite
retention requires the exact value `disabled`; a blank value keeps the default, and malformed values
fail the worker pass instead of silently changing policy.

Audit rows can retain event type, timestamp, opaque record identifiers, counts, and minimized
operational detail until expiry. They must not contain route coordinates, imported filenames,
Nextcloud tokens or paths, credentials, reset tokens, or health-note text. Activity deletion removes
or redacts activity-linked audit payloads. A user data export includes only audit rows that remain at
the time of export; exporting does not reset their expiry.

## GPX Handling

Real GPX, FIT, and TCX samples must go in `samples/`, which is ignored by git.

Importer behavior should:

- avoid logging raw coordinates;
- discard original GPX bytes after parsing;
- retain only bounded route and heart-rate display series, with an explicit route-retention control;
- make delete/export possible through authenticated settings controls;
- scope duplicate-detection hashes to the user so they are not stable cross-user fingerprints;
- document whether elevation, heart rate, and timestamps are stored.

An approved device-folder source is a browser-local capability, not a server path. Store its
`FileSystemDirectoryHandle` and only hashed handled-file markers in IndexedDB, scoped to the signed-in
runway user. Never store or log local filenames, transmit the original filename, request write access,
or scan another user's saved handle. IndexedDB is origin-wide rather than account-isolated: on
authenticated app startup, delete handles and markers for every other user id; before sign-out, delete
the entire device-folder database and stop sign-out if another open tab blocks that cleanup. Before
deleting all imported activity data, stop any active scan and remove the current handle. The server
also advances a per-user import generation inside the deletion transaction. Manual, share,
device-folder, and Nextcloud imports capture that generation before parsing or remote I/O; the final
recording transaction locks the profile row and rejects stale work. This is the authoritative barrier
against another tab, process, or worker recreating activity after deletion.

Scan only in a visible authenticated page, inspect at most 2,000 direct children and 500 GPX candidates,
fingerprint candidates sequentially, process at most one file per foreground check, and use the same
server-side size, parsing, deduplication, deletion-tombstone, and review-only controls as other PWA
imports. Changing the approved directory clears the old handled markers. A malformed, oversized, or
future-dated file is marked handled so it cannot starve older valid exports; reconnecting the folder
deliberately clears those markers for a retry. Disconnect deletes the local handle and markers but does
not alter Gadgetbridge files. Treat permission loss as an explicit reconnect state.

## Nextcloud Share Import

The preferred automated import source is a password-protected Nextcloud folder share. The app should not ask for a main Nextcloud account password. Full-account WebDAV can be considered later, but the default product path is a limited folder share.

Security rules:

- require a share password for Nextcloud share import;
- reject shares that still list files when probed with a deliberately wrong password, and accept only an explicit HTTP 401/403 response as proof that the password was rejected;
- seal the share token and password with `@hapi/iron` and never display either after save;
- use keyed blind indexes for source uniqueness and remote revision state; never persist raw WebDAV paths or imported filenames;
- do not log share URLs, share tokens, share passwords, remote paths, file contents, or route coordinates;
- require HTTPS share URLs in production;
- require `NEXTCLOUD_ALLOWED_ORIGINS` in production so server-side WebDAV is limited to exact HTTPS origins, including an explicit non-default port when one is used;
- do not follow WebDAV redirects while using share credentials;
- do not accept arbitrary download URLs as GPX sources;
- reject WebDAV XML containing a document type declaration and parse responses with entity processing disabled;
- only read `.gpx` files from the configured folder share;
- sync one file per run, choosing the newest eligible unhandled revision and then backfilling older files on later runs;
- treat a changed ETag, or changed last-modified/content-length metadata when no ETag is available, as a new revision at the same remote path;
- leave scheduled imports in the activity inbox for explicit review instead of auto-matching or mutating the plan;
- rely on keyed source item state, user-scoped content hashes, and deletion tombstones to avoid duplicate or deleted-content reimports;
- recheck the per-user import generation while holding the profile-row lock before recording fetched content;
- use atomic database claims and unique keyed-remote constraints so concurrent workers cannot import the same remote revision;
- expose clear disconnect behavior that removes credentials without deleting already imported activity history;
- when the user deletes imported activity data, disconnect import folders at the same time so scheduled sync cannot recreate the deleted records.

Red-team this flow with expired shares, wrong passwords, unprotected shares, non-folder shares, download-disabled shares, huge files, malformed XML, repeated syncs, overlapping workers, and the same GPX uploaded under a different name.

## Threat Model

| Risk                         | Control                                                                                                          |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| Account takeover             | Better Auth sessions, secure cookies, 2FA, passkeys, rate limiting, no custom password hashing.                  |
| Password reset abuse         | Generic reset responses, hashed single-use tokens, short expiry, rate limiting, audit events, safe email errors. |
| Cross-user data exposure     | Server-side user scoping on every query, no client-supplied ownership.                                           |
| Route privacy leak           | Do not log raw GPX; no public feeds/maps; the current release stores aggregate import data before route display. |
| Nextcloud share leak         | Seal tokens/passwords, blind-index remote state, allowlist exact origins, and do not log tokens/URLs/paths.      |
| Duplicate or stale imports   | Claims, user-scoped hashes, deletion tombstones, unique constraints, and a locked generation check.              |
| Health-adjacent overclaiming | Training logic flags risk and suggests conservative adjustments; it does not diagnose or treat.                  |
| CSRF/session misuse          | SameSite cookies, exact-Origin mutations, and a narrowly gated authenticated native-share navigation exception.  |
| Unsafe file upload           | GPX size limits, XML parsing without entity expansion features, aggregate extraction only.                       |
| Open registration surprise   | Local signups controlled by configuration. Operators can disable local auth or OIDC signup.                      |
| N+1 data leaks/perf collapse | Calendar, import, stats, settings, and history use bounded queries and aggregates.                               |

Trust boundaries:

- Browser to SvelteKit server.
- SvelteKit server to PostgreSQL.
- SvelteKit server to OIDC provider.
- SvelteKit server to SMTP provider or Proton Mail Bridge.
- SvelteKit server or worker to Nextcloud public-share WebDAV.
- Authenticated user to another user's data.
- Uploaded GPX file to importer/parser.
- OPNsense Caddy/Authentik/Cloudflare edge to the app container's dedicated VLAN address.

## Required Security Checks

- Auth routes tested for sign-in, sign-out, and protected-route redirect.
- Password reset tested for unknown email, duplicate requests, expired token, reused token, bad token, and successful reset.
- SMTP failure behavior tested without leaking tokens, credentials, or provider internals.
- User-owned tables checked for `userId` indexes.
- GPX parser tested against the private sample without printing route coordinates.
- Nextcloud share sync tested for newest-file-only behavior, repeated sync idempotency, same-content different-name dedupe, wrong password, expired share, non-folder share, no GPX files, and worker overlap.
- Browser review confirms no route data or personal details are visible before login.
- Security review confirms no sample exports are committed.
- Privacy review confirms default private behavior and no external map tiles.
