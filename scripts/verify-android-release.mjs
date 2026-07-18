import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const gradle = process.platform === 'win32' ? 'android\\gradlew.bat' : 'android/gradlew';
const fixtureOrigin = 'https://android-release-check.deftmartian.com';
const fixtureApplicationId = 'com.deftmartian.runway.releasecheck';
const common = [
	'-p',
	'android',
	'--no-daemon',
	'--dependency-verification',
	'strict',
	'-PrunwaySigningPropertiesFile=build/release-contract/no-signing.properties',
	`-PrunwayApplicationId=${fixtureApplicationId}`
];

const rejected = run([
	...common,
	'-PrunwayOrigin=https://runway.invalid',
	':app:verifyReleaseInstance'
]);
if (rejected.status === 0) {
	fail('placeholder release origin unexpectedly passed verifyReleaseInstance');
}
if (
	!`${rejected.stdout}\n${rejected.stderr}`.includes(
		'Release builds require the final runway HTTPS origin'
	)
) {
	fail('placeholder check failed for a reason other than the release-origin guard');
}

const verified = run([...common, `-PrunwayOrigin=${fixtureOrigin}`, ':app:verifyReleaseInstance']);
if (verified.status !== 0) {
	process.stderr.write(verified.stdout);
	process.stderr.write(verified.stderr);
	fail('valid release-shaped identity or debug artifact verification failed');
}

const unsignedRelease = run([
	...common,
	`-PrunwayOrigin=${fixtureOrigin}`,
	':app:verifyReleaseSigning'
]);
if (unsignedRelease.status === 0) {
	fail('release signing verification unexpectedly passed without signing.properties');
}
if (
	!`${unsignedRelease.stdout}\n${unsignedRelease.stderr}`.includes(
		'Release builds require untracked android/signing.properties'
	)
) {
	fail('unsigned release check failed for a reason other than the signing guard');
}
const sourceBuild = run([
	...common,
	`-PrunwayOrigin=${fixtureOrigin}`,
	'-PrunwayFdroidSourceBuild=true',
	':app:assembleRelease'
]);
if (sourceBuild.status !== 0) {
	process.stderr.write(sourceBuild.stdout);
	process.stderr.write(sourceBuild.stderr);
	fail('explicit unsigned F-Droid source build failed');
}
const unsignedArtifact = resolve(
	root,
	'android/app/build/outputs/apk/release/app-release-unsigned.apk'
);
if (!existsSync(unsignedArtifact)) {
	fail('F-Droid source build did not produce the expected unsigned release APK');
}
const manifestVerification = spawnSync(
	process.execPath,
	[resolve(root, 'scripts/verify-android-artifact.mjs'), 'release'],
	{
		cwd: root,
		encoding: 'utf8',
		env: process.env
	}
);
if (manifestVerification.status !== 0) {
	process.stderr.write(manifestVerification.stdout);
	process.stderr.write(manifestVerification.stderr);
	fail('release merged-manifest permission verification failed');
}
process.stdout.write(manifestVerification.stdout);

console.log('Android release identity guard and non-secret build artifact contract verified.');

function run(args) {
	return spawnSync(resolve(root, gradle), args, {
		cwd: root,
		encoding: 'utf8',
		env: process.env
	});
}

function fail(message) {
	console.error(`Android release contract failed: ${message}`);
	process.exit(1);
}
