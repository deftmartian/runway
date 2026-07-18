import { existsSync, globSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { XMLParser, XMLValidator } from 'fast-xml-parser';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const errors = [];
const requiredFiles = [
	'android/app/build.gradle.kts',
	'android/app/src/main/AndroidManifest.xml',
	'android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/NativeFolderSettingsActivity.kt',
	'android/app/src/main/java/com/deftmartian/runway/ReconciliationWorker.kt',
	'android/gradle/wrapper/gradle-wrapper.jar',
	'android/gradle/wrapper/gradle-wrapper.properties',
	'android/gradlew',
	'android/gradlew.bat',
	'android/assetlinks.json.template',
	'android/docs/RELEASE.md'
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
	'asset_statements'
]) {
	if (!manifest.includes(required)) errors.push(`AndroidManifest.xml is missing ${required}`);
}

const build = read('android/app/build.gradle.kts');
for (const required of [
	'compileSdk = 36',
	'targetSdk = 36',
	'androidbrowserhelper:2.7.2',
	'verifyReleaseInstance',
	'runwayOrigin',
	'runwayApplicationId'
]) {
	if (!build.includes(required)) errors.push(`Android Gradle contract is missing ${required}`);
}

const wrapper = read('android/gradle/wrapper/gradle-wrapper.properties');
for (const required of [
	'gradle-8.13-bin.zip',
	'distributionSha256Sum=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78',
	'validateDistributionUrl=true'
]) {
	if (!wrapper.includes(required)) errors.push(`Android Gradle wrapper is missing ${required}`);
}

const launcher = read('android/app/src/main/java/com/deftmartian/runway/RunwayLauncherActivity.kt');
for (const required of [
	': LauncherActivity()',
	'getUrlForIntent',
	'InstanceOriginPolicy.belongsTo',
	'TwaLauncher.CCT_FALLBACK_STRATEGY'
]) {
	if (!launcher.includes(required)) errors.push(`Android TWA launcher is missing ${required}`);
}

const kotlinFiles = globSync('android/app/src/**/*.kt', { cwd: root });
const kotlin = kotlinFiles.map((file) => read(file)).join('\n');
if (/\bandroid\.webkit\b|\bWebView\b/.test(kotlin)) {
	errors.push('Android source must not add an embedded WebView');
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
