import { readFile } from 'node:fs/promises';

export const migrationIntegrity = JSON.parse(
	await readFile(new URL('../drizzle/migration-integrity.json', import.meta.url), 'utf8')
);

const compatibilityMigration = migrationIntegrity.canonical.at(-1);
if (!compatibilityMigration) throw new Error('Migration integrity manifest is empty.');

export const rebasedFinal = [...migrationIntegrity.rebasedV011, compatibilityMigration];

export async function readMigrationLedger(sql) {
	const [record] = await sql`
		select to_regclass('drizzle.__drizzle_migrations') is not null as "exists"
	`;
	if (!record?.exists) return [];
	return sql`
		select "hash", "created_at"::text as "createdAt"
		from drizzle.__drizzle_migrations
		order by "created_at", "id"
	`;
}

export function migrationLedgerIsSupported(rows, { final = false } = {}) {
	if (final) {
		return (
			sequenceMatches(rows, migrationIntegrity.canonical) || sequenceMatches(rows, rebasedFinal)
		);
	}
	return (
		canonicalPrefixMatches(rows) ||
		sequenceMatches(rows, migrationIntegrity.rebasedV011) ||
		sequenceMatches(rows, rebasedFinal)
	);
}

export async function assertSupportedMigrationLedger(sql, options) {
	const rows = await readMigrationLedger(sql);
	if (!migrationLedgerIsSupported(rows, options)) {
		throw new Error(
			'Database migration ledger is not a supported runway lineage. Refusing to infer schema state from timestamps.'
		);
	}
	return rows;
}

export async function finalSchemaIsValid(sql) {
	const [tables, columns, constraints, indexes] = await Promise.all([
		sql`select table_name as "name" from information_schema.tables where table_schema = 'public'`,
		sql`
			select table_name || '.' || column_name as "name"
			from information_schema.columns
			where table_schema = 'public'
		`,
		sql`
			select constraint_name as "name"
			from information_schema.table_constraints
			where constraint_schema = 'public'
		`,
		sql`select indexname as "name" from pg_indexes where schemaname = 'public'`
	]);
	return (
		containsEvery(tables, migrationIntegrity.requiredTables) &&
		containsEvery(columns, migrationIntegrity.requiredColumns) &&
		containsEvery(constraints, migrationIntegrity.requiredConstraints) &&
		containsEvery(indexes, migrationIntegrity.requiredIndexes)
	);
}

export async function assertFinalMigrationState(sql) {
	await assertSupportedMigrationLedger(sql, { final: true });
	if (!(await finalSchemaIsValid(sql))) {
		throw new Error(
			'Database ledger is current but required runway schema invariants are missing.'
		);
	}
}

function canonicalPrefixMatches(rows) {
	return (
		rows.length <= migrationIntegrity.canonical.length &&
		rows.every((row, index) => entryMatches(row, migrationIntegrity.canonical[index]))
	);
}

function sequenceMatches(rows, expected) {
	return (
		rows.length === expected.length &&
		rows.every((row, index) => entryMatches(row, expected[index]))
	);
}

function entryMatches(actual, expected) {
	return actual?.hash === expected?.hash && actual?.createdAt === expected?.createdAt;
}

function containsEvery(rows, required) {
	const present = new Set(rows.map((row) => row.name));
	return required.every((name) => present.has(name));
}
