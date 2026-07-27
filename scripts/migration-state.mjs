import { readFile } from 'node:fs/promises';

export const migrationIntegrity = JSON.parse(
	await readFile(new URL('../drizzle/migration-integrity.json', import.meta.url), 'utf8')
);

const compatibilityMigrationIndex = migrationIntegrity.canonical.findIndex(
	(entry) => entry.tag === '0022_forward_compatible_upgrade'
);
if (compatibilityMigrationIndex < 0) {
	throw new Error('Migration integrity manifest is missing the v0.1.1 compatibility migration.');
}

const rebasedForwardMigrations = migrationIntegrity.canonical.slice(compatibilityMigrationIndex);
export const rebasedFinal = [...migrationIntegrity.rebasedV011, ...rebasedForwardMigrations];
const releasedV001Entries = migrationIntegrity.releasedV001.entries;
const releasedV001ForwardMigrationIndex = migrationIntegrity.canonical.findIndex(
	(entry) => entry.tag === migrationIntegrity.releasedV001.forwardFrom
);
if (releasedV001ForwardMigrationIndex !== releasedV001Entries.length) {
	throw new Error('Migration integrity manifest has an invalid v0.0.1 forward cutover.');
}
const releasedV001ForwardMigrations = migrationIntegrity.canonical.slice(
	releasedV001ForwardMigrationIndex
);
export const releasedV001Final = [...releasedV001Entries, ...releasedV001ForwardMigrations];

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
			sequenceMatches(rows, migrationIntegrity.canonical) ||
			sequenceMatches(rows, rebasedFinal) ||
			sequenceMatches(rows, releasedV001Final)
		);
	}
	return (
		canonicalPrefixMatches(rows) || rebasedPrefixMatches(rows) || releasedV001PrefixMatches(rows)
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

function rebasedPrefixMatches(rows) {
	return forkedPrefixMatches(
		rows,
		migrationIntegrity.rebasedV011,
		rebasedForwardMigrations,
		rebasedFinal
	);
}

function releasedV001PrefixMatches(rows) {
	return forkedPrefixMatches(
		rows,
		releasedV001Entries,
		releasedV001ForwardMigrations,
		releasedV001Final
	);
}

function forkedPrefixMatches(rows, releasedEntries, forwardMigrations, finalEntries) {
	if (rows.length < releasedEntries.length || rows.length > finalEntries.length) {
		return false;
	}
	return (
		rows
			.slice(0, releasedEntries.length)
			.every((row, index) => entryMatches(row, releasedEntries[index])) &&
		rows
			.slice(releasedEntries.length)
			.every((row, index) => entryMatches(row, forwardMigrations[index]))
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
