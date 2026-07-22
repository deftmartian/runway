import { sql } from 'drizzle-orm';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { db } from './index';

type LedgerRow = { hash: string; createdAt: string };
type NamedRow = { name: string };

const compatibilityMigration = migrationIntegrity.canonical.at(-1);
if (!compatibilityMigration) throw new Error('The migration integrity manifest is empty.');

const supportedFinalLedgers = [
	migrationIntegrity.canonical,
	[...migrationIntegrity.rebasedV011, compatibilityMigration]
];

export async function databaseIsReady(): Promise<boolean> {
	try {
		const [ledgerExists] = await db.execute<{ exists: boolean }>(sql`
			select to_regclass('drizzle.__drizzle_migrations') is not null as "exists"
		`);
		if (!ledgerExists?.exists) return false;

		const [ledger, tables, columns, constraints, indexes] = await Promise.all([
			db.execute<LedgerRow>(sql`
				select "hash", "created_at"::text as "createdAt"
				from drizzle.__drizzle_migrations
				order by "created_at", "id"
			`),
			db.execute<NamedRow>(sql`
				select table_name as "name"
				from information_schema.tables
				where table_schema = 'public'
			`),
			db.execute<NamedRow>(sql`
				select table_name || '.' || column_name as "name"
				from information_schema.columns
				where table_schema = 'public'
			`),
			db.execute<NamedRow>(sql`
				select constraint_name as "name"
				from information_schema.table_constraints
				where constraint_schema = 'public'
			`),
			db.execute<NamedRow>(sql`
				select indexname as "name"
				from pg_indexes
				where schemaname = 'public'
			`)
		]);

		return (
			ledgerIsFinal(ledger) &&
			containsEvery(tables, migrationIntegrity.requiredTables) &&
			containsEvery(columns, migrationIntegrity.requiredColumns) &&
			containsEvery(constraints, migrationIntegrity.requiredConstraints) &&
			containsEvery(indexes, migrationIntegrity.requiredIndexes)
		);
	} catch {
		return false;
	}
}

export function ledgerIsFinal(rows: LedgerRow[]): boolean {
	return supportedFinalLedgers.some(
		(expected) =>
			rows.length === expected.length &&
			rows.every(
				(row, index) =>
					row.hash === expected[index]?.hash && row.createdAt === expected[index]?.createdAt
			)
	);
}

function containsEvery(rows: NamedRow[], required: string[]): boolean {
	const present = new Set(rows.map((row) => row.name));
	return required.every((name) => present.has(name));
}
