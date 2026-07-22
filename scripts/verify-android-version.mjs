import { readFile } from 'node:fs/promises';

const packageMetadata = JSON.parse(await readFile('package.json', 'utf8'));
const build = await readFile('android/app/build.gradle.kts', 'utf8');
const fdroid = await readFile('android/fdroid/metadata/REPLACE_APPLICATION_ID.yml.example', 'utf8');

const versionName = singleMatch(build, /\bversionName\s*=\s*"([^"]+)"/, 'Android versionName');
const versionCode = Number(singleMatch(build, /\bversionCode\s*=\s*(\d+)/, 'Android versionCode'));
const fdroidVersionName = singleMatch(
	fdroid,
	/^\s*-?\s*versionName:\s*([^\s]+)\s*$/m,
	'F-Droid versionName'
);
const fdroidVersionCode = Number(
	singleMatch(fdroid, /^\s*versionCode:\s*(\d+)\s*$/m, 'F-Droid versionCode')
);

if (versionName !== packageMetadata.version || fdroidVersionName !== versionName) {
	fail(
		`web (${packageMetadata.version}), Android (${versionName}), and F-Droid (${fdroidVersionName}) versions must match`
	);
}
if (!Number.isSafeInteger(versionCode) || versionCode <= 0 || fdroidVersionCode !== versionCode) {
	fail(
		`Android and F-Droid versionCode must be the same positive integer (found ${versionCode} and ${fdroidVersionCode})`
	);
}

const tag = process.env['RUNWAY_RELEASE_TAG'] ?? process.env['GITHUB_REF_NAME'];
if (tag && tag !== `v${versionName}`) {
	fail(`release tag ${tag} does not match v${versionName}`);
}

console.log(
	`Android release version ${versionName} (${versionCode}) matches web and F-Droid metadata.`
);

function singleMatch(source, pattern, label) {
	const matches = [
		...source.matchAll(
			new RegExp(pattern.source, pattern.flags.includes('g') ? pattern.flags : `${pattern.flags}g`)
		)
	];
	if (matches.length !== 1 || !matches[0]?.[1]) fail(`${label} must appear exactly once`);
	return matches[0][1];
}

function fail(message) {
	console.error(`Android version verification failed: ${message}`);
	process.exit(1);
}
