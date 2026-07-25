import { spawn } from 'node:child_process';
import { cp, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const migrationImage = process.env['RUNWAY_MIGRATION_IMAGE'];
const integrity = JSON.parse(await readFile('drizzle/migration-integrity.json', 'utf8'));
const releasedFixtureJournal = JSON.parse(
	await readFile('tests/fixtures/migrations/v0.1.1/drizzle/meta/_journal.json', 'utf8')
);
const compatibilityMigrationIndex = integrity.canonical.findIndex(
	(entry) => entry.tag === '0022_forward_compatible_upgrade'
);
const forwardMigrations =
	compatibilityMigrationIndex < 0 ? [] : integrity.canonical.slice(compatibilityMigrationIndex);
const latestMigration = integrity.canonical.at(-1);
const rebasedEntries = integrity.rebasedV011;
const expectedFinalEntries = [...rebasedEntries, ...forwardMigrations];
const releasedFixtureFolder = 'tests/fixtures/migrations/v0.1.1/drizzle';
const compatibilityMigration = forwardMigrations[0];

if (
	compatibilityMigrationIndex < 0 ||
	forwardMigrations[0]?.tag !== '0022_forward_compatible_upgrade' ||
	latestMigration?.tag !== '0023_two_factor_attempt_lockout'
) {
	throw new Error('Rebased-history verification requires the complete forward migration chain.');
}
if (baseDatabaseUrl === defaultDatabaseUrl) {
	await run('docker', ['compose', 'up', '-d', '--wait', 'db']);
}

const base = new URL(baseDatabaseUrl);
assertLocalDatabase(base);
const adminUrl = new URL(base);
adminUrl.pathname = '/postgres';
const temporaryRoot = await mkdtemp('.runway-rebased-');
const compatibleFixtureFolder = join(temporaryRoot, 'drizzle');

try {
	await cp(releasedFixtureFolder, compatibleFixtureFolder, { recursive: true });
	await cp(
		`drizzle/${compatibilityMigration.tag}.sql`,
		join(compatibleFixtureFolder, `${compatibilityMigration.tag}.sql`)
	);
	await writeFile(
		join(compatibleFixtureFolder, 'meta', '_journal.json'),
		`${JSON.stringify(
			{
				...releasedFixtureJournal,
				entries: [
					...releasedFixtureJournal.entries,
					{
						idx: releasedFixtureJournal.entries.length,
						version: '7',
						when: Number(compatibilityMigration.createdAt),
						tag: compatibilityMigration.tag,
						breakpoints: true
					}
				]
			},
			null,
			2
		)}\n`
	);

	const scenarios = [
		{
			label: 'the three-entry v0.1.1 rebased history',
			folder: releasedFixtureFolder,
			expectedInitialEntries: rebasedEntries
		},
		{
			label: 'the rebased v0.1.1 history after 0022',
			folder: compatibleFixtureFolder,
			expectedInitialEntries: [...rebasedEntries, compatibilityMigration]
		}
	];

	for (const [index, scenario] of scenarios.entries()) {
		await verifyRebasedUpgrade(scenario, index);
	}
} finally {
	await rm(temporaryRoot, { recursive: true, force: true });
}

async function verifyRebasedUpgrade({ label, folder, expectedInitialEntries }, scenarioIndex) {
	const databaseName = `runway_rebased_${scenarioIndex}_${process.pid}_${Date.now()}`.slice(0, 63);
	const databaseUrl = new URL(base);
	databaseUrl.pathname = `/${databaseName}`;
	await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);

	try {
		await migrateDatabase(databaseUrl, folder);
		await withSql(databaseUrl, async (sql) => {
			const ledger = await sql`
				select "hash", "created_at"::text as "createdAt"
				from drizzle.__drizzle_migrations
				order by "created_at", "id"
			`;
			if (
				ledger.length !== expectedInitialEntries.length ||
				ledger.some(
					(entry, index) =>
						entry.hash !== expectedInitialEntries[index]?.hash ||
						entry.createdAt !== expectedInitialEntries[index]?.createdAt
				)
			) {
				throw new Error(`${label} did not create its exact migration ledger.`);
			}
			await sql`
				insert into "user" ("id", "name", "email", "email_verified", "created_at", "updated_at")
				values ('rebased-upgrade-probe', 'Migration probe', 'rebased-probe@example.invalid', false, now(), now())
			`;
			await sql`
				insert into "two_factor" ("id", "secret", "backup_codes", "user_id", "verified")
				values ('rebased-two-factor-probe', 'preserved-secret', '[]', 'rebased-upgrade-probe', true)
			`;
		});

		await runMigrationRunner(databaseUrl);
		await runMigrationRunner(databaseUrl);

		await withSql(databaseUrl, async (sql) => {
			const migrations = await sql`
				select "hash", "created_at"::text as "createdAt"
				from drizzle.__drizzle_migrations
				order by "created_at", "id"
			`;
			if (
				migrations.length !== expectedFinalEntries.length ||
				migrations.some(
					(entry, index) =>
						entry.createdAt !== expectedFinalEntries[index]?.createdAt ||
						entry.hash !== expectedFinalEntries[index]?.hash
				)
			) {
				throw new Error(`${label} did not apply the forward migration chain exactly once.`);
			}
			const [preserved] = await sql`
				select
					u."id",
					t."secret",
					t."failed_verification_count" as "failedVerificationCount",
					t."locked_until" as "lockedUntil"
				from "user" u
				join "two_factor" t on t."user_id" = u."id"
				where u."id" = 'rebased-upgrade-probe'
			`;
			if (
				preserved?.id !== 'rebased-upgrade-probe' ||
				preserved.secret !== 'preserved-secret' ||
				preserved.failedVerificationCount !== 0 ||
				preserved.lockedUntil !== null
			) {
				throw new Error(`${label} did not preserve and initialize two-factor state.`);
			}
		});

		console.log(
			`Migration upgrade verified from ${label} through ${latestMigration.tag}, with reruns idempotent.`
		);
	} finally {
		await withSql(
			adminUrl,
			(sql) => sql`drop database if exists ${sql(databaseName)} with (force)`
		);
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
		throw new Error('Rebased-migration verification only creates databases on a loopback server.');
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
