import { pathToFileURL } from 'node:url';

export function validateCiResults(input) {
	const errors = [];
	const expect = (name, actual, expected) => {
		if (actual !== expected) {
			errors.push(`${name} was ${actual || 'missing'}; expected ${expected}`);
		}
	};

	expect('change classification', input.results.scope, 'success');
	expect('quality', input.results.checks, 'success');

	const browserRequired = parseRequiredBoolean(
		'browser requirement',
		input.browserRequired,
		errors
	);
	const imageRequired = parseRequiredBoolean('image requirement', input.imageRequired, errors);

	if (browserRequired !== undefined) {
		expect('browser', input.results.browser, browserRequired ? 'success' : 'skipped');
	}

	const releaseEvent = input.eventName === 'push' && input.ref.startsWith('refs/tags/v');
	const publishEvent =
		input.eventName === 'push' &&
		(input.ref === `refs/heads/${input.defaultBranch}` || releaseEvent);

	if (imageRequired !== undefined) {
		if (imageRequired) {
			if (publishEvent) {
				expect('unprivileged image verification', input.results.imageVerify, 'skipped');
				expect('trusted image publication', input.results.imagePublish, 'success');
			} else {
				expect('unprivileged image verification', input.results.imageVerify, 'success');
				expect('trusted image publication', input.results.imagePublish, 'skipped');
			}
		} else {
			expect('unprivileged image verification', input.results.imageVerify, 'skipped');
			expect('trusted image publication', input.results.imagePublish, 'skipped');
		}
	}

	expect(
		'release source ancestry',
		input.results.releaseSource,
		releaseEvent ? 'success' : 'skipped'
	);
	for (const [name, actual] of [
		['unsigned Android APK', input.results.androidBuild],
		['signed Android APK', input.results.androidRelease],
		['GitHub release', input.results.release]
	]) {
		expect(name, actual, releaseEvent ? 'success' : 'skipped');
	}

	return errors;
}

function parseRequiredBoolean(name, value, errors) {
	if (value === 'true' || value === true) return true;
	if (value === 'false' || value === false) return false;
	errors.push(`${name} was ${value || 'missing'}; expected true or false`);
	return undefined;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	const errors = validateCiResults({
		eventName: process.env['EVENT_NAME'] ?? '',
		ref: process.env['REF'] ?? '',
		defaultBranch: process.env['DEFAULT_BRANCH'] ?? '',
		browserRequired: process.env['BROWSER_REQUIRED'],
		imageRequired: process.env['IMAGE_REQUIRED'],
		results: {
			scope: process.env['SCOPE_RESULT'],
			checks: process.env['CHECKS_RESULT'],
			browser: process.env['BROWSER_RESULT'],
			imageVerify: process.env['IMAGE_VERIFY_RESULT'],
			imagePublish: process.env['IMAGE_PUBLISH_RESULT'],
			releaseSource: process.env['RELEASE_SOURCE_RESULT'],
			androidBuild: process.env['ANDROID_BUILD_RESULT'],
			androidRelease: process.env['ANDROID_RELEASE_RESULT'],
			release: process.env['RELEASE_RESULT']
		}
	});

	if (errors.length > 0) {
		for (const error of errors) {
			console.error(`::error title=Unexpected CI job result::${error}.`);
		}
		process.exit(1);
	}

	console.log('Every applicable CI gate completed successfully.');
}
