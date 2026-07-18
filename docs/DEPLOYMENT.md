# Deployment

This document covers the public runway deployment through the existing OPNsense Caddy edge, with Cloudflare, Authentik, and PostgreSQL. Do not paste real secrets into tickets, logs, screenshots, or support threads.

## Production Shape

The Docker image runs the SvelteKit Node adapter on port `4100`. PostgreSQL runs separately. OPNsense Caddy terminates the public connection and proxies to runway; Cloudflare may proxy the public host. Authentik is runway's OIDC identity provider, not a second `forward_auth` gate. runway does not run another reverse proxy.

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
- optional `ANDROID_CREDENTIAL_SECRET` to keep Android pairing and import-receipt HMACs independent
  of auth-key rotation; otherwise a strong `BETTER_AUTH_SECRET` is used
- `ANDROID_APPLICATION_ID` and `ANDROID_CERTIFICATE_SHA256` when distributing the Android app; these
  make runway serve the exact Digital Asset Links statement at `/.well-known/assetlinks.json`
- `NEXTCLOUD_ALLOWED_ORIGINS=https://<nextcloud-host>[:port]` before enabling share sync
- `BODY_SIZE_LIMIT=12M` so SvelteKit allows runway to validate GPX files against its 10 MB app limit
- `RUNWAY_IMAGE=ghcr.io/deftmartian/runway:sha-<full-commit-sha>` or an immutable image digest

Standard production installs publish the app to `127.0.0.1:4100` for a reverse proxy on the Docker
host. `RUNWAY_BIND_ADDRESS` and `RUNWAY_PORT` can change that listener. Do not bind the app to every
host interface unless a firewall or trusted network prevents clients from bypassing the proxy.

The optional `deploy/compose.ipvlan.yaml` overlay follows the homelab stack convention: set
`VLAN_TAG`, `VLAN_TRUNK_INTERFACE`, and `DNS_SERVER`. It creates `vlan<VLAN_TAG>_runway` on
`192.168.<VLAN_TAG>.0/24`, uses `<VLAN_TRUNK_INTERFACE>.<VLAN_TAG>` as its parent, and assigns the app
the address ending in `.10`.

The base `compose.yaml` keeps a known loopback-only PostgreSQL password fallback for local development.
It is not the production entrypoint. Production commands must include
`-f compose.yaml -f deploy/compose.production.yaml`; that overlay refuses to render unless
`APP_DATABASE_URL`, `POSTGRES_PASSWORD`, `BETTER_AUTH_SECRET`, both public origins,
and `RUNWAY_IMAGE` are explicit. Add `-f deploy/compose.ipvlan.yaml` only for the optional VLAN
deployment.

Local account signups should stay closed unless the operator is intentionally onboarding a user:

- `ALLOW_LOCAL_SIGNUPS=false`
- `LOCAL_AUTH_ENABLED=true` if local fallback is allowed
- `ALLOW_OIDC_SIGNUPS=false` except during an intentional OIDC enrollment window

### Database Runtime Limits

The web and worker use separate bounded PostgreSQL pools. Compose defaults to 10 web connections and
5 worker connections through `APP_DATABASE_POOL_MAX` and `WORKER_DATABASE_POOL_MAX`. Keep their sum,
plus migration and operator capacity, below PostgreSQL's configured connection limit. A deployment
with multiple web or worker replicas must multiply the pool values by the replica count.

Both processes fail at startup when any of these settings is malformed or outside its safe range:

| Setting                                | Default | Allowed range    |
| -------------------------------------- | ------: | ---------------- |
| `DATABASE_POOL_MAX`                    |      10 | 1-50             |
| `DATABASE_CONNECT_TIMEOUT_SECONDS`     |      10 | 1-60 seconds     |
| `DATABASE_IDLE_TIMEOUT_SECONDS`        |      30 | 1-600 seconds    |
| `DATABASE_MAX_LIFETIME_SECONDS`        |    1800 | 60-86400 seconds |
| `DATABASE_STATEMENT_TIMEOUT_MS`        |   30000 | 1000-300000 ms   |
| `DATABASE_IDLE_TRANSACTION_TIMEOUT_MS` |   30000 | 1000-300000 ms   |

