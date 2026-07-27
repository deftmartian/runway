import { spawn } from 'node:child_process';

const [firstArgument, secondArgument] = process.argv.slice(2);
const image = firstArgument === '--' ? secondArgument : firstArgument;
if (!image || image.startsWith('-') || /\s/.test(image)) {
	throw new Error('Usage: node scripts/verify-arm64-image.mjs <published-image-reference>');
}

const databaseUrl = process.env['DATABASE_URL'];
const authSecret = process.env['BETTER_AUTH_SECRET'];
const expectedBuild = process.env['RUNWAY_EXPECTED_BUILD_ID'];
if (!databaseUrl) throw new Error('DATABASE_URL is required for the ARM64 image check.');
if (!authSecret) throw new Error('BETTER_AUTH_SECRET is required for the ARM64 image check.');
if (!expectedBuild || !/^[0-9a-f]{40}$/i.test(expectedBuild)) {
	throw new Error('RUNWAY_EXPECTED_BUILD_ID must be the full release commit SHA.');
}

const siteUrl = new URL(process.env['RUNWAY_ARM64_SITE_URL'] ?? 'http://127.0.0.1:4110');
if (
	siteUrl.protocol !== 'http:' ||
	!['127.0.0.1', 'localhost'].includes(siteUrl.hostname) ||
	!siteUrl.port
) {
	throw new Error('RUNWAY_ARM64_SITE_URL must be an explicit loopback HTTP URL and port.');
}

const productionOrigin = process.env['ORIGIN'] ?? 'https://runway.example.test';
const publicOrigin = process.env['PUBLIC_APP_ORIGIN'] ?? productionOrigin;
for (const [name, value] of [
	['ORIGIN', productionOrigin],
	['PUBLIC_APP_ORIGIN', publicOrigin]
]) {
	if (new URL(value).protocol !== 'https:') {
		throw new Error(`${name} must use HTTPS for the production ARM64 smoke test.`);
	}
}

const containerName = `runway-arm64-smoke-${process.pid}-${Date.now().toString(36)}`;
const dockerEnvironment = {
	...process.env,
	DATABASE_URL: databaseUrl,
	BETTER_AUTH_SECRET: authSecret,
	ORIGIN: productionOrigin,
	PUBLIC_APP_ORIGIN: publicOrigin,
	HOST: '0.0.0.0',
	PORT: siteUrl.port
};
let appStarted = false;
let runtimeImage = image;
let verificationPhase = 'resolve and pull the published ARM64 manifest';

try {
	if (process.env['RUNWAY_ARM64_SKIP_PULL'] !== 'true') {
		runtimeImage = await publishedArm64Reference(image);
		await run('docker', ['pull', '--platform', 'linux/arm64', runtimeImage]);
	}

	verificationPhase = 'inspect ARM64 image architecture and revision';
	const [architecture, revision] = (
		await capture('docker', [
			'image',
			'inspect',
			'--format',
			'{{.Architecture}}\t{{index .Config.Labels "org.opencontainers.image.revision"}}',
			runtimeImage
		])
	)
		.trim()
		.split('\t');
	if (architecture !== 'arm64') {
		throw new Error(
			`${runtimeImage} resolved to ${architecture || 'an unknown architecture'}, not arm64.`
		);
	}
	if (revision?.toLowerCase() !== expectedBuild.toLowerCase()) {
		throw new Error(
			`${runtimeImage} reports build ${revision || 'unknown'}, expected ${expectedBuild.toLowerCase()}.`
		);
	}

	verificationPhase = 'run ARM64 database migrations';
	await runWithCapturedOutput(
		'docker',
		[
			'run',
			'--rm',
			'--platform',
			'linux/arm64',
			'--network',
			'host',
			'--env',
			'DATABASE_URL',
			runtimeImage,
			'node',
			'scripts/run-migrations.mjs'
		],
		dockerEnvironment
	);

	verificationPhase = 'start the ARM64 application container';
	await capture(
		'docker',
		[
			'run',
			'--detach',
			'--name',
			containerName,
			'--platform',
			'linux/arm64',
			'--network',
			'host',
			'--env',
			'DATABASE_URL',
			'--env',
			'BETTER_AUTH_SECRET',
			'--env',
			'ORIGIN',
			'--env',
			'PUBLIC_APP_ORIGIN',
			'--env',
			'HOST',
			'--env',
			'PORT',
			runtimeImage
		],
		dockerEnvironment
	);
	appStarted = true;

	verificationPhase = 'wait for ARM64 application readiness';
	await waitForReady();
	verificationPhase = 'verify the ARM64 production preview';
	await run(process.execPath, ['scripts/verify-preview.mjs'], {
		...process.env,
		SITE_URL: siteUrl.href
	});

	verificationPhase = 'verify ARM64 live build identity';
	const live = await fetch(new URL('/health/live', siteUrl));
	if (!live.ok) throw new Error(`ARM64 /health/live returned ${live.status}.`);
	const identity = await live.json();
	if (identity.commit?.toLowerCase() !== expectedBuild.toLowerCase()) {
		throw new Error(
			`ARM64 runtime reports build ${identity.commit ?? 'unknown'}, expected ${expectedBuild.toLowerCase()}.`
		);
	}

	console.log(
		`Published ARM64 image ${image} passed migrations, preview, and build identity checks.`
	);
} catch (error) {
	if (appStarted) {
		await run('docker', ['logs', '--tail', '200', containerName], process.env).catch(
			() => undefined
		);
	}
	reportFailure(verificationPhase, error);
	throw error;
} finally {
	if (appStarted) {
		await capture('docker', ['rm', '--force', containerName], process.env).catch((error) => {
			console.error(`Could not remove ARM64 smoke container: ${error.message}`);
		});
	}
}

