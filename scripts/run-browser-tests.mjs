import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { rm } from 'node:fs/promises';
import postgres from 'postgres';

const suite = process.argv[2];
if (suite !== 'e2e' && suite !== 'visual') {
	throw new Error('Usage: node scripts/run-browser-tests.mjs <e2e|visual> [playwright arguments]');
}

const extraArgs = process.argv.slice(3);
const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const suppliedDatabaseUrl = process.env['RUNWAY_TEST_DATABASE_URL'];
const baseDatabaseUrl = suppliedDatabaseUrl ?? process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const fixedDate = process.env['RUNWAY_FIXED_DATE'] ?? '2026-05-15';
const runId = safeIdentifier(
	process.env['RUNWAY_TEST_RUN_ID'] ?? `${suite}_${process.pid}_${Date.now()}`
);
const databaseName = `runway_${runId}`.slice(0, 63);
const config = suite === 'visual' ? 'playwright.visual.config.ts' : 'playwright.config.ts';
const generatedPaths = [];
const buildDirectory = managedPath('RUNWAY_BUILD_DIR', `.runway-live/${suite}-build-${runId}`);
const kitDirectory = managedPath('RUNWAY_KIT_OUT_DIR', `.svelte-kit-${suite}-${runId}`);
const previewDirectory = managedPath(
	'RUNWAY_PREVIEW_DIR',
	`.runway-live/${suite}-preview-${runId}`
);
let databaseUrl = suppliedDatabaseUrl;
let createdDatabase = false;
let activeChild;
let receivedSignal;
let runError;

const onSigint = () => requestShutdown('SIGINT');
const onSigterm = () => requestShutdown('SIGTERM');
process.once('SIGINT', onSigint);
process.once('SIGTERM', onSigterm);

try {
	if (!suppliedDatabaseUrl && baseDatabaseUrl === defaultDatabaseUrl) {
		await run('docker', ['compose', 'up', '-d', '--wait', 'db']);
	}
	abortIfSignaled();

	if (!databaseUrl) {
		databaseUrl = await createIsolatedDatabase(baseDatabaseUrl, databaseName);
		createdDatabase = true;
	}
	abortIfSignaled();

	const previewUrl =
		process.env['PLAYWRIGHT_BASE_URL'] ?? `http://127.0.0.1:${await availablePort()}`;
	const env = {
		...process.env,
		DATABASE_URL: databaseUrl,
		PLAYWRIGHT_BASE_URL: previewUrl,
		RUNWAY_FIXED_DATE: fixedDate,
		RUNWAY_TEST_RUN_ID: runId,
		RUNWAY_BUILD_DIR: buildDirectory,
		RUNWAY_KIT_OUT_DIR: kitDirectory,
		RUNWAY_PREVIEW_DIR: previewDirectory
	};

	await run('corepack', ['pnpm', 'db:migrate'], env);
	await run(
		'corepack',
		['pnpm', 'exec', 'playwright', 'test', '--config', config, ...extraArgs],
		env
	);
} catch (error) {
	runError = error;
} finally {
	if (createdDatabase) {
		await dropIsolatedDatabase(baseDatabaseUrl, databaseName).catch((error) => {
			console.error(`Could not remove test database ${databaseName}:`, safeError(error));
			process.exitCode = 1;
		});
	}
	await Promise.all(
		generatedPaths.map((path) =>
			rm(path, { force: true, recursive: true }).catch((error) => {
				console.error(`Could not remove generated test path ${path}:`, safeError(error));
				process.exitCode = 1;
			})
		)
	);
	process.off('SIGINT', onSigint);
	process.off('SIGTERM', onSigterm);
}

if (receivedSignal) {
	process.exitCode = receivedSignal === 'SIGINT' ? 130 : 143;
} else if (runError) {
	throw runError;
}

function managedPath(environmentName, fallback) {
	const configured = process.env[environmentName];
	if (configured) return configured;
	generatedPaths.push(fallback);
	return fallback;
}

async function createIsolatedDatabase(input, name) {
	const base = new URL(input);
	assertLocalDatabase(base);
	const adminUrl = new URL(base);
	adminUrl.pathname = '/postgres';
	const sql = postgres(adminUrl.toString(), { max: 1 });
	try {
		await sql`create database ${sql(name)}`;
	} finally {
		await sql.end();
	}
	const isolated = new URL(base);
	isolated.pathname = `/${name}`;
	return isolated.toString();
}

async function dropIsolatedDatabase(input, name) {
	const base = new URL(input);
	assertLocalDatabase(base);
	const adminUrl = new URL(base);
	adminUrl.pathname = '/postgres';
	const sql = postgres(adminUrl.toString(), { max: 1 });
	try {
		await sql`drop database if exists ${sql(name)} with (force)`;
	} finally {
		await sql.end();
	}
}

function assertLocalDatabase(url) {
	if (
		!['127.0.0.1', 'localhost', '::1'].includes(url.hostname) &&
		process.env['RUNWAY_TEST_ALLOW_REMOTE_DATABASE'] !== 'true'
	) {
		throw new Error(
			'Refusing to create an ephemeral test database on a non-loopback host. Set RUNWAY_TEST_DATABASE_URL to a pre-created test database instead.'
		);
	}
}

function availablePort() {
	return new Promise((resolve, reject) => {
		const server = createServer();
		server.unref();
		server.once('error', reject);
		server.listen(0, '127.0.0.1', () => {
			const address = server.address();
			if (!address || typeof address === 'string') {
				server.close();
				reject(new Error('Could not allocate a Playwright preview port.'));
				return;
			}
			const { port } = address;
			server.close((error) => (error ? reject(error) : resolve(port)));
		});
	});
}

function safeIdentifier(value) {
	return (
		value
			.toLowerCase()
			.replace(/[^a-z0-9_]+/g, '_')
			.replace(/^_+|_+$/g, '') || 'test'
	);
}

function run(command, args, env = process.env) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: 'inherit' });
		activeChild = child;
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (activeChild === child) activeChild = undefined;
			if (code === 0) resolve();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}

function requestShutdown(signal) {
	receivedSignal ??= signal;
	if (activeChild && activeChild.exitCode === null && activeChild.signalCode === null) {
		activeChild.kill(signal);
	}
}

function abortIfSignaled() {
	if (receivedSignal) throw new Error(`Browser test run interrupted by ${receivedSignal}.`);
}

function safeError(error) {
	return error instanceof Error ? error.message : 'unknown error';
}
