import {
	migrationIntegrity,
	migrationLedgerIsSupported,
	rebasedFinal
} from './migration-state.mjs';

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

console.log('Canonical and rebased migration prefix policy verified.');

function assert(condition, message) {
	if (!condition) throw new Error(message);
}
