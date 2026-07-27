import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { verifyRestoredDatabase } from './database-backup-lib.mjs';
import { assertFinalMigrationState } from './migration-state.mjs';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const integrity = JSON.parse(await readFile('drizzle/migration-integrity.json', 'utf8'));
const migrationImage = process.env['RUNWAY_MIGRATION_IMAGE'];
const latestMigration = journal.entries.at(-1);
const releasedV001Entries = integrity.releasedV001.entries;
const releasedV001ForwardMigrationIndex = integrity.canonical.findIndex(
	(entry) => entry.tag === integrity.releasedV001.forwardFrom
);
if (releasedV001ForwardMigrationIndex !== releasedV001Entries.length) {
	throw new Error('Upgrade verification requires the exact v0.0.1 forward cutover.');
}
const releasedV001Final = [
	...releasedV001Entries,
	...integrity.canonical.slice(releasedV001ForwardMigrationIndex)
];
const releasedV001FixtureFolder = 'tests/fixtures/migrations/v0.0.1/drizzle';
const supportedCanonicalPredecessors = [
	{ tag: '0021_private_activity_traces', repairDuplicateDecisions: true },
	{ tag: '0022_forward_compatible_upgrade', repairDuplicateDecisions: false }
];

if (latestMigration?.tag !== '0024_groovy_excalibur') {
	throw new Error('Upgrade verification requires the current Health Connect migration.');
}

if (baseDatabaseUrl === defaultDatabaseUrl) {
	await run('docker', ['compose', 'up', '-d', '--wait', 'db']);
}

const base = new URL(baseDatabaseUrl);
assertLocalDatabase(base);
const adminUrl = new URL(base);
adminUrl.pathname = '/postgres';

await verifyUpgrade({
	label: 'released v0.0.1',
	initialEntries: releasedV001Entries,
	finalEntries: releasedV001Final,
	fixtureFolder: releasedV001FixtureFolder,
	repairDuplicateDecisions: true
});
for (const predecessor of supportedCanonicalPredecessors) {
	await verifyUpgrade(canonicalScenario(predecessor));
}

console.log(
	`Migration upgrades verified from released v0.0.1, ${supportedCanonicalPredecessors.map(({ tag }) => tag).join(' and ')}, through ${latestMigration.tag}, with existing data preserved and reruns idempotent.`
);

function canonicalScenario({ tag, repairDuplicateDecisions }) {
	const predecessorIndex = journal.entries.findIndex((entry) => entry.tag === tag);
	if (predecessorIndex < 0) throw new Error(`Missing supported migration predecessor ${tag}.`);
	const predecessorEntries = journal.entries.slice(0, predecessorIndex + 1);
	const pendingEntries = journal.entries.slice(predecessorIndex + 1);
	if (pendingEntries.length === 0) {
		throw new Error(`Migration predecessor ${tag} does not have a forward upgrade.`);
	}
	return {
		label: tag,
		initialEntries: integrity.canonical.slice(0, predecessorIndex + 1),
		finalEntries: integrity.canonical,
		predecessorEntries,
		pendingEntries,
		repairDuplicateDecisions
	};
}

async function verifyUpgrade({
	label,
	initialEntries,
	finalEntries,
	fixtureFolder,
	predecessorEntries,
	pendingEntries,
	repairDuplicateDecisions
}) {
	const databaseName =
		`runway_upgrade_${label.replaceAll(/[^a-z0-9]/gi, '_')}_${process.pid}_${Date.now()}`.slice(
			0,
			63
		);
	const databaseUrl = new URL(base);
	databaseUrl.pathname = `/${databaseName}`;
	let temporaryRoot;
	let predecessorFolder = fixtureFolder;

	if (!predecessorFolder) {
		temporaryRoot = await mkdtemp('.runway-upgrade-');
		predecessorFolder = join(temporaryRoot, 'drizzle');
		await cp('drizzle', predecessorFolder, { recursive: true });
		for (const pending of pendingEntries) {
			await rm(join(predecessorFolder, `${pending.tag}.sql`));
		}
		await writeFile(
			join(predecessorFolder, 'meta', '_journal.json'),
			`${JSON.stringify({ ...journal, entries: predecessorEntries }, null, 2)}\n`
		);
	}
	await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);

	try {
		await migrateDatabase(databaseUrl, predecessorFolder);
		await withSql(databaseUrl, async (sql) => {
			await assertExactLedger(sql, initialEntries, `${label} initial database`);
			await seedPreservedData(sql, repairDuplicateDecisions);
		});
		if (fixtureFolder && baseDatabaseUrl === defaultDatabaseUrl) {
			const restoreConnection = {
				host: 'db',
				port: '5432',
				user: decodeURIComponent(base.username),
				password: decodeURIComponent(base.password),
				database: databaseName,
				sslMode: 'disable'
			};
			const restoreState = await verifyRestoredDatabase(restoreConnection, {
				allowSupportedPredecessor: true
			});
			if (restoreState !== 'supported-predecessor') {
				throw new Error(`${label} was not recognized as a supported restore predecessor.`);
			}
			await assertCurrentRestorePolicyRejectsPredecessor(restoreConnection, label);
		}

		await runMigrationRunner(databaseUrl);
		const firstRunState = await withSql(databaseUrl, async (sql) => {
			await assertExactLedger(sql, finalEntries, `${label} first upgrade`);
			await assertFinalMigrationState(sql);
			return captureUpgradeState(sql);
		});
		if (fixtureFolder && baseDatabaseUrl === defaultDatabaseUrl) {
			const restoreState = await verifyRestoredDatabase({
				host: 'db',
				port: '5432',
				user: decodeURIComponent(base.username),
				password: decodeURIComponent(base.password),
				database: databaseName,
				sslMode: 'disable'
			});
			if (restoreState !== 'current') {
				throw new Error(`${label} upgraded database was not recognized as current.`);
			}
		}
		await runMigrationRunner(databaseUrl);

		await withSql(databaseUrl, async (sql) => {
			await assertExactLedger(sql, finalEntries, `${label} second upgrade`);
			await assertFinalMigrationState(sql);
			const secondRunState = await captureUpgradeState(sql);
			if (JSON.stringify(secondRunState) !== JSON.stringify(firstRunState)) {
				throw new Error(`${label} migration rerun changed its ledger or preserved probe data.`);
			}

			const [preserved] = await sql`
				select
					u."id",
					t."secret",
					t."failed_verification_count" as "failedVerificationCount",
					t."locked_until" as "lockedUntil",
					p."current_weekly_distance_meters" as "weeklyDistance",
					a."distance_meters" as "activityDistance",
					a."duration_seconds" as "activityDuration",
					a."source"::text as "activitySource"
				from "user" u
				join "two_factor" t on t."user_id" = u."id"
				join "athlete_profile" p on p."user_id" = u."id"
				join "activity" a on a."user_id" = u."id"
				where u."id" = 'migration-upgrade-probe'
			`;
			if (
				preserved?.id !== 'migration-upgrade-probe' ||
				preserved.secret !== 'preserved-secret' ||
				preserved.failedVerificationCount !== 0 ||
				preserved.lockedUntil !== null ||
				preserved.weeklyDistance !== 5000 ||
				preserved.activityDistance !== 3200 ||
				preserved.activityDuration !== 1800 ||
				preserved.activitySource !== 'gpx'
			) {
				throw new Error(`${label} upgrade did not preserve representative existing data.`);
			}

			if (repairDuplicateDecisions) await verifyRepairedDuplicateDecisions(sql);
		});
	} finally {
		await withSql(
			adminUrl,
			(sql) => sql`drop database if exists ${sql(databaseName)} with (force)`
		);
		if (temporaryRoot) await rm(temporaryRoot, { recursive: true, force: true });
	}
}

