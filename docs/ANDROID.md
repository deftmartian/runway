# Android App Architecture

runway's Android build is a full native Jetpack Compose client, not a PWA wrapper, Custom Tab, or
WebView. It is one product with the responsive web client: both operate on the same plans, activity
review records, history, privacy settings, and account. Android owns normal product navigation and
uses a small versioned API rather than rendering web pages inside the app.

The system browser remains part of the security design, but not the product surface. Android opens
it only to approve Better Auth device authorization and for account-security operations that need a
fresh browser session. Browser cookies, passwords, and web content are not shared with the native
app.

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

After the server handshake, Android starts Better Auth device authorization for the registered
`runway-android` client. The runner approves a short user code in the system browser while signed in
to their selected server. Android polls only the device-authorization endpoint, receives an
origin-bound bearer session after approval, and stores it with encrypted Android app state.

The native client calls `/api/mobile/v1` with that bearer session. It never uses a password, copies a
browser cookie, or places a bearer in a URL. Mobile mutations require a bounded JSON body and a
stable `Idempotency-Key`; the server stores a user-scoped receipt and returns the original result for
a retried request. Reusing a key with different content fails. The client makes one bounded retry and
asks the runner to refresh rather than blindly repeating an uncertain mutation.

The ordinary native surface includes onboarding, today, calendar, workout adjustments, review,
progress/history, privacy/settings, sign-out, and selected server state. Risky workout changes use
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
- Device authorization, account session, folder state, and import state are separately scoped.
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
modes, device authorization, session expiry/revocation, server switch, GPX share/folder retry,
Health Connect permission/revocation/route consent, WorkManager deferral, large text, TalkBack, and
at least one physical device. A green build alone does not establish those behaviours.
