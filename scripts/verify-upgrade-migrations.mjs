import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const databaseName = `runway_upgrade_${process.pid}_${Date.now()}`.slice(0, 63);
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const legacyEntries = journal.entries.slice(0, -1);
const upgradeMigration = journal.entries.at(-1);
const migrationImage = process.env['RUNWAY_MIGRATION_IMAGE'];

if (
	legacyEntries.length !== 22 ||
	legacyEntries.at(-1)?.tag !== '0021_private_activity_traces' ||
	upgradeMigration?.tag !== '0022_forward_compatible_upgrade'
) {
	throw new Error(
		'Upgrade verification requires the released 22-migration lineage and one forward migration.'
	);
}

if (baseDatabaseUrl === defaultDatabaseUrl) {
	await run('docker', ['compose', 'up', '-d', '--wait', 'db']);
}

const base = new URL(baseDatabaseUrl);
assertLocalDatabase(base);
const adminUrl = new URL(base);
adminUrl.pathname = '/postgres';
const databaseUrl = new URL(base);
databaseUrl.pathname = `/${databaseName}`;
const temporaryRoot = await mkdtemp('.runway-upgrade-');
const legacyFolder = join(temporaryRoot, 'drizzle');

await cp('drizzle', legacyFolder, { recursive: true });
await rm(join(legacyFolder, `${upgradeMigration.tag}.sql`));
await writeFile(
	join(legacyFolder, 'meta', '_journal.json'),
	`${JSON.stringify({ ...journal, entries: legacyEntries }, null, 2)}\n`
);
await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);

try {
	await migrateDatabase(databaseUrl, legacyFolder);
	await withSql(databaseUrl, async (sql) => {
		const [state] = await sql`
			select count(*)::int as count, max(created_at)::text as "latestMigration"
			from drizzle.__drizzle_migrations
		`;
		if (
			state?.count !== legacyEntries.length ||
			state.latestMigration !== String(legacyEntries.at(-1).when)
		) {
			throw new Error('Legacy database did not reach the exact 22-migration release state.');
		}

		await sql`
			insert into "user" ("id", "name", "email", "email_verified", "created_at", "updated_at")
			values ('migration-upgrade-probe', 'Migration probe', 'migration-probe@example.invalid', false, now(), now())
		`;
		await seedDuplicateActiveDecisions(sql);
	});

	await runMigrationRunner(databaseUrl);
	await runMigrationRunner(databaseUrl);

	await withSql(databaseUrl, async (sql) => {
		const [state] = await sql`
			select count(*)::int as count, max(created_at)::text as "latestMigration"
			from drizzle.__drizzle_migrations
		`;
		if (
			state?.count !== journal.entries.length ||
			state.latestMigration !== String(upgradeMigration.when)
		) {
			throw new Error(
				'Upgraded database did not reach the complete migration journal exactly once.'
			);
		}

		const [preserved] = await sql`
			select count(*)::int as count from "user" where "id" = 'migration-upgrade-probe'
		`;
		if (preserved?.count !== 1)
			throw new Error('Upgrade did not preserve the existing data probe.');

		const tables = await sql`
			select table_name as "tableName"
			from information_schema.tables
			where table_schema = 'public'
			  and table_name in ('android_device', 'android_import_request', 'android_pairing_request', 'import_operation_lease')
		`;
		if (tables.length !== 4) throw new Error('Upgrade is missing one or more post-v0.1.0 tables.');

		const columns = await sql`
			select table_name as "tableName", column_name as "columnName"
			from information_schema.columns
			where table_schema = 'public'
			  and (
			    (table_name = 'activity' and column_name = 'consequence_plan_id')
			    or (table_name = 'athlete_profile' and column_name = 'browser_folder_generation')
			  )
		`;
		if (columns.length !== 2)
			throw new Error('Upgrade is missing one or more post-v0.1.0 columns.');

		const [foreignKey] = await sql`
			select count(*)::int as count
			from information_schema.table_constraints
			where constraint_schema = 'public'
			  and constraint_name = 'activity_consequence_plan_user_fk'
		`;
		if (foreignKey?.count !== 1)
			throw new Error('Upgrade is missing the activity consequence foreign key.');

		const [decisionIndex] = await sql`
			select indexdef as "indexDefinition"
			from pg_indexes
			where schemaname = 'public' and indexname = 'plan_adjustment_active_decision_unique'
		`;
		if (!decisionIndex?.indexDefinition?.includes('UNIQUE')) {
			throw new Error('Upgrade is missing the active-decision uniqueness guard.');
		}

		const decisions = await sql`
			select "id", "reversed_at" as "reversedAt", "reversal_reason" as "reversalReason"
			from "plan_adjustment"
			where "trigger_id" = '10000000-0000-4000-8000-000000000005'
			order by "created_at", "id"
		`;
		if (
			decisions.length !== 2 ||
			decisions.filter((decision) => decision.reversedAt === null).length !== 1 ||
			decisions.filter(
				(decision) =>
					decision.reversedAt !== null &&
					decision.reversalReason === 'migration: superseded duplicate decision'
			).length !== 1
		) {
			throw new Error('Upgrade did not deterministically repair duplicate active decisions.');
		}
	});

	console.log(
		`Migration upgrade verified from ${legacyEntries.at(-1).tag} to ${upgradeMigration.tag} with existing data preserved.`
	);
} finally {
	await withSql(adminUrl, (sql) => sql`drop database if exists ${sql(databaseName)} with (force)`);
	await rm(temporaryRoot, { recursive: true, force: true });
}

