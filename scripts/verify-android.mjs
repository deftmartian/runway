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
	'android/app/src/main/res/layout/activity_server_connection.xml',
	'android/app/src/main/res/layout/activity_native_folder_settings.xml',
	'android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml',
	'android/app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml',
	'android/app/src/main/res/drawable/ic_launcher_monochrome.xml',
	'android/signing.properties.example',
	'scripts/verify-android-artifact.mjs',
	'android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/ServerConnectionActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/ServerConnectionStore.kt',
	'android/app/src/main/java/com/deftmartian/runway/ServerConnectionReset.kt',
	'android/app/src/main/java/com/deftmartian/runway/AndroidStateCoordinator.kt',
	'android/app/src/main/java/com/deftmartian/runway/NativeFolderSettingsActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/AndroidCredentialStore.kt',
	'android/app/src/main/java/com/deftmartian/runway/TreeAccessStore.kt',
	'android/app/src/main/java/com/deftmartian/runway/RunwayApiClient.kt',
	'android/app/src/main/java/com/deftmartian/runway/ReconciliationWorker.kt',
	'android/app/src/androidTest/java/com/deftmartian/runway/AndroidIdentityLifecycleInstrumentedTest.kt',
	'android/gradle/wrapper/gradle-wrapper.jar',
	'android/gradle/wrapper/gradle-wrapper.properties',
	'android/gradle/verification-metadata.xml',
	'android/app/gradle.lockfile',
	'android/gradlew',
	'android/gradlew.bat',
	'android/docs/RELEASE.md',
	'scripts/verify-android-version.mjs',
	'.github/workflows/container.yml',
	'src/routes/api/android/instance/+server.ts',
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
	'.ServerConnectionActivity',
	'.NativeFolderSettingsActivity',
	'android:icon="@mipmap/ic_launcher"'
]) {
	if (!manifest.includes(required)) errors.push(`AndroidManifest.xml is missing ${required}`);
}
for (const forbidden of ['android:autoVerify="true"', 'asset_statements', 'customtabs.trusted']) {
	if (manifest.includes(forbidden)) {
		errors.push(`AndroidManifest.xml still contains removed origin-bound behavior: ${forbidden}`);
	}
}

const build = read('android/app/build.gradle.kts');
for (const required of [
	'compileSdk = 36',
	'targetSdk = 36',
	'androidx.browser:browser:1.10.0',
	'verifyServerSelectionRelease',
	'assembleRelease',
	'runwayOrigin',
	'runwayOrigin is no longer supported',
	'runwayApplicationId',
	'releaseSigningPropertiesFile',
	'verifyReleaseSigning'
]) {
	if (!build.includes(required)) errors.push(`Android Gradle contract is missing ${required}`);
}

const versionVerification = read('scripts/verify-android-version.mjs');
for (const required of ["GITHUB_REF_TYPE'] === 'tag'", "startsWith('refs/tags/')"]) {
	if (!versionVerification.includes(required)) {
		errors.push(`Android version verification is missing its tag-only guard: ${required}`);
	}
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
	':app:verifyServerSelectionRelease',
	'instance-bound origin unexpectedly passed configuration',
	"verifyArtifact('release')",
	'-PrunwayFdroidSourceBuild=true',
	'app-release-unsigned.apk'
]) {
	if (!releaseVerification.includes(required)) {
		errors.push(`Android release verification is missing ${required}`);
	}
}

const releaseWorkflow = read('.github/workflows/container.yml');
for (const required of [
	'android-build:',
	'android-release:',
	'environment: android-release',
	'RUNWAY_ANDROID_KEYSTORE_BASE64',
	'corepack pnpm verify:android:version',
	'-PrunwayFdroidSourceBuild=true',
	'name: unsigned-android-release',
	'assembleRelease',
	'apksigner',
	'Number of signers: 1',
	'RUNWAY_ANDROID_CERT_SHA256',
	'The APK signer does not match the pinned Android release certificate.',
	'name: signed-android-release',
	'needs: [image, android-release]',
	'application/vnd.android.package-archive'
]) {
	if (!releaseWorkflow.includes(required)) {
		errors.push(`Android signed-release workflow is missing ${required}`);
	}
}
const unsignedBuildJob = section(releaseWorkflow, '  android-build:', '  android-release:');
if (unsignedBuildJob.includes('secrets.') || unsignedBuildJob.includes('environment:')) {
	errors.push('Unsigned Android build job must not receive a protected environment or secrets');
}
const protectedSigningJob = section(releaseWorkflow, '  android-release:', '  release:');
for (const forbidden of ['actions/checkout', 'corepack pnpm', 'gradlew', 'assembleRelease']) {
	if (protectedSigningJob.includes(forbidden)) {
		errors.push(`Protected Android signing job must not execute repository code: ${forbidden}`);
	}
}
const signingStep = section(
	protectedSigningJob,
	'      - name: Sign protected Android release candidate',
	'      - name: Verify signed APK and prepare provenance records'
);
for (const secretName of [
	'RUNWAY_ANDROID_KEYSTORE_BASE64',
	'RUNWAY_ANDROID_KEYSTORE_PASSWORD',
	'RUNWAY_ANDROID_KEY_ALIAS',
	'RUNWAY_ANDROID_KEY_PASSWORD'
]) {
	const occurrences = [...protectedSigningJob.matchAll(new RegExp(secretName, 'g'))].length;
	if (
		occurrences === 0 ||
		!signingStep.includes(secretName) ||
		protectedSigningJob.replace(signingStep, '').includes(secretName)
	) {
		errors.push(
			`Protected Android signing secret is not scoped to the apksigner step: ${secretName}`
		);
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
	'protectionLevel="signature"',
	'instance-bound build contract',
	'expectedExported',
	'android:usesCleartextTraffic',
	'android:allowBackup'
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
	'androidx.browser:browser:',
	'releaseRuntimeClasspath'
]) {
	if (!dependencyLock.includes(required))
		errors.push(`Android dependency lock is missing ${required}`);
}