Compose maps the role-specific pool settings to `DATABASE_POOL_MAX`; direct non-Compose deployments
set `DATABASE_POOL_MAX` themselves. Statement and idle-transaction timeouts are PostgreSQL session
limits, so a stalled request releases database work instead of occupying a connection indefinitely.
Tune them only with evidence from slow-query logs and an intentional PostgreSQL connection budget.

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

`ANDROID_CREDENTIAL_SECRET` does the same for unconsumed ten-minute Android pairing codes and
user-scoped import receipt keys. Android bearer credentials are random and only their SHA-256 hashes
are stored; changing this HMAC key does not reveal or rotate those bearer credentials, but it
invalidates unconsumed pairing codes and prevents an old request id from replaying against its prior
receipt. Prefer a separate stable value before distributing an Android build.

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

For a complete installed Android experience with reliable access after the browser process is stopped,
use the instance-bound TWA design in [ANDROID.md](ANDROID.md). The APK must be built for this exact HTTPS
origin and signing identity, and the origin must publish matching Digital Asset Links. Native folder
access, scoped device pairing, bounded background upload, and review-only import are present. External
distribution still depends on the signing, device-matrix, accessibility, upgrade, and release-evidence
gates recorded there.

## Reverse Proxy And Network Edge

An external HTTPS reverse proxy can own the public edge; runway does not need to bundle its own proxy.
The standard production configuration publishes only the web app on the Docker host loopback. A
reverse proxy running on that host can use this upstream:

```text
http://127.0.0.1:<RUNWAY_PORT>
```

When the reverse proxy reaches the app over a dedicated VLAN instead, add
`deploy/compose.ipvlan.yaml` to the Compose command. That overlay removes the host-published app port
and gives the web container this upstream:

```text
http://192.168.<VLAN_TAG>.10:4100
```

Only the web container joins the VLAN; the worker, migration service, and PostgreSQL remain on the
internal bridge. The VLAN parent interface must already exist on the Docker host. The firewall should
allow the upstream only from the reverse proxy's source address. Never publish
PostgreSQL in production. Keep any CDN proxy ranges current at the firewall, enable strict
trusted-proxy handling, and replace the upstream
`X-Forwarded-For` value with the single derived client address. Compose configures adapter-node with
`ADDRESS_HEADER=x-forwarded-for` and `XFF_DEPTH=1` for that one-hop reverse-proxy-to-app contract. Do
not allow other network clients to reach the app and supply their own forwarded header.

Plain HTTP to the runway upstream is acceptable only on same-host loopback or a dedicated, strongly
isolated link restricted to Caddy and the app. If the hop crosses a shared, wireless, routed, or
otherwise untrusted network, use an internal HTTPS upstream with certificate validation or mTLS;
route, session, heart-rate, schedule, and pain data must not cross that hop in cleartext.

`deploy/Caddyfile.example` is the reference contract for a Caddyfile deployment. In the OPNsense
Caddy UI, map the same settings to the runway host and private HTTP upstream: terminate HTTPS at
Caddy, replace the upstream `X-Forwarded-For` value with Caddy's derived client address, and send the
public scheme and host. Do not expose the private upstream to ordinary clients.

