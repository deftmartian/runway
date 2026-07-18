import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const variant = process.argv[2];
if (variant !== 'debug' && variant !== 'release') {
	fail('usage: node scripts/verify-android-artifact.mjs <debug|release>');
}

const manifestPath = resolve(
	root,
	`android/app/build/intermediates/merged_manifests/${variant}/process${capitalize(variant)}Manifest/AndroidManifest.xml`
);
const manifest = readFileSync(manifestPath, 'utf8');
const applicationId = manifest.match(/<manifest[^>]*\bpackage="([^"]+)"/)?.[1];
if (!applicationId) fail(`could not read the ${variant} application id from the merged manifest`);

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

console.log(
	`Android ${variant} merged manifest verified with no dangerous or unexpected permissions.`
);

function capitalize(value) {
	return value[0].toUpperCase() + value.slice(1);
}

function escapeRegExp(value) {
	return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function fail(message) {
	console.error(`Android artifact verification failed: ${message}`);
	process.exit(1);
}
