# Android release and personal F-Droid repository

## Stable identity

The application id is `dev.deftmartian.runway`. Android updates require the same application id and APK signing certificate. Keep the release key outside this repository, encrypted and access-controlled, with a tested independent backup. Losing it prevents in-place updates.

The personal F-Droid repository index key is separate from the APK signing key. Keep and test both independently. There is exactly one canonical APK signing certificate for `dev.deftmartian.runway`: GitHub releases and every APK in the personal F-Droid repository must use it. A differently signed APK with this same application id is a separate, non-upgradeable installation path.

## Build a candidate

Use JDK 17 and Android SDK Platform 36. First run the normal Android checks without signing material:

```sh
android/gradlew -p android --no-daemon --max-workers=1 lint test assembleDebug
```

For a locally signed release, copy the ignored template and provide the operator-owned key path and credentials:

```sh
cp android/signing.properties.example android/signing.properties
android/gradlew -p android --no-daemon --max-workers=1 assembleRelease
apksigner verify --verbose --print-certs android/app/build/outputs/apk/release/app-release.apk
```

Do not put a keystore, password, or `signing.properties` in Git, CI logs, shell history, or an artifact. Record the source commit, version name/code, APK SHA-256, application id, and signer certificate fingerprint with the release.

## GitHub release

Version tags are expected to use `v<semver>`. The protected Android release environment supplies the signing identity. If it is unavailable, publication must fail rather than create an unsigned normal release. Verify the GitHub artifact's SHA-256 and certificate against the recorded release identity before distributing it.

## Personal F-Droid repository

The source-build path intentionally remains unsigned so `fdroidserver` can apply the canonical APK signing key:

```sh
android/gradlew -p android --no-daemon --max-workers=1 -PrunwayFdroidSourceBuild=true assembleRelease
```

Run `fdroidserver` in a dedicated operator or CI environment. Configure it with the same canonical APK signing key used by the protected GitHub release, while keeping its repository-index key separate; do not let it generate or use another APK key for this application id. Keep both keys outside its web root. Before publishing, verify the F-Droid APK's certificate SHA-256 against `RUNWAY_ANDROID_CERT_SHA256`. Publish the index, APKs, and signatures over HTTPS, add the repository using its exact URL and index fingerprint, then test a clean install and an upgrade from the preceding GitHub-signed APK without losing the local ledger.

## Acceptance evidence

Before external release, collect current-build evidence for install, upgrade, first-run onboarding, Calendar/Inbox/Stats/History/Settings navigation, GPX share, folder grant and revocation, Health Connect permission and revocation, privacy route discard, backup warning, erase, large text, TalkBack, and system light/dark themes. Do not substitute old screenshots, source review, or compilation for this evidence.