By default Caddy ignores incoming `X-Forwarded-*` values when the immediate peer is not trusted. When
Cloudflare is in front, configure Caddy's global `servers.trusted_proxies` from Cloudflare's current
published CIDRs and enable `trusted_proxies_strict`, then pass `{client_ip}` as the single upstream
address. Review the current Caddy
[reverse-proxy header behavior](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers)
and [trusted-proxy options](https://caddyserver.com/docs/caddyfile/options#trusted-proxies) when the
edge topology changes. Do not copy CDN address ranges into this repository; keeping them current is
an edge/firewall operation.

At Cloudflare, use Full (strict) TLS, restrict the origin so only Cloudflare and operator traffic can
reach it, and bypass shared caching for authenticated, login, logout, and `/api/auth` routes. Treat
the current published Cloudflare address ranges as operational data: update the Caddy trust list and
origin firewall together, then verify the derived client address at the app.

The reference Caddy log filter replaces the password-reset `token` query value with `REDACTED`.
Configure the equivalent query filter in OPNsense and every CDN, WAF, tracing, and error-log layer.
After deployment, issue one disposable reset link, request it once, and inspect raw logs at every
layer. The real token must not appear; revoke the test session afterward.

The application generates a fresh CSP nonce per response. Do not replace its CSP at the edge with one
static header. If the edge adds a CSP, it must preserve the response nonce and be at
least as strict as the application policy.

Password-reset links arrive at `/login/reset-password?token=...` once before runway moves the bearer
token into an HttpOnly, path-scoped cookie and redirects to the clean URL. Configure reverse-proxy and
runtime/error log formats to redact the `token` query value, including on upstream failures. Apply the
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

## Published Image And Migrations

Successful image-affecting changes on `main` publish a tested AMD64/ARM64 image to
`ghcr.io/deftmartian/runway`. The publisher writes `latest` and `sha-<full-commit-sha>` tags; version
tags also publish the exact release tag and normalized semantic-version tags. The image carries OCI
source, revision, license, description, provenance, and SBOM metadata. Release tags run the browser
gate again and advance `latest` only after the same container verification succeeds. The tag
workflow creates or updates the matching GitHub Release only after the image-backed production stack
passes.

After the first successful publish, a repository owner must confirm that the linked `runway` package
is public in [GitHub's package settings](https://docs.github.com/en/packages/learn-github-packages/configuring-a-packages-access-control-and-visibility).
Public GHCR packages can be pulled anonymously; a package that remains private requires
authentication even though this source repository is public.

`latest` is useful for evaluation, but it moves. Production should record and deploy either the
full-SHA tag or the registry digest returned by `docker inspect`. Pull the selected artifact before
changing the running stack:

```sh
docker pull "${RUNWAY_IMAGE}"
```

This launch path assumes a clean, empty runway database. Verify the complete migration journal before
the first deployment:

```sh
corepack pnpm verify:migrations
corepack pnpm verify:compose:production
corepack pnpm verify:dependencies
corepack pnpm verify:image -- "${RUNWAY_IMAGE}"
```

`verify:dependencies` scans the pnpm production dependency graph; `verify:image` scans the selected
runtime image's OS packages and libraries. Both use the digest-pinned Trivy release and fail on fixed
high or critical advisories. The dependency scanner receives only `package.json` and `pnpm-lock.yaml`
as read-only mounts and never prints or uploads the lockfile. Node, PostgreSQL, the Dockerfile
frontend, the scanner, and GitHub Actions are pinned to reviewed immutable digests or commit SHAs.
Grouped weekly Dependabot updates keep those inputs reviewable, while the maintenance workflow
rechecks the unchanged lockfile and published image against newly disclosed advisories.

Start or update a standard production stack with the base file and production overlay:

```sh
docker compose -f compose.yaml -f deploy/compose.production.yaml up -d --wait app worker
```

For the optional VLAN deployment, add the network overlay:

```sh
docker compose -f compose.yaml -f deploy/compose.production.yaml -f deploy/compose.ipvlan.yaml up -d --wait app worker
```

The published image bundles the Drizzle SQL journal and a production-only migration runner. Compose
uses that same image for `migrate`, maps `APP_DATABASE_URL` to its `DATABASE_URL`, and requires the
migration to complete before app and worker start. It does not ship `drizzle-kit` or the development
dependency tree. Do not run the host `db:migrate` command unless `DATABASE_URL` has been explicitly
and safely set to the intended production database.

To validate a source checkout before it is published, build and select a local image explicitly:

```sh
docker build --build-arg "RUNWAY_BUILD_ID=$(git rev-parse HEAD)" --tag runway:local .
RUNWAY_IMAGE=runway:local docker compose -f compose.yaml -f deploy/compose.production.yaml up -d --wait app worker
```

Set the required production-overlay environment first. This exercises the local artifact under the
same fail-closed environment contract used for a published image.

The web container should serve user traffic. The worker container should run scheduled import sync. Both can use the same image with different environment flags.

Compose exposes three health checks:

- `/health/live` proves the Node process can answer;
- `/health/ready` proves PostgreSQL is reachable and its latest applied Drizzle migration exactly
  matches the build's journal;
- `/health/worker` proves the dedicated worker scheduler started and its last completed pass did not
  end in a top-level failure.

All three responses expose the semantic `release`, exact `build`, and backward-compatible `version`
build field. Record the health identity with the deployed image digest so an operator can distinguish
an old process from a failed rollout without inspecting private application data.

The worker starts one pass immediately and then checks on its interval. Scheduled import is opt-in:
only the worker service sets `IMPORT_WORKER_ENABLED=true`; the web service explicitly sets it false.
Worker health reports `starting` or `running` while a pass is legitimately active, fails an active pass
after 35 minutes, and fails when no successful pass has completed for 20 minutes. These limits are
longer than the normal five-minute interval and shorter than an indefinitely wedged scheduler. The
health payload contains only timestamps, ages, and scheduler state; it does not expose source names,
remote paths, credentials, or activity details.
Each pass also deletes at most 500 audit events older than the retention window. The default is 365
days. Set `AUDIT_EVENT_RETENTION_DAYS` to an integer from 1 to 3650 to override it. Use the exact value
`disabled` only when indefinite retention is an explicit deployment decision; invalid values make the
worker pass unhealthy.

## Backup, Restore, And Rollback

Before every database or image change, create a PostgreSQL custom-format backup outside the Compose
volume. The backup command refuses to overwrite a path, creates it with mode `0600`, streams database
bytes without printing them, and verifies the archive inventory before reporting success:

```sh
corepack pnpm db:backup -- /restricted-backups/runway-before-update.dump
```

The scripts use a transient client from the digest-pinned Compose PostgreSQL image. They read the
source in this order: `RUNWAY_BACKUP_DATABASE_URL`, `APP_DATABASE_URL`, `DATABASE_URL`, then the
rendered Compose app configuration. Keep URLs in the environment or restricted `.env`, never command
arguments or shell history. A dedicated backup role may be supplied through
`RUNWAY_BACKUP_DATABASE_URL`; it needs enough read access for `pg_dump`.

Periodically perform a real restore drill, not just an archive listing:

```sh
corepack pnpm db:backup:verify -- /restricted-backups/runway-before-update.dump
```

Verification creates a randomly named temporary database, restores the complete archive, checks the
required runway tables and exact migration journal, and drops the temporary database. The standard
Compose database owner can do this. A restricted backup role instead needs a separate same-cluster
`RUNWAY_BACKUP_ADMIN_DATABASE_URL` with permission to create and drop the temporary database. Budget
free disk for another full copy and run the drill away from peak load.

Recovery always targets a separately created, empty database. The restore command refuses the active
source database, refuses a non-empty target, refuses symlink or group/world-readable archives, restores
with ownership and grants removed, and verifies the migration journal before succeeding:

```sh
export RUNWAY_RESTORE_DATABASE_URL='postgres://restore-user:<password>@db:5432/runway_restore'
corepack pnpm db:restore -- /restricted-backups/runway-before-update.dump
unset RUNWAY_RESTORE_DATABASE_URL
```

If restore fails, discard the partially populated target instead of attempting to repair it in place.
After a successful restore, inject the matching key manifest, start the matching image with imports
disabled, confirm `/health/ready`, and run the read-only authentication checks from the restore
verification checklist above. The scripts never back up `BETTER_AUTH_SECRET`, `BETTER_AUTH_SECRETS`,
`IMPORT_SECRET_KEY`, `AUTH_RATE_LIMIT_SECRET`, or `ANDROID_CREDENTIAL_SECRET`; preserve that key
material separately as described in the contract.

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
the in-app update action. The container publisher bakes the source commit SHA into each image; local
source builds must pass `RUNWAY_BUILD_ID` explicitly. Confirm `/service-worker.js` exposes the new
revision, the update notice appears in an already-open installation, and old `runway-*` caches are
removed only after activation.
