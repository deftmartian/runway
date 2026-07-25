import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:net';
import postgres from 'postgres';

const configuredSiteUrl = process.env['SITE_URL'];
const siteUrl = configuredSiteUrl ?? `http://127.0.0.1:${await availablePort('127.0.0.1')}`;
const publicUrl = new URL(siteUrl);
const envFile = await readEnvFile();
const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl =
	process.env['RUNWAY_PREVIEW_BASE_DATABASE_URL'] ??
	process.env['DATABASE_URL'] ??
	envFile['DATABASE_URL'] ??
	defaultDatabaseUrl;
const databaseName = `runway_preview_${process.pid}_${Date.now()}`.slice(0, 63);
let databaseUrl;
let createdDatabase = false;
let activeChild;
let receivedSignal;
let runError;
let cleanupError;

const onSigint = () => requestShutdown('SIGINT');
const onSigterm = () => requestShutdown('SIGTERM');
process.once('SIGINT', onSigint);
process.once('SIGTERM', onSigterm);

if (configuredSiteUrl) {
	await assertPortAvailable(
		publicUrl.hostname,
		Number(publicUrl.port || (publicUrl.protocol === 'https:' ? 443 : 80))
	);
}
let preview;
let previewExit;
let previewExited;

try {
	if (baseDatabaseUrl === defaultDatabaseUrl) {
		await run('docker', ['compose', 'up', '-d', '--wait', 'db'], process.env);
	}
	abortIfSignaled();
	databaseUrl = await createIsolatedDatabase(baseDatabaseUrl, databaseName);
	createdDatabase = true;
	abortIfSignaled();

	const previewEnvironment = {
		...process.env,
		DATABASE_URL: databaseUrl,
		HOST: process.env['HOST'] ?? publicUrl.hostname,
		PORT:
			process.env['PORT'] ?? (publicUrl.port || (publicUrl.protocol === 'https:' ? '443' : '80')),
		ORIGIN: process.env['ORIGIN'] ?? publicUrl.origin,
		PUBLIC_APP_ORIGIN: process.env['PUBLIC_APP_ORIGIN'] ?? publicUrl.origin
	};
	await run(process.execPath, ['scripts/run-migrations.mjs'], previewEnvironment);
	abortIfSignaled();

	preview = spawn(process.execPath, ['scripts/run-preview.mjs'], {
		env: previewEnvironment,
		stdio: 'inherit'
	});
	previewExited = new Promise((resolve) => {
		preview.once('exit', (code, signal) => {
			previewExit = { code, signal };
			resolve(previewExit);
		});
	});

	await waitForReady(new URL('/health/live', publicUrl), previewExited);
	await run(process.execPath, ['scripts/verify-preview.mjs'], {
		...previewEnvironment,
		SITE_URL: publicUrl.origin
	});
} catch (error) {
	runError = error;
} finally {
	if (preview && !previewExit) {
		preview.kill('SIGTERM');
		await previewExited;
	}
	if (createdDatabase) {
		await dropIsolatedDatabase(baseDatabaseUrl, databaseName).catch((error) => {
			cleanupError = new Error(
				`Could not remove preview database ${databaseName}: ${safeError(error)}`
			);
		});
	}
	process.off('SIGINT', onSigint);
	process.off('SIGTERM', onSigterm);
}

if (cleanupError) {
	if (runError) console.error(cleanupError.message);
	else throw cleanupError;
}
if (receivedSignal) {
	process.exitCode = receivedSignal === 'SIGINT' ? 130 : 143;
} else if (runError) {
	throw runError;
}

async function waitForReady(url, exited) {
	const deadline = Date.now() + 30_000;
	while (Date.now() < deadline) {
		const outcome = await Promise.race([
			exited.then((status) => ({ type: 'exit', status })),
			fetch(url, { signal: AbortSignal.timeout(2_000) })
				.then((response) => ({ type: 'response', response }))
				.catch(() => ({ type: 'retry' })),
			delay(250).then(() => ({ type: 'retry' }))
		]);
		if (outcome.type === 'exit') {
			throw new Error(
				`Preview exited before becoming ready (${outcome.status.signal ?? outcome.status.code ?? 'unknown status'}).`
			);
		}
		if (outcome.type === 'response' && outcome.response.ok) return;
		await delay(250);
	}
	throw new Error(`Preview did not become ready at ${url.href} within 30 seconds.`);
}

function run(command, args, env) {
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
	if (preview && preview.exitCode === null && preview.signalCode === null) {
		preview.kill(signal);
	}
}

function abortIfSignaled() {
	if (receivedSignal) throw new Error(`Preview verification interrupted by ${receivedSignal}.`);
}

function safeError(error) {
	return error instanceof Error ? error.message : 'unknown error';
}

function delay(milliseconds) {
	return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function assertPortAvailable(host, port) {
	return new Promise((resolve, reject) => {
		const server = createServer();
		server.unref();
		server.once('error', () => {
			reject(new Error(`Preview verification port ${host}:${port} is already in use.`));
		});
		server.listen(port, host, () => {
			server.close((error) => (error ? reject(error) : resolve()));
		});
	});
}

function availablePort(host) {
	return new Promise((resolve, reject) => {
		const server = createServer();
		server.unref();
		server.once('error', reject);
		server.listen(0, host, () => {
			const address = server.address();
			if (!address || typeof address === 'string') {
				server.close();
				reject(new Error('Could not allocate a local preview verification port.'));
				return;
			}
			const { port } = address;
			server.close((error) => (error ? reject(error) : resolve(port)));
		});
	});
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
	if (!['127.0.0.1', 'localhost', '::1'].includes(url.hostname)) {
		throw new Error(
			'Refusing to create an ephemeral preview database on a non-loopback host. RUNWAY_PREVIEW_BASE_DATABASE_URL must identify a local PostgreSQL server.'
		);
	}
}

async function readEnvFile() {
	if (!existsSync('.env')) return {};
	const values = {};
	const text = await readFile('.env', 'utf8');
	for (const rawLine of text.split(/\r?\n/)) {
		const line = rawLine.trim();
		if (!line || line.startsWith('#')) continue;
		const index = line.indexOf('=');
		if (index <= 0) continue;
		const key = line.slice(0, index).trim();
		if (!/^[A-Z_][A-Z0-9_]*$/i.test(key)) continue;
		values[key] = parseEnvValue(line.slice(index + 1).trim());
	}
	return values;
}

function parseEnvValue(value) {
	if (
		(value.startsWith('"') && value.endsWith('"')) ||
		(value.startsWith("'") && value.endsWith("'"))
	) {
		return value.slice(1, -1);
	}
	return value;
}
