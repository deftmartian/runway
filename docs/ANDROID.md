# Android App Architecture

runway's Android build is the primary, full native Jetpack Compose client, not a PWA wrapper, Custom
Tab, or WebView. It is one product with the responsive web client: both operate on the same plans,
activity review records, history, privacy settings, and account. Android owns normal product
navigation and uses a small versioned API rather than rendering web pages inside the app.

The system browser remains part of the security design, but not the product surface. Android opens
it for identity-provider or website-owned protocols: OIDC and passkey sign-in, password reset, and
new passkey registration. Browser cookies, passwords, and web content are not shared with the native
app, and the return app link contains no identity or credential.

## Server, package, and trust boundary

The universal APK asks for an origin only: no path, query, fragment, or credentials. Release builds
accept HTTPS; debug builds additionally accept cleartext loopback, `.local`, link-local, and private
network origins for development. Before saving an origin Android calls `GET /api/android/instance`,
follows no redirects, and requires the response to identify runway and support the app's native API
version.

The normalized origin is private app state. Account session, device-import credential, folder grant,
queued work, and local receipts are origin-scoped. A monotonically changing connection generation
prevents a late response from an old server from mutating the newly selected server's state.

Changing servers requires confirmation. Android verifies the proposed server, attempts to revoke the
old server's device-import credential, then clears old local session/import state and cancels old
work before committing the new origin. If the old server is offline, **Switch anyway** explicitly
leaves its server-side device record for later revocation. The transition never changes GPX files or
activity data already stored on either server.

The canonical source namespace and application id are `dev.deftmartian.runway`. A fork distributed by
another operator must use an operator-owned stable application id and signing key. There is no
origin-bound build variant.

## Native account session

After the server handshake, Android reads enabled sign-in methods from `GET /api/android/instance`.
Local signup, password sign-in, TOTP, and recovery-code verification use native forms. Passwords and
challenge codes are sent only to the selected server over its verified connection and are not
persisted by the app. The native client header plus the absence of a browser Origin lets the server
stamp the created Better Auth bearer session as `runway-android`; an unstamped bearer cannot call
the mobile API.

OIDC and passkey sign-in start Better Auth device authorization for the registered
`runway-android` client. The system browser owns provider or WebAuthn interaction. Android polls the
device-authorization endpoint and receives an origin-bound bearer session after approval. The fixed
app link only resumes that poll: cookies, device codes, identities, and bearer tokens never cross
it.

Android encrypts the accepted account session in private app state and calls `/api/mobile/v1` with
the bearer. Training and import-review mutations require a bounded JSON body and a stable
`Idempotency-Key`; the server stores a user-scoped receipt and returns the original result for a
retried request. Reusing a key with different content fails. The client makes one bounded retry and
asks the runner to refresh rather than blindly repeating an uncertain mutation. Account-security
operations instead use recent-authentication checks, tight request bounds, and persistent rate
limits; Android does not blindly replay an uncertain security change.

JSON is confined to the native network codec. `NativePayloadCodec` validates and converts server
responses into immutable Kotlin payload models, and converts sealed `MobileCommand` values back into
the server's mutation shapes. The ViewModel and Compose screens do not inspect `JSONObject` values or
assemble action names and payloads ad hoc. Screen code is separated by product surface and shared
components rather than collected in one application-wide file.

The ordinary native surface includes onboarding, today and next run, a month calendar and day
detail, workout adjustments, result recording, review, progress/history, imports, privacy/settings,
account security, sign-out, and selected server/build state. Local password and two-factor setup,
recovery-code replacement, session revocation, passkey rename/removal, export, and account deletion
are native. Password reset and passkey registration remain website-owned. Risky workout changes use
the same server-side preview and explicit confirmation boundary as the web client. The app must not
silently change a plan after an import, a short run, an overrun, or reported pain.

## Native imports and separate device credential

