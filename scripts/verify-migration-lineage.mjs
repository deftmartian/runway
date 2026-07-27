import {
	migrationIntegrity,
	migrationLedgerIsSupported,
	rebasedFinal,
	releasedV001Final,
	releasedV001ViaV012Final,
	releasedV012Final
} from './migration-state.mjs';
import { restoredLedgerState } from './database-backup-lib.mjs';

const ledgerRows = (entries) =>
	entries.map((entry) => ({ hash: entry.hash, createdAt: entry.createdAt }));
const releasedV001ViaV012Entries = [
	...migrationIntegrity.releasedV001.entries,
	...migrationIntegrity.releasedV012.entries.slice(migrationIntegrity.releasedV001.entries.length)
];

for (let length = 0; length <= migrationIntegrity.canonical.length; length += 1) {
	const rows = ledgerRows(migrationIntegrity.canonical.slice(0, length));
	assert(migrationLedgerIsSupported(rows), `Canonical prefix ${length} was rejected.`);
	assert(
		migrationLedgerIsSupported(rows, { final: true }) ===
			(length === migrationIntegrity.canonical.length),
		`Canonical prefix ${length} has the wrong final-state result.`
	);
}

for (
	let length = migrationIntegrity.rebasedV011.length;
	length <= rebasedFinal.length;
	length += 1
) {
	const rows = ledgerRows(rebasedFinal.slice(0, length));
	assert(migrationLedgerIsSupported(rows), `Rebased prefix ${length} was rejected.`);
	assert(
		migrationLedgerIsSupported(rows, { final: true }) === (length === rebasedFinal.length),
		`Rebased prefix ${length} has the wrong final-state result.`
	);
}

verifyReleasedLineage(
	'Released v0.0.1',
	migrationIntegrity.releasedV001.entries,
	releasedV001Final
);
verifyReleasedLineage(
	'Fresh released v0.1.2',
	migrationIntegrity.releasedV012.entries,
	releasedV012Final
);
verifyReleasedLineage(
	'Released v0.0.1 upgraded by v0.1.2',
	releasedV001ViaV012Entries,
	releasedV001ViaV012Final
);

const skippedCompatibility = [
	...migrationIntegrity.rebasedV011,
	migrationIntegrity.canonical.at(-1)
];
assert(
	!migrationLedgerIsSupported(ledgerRows(skippedCompatibility)),
	'A rebased ledger that skips the compatibility migration was accepted.'
);

const tampered = ledgerRows(rebasedFinal);
tampered.at(-1).hash = 'not-the-released-hash';
assert(!migrationLedgerIsSupported(tampered), 'A tampered migration hash was accepted.');

const changedReleasedHash = ledgerRows(releasedV001Final);
changedReleasedHash[3].hash = migrationIntegrity.canonical[3].hash;
assert(
	!migrationLedgerIsSupported(changedReleasedHash),
	'A released v0.0.1 ledger with a rewritten historical hash was accepted.'
);

const omittedReleasedEntry = ledgerRows(releasedV001Final);
omittedReleasedEntry.splice(3, 1);
assert(
	!migrationLedgerIsSupported(omittedReleasedEntry),
	'A released v0.0.1 ledger with a missing entry was accepted.'
);

const skippedReleasedForwardMigration = ledgerRows([
	...migrationIntegrity.releasedV001.entries,
	...migrationIntegrity.canonical.slice(migrationIntegrity.releasedV001.entries.length + 1)
]);
assert(
	!migrationLedgerIsSupported(skippedReleasedForwardMigration),
	'A released v0.0.1 ledger that skips its first forward migration was accepted.'
);

const oldCompatibilityMigration = migrationIntegrity.releasedV012.entries.at(-1);
if (oldCompatibilityMigration?.tag !== '0022_forward_compatible_upgrade') {
	throw new Error('The released v0.1.2 fixture is missing its compatibility migration.');
}
const oldCompatibilityWithout0021 = ledgerRows([
	...migrationIntegrity.releasedV001.entries,
	oldCompatibilityMigration,
	...migrationIntegrity.canonical.slice(migrationIntegrity.releasedV012.entries.length)
]);
assert(
	!migrationLedgerIsSupported(oldCompatibilityWithout0021),
	'A released v0.1.2 ledger that skips 0021 was accepted.'
);