function reportFailure(phase, error) {
	const message = `ARM64 image verification failed during "${phase}": ${errorChain(error)}`;
	console.error(message);
	if (process.env['GITHUB_ACTIONS'] === 'true') {
		console.error(
			`::error title=ARM64 candidate verification failed::${escapeWorkflowCommand(message)}`
		);
	}
}

function errorChain(error) {
	const messages = [];
	const seen = new Set();
	let current = error;
	while (current instanceof Error && !seen.has(current)) {
		seen.add(current);
		messages.push(current.message);
		current = current.cause;
	}
	return messages.join(' <- ') || 'unknown error';
}

function escapeWorkflowCommand(value) {
	return value.replaceAll('%', '%25').replaceAll('\r', '%0D').replaceAll('\n', '%0A');
}

async function publishedArm64Reference(reference) {
	const separator = reference.lastIndexOf('@');
	const digest = reference.slice(separator + 1);
	if (separator < 1 || !/^sha256:[0-9a-f]{64}$/i.test(digest)) {
		throw new Error('Published ARM64 verification requires an immutable manifest digest.');
	}

	const manifest = JSON.parse(
		await capture('docker', [
			'buildx',
			'imagetools',
			'inspect',
			reference,
			'--format',
			'{{json .Manifest}}'
		])
	);
	const matches = (manifest.manifests ?? []).filter(
		(candidate) =>
			candidate.platform?.os === 'linux' && candidate.platform?.architecture === 'arm64'
	);
	if (matches.length !== 1 || !/^sha256:[0-9a-f]{64}$/i.test(matches[0]?.digest ?? '')) {
		throw new Error(
			`Published candidate must contain exactly one immutable Linux ARM64 image; found ${matches.length}.`
		);
	}
	return `${reference.slice(0, separator)}@${matches[0].digest}`;
}

async function waitForReady() {
	let lastError = new Error('ARM64 app did not become ready.');
	for (let attempt = 0; attempt < 90; attempt += 1) {
		try {
			const response = await fetch(new URL('/health/ready', siteUrl));
			if (response.ok) return;
			lastError = new Error(`ARM64 /health/ready returned ${response.status}.`);
		} catch (error) {
			lastError = error;
		}

		const running = (
			await capture('docker', ['inspect', '--format', '{{.State.Running}}', containerName])
		).trim();
		if (running !== 'true') {
			throw new Error('ARM64 app exited before becoming ready.', { cause: lastError });
		}
		await new Promise((resolve) => setTimeout(resolve, 1000));
	}
	throw lastError;
}

function run(command, args, env = process.env) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: 'inherit' });
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}

function runWithCapturedOutput(command, args, env = process.env) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: ['inherit', 'pipe', 'pipe'] });
		let output = '';
		for (const [stream, destination] of [
			[child.stdout, process.stdout],
			[child.stderr, process.stderr]
		]) {
			stream.on('data', (chunk) => {
				destination.write(chunk);
				output = `${output}${chunk}`.slice(-64 * 1024);
			});
		}
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) {
				resolve();
				return;
			}
			const diagnostic = sanitizedCommandDiagnostic(output);
			reject(
				new Error(
					`${command} exited with ${signal ?? code ?? 'unknown status'}${diagnostic ? `: ${diagnostic}` : ''}.`
				)
			);
		});
	});
}

function sanitizedCommandDiagnostic(output) {
	const sanitized = output
		.replace(/postgres(?:ql)?:\/\/[^\s"'`]+/giu, '[database URL redacted]')
		.replace(/runway-secret-v1_[A-Za-z0-9_-]+/gu, '[secret redacted]');
	const lines = sanitized
		.split(/\r?\n/u)
		.map((line) => line.trim())
		.filter(Boolean);
	const errors = lines.filter((line) => /(?:error|failed|refused|permission|timeout)/iu.test(line));
	return (errors.length > 0 ? errors : lines).slice(-6).join(' | ').slice(0, 1500);
}

function capture(command, args, env = process.env) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'inherit'] });
		let output = '';
		child.stdout.setEncoding('utf8');
		child.stdout.on('data', (chunk) => {
			output += chunk;
		});
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve(output);
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}
