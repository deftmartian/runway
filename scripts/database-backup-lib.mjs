import { spawn } from 'node:child_process';
import { createReadStream } from 'node:fs';
import { lstat, readFile } from 'node:fs/promises';
import { pipeline } from 'node:stream/promises';

/**
 * @typedef {object} PostgresConnection
 * @property {string} host
 * @property {string} port
 * @property {string} user
 * @property {string} password
 * @property {string} database
 * @property {string} sslMode
 */

/**
 * @typedef {object} PostgresToolOptions
 * @property {string} [inputPath]
 * @property {import('node:stream').Writable} [outputStream]
 * @property {boolean} [captureOutput]
 */

const supportedSslModes = new Set([
	'disable',
	'allow',
	'prefer',
	'require',
	'verify-ca',
	'verify-full'
]);

/**
 * @param {string} input
 * @param {string} settingName
 * @returns {PostgresConnection}
 */
export function parsePostgresUrl(input, settingName) {
	let url;
	try {
		url = new URL(input);
	} catch {
		throw new Error(`${settingName} must be a PostgreSQL URL.`);
	}
	if (url.protocol !== 'postgres:' && url.protocol !== 'postgresql:') {
		throw new Error(`${settingName} must use the postgres or postgresql scheme.`);
	}
	const database = decodeURIComponent(url.pathname.replace(/^\//, ''));
	const user = decodeURIComponent(url.username);
	if (!url.hostname || !database || !user) {
		throw new Error(`${settingName} must include a host, user, and database name.`);
	}

	const allowedParameters = new Set(['sslmode']);
	for (const name of url.searchParams.keys()) {
		if (!allowedParameters.has(name)) {
			throw new Error(`${settingName} contains unsupported connection parameter ${name}.`);
		}
	}
	const sslMode = url.searchParams.get('sslmode') ?? 'prefer';
	if (!supportedSslModes.has(sslMode)) {
		throw new Error(`${settingName} contains an unsupported sslmode.`);
	}

	return {
		host: url.hostname,
		port: url.port || '5432',
		user,
		password: decodeURIComponent(url.password),
		database,
		sslMode
	};
}

/**
 * @param {PostgresConnection} connection
 * @param {string[]} toolArguments
 */
export function postgresComposeInvocation(connection, toolArguments) {
	return {
		command: 'docker',
		arguments: [
			'compose',
			'run',
			'--rm',
			'--no-deps',
			'-T',
			'-e',
			'PGHOST',
			'-e',
			'PGPORT',
			'-e',
			'PGUSER',
			'-e',
			'PGPASSWORD',
			'-e',
			'PGDATABASE',
			'-e',
			'PGSSLMODE',
			'db',
			...toolArguments
		],
		environment: {
			...process.env,
			PGHOST: connection.host,
			PGPORT: connection.port,
			PGUSER: connection.user,
			PGPASSWORD: connection.password,
			PGDATABASE: connection.database,
			PGSSLMODE: connection.sslMode
		}
	};
}

/**
 * @param {NodeJS.ProcessEnv} [environment]
 * @returns {Promise<PostgresConnection>}
 */
export async function configuredSourceConnection(environment = process.env) {
	const direct =
		environment['RUNWAY_BACKUP_DATABASE_URL'] ||
		environment['APP_DATABASE_URL'] ||
		environment['DATABASE_URL'];
	if (direct) return parsePostgresUrl(direct, 'RUNWAY_BACKUP_DATABASE_URL');

	const compose = JSON.parse(
		await captureHostCommand('docker', ['compose', 'config', '--format', 'json'], environment)
	);
	const configured = compose.services?.app?.environment?.DATABASE_URL;
	if (typeof configured !== 'string' || !configured) {
		throw new Error(
			'No database URL is configured. Set RUNWAY_BACKUP_DATABASE_URL or APP_DATABASE_URL.'
		);
	}
	return parsePostgresUrl(configured, 'Compose DATABASE_URL');
}

/**
 * @param {PostgresConnection} connection
 * @param {string} database
 * @returns {PostgresConnection}
 */
export function withDatabase(connection, database) {
	return { ...connection, database };
}

/**
 * @param {PostgresConnection} source
 * @param {PostgresConnection} target
 */
export function assertDifferentDatabase(source, target) {
	if (
		source.host.toLowerCase() === target.host.toLowerCase() &&
		source.port === target.port &&
		source.database === target.database
	) {
		throw new Error('The restore target must not be the configured runway database.');
	}
}

/** @param {string} path */
export async function assertPrivateBackupFile(path) {
	const record = await lstat(path);
	if (!record.isFile() || record.isSymbolicLink()) {
		throw new Error('The backup path must be a regular file, not a link.');
	}
	if ((record.mode & 0o077) !== 0) {
		throw new Error('The backup file must not be accessible to group or other users.');
	}
}

/**
 * @param {PostgresConnection} connection
 * @param {string} inputPath
 */
export async function inspectBackupArchive(connection, inputPath) {
	const listing = await runPostgresTool(connection, ['pg_restore', '--list'], {
		inputPath,
		captureOutput: true
	});
	const requiredEntries = [
		/ TABLE public user /,
		/ TABLE public training_plan /,
		/ TABLE public activity /,
		/ TABLE drizzle __drizzle_migrations /
	];
	if (requiredEntries.some((entry) => !entry.test(listing))) {
		throw new Error('The archive does not contain the required runway database objects.');
	}
}

/** @param {PostgresConnection} connection */
export async function verifyRestoredDatabase(connection) {
	/** @type {{
	 * canonical: Array<{ createdAt: string, hash: string }>,
	 * rebasedV011: Array<{ createdAt: string, hash: string }>,
	 * requiredTables: string[],
	 * requiredColumns: string[],
	 * requiredConstraints: string[],
	 * requiredIndexes: string[]
	 * }} */
	const integrity = JSON.parse(
		await readFile(new URL('../drizzle/migration-integrity.json', import.meta.url), 'utf8')
	);
	const compatibilityMigration = integrity.canonical.at(-1);
	if (!compatibilityMigration) throw new Error('The migration integrity manifest is empty.');
	const supportedLedgers = [
		integrity.canonical,
		[...integrity.rebasedV011, compatibilityMigration]
	];
	const ledgerChecks = supportedLedgers.map((entries) => {
		const expected = JSON.stringify(entries.map((entry) => [entry.createdAt, entry.hash]));
		return `coalesce((select jsonb_agg(jsonb_build_array(created_at::text, hash) order by created_at, id) from drizzle.__drizzle_migrations), '[]'::jsonb) = '${expected}'::jsonb`;
	});
	const tableChecks = integrity.requiredTables.map(
		(name) => `to_regclass('public."${name}"') is not null`
	);
	const columnChecks = integrity.requiredColumns.map((qualifiedName) => {
		const [tableName, columnName] = qualifiedName.split('.');
		return `exists (select 1 from information_schema.columns where table_schema = 'public' and table_name = '${tableName}' and column_name = '${columnName}')`;
	});
	const constraintChecks = integrity.requiredConstraints.map(
		(name) =>
			`exists (select 1 from information_schema.table_constraints where constraint_schema = 'public' and constraint_name = '${name}')`
	);
	const indexChecks = integrity.requiredIndexes.map(
		(name) => `to_regclass('public."${name}"') is not null`
	);
	const query = [
		'select case when',
		`(${ledgerChecks.join(' or ')})`,
		...tableChecks.map((check) => `and ${check}`),
		...columnChecks.map((check) => `and ${check}`),
		...constraintChecks.map((check) => `and ${check}`),
		...indexChecks.map((check) => `and ${check}`),
		"then 'ready' else 'not-ready' end;"
	].join(' ');
	const output = await runPostgresTool(
		connection,
		[
			'psql',
			'--no-psqlrc',
			'--tuples-only',
			'--no-align',
			'--set',
			'ON_ERROR_STOP=1',
			'--command',
			query
		],
		{ captureOutput: true }
	);
	if (output.trim() !== 'ready') {
		throw new Error('The restored database does not match this runway migration journal.');
	}
}

/** @param {PostgresConnection} connection */
export async function databaseIsEmpty(connection) {
	const output = await runPostgresTool(
		connection,
		[
			'psql',
			'--no-psqlrc',
			'--tuples-only',
			'--no-align',
			'--set',
			'ON_ERROR_STOP=1',
			'--command',
			"select count(*) from pg_tables where schemaname not in ('pg_catalog', 'information_schema');"
		],
		{ captureOutput: true }
	);
	return output.trim() === '0';
}

/**
 * @param {PostgresConnection} connection
 * @param {string[]} toolArguments
 * @param {PostgresToolOptions} [options]
 * @returns {Promise<string>}
 */
export async function runPostgresTool(connection, toolArguments, options = {}) {
	const { inputPath, outputStream, captureOutput = false } = options;
	const invocation = postgresComposeInvocation(connection, toolArguments);
	const child = spawn(invocation.command, invocation.arguments, {
		env: invocation.environment,
		stdio: ['pipe', 'pipe', 'pipe']
	});
	const stderrPromise = consume(child.stderr, 32 * 1024);
	const inputPromise = inputPath
		? pipeline(createReadStream(inputPath), child.stdin)
		: Promise.resolve(child.stdin.end());
	let outputPromise;
	if (outputStream) {
		outputPromise = pipeline(child.stdout, outputStream).then(() => '');
	} else if (captureOutput) {
		outputPromise = consume(child.stdout, 4 * 1024 * 1024);
	} else {
		child.stdout.resume();
		outputPromise = Promise.resolve('');
	}
	/** @type {Promise<void>} */
	const exitPromise = new Promise((resolve, reject) => {
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`PostgreSQL tool exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});

	try {
		const [output] = await Promise.all([outputPromise, inputPromise, stderrPromise, exitPromise]);
		return output;
	} catch {
		child.kill('SIGTERM');
		throw new Error('The PostgreSQL operation failed; database details were withheld.');
	}
}

/**
 * @param {string} command
 * @param {string[]} arguments_
 * @param {NodeJS.ProcessEnv} environment
 */
async function captureHostCommand(command, arguments_, environment) {
	const child = spawn(command, arguments_, { env: environment, stdio: ['ignore', 'pipe', 'pipe'] });
	const outputPromise = consume(child.stdout, 4 * 1024 * 1024);
	const stderrPromise = consume(child.stderr, 32 * 1024);
	/** @type {Promise<void>} */
	const exitPromise = new Promise((resolve, reject) => {
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`Command exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
	try {
		const [output] = await Promise.all([outputPromise, stderrPromise, exitPromise]);
		return output;
	} catch {
		throw new Error('The Compose database configuration could not be read.');
	}
}

/**
 * @param {import('node:stream').Readable} stream
 * @param {number} maximumBytes
 * @returns {Promise<string>}
 */
function consume(stream, maximumBytes) {
	return new Promise((resolve, reject) => {
		let output = '';
		let size = 0;
		stream.setEncoding('utf8');
		stream.on('data', (/** @type {string} */ chunk) => {
			size += Buffer.byteLength(chunk);
			if (size > maximumBytes) {
				reject(new Error('Command output exceeded its safe bound.'));
				return;
			}
			output += chunk;
		});
		stream.once('error', reject);
		stream.once('end', () => resolve(output));
	});
}
