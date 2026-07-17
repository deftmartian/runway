# Deployment

This document covers the public runway deployment through the existing OPNsense Caddy edge, with Cloudflare, Authentik, and PostgreSQL. Do not paste real secrets into tickets, logs, screenshots, or support threads.

## Production Shape

The Docker image runs the SvelteKit Node adapter on port `4100`. PostgreSQL runs separately. OPNsense Caddy, Cloudflare, and Authentik sit in front of the app in deployment; runway does not run a second reverse proxy.

Compose can run a web container plus a worker container for scheduled Nextcloud sync. Both application containers run as the unprivileged Node user with a read-only root filesystem, all Linux capabilities dropped, and privilege escalation disabled. Keep PostgreSQL on a private network and set a real database password.

## Required Environment

Set these before running the production app:

- `NODE_ENV=production`
- `APP_DATABASE_URL` (the production overlay maps this to the containers' `DATABASE_URL`)
- `BETTER_AUTH_SECRET`
- optional `BETTER_AUTH_SECRETS` during a staged key rotation
- `ORIGIN=https://<runway-host>`
- `PUBLIC_APP_ORIGIN=https://<runway-host>`
- `PUBLIC_SOURCE_URL=https://<source-host>/<repository>` for the corresponding source of the deployed version
- `IMPORT_SECRET_KEY` or a strong `BETTER_AUTH_SECRET`
- `NEXTCLOUD_ALLOWED_ORIGINS=https://<nextcloud-host>[:port]` before enabling share sync
- `BODY_SIZE_LIMIT=12M` so SvelteKit allows runway to validate GPX files against its 10 MB app limit
- `RUNWAY_BUILD_ID=<image commit SHA>` so releases have an operator-visible, deterministic PWA revision
- `RUNWAY_IPV4_ADDRESS`, `IPVLAN_SUBNET`, `IPVLAN_GATEWAY`, `IPVLAN_PARENT`, and `DNS_SERVER` for the production `ipvlan` network
- optional `IPVLAN_NETWORK_NAME` when the default `runway-edge` name is not suitable

The base `compose.yaml` keeps a known loopback-only PostgreSQL password fallback for local development.
It is not the production entrypoint. Production commands must include
`-f compose.yaml -f deploy/compose.production.yaml`; that overlay refuses to render unless
`APP_DATABASE_URL`, `POSTGRES_PASSWORD`, `BETTER_AUTH_SECRET`, both public origins,
`RUNWAY_BUILD_ID`, and the production network settings are explicit.

Local account signups should stay closed unless the operator is intentionally onboarding a user:

- `ALLOW_LOCAL_SIGNUPS=false`
- `LOCAL_AUTH_ENABLED=true` if local fallback is allowed
- `ALLOW_OIDC_SIGNUPS=false` except during an intentional OIDC enrollment window

## Better Auth Secret And Encrypted Provider Tokens

`BETTER_AUTH_SECRET` is recoverable key material, not a disposable application setting. runway sets
Better Auth's `account.encryptOAuthTokens` option, so the installed Better Auth 1.6.x line encrypts
OAuth access and refresh tokens before writing them to the `account` table. The generic OAuth plugin
does not pass the ID token through that encryption path, so runway deliberately discards the ID token
after Better Auth validates it instead of persisting it in plaintext. Do not change either behavior
without a new source-level and database-level review. Better Auth's
[versioned secrets](https://better-auth.com/docs/reference/options#secrets) are the supported rotation
mechanism.

The same Better Auth key material also protects TOTP secrets, encrypted recovery codes, OAuth state,
and some cookie data. Local password hashes and passkey public credentials do not need the old
reversible key. If `IMPORT_SECRET_KEY` is blank, runway additionally uses `BETTER_AUTH_SECRET` to seal
Nextcloud share credentials. Set a dedicated `IMPORT_SECRET_KEY` and reconnect existing import
sources under it before retiring an auth key. `AUTH_RATE_LIMIT_SECRET` can likewise keep HMAC-keyed
security-rate-limit identities independent of auth-key rotation.

### Backup And Restore Contract

- Keep the PostgreSQL backup, image build ID, and a version manifest for the required keys. Store the
  actual secrets in a restricted secret manager or offline recovery store, separate from the database
  backup. A database backup alone cannot restore encrypted provider or TOTP data; a database backup
  plus its keys is highly sensitive.
- Record the active `BETTER_AUTH_SECRETS` version numbers and order without copying their values into
  the runbook, backup file name, CI output, or logs. The first entry is the key for new encryption.
- Preserve every key still referenced by restored ciphertext. Do not assume that retaining only the
  newest secret is enough.
- Restore into an isolated environment first, with scheduled imports and outbound callbacks disabled.
  Supply the matching key set before the first application boot so a test process cannot overwrite
  records with the wrong key configuration.

Restore verification checklist:

1. Restore the matching database backup and image build ID on an isolated network.
2. Inject `BETTER_AUTH_SECRET`, the complete active `BETTER_AUTH_SECRETS` keyring, and any separate
   import and rate-limit keys from the recovery store. Confirm version labels, never secret values, in
   the change record.
3. Run migrations only if the restored image expects them, then confirm `/health/ready` without
   enabling the import worker.
4. Run the count-only ciphertext query below for the expected current version. Do not select or print
   token, TOTP-secret, recovery-code, share-URL, or share-password columns.
5. With a designated test account, verify local sign-in, TOTP, and OIDC sign-in as applicable. Confirm
   only success or failure in the record. Never capture the provider token response or database value.
6. Prove rollback by stopping the isolated app and reopening the same restored copy with the original
   image and key configuration. Destroy the restore after the check.

### Planned Rotation

Replacing `BETTER_AUTH_SECRET` directly is destructive: legacy bare-hex ciphertext has no key-version
marker and cannot be decrypted with a different secret. Removing a versioned key similarly makes
`$ba$<version>$...` records that reference it unreadable. A new deployment may also invalidate
sessions, trusted-device state, or in-flight OAuth flows. Use a maintenance window and assume users
will sign in again.

For a routine rotation from key `K1` to `K2`:

1. Take and verify a database backup. Confirm `K1` is recoverable. Record the current image build ID.
   Finish or cancel active sign-in and TOTP-enrollment flows.
2. Generate `K2` with at least 32 high-entropy characters. Deploy every app and worker replica with
   `BETTER_AUTH_SECRET=K1` and `BETTER_AUTH_SECRETS=2:K2,1:K1`. The singular secret is the fallback for
   pre-versioned ciphertext; the first plural entry encrypts new values. Never place the literal
   values in shell history or the change ticket.
3. Confirm health, then complete OIDC re-authentication for each linked account. The callback rewrites
   access and refresh tokens it receives under version 2. A provider may omit a refresh token on
   repeat authorization, so the count-only query—not a successful browser redirect—is the retirement
   gate. If an old refresh-token count remains, revoke that account's Authentik grant and authorize it
   again while both keys are still available.
4. TOTP verification does not re-encrypt its stored secret. While `K1` remains available, each TOTP
   user must disable and re-enable TOTP, then replace their saved recovery codes. Alternatively, keep
   `K1` as a decryption-only key; do not claim it has been retired.
5. Query only counts, substituting the actual current version number:

   ```sql
   select
     count(*) filter (where access_token is not null and access_token not like '$ba$2$%') as access_not_current,
     count(*) filter (where refresh_token is not null and refresh_token not like '$ba$2$%') as refresh_not_current,
     count(*) filter (where id_token is not null) as persisted_id_tokens
   from "account"
   where provider_id <> 'credential';

   select
     count(*) filter (where secret not like '$ba$2$%') as totp_not_current,
     count(*) filter (where backup_codes not like '$ba$2$%') as recovery_codes_not_current
   from "two_factor";
   ```

   Every count must be zero before removing `K1`. These queries reveal only counts; never replace them
   with a token-value dump.

6. Revoke runway sessions and trusted-device grants in a reviewed maintenance transaction, then
   require fresh sign-in. If policy requires old provider grants to stop working, revoke them in
   Authentik as well and reauthorize after revocation.
7. Retire `K1` by deploying all replicas with `BETTER_AUTH_SECRET=K2` and
   `BETTER_AUTH_SECRETS=2:K2`. Repeat health, local/TOTP/OIDC sign-in, ciphertext-count, logout, and
   rollback checks before destroying the pre-rotation backup on its retention schedule.

For the next rotation, advance the version (`3:K3,2:K2`) instead of reusing `2`. Keeping an old key in
the plural list is supported decryption, not retirement; Better Auth does not automatically sweep and
re-encrypt every stored record.

### Compromised-Key Rotation

If `K1` may be compromised, do not put it in the new keyring merely to avoid disruption. First block
new auth traffic, revoke Authentik grants and active runway sessions, and preserve forensic evidence.
Deploy only the new key, clear unreadable stored OAuth token columns without deleting the provider
account linkage, and require OIDC reauthorization. Remove old TOTP records and require TOTP plus
recovery-code enrollment again. Any Nextcloud source sealed through the old fallback must be
disconnected and reconnected under a new explicit `IMPORT_SECRET_KEY`. Verify that no old ciphertext
or persisted ID token remains. This is intentionally disruptive because the old key and every bearer
token it could decrypt must be treated as exposed.

## Authentik OIDC

Create an Authentik OAuth2/OpenID provider for runway:

- flow: authorization code;
- client type: confidential;
- PKCE: required when available;
- scopes: `openid`, `profile`, `email`;
- issuer URL in runway: `OIDC_ISSUER=https://<authentik-host>/application/o/<provider-slug>/`;
- client id in runway: `OIDC_CLIENT_ID`;
- client secret in runway: `OIDC_CLIENT_SECRET`;
- redirect URI: `https://<runway-host>/api/auth/oauth2/callback/authentik`.

Then create or attach an Authentik application using that provider.

OIDC enrollment is closed by default. To enroll the first intended account, set
`ALLOW_OIDC_SIGNUPS=true`, deploy, complete that account's Authentik sign-in, then immediately set it
back to `false` and redeploy. Do not leave it open as a substitute for an account-approval flow.

Verification:

- open `/login` and confirm the Authentik button appears;
- click it and confirm the redirect lands on the expected Authentik authorization URL;
- after callback, confirm the user reaches `/app`;
- confirm logout clears the runway session;
- test what happens when the same email exists as a local account and as an OIDC identity before opening this beyond trusted users.

If Authentik rejects the callback URI, inspect the generated authorization redirect and add the exact `redirect_uri` value it sends. Better Auth owns the callback route under `/api/auth`.

## SMTP And Password Reset

Password reset uses provider-neutral SMTP.

Required variables:

- `MAIL_ENABLED=true`
- `SMTP_HOST`
- `SMTP_PORT`
- `SMTP_TLS_MODE=starttls` for port `587`, or `tls` for port `465`
- `SMTP_USER` if the relay requires auth
- `SMTP_PASSWORD` if the relay requires auth
- `SMTP_FROM`
- optional `PASSWORD_RESET_RATE_LIMIT_SECRET`

For Proton Mail, use one of these paths:

- Proton custom-domain SMTP token, if available for the account/domain: `SMTP_HOST=smtp.protonmail.ch`, `SMTP_PORT=587`, `SMTP_TLS_MODE=starttls`, `SMTP_USER=<custom-domain address>`, `SMTP_PASSWORD=<SMTP token>`.
- Proton Mail Bridge on a trusted host, with runway pointed at that Bridge SMTP endpoint. Bridge commonly exposes SMTP on port `1025`; from Docker, the host must be reachable from the container, not `localhost` inside the app container.

Do not use the Proton account password as `SMTP_PASSWORD`.

Verification:

- request a reset for a known local account;
- confirm the email arrives and the reset link works once;
- confirm the same link cannot be reused;
- request a reset for an unknown address and confirm the UI does not disclose whether the account exists;
- confirm SMTP failures do not print credentials, reset tokens, or recipient-specific provider responses.

## Nextcloud Share Sync

Use a password-protected public folder share that contains GPX exports. Do not enter a main Nextcloud account password into runway.

Nextcloud setup:

- create a folder for GPX exports;
- create a public share link for that folder;
- require a share password;
- keep downloads enabled;
- keep the share active;
- do not expose unrelated files in the same share.

Runway setup:

- set `NEXTCLOUD_ALLOWED_ORIGINS` to the exact Nextcloud HTTPS origin in production, including a non-default port when used;
- connect the share URL and password from `/app/import`;
- use "Test" to verify the folder can be listed;
- use "Sync now" to verify the newest unimported GPX imports once.

Verification:

- wrong password is rejected;
- unprotected share is rejected;
- repeated sync does not import the same file revision twice and later runs backfill older unhandled files;
- replacing a file at the same path with a changed ETag or content metadata imports the new revision;
- the same GPX under a different file name is skipped by content hash;
- deleting one imported activity writes a hash-only tombstone so a connected source cannot recreate it;
- deleting all imported activity data disconnects import sources so scheduled sync cannot recreate deleted records.

## Gadgetbridge Device Folder

Recent Gadgetbridge builds can automatically export a GPX after a GPS activity sync. In Gadgetbridge,
open **Settings → Automations → Auto export GPX tracks**, choose a user-visible export directory, and
select the devices that should export. Then install runway from its public HTTPS origin, open
`/app/import`, choose **Allow device folder**, and select that same directory.

The browser must expose the directory through Android's document picker. App-private or protected
directories may not be selectable on every Android build; choose a dedicated user-visible export
directory when possible. The installed PWA checks the direct children of the approved directory when
runway opens or returns to the foreground. It imports at most one unhandled GPX per check, newest first,
and never modifies the directory. It cannot run while closed. If access is revoked, choose the folder
again from Import. Browsers without persistent directory access should use Android Share or the
Nextcloud source instead.

Use a dedicated export directory. runway refuses to enumerate beyond 2,000 direct entries or 500 GPX
candidates in one check. Invalid, oversized, and future-dated files are skipped so they cannot block
older exports; correct the source problem and reconnect the directory to retry them. Switching folders
also clears the local handled-file markers. Signing out clears the browser's retained directory
capability; another open runway tab can block sign-out until that tab is closed.

Verify this on the actual Android device and public HTTPS origin: link the export directory, let
Gadgetbridge write a GPX, background and foreground runway, confirm one review-only activity appears,
then confirm repeated focus does not duplicate it. Also verify permission revocation, account switching,
disconnect, folder switching, future-dated and malformed GPX quarantine, the entry bounds, the 10 MB
limit, and local capability deletion on sign-out and privacy deletion.

## Reverse Proxy And Network Edge

An external HTTPS reverse proxy can own the public edge; runway does not need a local Caddy container
or host proxy. The production Compose overlay attaches the web container to an operator-configured
address on a dedicated `ipvlan`; the worker, migration service, and PostgreSQL remain on the internal
bridge. Configure the reverse proxy to use this upstream:

```text
http://<RUNWAY_IPV4_ADDRESS>:4100
```

The firewall should allow that target only from the reverse-proxy path and intended operator networks.
Do not publish the app or database through the Docker host in production. Keep any CDN proxy ranges
current at the firewall, enable strict trusted-proxy handling, and replace the upstream
`X-Forwarded-For` value with the single derived client address. Compose configures adapter-node with
`ADDRESS_HEADER=x-forwarded-for` and `XFF_DEPTH=1` for that one-hop reverse-proxy-to-app contract. Do
not allow other network clients to reach the app and supply their own forwarded header.

The application generates a fresh CSP nonce per response. Do not replace its CSP at the edge with one
static header. If the edge adds a CSP, it must preserve the response nonce and be at
least as strict as the application policy.

Password-reset links arrive at `/login/reset-password?token=...` once before runway moves the bearer
token into an HttpOnly, path-scoped cookie and redirects to the clean URL. Configure reverse-proxy and
runtime/error log formats to delete the `token` query value, including on upstream failures. Apply the
equivalent rule to CDN logs, request tracing, WAF event exports, and any upstream log processor—or
omit query strings entirely for this path. Send a disposable reset request
after deployment and verify the raw edge and origin log output; the token value must not appear
anywhere.

Expected edge behavior:

- HTTPS only;
- HSTS on the public host;
- `X-Content-Type-Options: nosniff`;
- `X-Frame-Options: DENY`;
- `Content-Security-Policy` at least as strict as the app baseline;
- `Permissions-Policy` disabling camera, microphone, and geolocation;
- `Referrer-Policy: strict-origin-when-cross-origin`, with reset links protected by `no-referrer`;
- long-lived immutable cache for hashed SvelteKit assets under `/_app/immutable/`;
- no shared caching for `/app`, `/login`, `/logout`, and `/api/auth`.

Run after deployment:

```sh
SITE_URL=https://<runway-host> corepack pnpm verify:preview
```

On an HTTPS URL this command also requires HSTS, unique response nonces, live/ready health, a
deployment-specific service-worker revision, same-origin login redirects, and cross-site POST
rejection. A direct HTTP origin check intentionally cannot prove the TLS/HSTS boundary.

## Docker And Migrations

This launch path assumes a clean, empty runway database. Verify the complete migration journal before
the first deployment:

```sh
corepack pnpm verify:migrations
corepack pnpm verify:compose:production
corepack pnpm verify:dependencies
RUNWAY_BUILD_ID=$(git rev-parse HEAD) docker compose -f compose.yaml -f deploy/compose.production.yaml build
```

`verify:dependencies` runs a digest-pinned Trivy release against the pnpm production dependency
graph and fails on known high or critical advisories. The scanner container receives only
`package.json` and `pnpm-lock.yaml` as read-only mounts; it downloads the vulnerability database but
does not print or upload the lockfile. Node, PostgreSQL, the Dockerfile frontend, the scanner, and
GitHub Actions are pinned to reviewed immutable digests or commit SHAs. Dependabot should remain
enabled so digest and version updates arrive as reviewable pull requests rather than silently moving
at build time.

Start or update the production stack with both files:

```sh
docker compose -f compose.yaml -f deploy/compose.production.yaml up -d --build --wait app worker
```

The production Compose stack runs the `migrate` service with `APP_DATABASE_URL` mapped to the
`DATABASE_URL` expected by Drizzle. That service must complete successfully before the app and worker
start. Do not run the host `db:migrate` command unless `DATABASE_URL` has been explicitly and safely
set to the intended production database.

The web container should serve user traffic. The worker container should run scheduled import sync. Both can use the same image with different environment flags.

Compose exposes three health checks:

- `/health/live` proves the Node process can answer;
- `/health/ready` proves PostgreSQL is reachable and its latest applied Drizzle migration exactly
  matches the build's journal;
- `/health/worker` proves the dedicated worker scheduler started and its last completed pass did not
  end in a top-level failure.

The worker starts one pass immediately and then checks on its interval. Scheduled import is opt-in:
only the worker service sets `IMPORT_WORKER_ENABLED=true`; the web service explicitly sets it false.
Each pass also deletes at most 500 audit events older than the retention window. The default is 365
days. Set `AUDIT_EVENT_RETENTION_DAYS` to an integer from 1 to 3650 to override it. Use the exact value
`disabled` only when indefinite retention is an explicit deployment decision; invalid values make the
worker pass unhealthy.

## Backup, Restore, And Rollback

Before every database or image change, take a PostgreSQL custom-format backup with `pg_dump -Fc`,
store it outside the Compose volume with access limited to the operator, and record the image build
ID. Periodically prove the backup by restoring it into a separate empty database with `pg_restore`
and running `/health/ready` plus a read-only application smoke check against that restored copy.

For an application-only regression, redeploy the previous image build ID. For a database-changing
release, restore the pre-release backup into a new database and point the previous image at that
restored database; do not try to reverse Drizzle SQL in place. Keep the failed database intact until
the incident is understood. Never print backup contents or database URLs into CI logs.

## PWA Verification

The LAN preview is not proof of passkeys, installability, share-target integration, or secure-cookie behavior. Verify PWA behavior on the HTTPS origin and intended devices:

- manifest has stable identity/scope, separate any/maskable 192px and 512px icons, install screenshots, and Calendar/Import/Stats shortcuts;
- Apple touch icon and installed safe-area layout render cleanly on iOS/iPadOS;
- service worker registers;
- Settings offers installation only when the browser reports the app installable;
- offline navigation shows the public offline shell;
- authenticated pages are not cached for offline reading;
- a new build presents `Update ready`, refuses to reload over dirty or pending forms, and activates after explicit confirmation;
- sharing one GPX from a supported operating-system share sheet imports it into the authenticated activity inbox as review-only, while signed-out, malformed, duplicate, and oversized shares fail safely.

The service worker uses SvelteKit's build version for cache names and waits unless the runner accepts
the in-app update action. `RUNWAY_BUILD_ID` must change for every deployed image; a commit SHA is the
preferred value. Confirm `/service-worker.js` exposes the new revision, the update notice appears in
an already-open installation, and old `runway-*` caches are removed only after activation.
