import { readFile } from 'node:fs/promises';

export const migrationIntegrity = JSON.parse(
	await readFile(new URL('../drizzle/migration-integrity.json', import.meta.url), 'utf8')
);

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
	if (final) return sequenceMatches(rows, migrationIntegrity.canonical);
	return (
		rows.length <= migrationIntegrity.canonical.length &&
		rows.every((row, index) => entryMatches(row, migrationIntegrity.canonical[index]))
	);
}

export async function assertSupportedMigrationLedger(sql, options) {
	const rows = await readMigrationLedger(sql);
	if (rows.length === 0 && !(await databaseIsEmptyForBaseline(sql))) {
		throw new Error(
			'Database has no supported migration ledger but is not empty. Use a new empty database for this clean-install baseline.'
		);
	}
	if (!migrationLedgerIsSupported(rows, options)) {
		throw new Error(
			'Database migration ledger does not match this clean-install baseline. Use an empty database rather than editing the ledger.'
		);
	}
	return rows;
}

export async function databaseIsEmptyForBaseline(sql) {
	const [record] = await sql`
		select not (
			exists (
				select 1
				from pg_class as relation
				join pg_namespace as namespace on namespace.oid = relation.relnamespace
				where
					namespace.nspname not in ('pg_catalog', 'information_schema')
					and namespace.nspname not like 'pg_toast%'
					and namespace.nspname not like 'pg_temp_%'
					and relation.relkind in ('r', 'p', 'i', 'I', 'v', 'm', 'S', 'f')
					and not (
						namespace.nspname = 'drizzle'
						and relation.relname in (
							'__drizzle_migrations',
							'__drizzle_migrations_id_seq',
							'__drizzle_migrations_pkey'
						)
					)
			)
			or exists (
				select 1
				from pg_type as type
				join pg_namespace as namespace on namespace.oid = type.typnamespace
				where
					namespace.nspname not in ('pg_catalog', 'information_schema')
					and namespace.nspname not like 'pg_toast%'
					and namespace.nspname not like 'pg_temp_%'
					and type.typtype in ('c', 'd', 'e', 'r', 'm')
					and not (
						namespace.nspname = 'drizzle'
						and type.typname in (
							'__drizzle_migrations',
							'___drizzle_migrations'
						)
					)
			)
			or exists (
				select 1
				from pg_proc as procedure
				join pg_namespace as namespace on namespace.oid = procedure.pronamespace
				where
					namespace.nspname not in ('pg_catalog', 'information_schema')
					and namespace.nspname not like 'pg_toast%'
					and namespace.nspname not like 'pg_temp_%'
			)
		) as "empty"
	`;
	return record?.empty === true;
}

export async function finalSchemaIsValid(sql) {
	const [tables, columns, constraints, indexes, enums] = await Promise.all([
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
		sql`select indexname as "name" from pg_indexes where schemaname = 'public'`,
		sql`
			select type.typname as "name"
			from pg_type as type
			join pg_namespace as namespace on namespace.oid = type.typnamespace
			where namespace.nspname = 'public' and type.typtype = 'e'
		`
	]);
	return (
		containsEvery(tables, migrationIntegrity.requiredTables) &&
		containsEvery(columns, migrationIntegrity.requiredColumns) &&
		containsEvery(constraints, migrationIntegrity.requiredConstraints) &&
		containsEvery(indexes, migrationIntegrity.requiredIndexes) &&
		containsEvery(enums, migrationIntegrity.requiredEnums)
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
