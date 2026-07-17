import { spawn } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import postgres from 'postgres';

const defaultDatabasePassword = process.env['POSTGRES_PASSWORD'] ?? 'runway_dev_password';
const defaultDatabaseUrl = `postgres://runway:${encodeURIComponent(defaultDatabasePassword)}@127.0.0.1:5432/runway`;
const baseDatabaseUrl = process.env['DATABASE_URL'] ?? defaultDatabaseUrl;
const databaseName = `runway_migration_${process.pid}_${Date.now()}`.slice(0, 63);
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const expectedMigration = journal.entries.at(-1);

if (!expectedMigration) throw new Error('The Drizzle migration journal is empty.');
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
	await run('corepack', ['pnpm', 'db:migrate'], {
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

		const requiredTables = [
			'user',
			'session',
			'training_plan',
			'workout',
			'activity',
			'import_source',
			'password_reset_token'
		];
		const tables = await sql`
			select table_name as "tableName"
			from information_schema.tables
			where table_schema = 'public'
		`;
		const existing = new Set(tables.map((row) => row.tableName));
		const missing = requiredTables.filter((table) => !existing.has(table));
		if (missing.length > 0)
			throw new Error(`Fresh database is missing tables: ${missing.join(', ')}`);
	});

	console.log(
		`Fresh migration verified through ${expectedMigration.tag} (${journal.entries.length} migrations).`
	);
} finally {
	await withSql(adminUrl, (sql) => sql`drop database if exists ${sql(databaseName)} with (force)`);
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
