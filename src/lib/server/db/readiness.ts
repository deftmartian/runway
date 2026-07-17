import { sql } from 'drizzle-orm';
import migrationJournal from '../../../../drizzle/meta/_journal.json';
import { db } from './index';

const latestMigration = migrationJournal.entries.at(-1);

if (!latestMigration) throw new Error('The migration journal is empty.');

const expectedMigrationTimestamp = String(latestMigration.when);

export async function databaseIsReady(): Promise<boolean> {
	const rows = await db.execute<{ latestMigration: string | null }>(sql`
		select max(created_at)::text as "latestMigration"
		from drizzle.__drizzle_migrations
	`);

	return rows[0]?.latestMigration === expectedMigrationTimestamp;
}
