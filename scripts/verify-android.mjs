import { createHash } from 'node:crypto';
import { existsSync, globSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { XMLParser, XMLValidator } from 'fast-xml-parser';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const errors = [];
const requiredFiles = [
	'android/app/build.gradle.kts',
	'android/.gitignore',
	'android/app/src/main/AndroidManifest.xml',
	'android/app/src/main/res/layout/activity_native_folder_settings.xml',
	'android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
	'android/app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml',
	'android/app/src/main/res/drawable/ic_launcher_monochrome.xml',
	'android/signing.properties.example',
	'scripts/verify-android-artifact.mjs',
	'android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/NativeFolderSettingsActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/AndroidCredentialStore.kt',
	'android/app/src/main/java/com/deftmartian/runway/RunwayApiClient.kt',
	'android/app/src/main/java/com/deftmartian/runway/ReconciliationWorker.kt',
	'android/gradle/wrapper/gradle-wrapper.jar',
	'android/gradle/wrapper/gradle-wrapper.properties',
	'android/gradle/verification-metadata.xml',
	'android/app/gradle.lockfile',
	'android/gradlew',
	'android/gradlew.bat',
	'android/assetlinks.json.template',
	'android/docs/RELEASE.md',
	'src/routes/[...wellKnown]/+server.ts',
	'src/routes/app/import/+page.server.ts',
	'src/routes/app/import/+page.svelte',
	'src/lib/components/import/AndroidSourceSetup.svelte',
	'src/lib/components/import/ImportSourceSetup.svelte'
];

for (const file of requiredFiles) {
	if (!existsSync(resolve(root, file))) errors.push(`missing Android contract file: ${file}`);
}

const parser = new XMLParser({ ignoreAttributes: false });
const xmlFiles = [
	...globSync('android/app/src/main/**/*.xml', { cwd: root }),
	...globSync('android/app/src/debug/**/*.xml', { cwd: root })
];
for (const file of xmlFiles) {
	try {
		const xml = readFileSync(resolve(root, file), 'utf8');
		const validation = XMLValidator.validate(xml);
		if (validation !== true) throw new Error(validation.err.msg);
		parser.parse(xml);
	} catch (error) {
		errors.push(
			`${file} is not valid XML: ${error instanceof Error ? error.message : String(error)}`
		);
	}
}

const manifest = read('android/app/src/main/AndroidManifest.xml');
const permissions = [...manifest.matchAll(/<uses-permission\s+android:name="([^"]+)"/g)].map(
	(match) => match[1]
);
if (permissions.length !== 1 || permissions[0] !== 'android.permission.INTERNET') {
	errors.push('Android must request only android.permission.INTERNET');
}
for (const required of [
	'.RunwayLauncherActivity',
	'.NativeFolderSettingsActivity',
	'android:autoVerify="true"',
	'android.support.customtabs.trusted.DEFAULT_URL',
	'asset_statements',
	'android:icon="@mipmap/ic_launcher"'
]) {
	if (!manifest.includes(required)) errors.push(`AndroidManifest.xml is missing ${required}`);
}

const build = read('android/app/build.gradle.kts');
for (const required of [
	'compileSdk = 36',
	'targetSdk = 36',
	'androidbrowserhelper:2.7.2',
	'verifyReleaseInstance',
	'assembleRelease',
	'runwayOrigin',
	'runwayApplicationId',
	'releaseSigningPropertiesFile',
	'verifyReleaseSigning'
]) {
	if (!build.includes(required)) errors.push(`Android Gradle contract is missing ${required}`);
}

const androidIgnore = read('android/.gitignore');
for (const required of ['signing.properties', '*.jks', '*.keystore']) {
	if (!androidIgnore.split(/\r?\n/).includes(required)) {
		errors.push(`Android ignore contract is missing ${required}`);
	}
}

const releaseVerification = read('scripts/verify-android-release.mjs');
for (const required of [
	"'--dependency-verification'",
	"'strict'",
	'runwaySigningPropertiesFile',
	':app:verifyReleaseSigning',
	'-PrunwayFdroidSourceBuild=true',
	'app-release-unsigned.apk'
]) {
	if (!releaseVerification.includes(required)) {
		errors.push(`Android release verification is missing ${required}`);
	}
}

const artifactVerification = read('scripts/verify-android-artifact.mjs');
for (const required of [
	'android.permission.INTERNET',
	'android.permission.ACCESS_NETWORK_STATE',
	'android.permission.WAKE_LOCK',
	'android.permission.RECEIVE_BOOT_COMPLETED',
	'android.permission.FOREGROUND_SERVICE',
	'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
	'protectionLevel="signature"'
]) {
	if (!artifactVerification.includes(required)) {
		errors.push(`Android merged-manifest permission gate is missing ${required}`);
	}
}

const wrapper = read('android/gradle/wrapper/gradle-wrapper.properties');
for (const required of [
	'gradle-8.13-bin.zip',
	'distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78',
	'validateDistributionUrl=true'
]) {
	if (!wrapper.includes(required)) errors.push(`Android Gradle wrapper is missing ${required}`);
}

const wrapperJarChecksum = createHash('sha256')
	.update(readFileSync(resolve(root, 'android/gradle/wrapper/gradle-wrapper.jar')))
	.digest('hex');
if (wrapperJarChecksum !== '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f') {
	errors.push('Android Gradle wrapper JAR does not match the reviewed Gradle 8.13 wrapper');
}

const rootBuild = read('android/build.gradle.kts');
for (const required of ['lockAllConfigurations()', 'LockMode.STRICT']) {
	if (!rootBuild.includes(required))
		errors.push(`Android dependency locking is missing ${required}`);
}

