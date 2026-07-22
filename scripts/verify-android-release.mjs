import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const gradle = process.platform === 'win32' ? 'android\\gradlew.bat' : 'android/gradlew';
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

const selectableServer = run([...common, ':app:verifyServerSelectionRelease']);
if (selectableServer.status !== 0) {
	process.stderr.write(selectableServer.stdout);
	process.stderr.write(selectableServer.stderr);
	fail('selectable-server release configuration failed verification');
}

const rejected = run([...common, '-PrunwayOrigin=https://runway.example', ':app:tasks']);
if (rejected.status === 0) {
	fail('obsolete instance-bound origin unexpectedly passed configuration');
}
if (
	!`${rejected.stdout}\n${rejected.stderr}`.includes(
		'runwayOrigin is no longer supported; every runway APK uses in-app server selection'
	)
) {
	fail('instance-bound build failed for a reason other than the selectable-server guard');
}

const unsignedRelease = run([...common, ':app:verifyReleaseSigning']);
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
const sourceBuild = run([...common, '-PrunwayFdroidSourceBuild=true', ':app:assembleRelease']);
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
verifyArtifact('release');

console.log('Android release identity guard and non-secret build artifact contract verified.');

function run(args) {
	return spawnSync(resolve(root, gradle), args, {
		cwd: root,
		encoding: 'utf8',
		env: process.env
	});
}

function verifyArtifact(variant) {
	const args = [resolve(root, 'scripts/verify-android-artifact.mjs'), variant];
	const verification = spawnSync(process.execPath, args, {
		cwd: root,
		encoding: 'utf8',
		env: process.env
	});
	if (verification.status !== 0) {
		process.stderr.write(verification.stdout);
		process.stderr.write(verification.stderr);
		fail(`${variant} selectable-server artifact verification failed`);
	}
	process.stdout.write(verification.stdout);
}

function fail(message) {
	console.error(`Android release contract failed: ${message}`);
	process.exit(1);
}