const rebasedWithOldCompatibility = ledgerRows([
	...migrationIntegrity.rebasedV011,
	oldCompatibilityMigration,
	...migrationIntegrity.canonical.slice(migrationIntegrity.releasedV012.entries.length)
]);
assert(
	!migrationLedgerIsSupported(rebasedWithOldCompatibility),
	'A rebased v0.1.1 ledger with the incompatible v0.1.2 migration was accepted.'
);

const skippedV012ForwardMigration = ledgerRows([
	...releasedV001ViaV012Entries,
	migrationIntegrity.canonical.at(-1)
]);
assert(
	!migrationLedgerIsSupported(skippedV012ForwardMigration),
	'A released v0.1.2 ledger that skips 0023 was accepted.'
);

const changedV012Timestamp = ledgerRows(releasedV001ViaV012Final);
changedV012Timestamp[releasedV001ViaV012Entries.length - 1].createdAt = 'not-the-released-time';
assert(
	!migrationLedgerIsSupported(changedV012Timestamp),
	'A released v0.1.2 ledger with a changed compatibility timestamp was accepted.'
);

const mixedReleasedCanonical = ledgerRows(releasedV001Final);
mixedReleasedCanonical[9].hash = migrationIntegrity.canonical[9].hash;
assert(
	!migrationLedgerIsSupported(mixedReleasedCanonical),
	'A mixed released/canonical pre-cutover ledger was accepted.'
);

assert(
	restoredLedgerState(ledgerRows(migrationIntegrity.releasedV001.entries), migrationIntegrity) ===
		'supported-predecessor',
	'Backup restore policy rejected the exact released v0.0.1 ledger.'
);
assert(
	restoredLedgerState(ledgerRows(releasedV001Final), migrationIntegrity) === 'current',
	'Backup restore policy rejected the upgraded released v0.0.1 ledger.'
);
assert(
	restoredLedgerState(ledgerRows(migrationIntegrity.releasedV012.entries), migrationIntegrity) ===
		'supported-predecessor',
	'Backup restore policy rejected the exact fresh released v0.1.2 ledger.'
);
assert(
	restoredLedgerState(ledgerRows(releasedV012Final), migrationIntegrity) === 'current',
	'Backup restore policy rejected the upgraded fresh released v0.1.2 ledger.'
);
assert(
	restoredLedgerState(ledgerRows(releasedV001ViaV012Entries), migrationIntegrity) ===
		'supported-predecessor',
	'Backup restore policy rejected the v0.0.1 database upgraded by v0.1.2.'
);
assert(
	restoredLedgerState(ledgerRows(releasedV001ViaV012Final), migrationIntegrity) === 'current',
	'Backup restore policy rejected the current v0.1.2 upgrade lineage.'
);
assert(
	restoredLedgerState(mixedReleasedCanonical, migrationIntegrity) === 'unsupported',
	'Backup restore policy accepted a mixed released/canonical ledger.'
);

console.log(
	'Canonical, released v0.0.1, released v0.1.2, and rebased migration prefix policy verified.'
);

function verifyReleasedLineage(label, releasedEntries, finalEntries) {
	for (let length = releasedEntries.length; length <= finalEntries.length; length += 1) {
		const rows = ledgerRows(finalEntries.slice(0, length));
		assert(migrationLedgerIsSupported(rows), `${label} prefix ${length} was rejected.`);
		assert(
			migrationLedgerIsSupported(rows, { final: true }) === (length === finalEntries.length),
			`${label} prefix ${length} has the wrong final-state result.`
		);
	}
}

function assert(condition, message) {
	if (!condition) throw new Error(message);
}
