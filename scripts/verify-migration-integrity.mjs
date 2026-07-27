import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { readFile, readdir } from 'node:fs/promises';

const manifest = JSON.parse(await readFile('drizzle/migration-integrity.json', 'utf8'));
const journal = JSON.parse(await readFile('drizzle/meta/_journal.json', 'utf8'));
const releasedV001FixtureRoot = 'tests/fixtures/migrations/v0.0.1/drizzle';
const releasedV012FixtureRoot = 'tests/fixtures/migrations/v0.1.2/drizzle';
const rebasedV011FixtureRoot = 'tests/fixtures/migrations/v0.1.1/drizzle';

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

await verifyReleasedFixture(
	'released v0.0.1',
	releasedV001FixtureRoot,
	manifest.releasedV001.entries
);
verifyForwardCutover('released v0.0.1', manifest.releasedV001);
await verifyReleasedFixture(
	'released v0.1.2',
	releasedV012FixtureRoot,
	manifest.releasedV012.entries
);
verifyForwardCutover('released v0.1.2', manifest.releasedV012);
await verifyReleasedFixture('rebased v0.1.1', rebasedV011FixtureRoot, manifest.rebasedV011);
if (process.env['RUNWAY_VERIFY_RELEASE_PROVENANCE'] === 'true') {
	await verifyReleasedProvenance('released v0.0.1', releasedV001FixtureRoot, manifest.releasedV001);
	await verifyReleasedProvenance('released v0.1.2', releasedV012FixtureRoot, manifest.releasedV012);
}

console.log(
	'Canonical, released v0.0.1, released v0.1.2, and rebased v0.1.1 migration files match the pinned integrity manifest.'
);

async function verifyReleasedFixture(label, root, expectedEntries) {
	const releasedJournal = JSON.parse(await readFile(`${root}/meta/_journal.json`, 'utf8'));
	if (releasedJournal.entries.length !== expectedEntries.length) {
		fail(`The ${label} fixture journal length differs from the pinned migration manifest.`);
	}
	const sqlFiles = (await readdir(root)).filter((path) => path.endsWith('.sql')).sort();
	const expectedSqlFiles = expectedEntries.map((entry) => `${entry.tag}.sql`).sort();
	if (
		sqlFiles.length !== expectedSqlFiles.length ||
		sqlFiles.some((path, index) => path !== expectedSqlFiles[index])
	) {
		fail(`The ${label} fixture does not contain exactly its pinned migration SQL files.`);
	}
	for (const [index, expected] of expectedEntries.entries()) {
		const actual = releasedJournal.entries[index];
		if (
			actual?.idx !== index ||
			actual?.tag !== expected.tag ||
			String(actual?.when) !== expected.createdAt
		) {
			fail(`${label} journal entry ${index} differs from the pinned manifest.`);
		}
		await verifySql(`${root}/${expected.tag}.sql`, expected.hash);
	}
}

function verifyForwardCutover(label, release) {
	if (manifest.canonical[release.entries.length]?.tag !== release.forwardFrom) {
		fail(`The ${label} forward cutover differs from the canonical migration journal.`);
	}
}

async function verifyReleasedProvenance(label, fixtureRoot, release) {
	const { tag, commit, entries } = release;
	const resolvedTag = git(['rev-parse', `${tag}^{commit}`])
		.toString('utf8')
		.trim();
	if (resolvedTag !== commit) {
		fail(`${tag} does not resolve to the pinned released migration commit.`);
	}
	for (const entry of entries) {
		const path = `drizzle/${entry.tag}.sql`;
		const released = git(['show', `${commit}:${path}`]);
		const fixture = await readFile(`${fixtureRoot}/${entry.tag}.sql`);
		if (!released.equals(fixture)) {
			fail(`${fixtureRoot}/${entry.tag}.sql differs from ${commit}:${path}.`);
		}
	}
	const releasedJournal = git(['show', `${commit}:drizzle/meta/_journal.json`]);
	const fixtureJournal = await readFile(`${fixtureRoot}/meta/_journal.json`);
	if (!releasedJournal.equals(fixtureJournal)) {
		fail(`The ${label} fixture journal differs from commit ${commit}.`);
	}
}

function git(arguments_) {
	try {
		return execFileSync('git', arguments_, {
			cwd: process.cwd(),
			encoding: 'buffer',
			maxBuffer: 4 * 1024 * 1024,
			stdio: ['ignore', 'pipe', 'pipe']
		});
	} catch {
		fail('Released migration provenance could not be verified from local Git history.');
	}
}

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