const launcher = read('android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt');
for (const required of [
	': ComponentActivity()',
	'CustomTabsIntent.Builder()',
	'InstanceOriginPolicy.belongsTo',
	'ReconciliationScheduler.runOnce(this)',
	'setUrlBarHidingEnabled(false)',
	'ActivityNotFoundException'
]) {
	if (!launcher.includes(required))
		errors.push(`Android Custom Tab launcher is missing ${required}`);
}

const serverConnection = read(
	'android/app/src/main/java/com/deftmartian/runway/ServerConnectionActivity.kt'
);
for (const required of [
	'RunwayApiClient(origin).probe()',
	'InstanceOriginPolicy.normalizeOrigin',
	'store.replace(initialConnection, origin)',
	'RunwayApiClient(previous.origin).disconnect',
	'R.string.server_change_consequence',
	'RunwayLauncherActivity::class.java'
]) {
	if (!serverConnection.includes(required)) {
		errors.push(`Android server selection is missing ${required}`);
	}
}

const credentialStore = read(
	'android/app/src/main/java/com/deftmartian/runway/AndroidCredentialStore.kt'
);
for (const required of [
	'expectedOrigin',
	'credential.origin == expectedOrigin',
	'updateAAD',
	'AndroidCredentialState',
	'generationKey',
	'useIfCurrent',
	'clearIfCurrent'
]) {
	if (!credentialStore.includes(required)) {
		errors.push(`Android credential origin binding is missing ${required}`);
	}
}

const stateCoordinator = read(
	'android/app/src/main/java/com/deftmartian/runway/AndroidStateCoordinator.kt'
);
for (const required of ['ReentrantReadWriteLock(true)', 'fun <T> read', 'fun <T> write']) {
	if (!stateCoordinator.includes(required)) {
		errors.push(`Android lifecycle serialization is missing ${required}`);
	}
}

const lifecycleInstrumentation = read(
	'android/app/src/androidTest/java/com/deftmartian/runway/AndroidIdentityLifecycleInstrumentedTest.kt'
);
for (const required of [
	'testInterruptedServerTransitionRecoversBeforeNewWorkCanReadState',
	'testStaleCredentialSnapshotCannotClearReplacement',
	'PENDING_CLEANUP_ORIGIN_KEY',
	'clearIfCurrent(stale)'
]) {
	if (!lifecycleInstrumentation.includes(required)) {
		errors.push(`Android lifecycle instrumentation is missing ${required}`);
	}
}

const connectionStore = read(
	'android/app/src/main/java/com/deftmartian/runway/ServerConnectionStore.kt'
);
for (const required of [
	'PENDING_CLEANUP_ORIGIN_KEY',
	'PENDING_TARGET_ORIGIN_KEY',
	'finishPendingTransition()',
	'mutateIfCurrent'
]) {
	if (!connectionStore.includes(required)) {
		errors.push(`Android server transition recovery is missing ${required}`);
	}
}

const treeStore = read('android/app/src/main/java/com/deftmartian/runway/TreeAccessStore.kt');
for (const required of [
	'TREE_OWNER_ORIGIN_KEY',
	'TREE_OWNER_SERVER_GENERATION_KEY',
	'expectedConnection',
	'TreeAccessMutation.Conflict'
]) {
	if (!treeStore.includes(required)) {
		errors.push(`Android folder ownership boundary is missing ${required}`);
	}
}

const instanceRoute = read('src/routes/api/android/instance/+server.ts');
for (const required of [
	"request.headers.get('x-runway-client') !== 'runway-android/1'",
	'buildAndroidInstanceDescriptor()',
	"'Cache-Control': 'private, no-store'"
]) {
	if (!instanceRoute.includes(required)) {
		errors.push(`Android instance discovery route is missing ${required}`);
	}
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
	'executor.shutdown()',
	'pickerConnection',
	'clearIfCurrent',
	'RunwayApiClient(serverOrigin).disconnect'
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
	'/api/android/instance',
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

if (errors.length > 0) {
	console.error(`Android verification failed:\n- ${errors.join('\n- ')}`);
	process.exit(1);
}

console.log(
	`Android selectable-server contract verified across ${xmlFiles.length} XML files and ${kotlinFiles.length} Kotlin files.`
);

function read(file) {
	return readFileSync(resolve(root, file), 'utf8');
}

function section(source, start, end) {
	const startIndex = source.indexOf(start);
	if (startIndex < 0) return '';
	const endIndex = source.indexOf(end, startIndex + start.length);
	return source.slice(startIndex, endIndex < 0 ? source.length : endIndex);
}
