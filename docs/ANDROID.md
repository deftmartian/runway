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

The file must be available directly over valid HTTPS without authentication or redirects. Use the
release certificate, not an upload certificate or an unrelated debug certificate. If verification
fails or no capable TWA browser is installed, runway remains usable in a Custom Tab with browser UI.
That fallback is a security property and must not be replaced by WebView.

## One product, two capability surfaces

The TWA contains the full runway product: onboarding, plan, calendar, run detail, import review,
history, stats, settings, and authentication. The native folder activity is only a system-capability
sheet. It does not duplicate accounts, plans, runs, history, or navigation.

After the first launch, Android publishes a **Folder** launcher shortcut. The folder sheet is also
registered for `runway-native://folder`; a package-bound `intent://` link can be exposed from the PWA
import page once device/browser behavior is verified. It always provides **Back to runway**, which
returns to the TWA launcher. Android's app-storage management entry points to the same sheet.

There is intentionally no JavaScript bridge. The TWA page and native worker do not share browser
cookies. Native/server communication must use the pairing API described below.

## Folder and share flow

`ACTION_OPEN_DOCUMENT_TREE` returns the directory URI. The app calls
`takePersistableUriPermission()` with the read flag and stores only that URI in private preferences.
It releases the old grant when switching folders, checks Android's persisted-grant list before every
scan, and asks the user to choose again after revocation.

Scans are bounded to 2,000 direct entries. Candidate selection accepts GPX-specific MIME types or a
`.gpx` name from common providers and rejects known empty or over-10 MB files before opening them.
Share intake accepts exactly one `content://` URI with a read grant, performs a bounded streaming read
off the UI thread, and does not log names, URIs, XML, coordinates, or metadata.

WorkManager provides eventual reconciliation, not a filesystem watcher. Periodic work has a 15-minute
minimum interval and Android may defer it for battery, network, or OEM policy. Product copy must not
promise immediate import; the native sheet retains an explicit **Check now** action.

The app requests no location, activity-recognition, broad storage, contacts, advertising, or Play
Services permission. The only manifest permission is internet access.

## Native upload and authentication boundary

The current skeleton does not upload. It reports `api_unavailable` until the server provides:

1. An authenticated TWA session starts a short-lived, single-use pairing request.
2. The native app proves possession of an app-generated key and the user confirms the named device.
3. The server issues a scoped, revocable credential limited to activity import and device status.
4. Android stores it with Keystore-backed encryption and never exposes it to web content.
5. Upload uses a bounded streaming request, client request id, and content digest.
6. The server authenticates before parsing, applies existing user/parser/privacy bounds, and returns a
   stable `imported`, `duplicate`, `quarantined`, or `retryable` result.
7. Native storage records only non-sensitive retry state; server-side idempotency is authoritative.

Do not ask for the user's password, copy browser cookies, add a JavaScript bridge, put a bearer token
in a URL, or issue a non-expiring all-purpose API key.

## Commercial-grade gates

The Android app is not ready for external release until there is evidence for:

- final package/origin/signing ownership, key backup/recovery, Android developer registration, and a
  supported update window;
- correct Digital Asset Links verification using the actual F-Droid release signer plus negative tests
  proving another origin/signature falls back to browser UI;
- the scoped pairing/import API, revocation UI, expiry/rotation, rate limits, and audit events;
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

Until the mobile API exists, the commercial-quality behavior is the explicit block: the installed app
is a complete runway experience, folder persistence can be tested, and nothing falsely claims an upload.