Background workers do not need—and must not receive—the general native account session. While the
runner is signed in, Android asks `/api/mobile/v1/android/pairing` for a ten-minute, single-use code
and immediately exchanges it through `/api/android/pair`; the runner never sees or copies the code.
The resulting `rwy1_` credential is limited to device status, bounded GPX import, and bounded Health
Connect changes. The server stores only its hash; Android encrypts it under a non-exportable Android
Keystore key, binds it to the selected origin, and supports revocation from the server. Deleting
imported activity data revokes active import devices before removing the activity rows.

### Folder and share flow

`ACTION_OPEN_DOCUMENT_TREE` provides one directory URI. Android takes only the persistable read grant
and stores it in private app state; no filesystem path, broad storage permission, or write access is
requested. A scan starts at the directory root, inspects at most 10,000 direct children, and rejects
known-empty or over-10 MB GPX candidates before opening them. Storage Access Framework providers do
not promise watcher semantics or stable enumeration, so runway restarts and deduplicates rather than
persisting a cursor.

**Check now** is the immediate user control. Returning to the app can enqueue a constrained check;
WorkManager provides eventual periodic reconciliation and Android may defer it. Each worker handles
at most one GPX and a trigger may chain bounded workers for a known backlog. The UI must report the
last outcome and never promise immediate background import.

The share receiver accepts exactly one granted `content://` URI, uses a bounded off-main-thread stream,
and does not log names, URIs, XML, coordinates, or metadata. All native imports enter the same
review-only parser and privacy/deletion barriers as a web upload. Import request receipts and scoped
content keys make network retries and process recovery safe.

### Health Connect

Health Connect is optional, read-only, and limited to running and treadmill-running sessions. After
explicit permission, runway reads exercise sessions and the metrics needed to summarize them:
distance, heart rate, speed, cadence, and elevation. It never writes, deletes, or administers Health
Connect data. The first foreground synchronization reads a bounded recent window; later runs use
provider changes. Background reads are separately permitted and routes are never read in background.
Route access requires per-record foreground consent. The server still applies the runner's route-data
privacy setting, retaining a redacted summary when private trace retention is disabled.

Imported sessions stay in Review until the runner accepts their role. They cannot mutate a plan by
themselves. Health Connect availability comes from its SDK status, not Play Store, OEM, or ROM
heuristics.

## Security and privacy controls

- Android accepts only selected, verified origins and follows no redirects during the instance check.
- Native authentication, device authorization, account session, folder state, and import state are
  separately scoped.
- The import credential is not an account token and cannot call the mobile planning API.
- Android requests no location, broad storage, contacts, advertising, or Play Services permission.
- Screens, workers, shares, and state transitions carry the server generation so stale work cannot
  cross an account or server handoff.
- GPX bytes, route coordinates, file names, provider URIs, credentials, and health data are not
  logged. Original GPX bytes are discarded after server parsing.

## Verification and release

Run the static contract and real build after a native, manifest, Android API, or server-selection
change:

```sh
corepack pnpm verify:android
corepack pnpm verify:android:build
corepack pnpm verify:android:release
corepack pnpm verify:android:version
```

The build gate runs Gradle lint, unit tests, debug assembly, and instrumentation assembly. The release
contract verifies selectable-server configuration and that a normal release refuses missing signing
material; the explicit F-Droid source-build path stays unsigned. Both inspect the merged manifest and
dependency verification metadata. CI does not replace emulator, device, accessibility, or real
background-work evidence.

Before an external release, record actual evidence for install and upgrade, selected-server failure
modes, native password/signup/TOTP, external OIDC/passkey authorization, session expiry/revocation,
server switch, GPX share/folder retry, Health Connect permission/revocation/route consent,
WorkManager deferral, large text, TalkBack, and at least one physical device. A green build alone
does not establish those behaviours.

The README's Android images were captured from the real Compose client on a Pixel 6-shaped API 35
AVD with a disposable account and synthetic plan. That pass exercised server selection and canonical
origin rejection, native account creation, password sign-in, all onboarding steps, Today, Calendar
and selected-day actions, Progress, account security, app relaunch, large text, and both system
themes. It does not claim physical-device, Health Connect provider, background scheduling, TalkBack,
OIDC, passkey, or TOTP coverage.
