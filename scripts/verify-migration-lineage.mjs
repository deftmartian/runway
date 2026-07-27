import {
	migrationIntegrity,
	migrationLedgerIsSupported,
	rebasedFinal,
	releasedV001Final
} from './migration-state.mjs';
import { restoredLedgerState } from './database-backup-lib.mjs';

const ledgerRows = (entries) =>
	entries.map((entry) => ({ hash: entry.hash, createdAt: entry.createdAt }));

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

for (
	let length = migrationIntegrity.releasedV001.entries.length;
	length <= releasedV001Final.length;
	length += 1
) {
	const rows = ledgerRows(releasedV001Final.slice(0, length));
	assert(migrationLedgerIsSupported(rows), `Released v0.0.1 prefix ${length} was rejected.`);
	assert(
		migrationLedgerIsSupported(rows, { final: true }) === (length === releasedV001Final.length),
		`Released v0.0.1 prefix ${length} has the wrong final-state result.`
	);
}

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
	restoredLedgerState(mixedReleasedCanonical, migrationIntegrity) === 'unsupported',
	'Backup restore policy accepted a mixed released/canonical ledger.'
);

console.log('Canonical, released v0.0.1, and rebased migration prefix policy verified.');

function assert(condition, message) {
	if (!condition) throw new Error(message);
}
