import { createHash } from 'node:crypto';
import { readdir, readFile } from 'node:fs/promises';
import { schemaInventory } from './migration-schema-inventory.mjs';

const manifest = JSON.parse(await readFile('drizzle/migration-integrity.json', 'utf8'));
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));

if (manifest.canonical.length === 0 || manifest.canonical.length !== journal.entries.length) {
	fail('the integrity manifest and Drizzle journal must contain the same non-empty history');
}

const expectedSqlFiles = manifest.canonical.map((entry) => `${entry.tag}.sql`).sort();
const actualSqlFiles = (await readdir('drizzle')).filter((name) => name.endsWith('.sql')).sort();
if (JSON.stringify(actualSqlFiles) !== JSON.stringify(expectedSqlFiles)) {
	fail('the Drizzle directory contains SQL outside the pinned canonical journal');
}

for (const [index, expected] of manifest.canonical.entries()) {
	const actual = journal.entries[index];
	if (
		actual?.tag !== expected.tag ||
		String(actual?.when) !== expected.createdAt ||
		actual?.idx !== index
	) {
		fail(`journal entry ${index} differs from the pinned manifest`);
	}
	const contents = await readFile(`drizzle/${expected.tag}.sql`);
	const hash = createHash('sha256').update(contents).digest('hex');
	if (hash !== expected.hash)
		fail(`drizzle/${expected.tag}.sql changed after its identity was pinned`);
}

const snapshotFiles = (await readdir('drizzle/meta'))
	.filter((name) => /^\d{4}_snapshot\.json$/.test(name))
	.sort();
const expectedSnapshots = manifest.canonical.map((_, index) => {
	return `${String(index).padStart(4, '0')}_snapshot.json`;
});
if (JSON.stringify(snapshotFiles) !== JSON.stringify(expectedSnapshots)) {
	fail('the Drizzle snapshot set does not match the canonical journal');
}

const snapshot = JSON.parse(await readFile(`drizzle/meta/${expectedSnapshots.at(-1)}`, 'utf8'));
const expectedInventory = schemaInventory(snapshot);
for (const [name, expected] of Object.entries(expectedInventory)) {
	if (JSON.stringify(manifest[name]) !== JSON.stringify(expected)) {
		fail(`${name} does not contain the complete final snapshot inventory`);
	}
}

console.log(
	'Clean-install journal, SQL hashes, snapshots, and complete schema inventory match the pinned manifest.'
);

function fail(message) {
	throw new Error(`Migration integrity verification failed: ${message}.`);
}
