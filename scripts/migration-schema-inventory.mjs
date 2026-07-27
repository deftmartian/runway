export function schemaInventory(snapshot) {
	const tables = Object.values(snapshot.tables ?? {}).sort((left, right) =>
		left.name.localeCompare(right.name)
	);
	const primaryKeys = tables.flatMap((table) => {
		const columnPrimaryKeys = Object.values(table.columns ?? {}).filter(
			(column) => column.primaryKey
		);
		if (columnPrimaryKeys.length === 0) return [];
		if (columnPrimaryKeys.length !== 1) {
			throw new Error(`Table ${table.name} has an unsupported inline primary-key shape.`);
		}
		return [`${table.name}_pkey`];
	});
	const namedConstraints = tables.flatMap((table) =>
		['foreignKeys', 'compositePrimaryKeys', 'uniqueConstraints', 'checkConstraints'].flatMap(
			(key) => Object.keys(table[key] ?? {})
		)
	);
	const constraintIndexes = tables.flatMap((table) => [
		...Object.keys(table.compositePrimaryKeys ?? {}),
		...Object.keys(table.uniqueConstraints ?? {})
	]);

	return {
		requiredTables: sortedUnique(tables.map((table) => table.name)),
		requiredColumns: sortedUnique(
			tables.flatMap((table) =>
				Object.keys(table.columns ?? {}).map((column) => `${table.name}.${column}`)
			)
		),
		requiredConstraints: sortedUnique([...primaryKeys, ...namedConstraints]),
		requiredIndexes: sortedUnique([
			...primaryKeys,
			...constraintIndexes,
			...tables.flatMap((table) => Object.keys(table.indexes ?? {}))
		]),
		requiredEnums: sortedUnique(
			Object.values(snapshot.enums ?? {}).map((enumeration) => enumeration.name)
		)
	};
}

function sortedUnique(values) {
	return [...new Set(values)].sort((left, right) => left.localeCompare(right));
}
