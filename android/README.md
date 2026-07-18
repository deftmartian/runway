# runway for Android (pre-release)

This project packages a complete self-hosted runway instance as an Android app. Its launcher opens
the full PWA in a Trusted Web Activity (TWA), while small native activities own Android-only
capabilities: persisted Gadgetbridge folder access, scheduled reconciliation, and GPX shares.

It is not a separate companion product and it does not reimplement the planning UI. It also does not
use a generic WebView. If Digital Asset Links verification fails, Android Browser Helper deliberately
falls back to a browser Custom Tab with visible origin controls.

## Instance-bound build

Every release APK is bound at build time to one HTTPS runway origin and one Android application id:

```sh
./gradlew \
  -PrunwayOrigin=https://runway.example.com \
  -PrunwayApplicationId=com.example.runway \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`runwayOrigin` must be an origin only: no credentials, path, query, or fragment. Release builds fail
when it is missing, uses the placeholder `.invalid` host, is not HTTPS, or uses a non-default port.
Put the public instance behind normal HTTPS port 443. Local/private cleartext and custom ports are
accepted only by debug builds and open as a Custom Tab rather than a verified TWA.

The source namespace and default pre-release application id are `com.deftmartian.runway`. Choose the
final application id before the first signed distribution. An instance operator should use a stable,
operator-owned id when independently signing a personal F-Droid build; changing the id or signing key
later prevents in-place updates.

The same instance must publish `/.well-known/assetlinks.json` containing the exact application id and
SHA-256 fingerprint of the certificate that signs the installed APK. Set the server's
`ANDROID_APPLICATION_ID` and `ANDROID_CERTIFICATE_SHA256` variables; use
[`assetlinks.json.template`](assetlinks.json.template) to review the expected shape. Without that
bidirectional association, the app remains safe but displays browser controls.

## Android capability surface

The normal launcher is the full runway PWA. Folder settings are available by long-pressing the runway
launcher icon and choosing **Folder** after the first launch. The native page can also be opened with
an explicit package-bound Android intent:

```text
intent://folder#Intent;scheme=runway-native;package=REPLACE_APPLICATION_ID;end
```

The native page:

- selects a Gadgetbridge directory with `ACTION_OPEN_DOCUMENT_TREE`;
- retains only the read grant with `takePersistableUriPermission()`;
- scans up to 10,000 direct children from the start without raw filesystem paths, provider-order
  offsets, or broad storage access, and reports when the selected folder exceeds that bound;
- checks on launcher resume, enables unique inexact WorkManager checks, and provides an explicit
  **Check now** action;
- returns directly to the full runway TWA.

The exported GPX share activity accepts one `content://` URI, checks its grant/type/size, and reads it
off the UI thread with a 10 MB limit. Names, URIs, XML, coordinates, and metadata are not logged.

## Pairing and imports

Open **Import sources** in the signed-in PWA and create a pairing code. Open the Android **Folder**
screen, enter the code and a device label, then choose the Gadgetbridge directory. The code expires
after ten minutes and works once. Android receives a random, one-year credential limited to device
status and bounded GPX import; runway stores only its hash and the app encrypts it under an Android
Keystore key. It never copies browser cookies or asks for the account password.

Each folder worker uploads at most one unhandled GPX and prefers the newest provider-dated file. A
manual or periodic trigger can chain up to eight bounded workers while a backlog remains, without
changing Android's 15-minute minimum for periodic scheduling. The Folder screen reports the latest
outcome and a lower bound for files still waiting. Workers require the same bounded content revision
to be observed twice at least 30 seconds apart, so a
provider with missing or unreliable modification times cannot make a partial file terminal. Content
digests are the authoritative handled identity; size plus modification time only accelerates scans,
and weak provider identities are rechecked. Operating-system shares upload the one selected GPX.
Both paths use stable request ids, strict size bounds, and the existing review-only parser. Imported,
duplicate, and terminally rejected content revisions are marked handled locally; network and server
failures retry. Disconnect a device from the PWA if it is lost or no longer trusted. Deleting imported
GPX data revokes all active Android
devices before removing the records so background work cannot recreate them.

## Build prerequisites

- JDK 17
- Android SDK platform 36 and matching build tools
- Gradle 8.13 for the initial wrapper generation

The reviewed Gradle 8.13 wrapper is checked in and pins the distribution SHA-256. Keep Android SDK
paths in the ignored `local.properties` file or normal `ANDROID_HOME`/`ANDROID_SDK_ROOT` variables;
do not commit machine-specific paths.

The committed dependency lock pins every resolved configuration, and Gradle verifies downloaded
plugin and library artifacts against `gradle/verification-metadata.xml`. CI also validates the
wrapper JAR and scans the locked release runtime for moderate-or-higher advisories. When deliberately
changing an Android dependency, regenerate both controls and review every version and checksum change:

```sh
./gradlew --no-daemon \
  -PrunwayOrigin=https://runway.example.test \
  --write-locks --write-verification-metadata sha256 \
  :app:dependencies lint test assembleDebug
```

Do not use `--dependency-verification off` to make an unexplained checksum failure pass.

Build with the wrapper:

```sh
cd android
./gradlew --version
./gradlew \
  -PrunwayOrigin=https://runway.example.com \
  -PrunwayApplicationId=com.example.runway \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

When deliberately upgrading Gradle, regenerate the scripts and JAR together, review the wrapper
change, and replace `distributionSha256Sum` with the checksum published for that exact distribution.
Do not commit `local.properties`, `signing.properties`, signing keys, or signing passwords. Direct
release builds load the four required values shown in `signing.properties.example` and fail before
packaging if the external keystore or any value is missing. The explicit F-Droid source-build mode
produces an unsigned release for `fdroid publish` to sign; it refuses a local signing configuration.

From the repository root, the normal development gate is:

```sh
corepack pnpm verify:android
corepack pnpm verify:android:build
corepack pnpm verify:android:release
```

The first command reviews the static Android/TWA/security contract. The second runs Gradle `lint`,
`test`, and `assembleDebug` with a non-routable HTTPS test origin. The third proves the release-origin
guard, rejects an unsigned normal release, and builds only the explicitly unsigned F-Droid source
artifact without private key material. The build and release gates inspect their merged manifests and
reject any permission outside the reviewed normal WorkManager/internet allowlist. CI runs these exact commands; a green build does not replace
emulator, device, App Link, TWA, large-text, or TalkBack checks.

Plain `./gradlew test` also works with the non-distributable placeholder origin. Tasks that create or
install a release artifact still fail closed until `-PrunwayOrigin` names a real HTTPS instance.

See [Android architecture and production gates](../docs/ANDROID.md) and
[release guidance](docs/RELEASE.md).
