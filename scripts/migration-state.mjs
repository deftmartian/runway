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
const releasedV001Lineage = buildReleasedLineage(migrationIntegrity.releasedV001);
const releasedV012Lineage = buildReleasedLineage(migrationIntegrity.releasedV012);
const releasedV001ViaV012Lineage = buildReleasedLineage({
	tag: 'v0.0.1 upgraded by v0.1.2',
	forwardFrom: migrationIntegrity.releasedV012.forwardFrom,
	entries: [
		...migrationIntegrity.releasedV001.entries,
		...migrationIntegrity.releasedV012.entries.slice(migrationIntegrity.releasedV001.entries.length)
	]
});
const releasedLineages = [releasedV001Lineage, releasedV012Lineage, releasedV001ViaV012Lineage];
export const releasedV001Final = releasedV001Lineage.final;
export const releasedV012Final = releasedV012Lineage.final;
export const releasedV001ViaV012Final = releasedV001ViaV012Lineage.final;

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
			releasedLineages.some(({ final: expected }) => sequenceMatches(rows, expected))
		);
	}
	return canonicalPrefixMatches(rows) || rebasedPrefixMatches(rows) || releasedPrefixMatches(rows);
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

function releasedPrefixMatches(rows) {
	return releasedLineages.some(({ entries, forwardMigrations, final }) =>
		forkedPrefixMatches(rows, entries, forwardMigrations, final)
	);
}

function buildReleasedLineage(release) {
	const forwardMigrationIndex = migrationIntegrity.canonical.findIndex(
		(entry) => entry.tag === release.forwardFrom
	);
	if (forwardMigrationIndex !== release.entries.length) {
		throw new Error(`Migration integrity manifest has an invalid ${release.tag} forward cutover.`);
	}
	const forwardMigrations = migrationIntegrity.canonical.slice(forwardMigrationIndex);
	return {
		entries: release.entries,
		forwardMigrations,
		final: [...release.entries, ...forwardMigrations]
	};
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