async function assertCurrentRestorePolicyRejectsPredecessor(connection, label) {
	try {
		await verifyRestoredDatabase(connection);
	} catch (error) {
		if (
			error instanceof Error &&
			error.message.includes('exact supported predecessor') &&
			error.message.includes('must be migrated')
		) {
			return;
		}
		throw error;
	}
	throw new Error(`${label} restore was treated as current before its forward migrations.`);
}

async function assertExactLedger(sql, expectedEntries, label) {
	const rows = await sql`
		select "hash", "created_at"::text as "createdAt"
		from drizzle.__drizzle_migrations
		order by "created_at", "id"
	`;
	if (
		rows.length !== expectedEntries.length ||
		rows.some(
			(row, index) =>
				row.hash !== expectedEntries[index]?.hash ||
				row.createdAt !== expectedEntries[index]?.createdAt
		)
	) {
		throw new Error(`${label} does not have its exact ordered migration ledger.`);
	}
}

async function seedPreservedData(sql, repairDuplicateDecisions) {
	await sql`
		insert into "user" ("id", "name", "email", "email_verified", "created_at", "updated_at")
		values ('migration-upgrade-probe', 'Migration probe', 'migration-probe@example.invalid', false, now(), now())
	`;
	await sql`
		insert into "two_factor" ("id", "secret", "backup_codes", "user_id", "verified")
		values ('migration-two-factor-probe', 'preserved-secret', '[]', 'migration-upgrade-probe', true)
	`;
	await sql`
		insert into "athlete_profile" (
			"id", "user_id", "current_weekly_distance_meters", "current_runs_per_week",
			"longest_recent_run_meters"
		) values (
			'10000000-0000-4000-8000-000000000008', 'migration-upgrade-probe', 5000, 2, 3200
		)
	`;
	await sql`
		insert into "activity" (
			"id", "user_id", "source", "occurred_at", "activity_date", "distance_meters",
			"duration_seconds", "route_summary", "review_state", "deviation"
		) values (
			'10000000-0000-4000-8000-000000000009', 'migration-upgrade-probe', 'gpx',
			now(), current_date, 3200, 1800,
			'{"pointCount":42,"startEndRedacted":true,"hasElevation":true}'::jsonb,
			'accepted', 'unplanned'
		)
	`;
	if (repairDuplicateDecisions) await seedDuplicateActiveDecisions(sql);
}

async function captureUpgradeState(sql) {
	const [ledger, user, profile, activity, decisions] = await Promise.all([
		sql`
			select "hash", "created_at"::text as "createdAt"
			from drizzle.__drizzle_migrations
			order by "created_at", "id"
		`,
		sql`
			select "id", "name", "email", "two_factor_enabled" as "twoFactorEnabled"
			from "user"
			where "id" = 'migration-upgrade-probe'
		`,
		sql`
			select "id", "current_weekly_distance_meters" as "weeklyDistance"
			from "athlete_profile"
			where "user_id" = 'migration-upgrade-probe'
		`,
		sql`
			select "id", "distance_meters" as "distance", "duration_seconds" as "duration"
			from "activity"
			where "user_id" = 'migration-upgrade-probe'
		`,
		sql`
			select "id", "reversed_at"::text as "reversedAt", "reversal_reason" as "reversalReason"
			from "plan_adjustment"
			where "user_id" = 'migration-upgrade-probe'
			order by "id"
		`
	]);
	return { ledger, user, profile, activity, decisions };
}

async function verifyRepairedDuplicateDecisions(sql) {
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
