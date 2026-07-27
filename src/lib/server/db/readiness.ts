import { sql } from 'drizzle-orm';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { db } from './index';

type LedgerRow = { hash: string; createdAt: string };
type NamedRow = { name: string };

export async function databaseIsReady(): Promise<boolean> {
	try {
		const [ledgerExists] = await db.execute<{ exists: boolean }>(sql`
			select to_regclass('drizzle.__drizzle_migrations') is not null as "exists"
		`);
		if (!ledgerExists?.exists) return false;

		const [ledger, tables, columns, constraints, indexes, enums] = await Promise.all([
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
			`),
			db.execute<NamedRow>(sql`
				select type.typname as "name"
				from pg_type as type
				join pg_namespace as namespace on namespace.oid = type.typnamespace
				where namespace.nspname = 'public' and type.typtype = 'e'
			`)
		]);

		return (
			ledgerIsFinal(ledger) &&
			containsEvery(tables, migrationIntegrity.requiredTables) &&
			containsEvery(columns, migrationIntegrity.requiredColumns) &&
			containsEvery(constraints, migrationIntegrity.requiredConstraints) &&
			containsEvery(indexes, migrationIntegrity.requiredIndexes) &&
			containsEvery(enums, migrationIntegrity.requiredEnums)
		);
	} catch {
		return false;
	}
}

export function ledgerIsFinal(rows: LedgerRow[]): boolean {
	return (
		rows.length === migrationIntegrity.canonical.length &&
		rows.every(
			(row, index) =>
				row.hash === migrationIntegrity.canonical[index]?.hash &&
				row.createdAt === migrationIntegrity.canonical[index]?.createdAt
		)
	);
}

function containsEvery(rows: NamedRow[], required: string[]): boolean {
	const present = new Set(rows.map((row) => row.name));
	return required.every((name) => present.has(name));
}
