import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import postgres from 'postgres';
import { assertFinalMigrationState } from './migration-state.mjs';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const databaseName = `runway_migration_${process.pid}_${Date.now()}`.slice(0, 63);
const nonemptyDatabaseName = `runway_nonempty_${process.pid}_${Date.now()}`.slice(0, 63);
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const expectedMigration = journal.entries.at(-1);

if (!expectedMigration) throw new Error('The Drizzle migration journal is empty.');
await verifySnapshotParity();
if (baseDatabaseUrl === defaultDatabaseUrl) {
	await run('docker', ['compose', 'up', '-d', '--wait', 'db']);
}

const base = new URL(baseDatabaseUrl);
assertLocalDatabase(base);
const adminUrl = new URL(base);
adminUrl.pathname = '/postgres';
const databaseUrl = new URL(base);
databaseUrl.pathname = `/${databaseName}`;
const nonemptyDatabaseUrl = new URL(base);
nonemptyDatabaseUrl.pathname = `/${nonemptyDatabaseName}`;
let databaseCreated = false;
let nonemptyDatabaseCreated = false;

try {
	await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);
	databaseCreated = true;
	await withSql(adminUrl, (sql) => sql`create database ${sql(nonemptyDatabaseName)}`);
	nonemptyDatabaseCreated = true;

	await withSql(
		nonemptyDatabaseUrl,
		(sql) => sql`
		create table public.preexisting_probe (id integer primary key)
	`
	);
	await runExpectingFailure(
		'node',
		['scripts/run-migrations.mjs'],
		{
			...process.env,
			DATABASE_URL: nonemptyDatabaseUrl.toString()
		},
		'Database has no supported migration ledger but is not empty.'
	);
	await withSql(nonemptyDatabaseUrl, async (sql) => {
		const [state] = await sql`
			select
				to_regclass('public.preexisting_probe') is not null as "probeExists",
				to_regclass('public.user') is not null as "runwayTableExists",
				to_regclass('drizzle.__drizzle_migrations') is not null as "ledgerExists"
		`;
		if (!state?.probeExists || state.runwayTableExists || state.ledgerExists) {
			throw new Error('The rejected non-empty database was changed by the migration runner.');
		}
	});

	await run('node', ['scripts/run-migrations.mjs'], {
		...process.env,
		DATABASE_URL: databaseUrl.toString()
	});
	await run('node', ['scripts/run-migrations.mjs'], {
		...process.env,
		DATABASE_URL: databaseUrl.toString()
	});

	await withSql(databaseUrl, async (sql) => {
		const [migrationState] = await sql`
			select
				count(*)::int as count,
				max(created_at)::text as "latestMigration"
			from drizzle.__drizzle_migrations
		`;
		if (
			migrationState?.count !== journal.entries.length ||
			migrationState.latestMigration !== String(expectedMigration.when)
		) {
			throw new Error('Fresh database did not reach the complete migration journal.');
		}

		await assertFinalMigrationState(sql);

		const [decisionIndex] = await sql`
			select indexdef as "indexDefinition"
			from pg_indexes
			where schemaname = 'public' and indexname = 'plan_adjustment_active_decision_unique'
		`;
		if (!decisionIndex?.indexDefinition?.includes('UNIQUE')) {
			throw new Error('Fresh database is missing the active-decision uniqueness guard.');
		}
	});

	console.log(
		`Clean-install baseline ${expectedMigration.tag} rejected a non-empty database, then passed an empty install and idempotent rerun.`
	);
} finally {
	if (databaseCreated) {
		await withSql(
			adminUrl,
			(sql) => sql`drop database if exists ${sql(databaseName)} with (force)`
		);
	}
	if (nonemptyDatabaseCreated) {
		await withSql(
			adminUrl,
			(sql) => sql`drop database if exists ${sql(nonemptyDatabaseName)} with (force)`
		);
	}
}

async function verifySnapshotParity() {
	const temporaryRoot = await mkdtemp('.runway-drizzle-');
	const temporaryOutput = join(temporaryRoot, 'drizzle');
	const temporaryConfig = join(temporaryRoot, 'drizzle.config.mjs');
	try {
		await cp('drizzle', temporaryOutput, { recursive: true });
		await writeFile(
			temporaryConfig,
			`export default ${JSON.stringify({
				schema: resolve('src/lib/server/db/schema.ts'),
				out: temporaryOutput,
				dialect: 'postgresql',
				dbCredentials: { url: baseDatabaseUrl },
				strict: true
			})};\n`
		);
		await run('corepack', ['pnpm', 'exec', 'drizzle-kit', 'generate', '--config', temporaryConfig]);
		const generatedJournal = JSON.parse(
			await readFile(join(temporaryOutput, 'meta', '_journal.json'), 'utf8')
		);
		if (generatedJournal.entries.length !== journal.entries.length) {
			throw new Error(
				'The Drizzle snapshot is stale. Generate and review a migration before continuing.'
			);
		}
	} finally {
		await rm(temporaryRoot, { recursive: true, force: true });
	}
}

async function withSql(url, callback) {
	const sql = postgres(url.toString(), { max: 1 });
	try {
		return await callback(sql);
	} finally {
		await sql.end();
	}
}

function assertLocalDatabase(url) {
	if (
		!['127.0.0.1', 'localhost', '::1'].includes(url.hostname) &&
		process.env['RUNWAY_TEST_ALLOW_REMOTE_DATABASE'] !== 'true'
	) {
		throw new Error('Fresh-migration verification only creates databases on a loopback server.');
	}
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

function runExpectingFailure(command, args, env, expectedMessage) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env, stdio: ['ignore', 'pipe', 'pipe'] });
		let output = '';
		child.stdout.setEncoding('utf8');
		child.stderr.setEncoding('utf8');
		child.stdout.on('data', (chunk) => {
			output += chunk;
		});
		child.stderr.on('data', (chunk) => {
			output += chunk;
		});
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) {
				reject(new Error(`${command} unexpectedly accepted a non-empty database.`));
			} else if (!output.includes(expectedMessage)) {
				reject(
					new Error(
						`${command} failed with ${signal ?? code ?? 'unknown status'} for an unexpected reason.`
					)
				);
			} else {
				resolve();
			}
		});
	});
}
