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
SHA-256 fingerprint of the certificate that signs the installed APK. Start from
[`assetlinks.json.template`](assetlinks.json.template). Without that bidirectional association, the
app remains safe but displays browser controls.

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
- scans at most 2,000 direct children without raw filesystem paths or broad storage access;
- enables unique, inexact WorkManager checks and provides an explicit **Check now** action;
- returns directly to the full runway TWA.

The exported GPX share activity accepts one `content://` URI, checks its grant/type/size, and reads it
off the UI thread with a 10 MB limit. Names, URIs, XML, coordinates, and metadata are not logged.

## Intentional pre-release block

The native worker reports `api_unavailable` and share bytes are discarded after local checks. The
server does not yet expose scoped device pairing/import, so nothing is uploaded. Do not bridge the
gap with browser cookies, a username/password, an embedded token URL, or an unbounded API key.

## Build prerequisites

- JDK 17
- Android SDK platform 36 and matching build tools
- Gradle 8.13 for the initial wrapper generation

The current development machine did not have those tools, so no unverified wrapper JAR was added. On
a toolchain-enabled machine, generate and review it once:

```sh
cd android
gradle wrapper --gradle-version 8.13 --distribution-type bin
./gradlew --version
./gradlew \
  -PrunwayOrigin=https://runway.example.com \
  -PrunwayApplicationId=com.example.runway \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Commit the wrapper scripts, properties, and JAR together after verifying the distribution checksum.
Do not commit `local.properties`, signing keys, or signing passwords.

See [Android architecture and production gates](../docs/ANDROID.md) and
[release guidance](docs/RELEASE.md).
