import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const databaseName = `runway_rebased_${process.pid}_${Date.now()}`.slice(0, 63);
const migrationImage = process.env['RUNWAY_MIGRATION_IMAGE'];
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const upgradeMigration = journal.entries.at(-1);
const rebasedEntries = [
	{ hash: 'v0.1.1-rebased-0000', createdAt: 1784301464615 },
	{ hash: 'v0.1.1-rebased-0001', createdAt: 1784387613123 },
	{ hash: 'v0.1.1-rebased-0002', createdAt: 1784403938842 }
];

if (upgradeMigration?.tag !== '0022_forward_compatible_upgrade') {
	throw new Error('Rebased-history verification requires the forward compatibility migration.');
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

await withSql(adminUrl, (sql) => sql`create database ${sql(databaseName)}`);

try {
	await migrateDatabase(databaseUrl, 'drizzle');
	await withSql(databaseUrl, async (sql) => {
		await sql`
			insert into "user" ("id", "name", "email", "email_verified", "created_at", "updated_at")
			values ('rebased-upgrade-probe', 'Migration probe', 'rebased-probe@example.invalid', false, now(), now())
		`;
		await sql`delete from drizzle.__drizzle_migrations`;
		for (const entry of rebasedEntries) {
			await sql`
				insert into drizzle.__drizzle_migrations ("hash", "created_at")
				values (${entry.hash}, ${entry.createdAt})
			`;
		}
	});

	if (migrationImage) {
		await run('docker', [
			'run',
			'--rm',
			'--network',
			'host',
			'-e',
			`DATABASE_URL=${databaseUrl.toString()}`,
			migrationImage,
			'node',
			'scripts/run-migrations.mjs'
		]);
	} else {
		await migrateDatabase(databaseUrl, 'drizzle');
	}
	await migrateDatabase(databaseUrl, 'drizzle');

	await withSql(databaseUrl, async (sql) => {
		const migrations = await sql`
			select "hash", "created_at"::text as "createdAt"
			from drizzle.__drizzle_migrations
			order by "created_at"
		`;
		if (
			migrations.length !== rebasedEntries.length + 1 ||
			migrations.at(-1)?.createdAt !== String(upgradeMigration.when)
		) {
			throw new Error(
				'Rebased v0.1.1 database did not apply the compatibility migration exactly once.'
			);
		}
		const [preserved] = await sql`
			select count(*)::int as count from "user" where "id" = 'rebased-upgrade-probe'
		`;
		if (preserved?.count !== 1) {
			throw new Error('Rebased-history upgrade did not preserve the existing data probe.');
		}
	});

	console.log('Migration upgrade verified from the three-entry v0.1.1 rebased history.');
} finally {
	await withSql(adminUrl, (sql) => sql`drop database if exists ${sql(databaseName)} with (force)`);
}

async function migrateDatabase(url, migrationsFolder) {
	const client = postgres(url.toString(), { max: 1, onnotice: () => undefined });
	try {
		await migrate(drizzle(client), { migrationsFolder });
	} finally {
		await client.end();
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
		throw new Error('Rebased-migration verification only creates databases on a loopback server.');
	}
}

function run(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, { env: process.env, stdio: 'inherit' });
		child.once('error', reject);
		child.once('exit', (code, signal) => {
			if (code === 0) resolve();
			else reject(new Error(`${command} exited with ${signal ?? code ?? 'unknown status'}.`));
		});
	});
}