const dependencyVerification = read('android/gradle/verification-metadata.xml');
for (const required of ['<verify-metadata>true</verify-metadata>', '<sha256 value="']) {
	if (!dependencyVerification.includes(required)) {
		errors.push(`Android dependency verification metadata is missing ${required}`);
	}
}

const dependencyLock = read('android/app/gradle.lockfile');
for (const required of [
	'androidx.activity:activity-ktx:',
	'androidx.core:core-ktx:',
	'androidx.work:work-runtime:',
	'com.google.androidbrowserhelper:androidbrowserhelper:',
	'releaseRuntimeClasspath'
]) {
	if (!dependencyLock.includes(required))
		errors.push(`Android dependency lock is missing ${required}`);
}

const launcher = read('android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt');
for (const required of [
	': LauncherActivity()',
	'getUrlForIntent',
	'InstanceOriginPolicy.belongsTo',
	'ReconciliationScheduler.runOnce(this)',
	'TwaLauncher.CCT_FALLBACK_STRATEGY'
]) {
	if (!launcher.includes(required)) errors.push(`Android TWA launcher is missing ${required}`);
}

const folderSettings = read(
	'android/app/src/main/java/com/deftmartian/runway/NativeFolderSettingsActivity.kt'
);
for (const required of [
	'setContentView(R.layout.activity_native_folder_settings)',
	'R.id.primary_action',
	'R.id.background_status',
	'getWorkInfosForUniqueWork',
	'enableEdgeToEdge()',
	'EdgeToEdgeLayout.applySystemBarPadding',
	'executor.shutdown()'
]) {
	if (!folderSettings.includes(required)) {
		errors.push(
			`Android folder settings are missing the resource-backed state contract: ${required}`
		);
	}
}
if (folderSettings.includes('private fun buildContent()')) {
	errors.push(
		'Android folder settings must use the reviewed resource layout, not a programmatic stack'
	);
}

const importPageServer = read('src/routes/app/import/+page.server.ts');
const androidSourceSetup = read('src/lib/components/import/AndroidSourceSetup.svelte');
if (!importPageServer.includes('androidApplicationId: androidApplicationId ?? null')) {
	errors.push('Android folder link must fail closed without a configured release identity');
}
for (const required of ['intent://folder#Intent', 'package=${androidApplicationId};end']) {
	if (!androidSourceSetup.includes(required)) {
		errors.push(`Android folder link is missing package binding: ${required}`);
	}
}

const assetLinksRoute = read('src/routes/[...wellKnown]/+server.ts');
for (const required of [
	'ANDROID_APPLICATION_ID',
	'ANDROID_CERTIFICATE_SHA256',
	'buildAndroidAssetLinks'
]) {
	if (!assetLinksRoute.includes(required)) {
		errors.push(`Android Digital Asset Links route is missing ${required}`);
	}
}

const kotlinFiles = globSync('android/app/src/**/*.kt', { cwd: root });
const kotlin = kotlinFiles.map((file) => read(file)).join('\n');
if (/\bandroid\.webkit\b|\bWebView\b/.test(kotlin)) {
	errors.push('Android source must not add an embedded WebView');
}
for (const required of [
	'AndroidKeyStore',
	'AES/GCM/NoPadding',
	'/api/android/pair',
	'/api/android/import',
	'X-Runway-Request-Id',
	'GpxCandidatePolicy.MAX_FILE_BYTES'
]) {
	if (!kotlin.includes(required)) errors.push(`Android import boundary is missing ${required}`);
}
if (kotlin.includes('api_unavailable')) {
	errors.push('Android source still contains the blocked pre-release API state');
}
for (const required of [
	'completePairing(',
	'filterPotentiallyUnhandled(',
	'observeContent(',
	'metadataRevisionIdentity(',
	'WindowInsetsCompat.Type.displayCutout()',
	'ImportConnectionGeneration.capture(',
	'ReconciliationScheduler.cancelAll(',
	'ReconciliationStatusStore(',
	'continueBacklog('
]) {
	if (!kotlin.includes(required))
		errors.push(`Android release blocker contract is missing ${required}`);
}
if (/ScanProgressStore|nextOffset|startOffset/.test(kotlin)) {
	errors.push('Android source still relies on unstable provider cursor offsets');
}

const scanner = read('android/app/src/main/java/com/deftmartian/runway/SafTreeScanner.kt');
if (!scanner.includes('MAX_ENTRIES_PER_SCAN = 10_000')) {
	errors.push('Android restart scan must keep the explicit 10,000-entry bound');
}

const adaptiveIcon = read('android/app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml');
if (!adaptiveIcon.includes('<monochrome')) {
	errors.push('Android adaptive icon is missing its monochrome layer');
}

const strings = read('android/app/src/main/res/values/strings.xml');
const stringResources = new Set(
	[...strings.matchAll(/<string\s+name="([^"]+)"/g)].map((match) => match[1])
);
for (const match of kotlin.matchAll(/R\.string\.([A-Za-z0-9_]+)/g)) {
	if (!stringResources.has(match[1])) errors.push(`missing Android string resource: ${match[1]}`);
}

try {
	const statements = JSON.parse(read('android/assetlinks.json.template'));
	if (!Array.isArray(statements) || statements.length !== 1) {
		errors.push('android/assetlinks.json.template must contain one Digital Asset Links statement');
	}
} catch (error) {
	errors.push(
		`android/assetlinks.json.template is invalid JSON: ${error instanceof Error ? error.message : String(error)}`
	);
}

if (errors.length > 0) {
	console.error(`Android verification failed:\n- ${errors.join('\n- ')}`);
	process.exit(1);
}

console.log(
	`Android TWA contract verified across ${xmlFiles.length} XML files and ${kotlinFiles.length} Kotlin files.`
);

function read(file) {
	return readFileSync(resolve(root, file), 'utf8');
}
