# Android App Architecture

The Android build is a full way to use runway, not a secondary companion. The launcher opens the
complete, instance-bound PWA in a Trusted Web Activity (TWA). Native Android code exists only where
the browser capability model is insufficient: durable Storage Access Framework grants, background
reconciliation, operating-system shares, and a small folder-settings surface.

The source lives in [`android/`](../android/). Android Browser Helper is pinned explicitly and its
fallback is a Custom Tab with visible origin controls, never an embedded WebView.

## Instance, package, and trust boundary

Each APK is built for exactly one runway origin using `-PrunwayOrigin=https://…`. Release instances
use normal HTTPS port 443 so Android App Link and TWA origin verification have one unambiguous host.
The Gradle build injects that origin into the TWA start URL, Android App Link filter, app-to-site asset statement, and
same-origin deep-link policy. Incoming HTTPS intents for another scheme, host, effective port, or
userinfo are ignored instead of being allowed into a full-screen shell.

The build also accepts `-PrunwayApplicationId=…`. The default `com.deftmartian.runway` value remains
pre-release. Independent self-hosters that sign and distribute their own APK should choose a stable
operator-owned id to avoid colliding with another signer or installation. Identity is the tuple of
application id, signing certificate, and instance origin; freeze all three before the first release.

TWA chrome is removed only when both sides agree:

- the APK contains an `asset_statements` relation to the configured web origin;
- that exact origin serves `/.well-known/assetlinks.json` relating
  `delegate_permission/common.handle_all_urls` to the installed application id and signing-certificate
  SHA-256 fingerprint.

The file must be available directly over valid HTTPS without authentication or redirects. Set
`ANDROID_APPLICATION_ID` and `ANDROID_CERTIFICATE_SHA256` on the runway web process to serve it; the
certificate variable accepts one or more comma-separated, colon-delimited SHA-256 fingerprints. Use
the release certificate, not an upload certificate or an unrelated debug certificate. Missing or
malformed configuration returns 404 instead of publishing a guessed trust statement. If verification
fails or no capable TWA browser is installed, runway remains usable in a Custom Tab with browser UI.
That fallback is a security property and must not be replaced by WebView.

## One product, two capability surfaces

The TWA contains the full runway product: onboarding, plan, calendar, run detail, import review,
history, stats, settings, and authentication. The native folder activity is only a system-capability
sheet. It does not duplicate accounts, plans, runs, history, or navigation. Its resource-backed
layout follows the system light/dark theme, scales with Android font settings, exposes headings and
live status regions to accessibility services, and keeps one primary action that changes from
account connection to folder selection to **Check now**.

After the first launch, Android publishes a **Folder** launcher shortcut. The folder sheet is also
registered for `runway-native://folder`. When the server's Android application id and certificate are
configured, the PWA opens it with a package-bound `intent://` link so another app cannot claim the
custom scheme. Without that release identity, the UI directs the runner to the **Folder** launcher
shortcut instead. The sheet always provides **Back to runway**, which returns to the TWA launcher.
Android's app-storage management entry points to the same sheet.

There is intentionally no JavaScript bridge. The TWA page and native worker do not share browser
cookies. Native/server communication uses the pairing API described below.

## Folder and share flow

`ACTION_OPEN_DOCUMENT_TREE` returns the directory URI. The app calls
`takePersistableUriPermission()` with the read flag and stores only that URI in private preferences.
It releases the old grant when switching folders, checks Android's persisted-grant list before every
scan, and asks the user to choose again after revocation.

Scans restart from the folder root and inspect at most 10,000 direct entries. They never resume at a
cursor offset because Storage Access Framework providers do not promise stable row ordering between
queries. Durable keyed revision/content markers make restart-and-dedupe safe. Candidate selection
accepts GPX-specific MIME types or a `.gpx` name from common providers and rejects known empty or
over-10 MB files before opening them. The Folder screen reports the hard limit if a selected directory
is larger, instead of implying files beyond it were checked.

Share intake accepts exactly one `content://` URI with a read grant, performs a bounded streaming read
off the UI thread, and does not log names, URIs, XML, coordinates, or metadata.

WorkManager provides eventual reconciliation, not a filesystem watcher. Reopening or returning to the
Android launcher enqueues a constrained one-time check. Periodic work has a 15-minute minimum interval
and Android may defer it for battery, network, or OEM policy. Product copy must not promise immediate
import; the native sheet retains an explicit **Check now** action. Each worker handles at most one GPX,
but one trigger may chain up to eight bounded workers to reduce a known backlog. The sheet shows the
last outcome and a lower-bound backlog count.

The app requests no location, activity-recognition, broad storage, contacts, advertising, or Play
Services permission, and it requests no dangerous or runtime permission. The source manifest declares
internet access. WorkManager contributes the normal `ACCESS_NETWORK_STATE`, `WAKE_LOCK`,
`RECEIVE_BOOT_COMPLETED`, and `FOREGROUND_SERVICE` permissions needed for constrained, reliable
reconciliation plus an app-scoped signature permission for safe dynamic receivers. The build gate
checks the merged artifact against that exact allowlist and fails on any unexpected addition.

## Native upload and authentication boundary

The implemented pairing and upload flow is:

1. A signed-in PWA session creates a cryptographically random, ten-minute, single-use pairing code.
   Persistent per-user and per-address limits bound code creation and exchange attempts.
2. The runner enters that code and a device label in the native Folder screen. No credential is put
   in a URL, copied through JavaScript, or derived from an account password or browser cookie.
3. The server consumes the code under a row lock and returns a random 256-bit bearer credential once.
   It stores only the SHA-256 token hash, user/device binding, one-year expiry, revocation state, and
   non-sensitive timestamps.
4. Android encrypts the credential with AES-GCM under a non-exportable Android Keystore key. App
   backup and device transfer exclude every runway data domain.
5. The credential is accepted only by `/api/android/status` and `/api/android/import`. Import requires
   a GPX-specific content type, UUID request id, SHA-256 content digest, and no content encoding. The
   server authenticates and rate-limits before reading at most 10 MB from the request stream.
6. The request id is claimed before parsing while the user's account row is locked and the device is
   revalidated. This closes the gap between initial bearer authentication and concurrent device
   revocation or privacy deletion. Completed receipts return the original stable result; an id reused
   for different content is rejected. Raw digests are converted to user-scoped HMAC keys before
   storage.
7. The native worker retains only keyed handled/revision markers, a bounded settling observation, and
   a pending request id. It observes the same bounded content revision twice at least 30 seconds
   apart before upload, including when provider modification time is missing. Content digest is the
   authoritative handled identity; a document URI plus size and modification time only accelerates
   scans. Weak provider revisions are rechecked, so overwriting one document URI can produce a new
   import instead of being hidden by a permanent URI marker. It marks `imported`, `duplicate`, and
   `quarantined` outcomes terminal and retries transient failures. The server still applies parser,
   duplicate, tombstone, route-retention, time zone, Review-state, and import-generation controls.
8. A device can be revoked from Import sources. Deleting imported activity data revokes all active
   Android devices in the same database transaction before deleting the activity rows.

Folder selection carries a monotonically changing local generation. Switching or disconnecting the
folder and forgetting or losing the credential cancel both periodic and queued one-time work. A worker
reloads the credential, tree URI, and generation immediately before upload, so work already reading an
old grant cannot send after the account or folder changes.

The short code proves possession of the authenticated browser-approved pairing value; it is not
hardware attestation. A distribution that needs stronger managed-device assurance should replace the
exchange with a reviewed hardware-backed proof or standard authorization protocol without changing
the scoped import boundary. Do not add a JavaScript bridge, password capture, browser-cookie copy,
bearer URL, or non-expiring general API key.

## Commercial-grade gates

The Android app is not ready for external release until there is evidence for:

- final package/origin/signing ownership, key backup/recovery, Android developer registration, and a
  supported update window;
- correct Digital Asset Links verification using the actual F-Droid release signer plus negative tests
  proving another origin/signature falls back to browser UI;
- a reviewed decision on whether short-code proof is sufficient or hardware-backed device proof is
  required, plus credential renewal UX before the one-year expiry;
- request idempotency across process death, network loss, retries, reinstall, and concurrent workers;
- encrypted credentials and a threat model for malicious providers, shares, servers, intents, backups,
  logs, screenshots, browser fallback, and rooted devices;
- parser corpus/fuzz coverage and adversarial Android provider/stream tests;
- upgrade, deep-link, share, TWA fallback, permission-revocation, offline, and background tests on the
  supported Android range, Pixel, Samsung, and one aggressive background-management OEM;
- accessibility, dark theme, small screen, large text, keyboard, screen reader, and localization passes
  across both the PWA and native folder sheet;
- honest status UI distinguishing discovered, uploaded, accepted, duplicate, quarantined, and rejected;
- signed reproducible candidates, dependency/SBOM and license review, vulnerability scanning, and an
  F-Droid install/update/rollback drill;
- privacy policy, support contact, incident response, release notes, and opt-in diagnostics that exclude
  file names, URIs, coordinates, GPX bytes, auth material, and training or injury data.

The mobile API now supports real review-only imports. External distribution still waits on the
remaining signing, device-matrix, upgrade, accessibility, incident-response, and release-evidence
gates above.

## Local and continuous verification

The static contract and the real Android build are separate gates. Run both after any native,
manifest, Android resource, pairing/import API, or Digital Asset Links change:

```sh
corepack pnpm verify:android
corepack pnpm verify:android:build
corepack pnpm verify:android:release
```

The build command runs Gradle `lint`, `test`, and `assembleDebug` against the non-routable
`https://runway.example.test` build origin. The release-contract command separately proves placeholder
origins are rejected, a release-shaped identity passes, normal release packaging fails without
`android/signing.properties`, and the explicit F-Droid path can produce only an unsigned source-built
artifact without private material. The check points Gradle at an isolated nonexistent signing file,
so it never reads an operator's local key configuration. Both commands verify their merged manifest
contains only the reviewed normal operational permissions. Neither command produces a directly distributable release. The
repository check workflow runs the same commands with JDK 17, Android platform 36, and build tools
36.0.0. Emulator, physical-device, TWA association, and accessibility evidence remain explicit release
gates rather than being implied by a successful build.
