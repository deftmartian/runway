# Android Release And Source-built Personal F-Droid Repository

This is release guidance, not evidence that the Android app is production-ready. Complete the gates
in [docs/ANDROID.md](../../docs/ANDROID.md) before distributing it outside a test group.

## Freeze the distribution identity

Before the first signed release, choose and record:

- the final application id;
- app name, icons, source URL, minimum Android version, and update-support period;
- the APK signing owner, protected key location, backup, and tested recovery procedure.

The source default is `com.deftmartian.runway`. An independently distributed build may set
`-PrunwayApplicationId=com.example.runway`, but that id must remain stable. Android update identity
is the application id plus signing key. Losing the key or changing the id breaks in-place upgrades.

Every release uses in-app server selection. The runner chooses a compatible HTTPS runway server on
first launch, and the app opens it in a Custom Tab with browser origin controls visible. There is no
instance-bound or TWA build. Passing `-PrunwayOrigin` is an intentional build error.

Keep the APK signing key outside this repository, encrypted, access-controlled, and backed up. The
personal F-Droid repository index key is separate from the APK signing key; restrict and test both.

## Produce a release candidate

Before using signing material, run the repository gates:

```sh
corepack pnpm verify:android
corepack pnpm verify:android:build
corepack pnpm verify:android:release
corepack pnpm verify:android:version
```

These check Kotlin, resources, lint, unit tests, dependency locks, the merged permission and exported
component allowlists, disabled backups, release cleartext/debuggable flags, the server-selection
contract, and the unsigned F-Droid path. They do not replace device testing.

Copy `android/signing.properties.example` to the ignored `android/signing.properties` and point it
at the operator-owned keystore. Keep passwords and aliases in that ignored file or materialize it from
protected CI secrets; do not put them in committed Gradle properties or shell history.

Build a signed candidate:

```sh
cd android
APP_ID=com.example.runway

./gradlew --no-daemon --dependency-verification strict \
  -PrunwayApplicationId="$APP_ID" \
  :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
./gradlew --no-daemon --dependency-verification strict \
  -PrunwayApplicationId="$APP_ID" \
  :app:dependencies
```

Verify the APK with `apksigner verify --verbose --print-certs`. Record its SHA-256, source commit,
version code/name, application id, signing certificate fingerprint, Android Gradle Plugin, AndroidX
Browser, Gradle, JDK, SDK, and build-tools versions.

There are no Play Services dependencies. Review the dependency report before every release and reject
trackers, undeclared network behavior, or a WebView fallback.

## Publish an installable GitHub APK

Version tags do not create a container-only release. The `Container` workflow builds and verifies an
unsigned Android release without access to signing secrets. A separate protected job, which never
checks out or executes repository code, downloads that candidate and exposes the signing secrets only
to its `apksigner` step. It then verifies and attaches the installable APK, SHA-256 record, and signer
fingerprint to the GitHub release. Release publication waits for both the image and Android jobs.

Create a protected GitHub environment named `android-release`, restrict its deployment branches to
version tags, and add these environment secrets:

- `RUNWAY_ANDROID_KEYSTORE_BASE64`: the complete release keystore encoded as one base64 value;
- `RUNWAY_ANDROID_KEYSTORE_PASSWORD`;
- `RUNWAY_ANDROID_KEY_ALIAS`;
- `RUNWAY_ANDROID_KEY_PASSWORD`.

The signing step materializes the keystore only in the ephemeral runner and removes it with a shell
exit trap. No checkout, package script, Gradle task, or third-party build action receives those
secrets, and the keystore is never uploaded. Missing or partial signing secrets fail the tag job; the
GitHub release is not created without an installable APK. `verify:android:version` also requires the
tag, web version, Android `versionName`/`versionCode`, and F-Droid metadata to agree.

## Publish through a personal F-Droid repository

The supported personal-repository flow builds the pinned source commit. It does not copy an APK built
elsewhere into `repo/`. Copy
`android/fdroid/metadata/REPLACE_APPLICATION_ID.yml.example` to the personal repository's
`metadata/` directory using the final application id as its filename. Fill every placeholder and
keep `runwayFdroidSourceBuild=true`; this mode refuses `android/signing.properties` and emits an
unsigned release for fdroidserver to sign.

Run fdroidserver in a dedicated operator or CI environment. Keep index and APK signing keys outside
the web root, build the exact metadata commit, then sign and publish it:

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
the exact URL and repository fingerprint or QR code, install the app, select a server, link a folder,
and test an upgrade from the previous signed APK without losing the selected server or persisted
folder grant.

After the first controlled publish, record and pin the fdroidserver-managed APK certificate in
`AllowedAPKSigningKeys`. The repository fingerprint proves the F-Droid index; the APK certificate
proves Android updates. They are separate identities.

Useful upstream references:

- <https://developer.android.com/develop/ui/views/layout/webapps/in-app-browsing-using-cct>
- <https://f-droid.org/en/docs/Setup_an_F-Droid_App_Repo/>
- <https://f-droid.org/docs/Build_Metadata_Reference/>
- <https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/>

## Release evidence

Attach to each internal release record:

- signed APK, APK SHA-256, signer fingerprint, application id, source commit, and clean-tree evidence;
- unit, lint, emulator, physical-device, Custom Tab, share, server-switch, and folder results;
- first-run, invalid-server, incompatible-version, TLS-failure, and cross-origin isolation results;
- dependency, SBOM, license, permission, and manifest review;
- server/mobile API compatibility range;
- install, upgrade, rollback, signing-key recovery, and F-Droid index verification;
- accessibility, large-text, dark-theme, and supported-device evidence;
- privacy policy, support window, release notes, and incident-response review.

Do not publish a debug APK. Debug builds permit cleartext private and local origins; releases require
HTTPS.
