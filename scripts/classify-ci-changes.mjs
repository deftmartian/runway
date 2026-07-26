import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const zeroSha = /^0{40}$/u;

export function classifyPaths(inputPaths, { forceFull = false } = {}) {
	const paths = [...new Set(inputPaths.map(normalizePath).filter(Boolean))].sort();
	if (forceFull || paths.length === 0) {
		return { full: true, browser: true, image: true, paths };
	}

	const full = !paths.every(isDocumentationOnlyPath);
	const image = paths.some(isImageRelevantPath);

	// An image change still crosses the browser gate. This keeps Docker, Compose,
	// migration, and release-workflow changes behind the same product-level checks
	// as source changes instead of silently narrowing the publication contract.
	const browser = image || paths.some(isBrowserRelevantPath);

	return { full, browser, image, paths };
}

export function classifyEvent({
	eventName,
	eventPath,
	ref = process.env['GITHUB_REF'],
	git = changedPaths
}) {
	if (eventName === 'workflow_dispatch' || ref?.startsWith('refs/tags/')) {
		return classifyPaths([], { forceFull: true });
	}

	const event = JSON.parse(readFileSync(eventPath, 'utf8'));
	try {
		return classifyPaths(git(eventName, event));
	} catch (error) {
		console.warn(
			`Could not determine the changed-file scope; running every CI gate: ${safeError(error)}`
		);
		return classifyPaths([], { forceFull: true });
	}
}

function changedPaths(eventName, event) {
	if (eventName === 'pull_request') {
		const base = event.pull_request?.base?.sha;
		const head = event.pull_request?.head?.sha;
		if (!isSha(base) || !isSha(head)) {
			throw new Error('pull-request base or head SHA is missing');
		}
		return gitDiff(`${base}...${head}`);
	}

	if (eventName === 'push') {
		const before = event.before;
		const after = event.after;
		if (!isSha(after)) throw new Error('push head SHA is missing');
		if (!isSha(before) || zeroSha.test(before)) return [];
		return gitDiff(`${before}..${after}`);
	}

	throw new Error(`unsupported event ${eventName}`);
}

function gitDiff(range) {
	return execFileSync(
		'git',
		['diff', '--name-only', '--no-renames', '--diff-filter=ACDMRTUXB', range],
		{ encoding: 'utf8' }
	)
		.split('\n')
		.filter(Boolean);
}

function isDocumentationOnlyPath(path) {
	return (
		path === 'AGENTS.md' ||
		path === 'CONTRIBUTING.md' ||
		path === 'LICENSE' ||
		path === 'README.md' ||
		path.startsWith('docs/')
	);
}

function isBrowserRelevantPath(path) {
	return (
		path.startsWith('src/') ||
		path.startsWith('static/') ||
		path.startsWith('drizzle/') ||
		path.startsWith('tests/e2e/') ||
		path.startsWith('tests/visual/') ||
		path.startsWith('tests/support/') ||
		[
			'package.json',
			'pnpm-lock.yaml',
			'playwright.config.ts',
			'playwright.visual.config.ts',
			'svelte.config.js',
			'vite.config.ts'
		].includes(path)
	);
}

function isImageRelevantPath(path) {
	if (
		isDocumentationOnlyPath(path) ||
		path.startsWith('android/') ||
		path === '.github/dependabot.yml' ||
		path === '.gitattributes' ||
		path === '.gitignore'
	) {
		return false;
	}

	// Default unknown files into the image lane. Missing a new build input is a
	// release-safety failure; an occasional extra image check is only a cost.
	return true;
}

function normalizePath(path) {
	return String(path)
		.trim()
		.replaceAll('\\', '/')
		.replace(/^\.\/+/u, '');
}

function isSha(value) {
	return typeof value === 'string' && /^[0-9a-f]{40}$/u.test(value);
}

function safeError(error) {
	return error instanceof Error ? error.message : 'unknown error';
}

function writeOutputs(scope, outputPath) {
	for (const key of ['full', 'browser', 'image']) {
		appendFileSync(outputPath, `${key}=${scope[key]}\n`);
	}
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
	const eventName = process.env['GITHUB_EVENT_NAME'];
	const eventPath = process.env['GITHUB_EVENT_PATH'];
	const outputPath = process.env['GITHUB_OUTPUT'];
	if (!eventName || !eventPath || !outputPath) {
		throw new Error('GITHUB_EVENT_NAME, GITHUB_EVENT_PATH, and GITHUB_OUTPUT are required.');
	}

	const scope = classifyEvent({ eventName, eventPath });
	writeOutputs(scope, outputPath);
	console.log(
		`CI scope: full=${scope.full}, browser=${scope.browser}, image=${scope.image}, changed=${scope.paths.length}`
	);
}
