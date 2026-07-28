import { spawn } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { assertFinalMigrationState, assertSupportedMigrationLedger } from './migration-state.mjs';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const databaseName = `runway_migration_${process.pid}_${Date.now()}`.slice(0, 63);
const nonemptyDatabaseName = `runway_nonempty_${process.pid}_${Date.now()}`.slice(0, 63);
const predecessorDatabaseName = `runway_predecessor_${process.pid}_${Date.now()}`.slice(0, 63);
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const expectedMigration = journal.entries.at(-1);
const predecessorMigration = journal.entries.at(-2);
const predecessorJournal = journal.entries.slice(0, -1);

if (!expectedMigration) throw new Error('The Drizzle migration journal is empty.');
if (!predecessorMigration) {
	throw new Error(
		'Migration verification needs an immediate predecessor migration to test upgrades.'
	);
}
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
const predecessorDatabaseUrl = new URL(base);
predecessorDatabaseUrl.pathname = `/${predecessorDatabaseName}`;
let databaseCreated = false;
let nonemptyDatabaseCreated = false;
let predecessorDatabaseCreated = false;

try {
	await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);
	databaseCreated = true;
	await withSql(adminUrl, (sql) => sql`create database ${sql(nonemptyDatabaseName)}`);
	nonemptyDatabaseCreated = true;
	await withSql(adminUrl, (sql) => sql`create database ${sql(predecessorDatabaseName)}`);
	predecessorDatabaseCreated = true;

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

	await applyImmediatePredecessor(predecessorDatabaseUrl);
	await withSql(predecessorDatabaseUrl, async (sql) => {
		await assertSupportedMigrationLedger(sql);
		await sql`
			insert into "user" (id, name, email)
			values ('migration-upgrade-fixture-user', 'Migration upgrade fixture', 'migration-upgrade-fixture@example.invalid')
		`;
		await sql`
			insert into athlete_profile (id, user_id, current_weekly_distance_meters)
			values ('00000000-0000-4000-8000-000000000001', 'migration-upgrade-fixture-user', 42000)
		`;
	});
	await run('node', ['scripts/run-migrations.mjs'], {
		...process.env,
		DATABASE_URL: predecessorDatabaseUrl.toString()
	});
	await run('node', ['scripts/run-migrations.mjs'], {
		...process.env,
		DATABASE_URL: predecessorDatabaseUrl.toString()
	});
	await withSql(predecessorDatabaseUrl, async (sql) => {
		await assertFinalMigrationState(sql);
		const [state] = await sql`
			select
				exists (
					select 1
					from athlete_profile
					where id = '00000000-0000-4000-8000-000000000001'
						and user_id = 'migration-upgrade-fixture-user'
						and current_weekly_distance_meters = 42000
				) as "sentinelPreserved",
				to_regclass('public.device_code') is not null as "deviceCodeExists",
				to_regclass('public.mobile_request_receipt') is not null as "mobileRequestReceiptExists",
				not exists (
					select 1
					from information_schema.columns
					where table_schema = 'public'
						and table_name = 'athlete_profile'
						and column_name = 'browser_folder_generation'
				) as "obsoleteColumnRemoved"
				,
				exists (
					select 1
					from information_schema.columns
					where table_schema = 'public'
						and table_name = 'session'
						and column_name = 'mobile_client_id'
				) as "mobileSessionScopeExists"
		`;
		if (
			!state?.sentinelPreserved ||
			!state.deviceCodeExists ||
			!state.mobileRequestReceiptExists ||
			!state.obsoleteColumnRemoved ||
			!state.mobileSessionScopeExists
		) {
			throw new Error(
				'Immediate-predecessor upgrade did not preserve data and reach the required schema.'
			);
		}
	});

	console.log(
		`Clean-install baseline ${expectedMigration.tag} rejected a non-empty database; empty install and exact ${predecessorMigration.tag} upgrade both passed idempotent reruns.`
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
	if (predecessorDatabaseCreated) {
		await withSql(
			adminUrl,
			(sql) => sql`drop database if exists ${sql(predecessorDatabaseName)} with (force)`
		);
	}
}

async function applyImmediatePredecessor(url) {
	const temporaryRoot = await mkdtemp('.runway-predecessor-');
	const migrationsFolder = join(temporaryRoot, 'drizzle');
	try {
		await mkdir(join(migrationsFolder, 'meta'), { recursive: true });
		for (const migration of predecessorJournal) {
			await cp(
				join('drizzle', `${migration.tag}.sql`),
				join(migrationsFolder, `${migration.tag}.sql`)
			);
		}
		await writeFile(
			join(migrationsFolder, 'meta', '_journal.json'),
			`${JSON.stringify({ ...journal, entries: predecessorJournal }, null, '\t')}\n`
		);
		await withSql(url, (sql) => migrate(drizzle(sql), { migrationsFolder }));
	} finally {
		await rm(temporaryRoot, { recursive: true, force: true });
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
