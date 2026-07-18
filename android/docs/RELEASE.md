# Android Release And Source-built Personal F-Droid Repository

This is release guidance, not evidence that the Android app is production-ready. Complete the gates
in [`docs/ANDROID.md`](../../docs/ANDROID.md) before distributing to non-test users.

## Freeze one instance identity

Each APK is bound to one runway instance. Before the first signed release, record:

- final HTTPS origin on port 443, with no path, query, fragment, or credentials;
- final application id (the source default `com.deftmartian.runway` is still changeable);
- app name, icons, signing owner, source URL, Android support range, and update-support period.

For an independently signed self-hosted build, prefer an operator-owned id such as
`com.example.runway`; do not publish unrelated signing keys under the same id. Android update identity
is the application id plus signing key. Losing the key, changing the id, or changing the bound origin
breaks either upgrades or TWA trust.

Keep the APK signing key outside this repository, encrypted, access-controlled, and backed up with a
tested recovery procedure. The personal F-Droid repository index key is separate from the APK signing
key; back up and restrict both.

## Establish Digital Asset Links

Obtain the SHA-256 fingerprint from the certificate that will sign the installed APK:

```sh
keytool -list -v -keystore /secure/path/runway-release.jks -alias REPLACE_ALIAS
```

Fill [`android/assetlinks.json.template`](../assetlinks.json.template) with the final application id
and colon-delimited certificate SHA-256. Serve it from the bound instance at exactly:

```text
https://runway.example.com/.well-known/assetlinks.json
```

It must return JSON directly over valid HTTPS without authentication or redirects. The certificate
fingerprint is public and is not a secret. If debug builds need verified TWA behavior, add a separate
statement for the debug application id and debug certificate temporarily; do not confuse either with
the release statement.

For the normal runway deployment, set `ANDROID_APPLICATION_ID` to the final package id and
`ANDROID_CERTIFICATE_SHA256` to the colon-delimited signing fingerprint. runway serves and validates
the statement itself. Multiple fingerprints can be comma-separated during an intentional signing-key
transition; remove retired fingerprints after the installed-base migration is complete.

After installing the signed release candidate, force and inspect Android App Link verification:

```sh
adb shell pm verify-app-links --re-verify REPLACE_APPLICATION_ID
adb shell pm get-app-links REPLACE_APPLICATION_ID
```

Opening the bound origin should be full-screen. Another origin, a mismatched signature, or a missing
assetlinks file must fall back to a Custom Tab with visible browser controls.

## Produce a release candidate

Before using release signing material, run the same debug compilation gate used by CI from the
repository root:

```sh
corepack pnpm verify:android
corepack pnpm verify:android:build
```

This catches Kotlin, resource, lint, and unit-test failures using a non-routable HTTPS test origin.
It is development evidence only and does not validate signing or Digital Asset Links.

Copy `android/signing.properties.example` to the ignored `android/signing.properties` and point it at
the operator-owned keystore. Provide every required password and alias through that local file (or
materialize the same ignored file from protected CI secrets), never through committed Gradle values.
Then run:

```sh
cd android
ORIGIN=https://runway.example.com
APP_ID=com.example.runway

./gradlew --no-daemon --dependency-verification strict \
  -PrunwayOrigin="$ORIGIN" \
  -PrunwayApplicationId="$APP_ID" \
  :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
./gradlew --no-daemon --dependency-verification strict \
  -PrunwayOrigin="$ORIGIN" \
  -PrunwayApplicationId="$APP_ID" \
  :app:dependencies
```

Verify the result with `apksigner verify --verbose --print-certs`. Confirm its fingerprint exactly
matches `assetlinks.json`, then record APK SHA-256, source commit, version code/name, instance origin,
application id, signer, Android Gradle Plugin, Android Browser Helper, Gradle, JDK, SDK, and build-tools
versions.

There are no Play Services dependencies. Android Browser Helper uses the user's TWA-capable browser
and falls back to Custom Tabs. Review the dependency report before every release and reject trackers,
undeclared network behavior, or a WebView fallback.

## Publish source builds through a personal F-Droid repository

The supported personal-repository flow builds from the pinned source commit. It does not copy an APK
built elsewhere into `repo/`. Copy `android/fdroid/metadata/REPLACE_APPLICATION_ID.yml.example` to the
personal repo's `metadata/` directory using the final application id as its filename. Fill every
placeholder and keep `runwayFdroidSourceBuild=true`; that explicit mode requires a real release origin,
refuses `android/signing.properties`, and emits an unsigned release for fdroidserver to sign.

Run fdroidserver in a dedicated operator or CI environment. Keep its index and APK signing keys
outside the web root, build the exact metadata commit, then sign and publish that result:

```sh
mkdir runway-fdroid && cd runway-fdroid
fdroid init
fdroid lint REPLACE_APPLICATION_ID
fdroid build --latest REPLACE_APPLICATION_ID
fdroid publish REPLACE_APPLICATION_ID
fdroid update
fdroid deploy
```

Use the source-controlled Fastlane metadata under `android/fastlane/metadata/android/en-US/`. Serve
the repository index, archive, APKs, and signatures over HTTPS. Add it to a clean F-Droid client with
the exact URL and repository fingerprint/QR code, install the app, verify TWA association, link a
folder, and then test an upgrade from the previous signed APK without losing the persisted grant.

After the first controlled publish, record and pin the fdroidserver-managed APK certificate in
`AllowedAPKSigningKeys` and deploy the same fingerprint through `ANDROID_CERTIFICATE_SHA256` for
Digital Asset Links. The repository fingerprint proves the F-Droid index. The APK certificate proves
Android updates and origin trust. Verify both; they are not interchangeable.

Useful upstream references:

- <https://developer.chrome.com/docs/android/trusted-web-activity/>
- <https://f-droid.org/en/docs/Setup_an_F-Droid_App_Repo/>
- <https://f-droid.org/docs/Build_Metadata_Reference/>
- <https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/>

## Release evidence

Attach to each internal release record:

- signed APK, SHA-256, signer fingerprint, application id, and instance origin;
- source commit and clean-tree evidence;
- unit, lint, emulator, physical-device, TWA, App Link, share, and folder results;
- dependency, SBOM, license, permission, and manifest review;
- server/mobile API compatibility range;
- migration, upgrade, rollback, origin-verification, and key-recovery results;
- privacy policy, store metadata, support window, and incident-response review.

Do not publish a debug APK. Debug builds permit cleartext private/local origins; releases require HTTPS.
