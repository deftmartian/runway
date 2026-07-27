import { fileURLToPath } from 'node:url';
import { drizzle } from 'drizzle-orm/postgres-js';
import { migrate } from 'drizzle-orm/postgres-js/migrator';
import postgres from 'postgres';
import { assertFinalMigrationState, assertSupportedMigrationLedger } from './migration-state.mjs';

const databaseUrl = process.env['DATABASE_URL'];
if (!databaseUrl) throw new Error('DATABASE_URL is not set');
if (process.env['NODE_ENV'] === 'production' && databaseUrl.includes('runway_dev_password')) {
	throw new Error('DATABASE_URL must not use the development database password in production.');
}

const migrationsFolder = fileURLToPath(new URL('../drizzle', import.meta.url));
const client = postgres(databaseUrl, { max: 1, onnotice: () => undefined });
const MIGRATION_LOCK_NAMESPACE = 1_921_014;
const MIGRATION_LOCK_ID = 20_260_722;
const MIGRATION_LOCK_WAIT_MS = 60_000;
const MIGRATION_LOCK_POLL_MS = 250;

try {
	await acquireMigrationLock(client);
	await assertSupportedMigrationLedger(client);
	await migrate(drizzle(client), { migrationsFolder });
	await assertFinalMigrationState(client);
	console.log('Database migrations applied.');
} finally {
	await client`select pg_advisory_unlock(${MIGRATION_LOCK_NAMESPACE}, ${MIGRATION_LOCK_ID})`.catch(
		() => undefined
	);
	await client.end();
}

async function acquireMigrationLock(sql) {
	const deadline = Date.now() + MIGRATION_LOCK_WAIT_MS;
	while (Date.now() < deadline) {
		const [record] = await sql`
			select pg_try_advisory_lock(${MIGRATION_LOCK_NAMESPACE}, ${MIGRATION_LOCK_ID}) as "acquired"
		`;
		if (record?.acquired) return;
		await new Promise((resolve) => setTimeout(resolve, MIGRATION_LOCK_POLL_MS));
	}
	throw new Error('Timed out waiting for another runway database migration to finish.');
}
