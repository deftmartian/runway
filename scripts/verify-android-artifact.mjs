import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const variant = process.argv[2];
const canonicalApplicationId = 'dev.deftmartian.runway';
if (variant !== 'debug' && variant !== 'release') {
	fail('usage: node scripts/verify-android-artifact.mjs <debug|release> [expected-application-id]');
}
if (process.argv.length > 4) {
	fail('Android artifact verification received too many arguments');
}
const expectedApplicationId =
	process.argv[3] ??
	(variant === 'debug' ? `${canonicalApplicationId}.debug` : canonicalApplicationId);
if (!/^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/.test(expectedApplicationId)) {
	fail('expected Android application id is invalid');
}

const manifestPath = resolve(
	root,
	`android/app/build/intermediates/merged_manifests/${variant}/process${capitalize(variant)}Manifest/AndroidManifest.xml`
);
const manifest = readFileSync(manifestPath, 'utf8');
const applicationId = manifest.match(/<manifest[^>]*\bpackage="([^"]+)"/)?.[1];
if (!applicationId) fail(`could not read the ${variant} application id from the merged manifest`);
if (applicationId !== expectedApplicationId) {
	fail(
		`${variant} application id is ${applicationId}; expected the reviewed identity ${expectedApplicationId}`
	);
}

const permissions = new Set(
	[...manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"/g)].map((match) => match[1])
);
const privateReceiverPermission = `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`;
const allowedPermissions = new Set([
	'android.permission.INTERNET',
	'android.permission.ACCESS_NETWORK_STATE',
	'android.permission.WAKE_LOCK',
	'android.permission.RECEIVE_BOOT_COMPLETED',
	'android.permission.FOREGROUND_SERVICE',
	'android.permission.health.READ_EXERCISE',
	'android.permission.health.READ_DISTANCE',
	'android.permission.health.READ_HEART_RATE',
	'android.permission.health.READ_SPEED',
	'android.permission.health.READ_STEPS_CADENCE',
	'android.permission.health.READ_ELEVATION_GAINED',
	'android.permission.health.READ_EXERCISE_ROUTES',
	'android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND',
	privateReceiverPermission
]);
const unexpected = [...permissions].filter((permission) => !allowedPermissions.has(permission));
const missing = [...allowedPermissions].filter((permission) => !permissions.has(permission));
if (unexpected.length > 0) fail(`unexpected merged permissions: ${unexpected.join(', ')}`);
if (missing.length > 0) fail(`expected operational permissions are missing: ${missing.join(', ')}`);

const privateDeclaration = new RegExp(
	`<permission\\s+android:name="${escapeRegExp(privateReceiverPermission)}"\\s+android:protectionLevel="signature"\\s*/>`
);
if (!privateDeclaration.test(manifest)) {
	fail('AndroidX dynamic-receiver permission is not declared with signature protection');
}

if (
	!manifest.includes('android:name="dev.deftmartian.runway.MainActivity"') ||
	!manifest.includes('android.intent.action.MAIN') ||
	!manifest.includes('android.intent.category.LAUNCHER')
) {
	fail('native Compose activity is not the installed app entry point');
}

const exportedComponents = [
	...manifest.matchAll(
		/<(activity-alias|activity|service|receiver|provider)\b([^>]*\bandroid:exported="true"[^>]*)>/g
	)
].map((match) => ({
	type: match[1] === 'activity-alias' ? 'activity' : match[1],
	name: attribute(match[2], 'android:name'),
	permission: attribute(match[2], 'android:permission')
}));
const expectedExported = new Map([
	['activity:dev.deftmartian.runway.ShareReceiverActivity', null],
	['activity:dev.deftmartian.runway.NativeFolderSettingsActivity', null],
	[
		'activity:dev.deftmartian.runway.HealthConnectPermissionsRationaleActivity',
		'android.permission.START_VIEW_PERMISSION_USAGE'
	],
	['activity:dev.deftmartian.runway.MainActivity', null],
	['service:androidx.health.platform.client.impl.sdkservice.HealthDataSdkService', null],
	[
		'service:androidx.work.impl.background.systemjob.SystemJobService',
		'android.permission.BIND_JOB_SERVICE'
	],
	['receiver:androidx.work.impl.diagnostics.DiagnosticsReceiver', 'android.permission.DUMP'],
	['receiver:androidx.profileinstaller.ProfileInstallReceiver', 'android.permission.DUMP']
]);
if (variant === 'debug') {
	expectedExported.set('activity:androidx.compose.ui.tooling.PreviewActivity', null);
	expectedExported.set('activity:androidx.activity.ComponentActivity', null);
}
for (const component of exportedComponents) {
	const key = `${component.type}:${component.name}`;
	if (!expectedExported.has(key)) fail(`unexpected exported component: ${key}`);
	const expectedPermission = expectedExported.get(key);
	if (component.permission !== expectedPermission) {
		fail(
			`${key} permission is ${component.permission ?? 'missing'}; expected ${expectedPermission ?? 'none'}`
		);
	}
	expectedExported.delete(key);
}
if (expectedExported.size > 0) {
	fail(`expected exported components are missing: ${[...expectedExported.keys()].join(', ')}`);
}

const applicationAttributes = manifest.match(/<application\b([^>]*)>/)?.[1];
if (!applicationAttributes) fail('merged manifest is missing its application declaration');
if (attribute(applicationAttributes, 'android:allowBackup') !== 'false') {
	fail('Android backups must be disabled');
}
if (variant === 'release') {
	if (attribute(applicationAttributes, 'android:usesCleartextTraffic') !== 'false') {
		fail('release artifact permits cleartext traffic');
	}
	if (attribute(applicationAttributes, 'android:debuggable') === 'true') {
		fail('release artifact is debuggable');
	}
}

const buildConfigPath = resolve(
	root,
	`android/app/build/generated/source/buildConfig/${variant}/dev/deftmartian/runway/BuildConfig.java`
);
const buildConfig = readFileSync(buildConfigPath, 'utf8');
if (buildConfig.includes('RUNWAY_INSTANCE_BOUND') || buildConfig.includes('RUNWAY_BOUND_ORIGIN')) {
	fail('artifact still contains an instance-bound build contract');
}
if (manifest.includes('android:autoVerify="true"')) {
	fail('selectable-server artifact must not claim an Android App Link origin');
}
if (
	!manifest.includes('<package android:name="com.google.android.apps.healthdata" />') ||
	!manifest.includes('androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE')
) {
	fail(
		'artifact is missing Health Connect package visibility or its pre-Android-14 rationale entry'
	);
}

console.log(
	`Android ${variant} selectable-server artifact verified against the reviewed operational and Health Connect permission allowlist.`
);

function capitalize(value) {
	return value[0].toUpperCase() + value.slice(1);
}

function escapeRegExp(value) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function attribute(attributes, name) {
	return attributes.match(new RegExp(`${escapeRegExp(name)}="([^"]+)"`))?.[1] ?? null;
}

function fail(message) {
	console.error(`Android artifact verification failed: ${message}`);
	process.exit(1);
}
