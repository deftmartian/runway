import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

const manifest = JSON.parse(await readFile('drizzle/migration-integrity.json', 'utf8'));
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const releasedFixtureRoot = 'tests/fixtures/migrations/v0.1.1/drizzle';

if (journal.entries.length !== manifest.canonical.length) {
	fail('The canonical journal length differs from the pinned migration manifest.');
}

for (const [index, expected] of manifest.canonical.entries()) {
	const actual = journal.entries[index];
	if (
		actual?.idx !== index ||
		actual?.tag !== expected.tag ||
		String(actual?.when) !== expected.createdAt
	) {
		fail(`Canonical journal entry ${index} differs from the pinned migration manifest.`);
	}
	await verifySql(`drizzle/${expected.tag}.sql`, expected.hash);
}

const releasedJournal = JSON.parse(
	await readFile(`${releasedFixtureRoot}/meta/_journal.json`, 'utf8')
);
if (releasedJournal.entries.length !== manifest.rebasedV011.length) {
	fail('The v0.1.1 fixture journal length differs from the pinned migration manifest.');
}
for (const [index, expected] of manifest.rebasedV011.entries()) {
	const actual = releasedJournal.entries[index];
	if (
		actual?.idx !== index ||
		actual?.tag !== expected.tag ||
		String(actual?.when) !== expected.createdAt
	) {
		fail(`Released v0.1.1 journal entry ${index} differs from the pinned manifest.`);
	}
	await verifySql(`${releasedFixtureRoot}/${expected.tag}.sql`, expected.hash);
}

console.log('Canonical and v0.1.1 released migration files match the pinned integrity manifest.');

async function verifySql(path, expectedHash) {
	const contents = await readFile(path);
	const actualHash = createHash('sha256').update(contents).digest('hex');
	if (actualHash !== expectedHash)
		fail(`${path} changed after its migration identity was released.`);
}

function fail(message) {
	console.error(`Migration integrity verification failed: ${message}`);
	process.exit(1);
}