async function migrateDatabase(url, migrationsFolder) {
	const client = postgres(url.toString(), { max: 1, onnotice: () => undefined });
	try {
		await migrate(drizzle(client), { migrationsFolder });
	} finally {
		await client.end();
	}
}

async function runMigrationRunner(url) {
	if (migrationImage) {
		await run('docker', [
			'run',
			'--rm',
			'--network',
			'host',
			'-e',
			`DATABASE_URL=${url.toString()}`,
			migrationImage,
			'node',
			'scripts/run-migrations.mjs'
		]);
		return;
	}
	await run('node', ['scripts/run-migrations.mjs'], {
		...process.env,
		DATABASE_URL: url.toString()
	});
}

async function seedDuplicateActiveDecisions(sql) {
	await sql`
		insert into "goal" (
			"id", "user_id", "title", "kind", "state", "start_mode", "distance", "target_date", "priority"
		) values (
			'10000000-0000-4000-8000-000000000001', 'migration-upgrade-probe', 'Upgrade probe',
			'race', 'active', 'established', '5k', current_date + 7, 'finish_healthy'
		)
	`;
	await sql`
		insert into "training_plan" (
			"id", "user_id", "goal_id", "status", "phase", "start_date", "target_date", "weeks", "risk", "plan_summary"
		) values (
			'10000000-0000-4000-8000-000000000002', 'migration-upgrade-probe',
			'10000000-0000-4000-8000-000000000001', 'active', 'distance', current_date,
			current_date + 7, 1, 'conservative', '{"kind":"distance"}'::jsonb
		)
	`;
	await sql`
		insert into "training_week" (
			"id", "user_id", "plan_id", "week_number", "start_date", "target_distance_meters",
			"target_duration_seconds", "long_run_meters", "risk"
		) values (
			'10000000-0000-4000-8000-000000000003', 'migration-upgrade-probe',
			'10000000-0000-4000-8000-000000000002', 1, current_date, 3000, 0, 3000, 'conservative'
		)
	`;
	await sql`
		insert into "workout" (
			"id", "user_id", "plan_id", "week_id", "scheduled_date", "type", "status",
			"prescription_kind", "target_distance_meters", "intensity", "purpose", "reason"
		) values (
			'10000000-0000-4000-8000-000000000004', 'migration-upgrade-probe',
			'10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000003',
			current_date, 'easy', 'planned', 'distance', 3000, 'easy', 'Upgrade probe', 'Upgrade probe'
		)
	`;
	await sql`
		insert into "plan_adjustment" (
			"id", "user_id", "plan_id", "workout_id", "trigger_type", "trigger_id",
			"previous_target_distance_meters", "new_target_distance_meters",
			"previous_scheduled_date", "new_scheduled_date", "previous_state", "new_state", "reason", "created_at"
		) values
		(
			'10000000-0000-4000-8000-000000000006', 'migration-upgrade-probe',
			'10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000004',
			'decision', '10000000-0000-4000-8000-000000000005', 3000, 2800, current_date, current_date,
			'{}'::jsonb, '{}'::jsonb, 'Older duplicate', now() - interval '1 minute'
		),
		(
			'10000000-0000-4000-8000-000000000007', 'migration-upgrade-probe',
			'10000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000004',
			'decision', '10000000-0000-4000-8000-000000000005', 3000, 2600, current_date, current_date,
			'{}'::jsonb, '{}'::jsonb, 'Current decision', now()
		)
	`;
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
		throw new Error('Migration-upgrade verification only creates databases on a loopback server.');
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
