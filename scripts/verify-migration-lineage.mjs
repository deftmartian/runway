import { migrationIntegrity, migrationLedgerIsSupported } from './migration-state.mjs';
import { restoredLedgerState } from './database-backup-lib.mjs';

const ledgerRows = (entries) =>
	entries.map((entry) => ({ hash: entry.hash, createdAt: entry.createdAt }));
const current = ledgerRows(migrationIntegrity.canonical);

assert(migrationLedgerIsSupported([]), 'An empty database was rejected.');
assert(
	!migrationLedgerIsSupported([], { final: true }),
	'An empty database was considered current.'
);
assert(migrationLedgerIsSupported(current), 'The clean-install baseline was rejected.');
assert(
	migrationLedgerIsSupported(current, { final: true }),
	'The clean-install baseline was not considered current.'
);
assert(
	restoredLedgerState(current, migrationIntegrity) === 'current',
	'A current backup was rejected.'
);

const changedHash = structuredClone(current);
changedHash[0].hash = 'not-the-baseline-hash';
assert(!migrationLedgerIsSupported(changedHash), 'A changed baseline hash was accepted.');

const changedTimestamp = structuredClone(current);
changedTimestamp[0].createdAt = 'not-the-baseline-time';
assert(!migrationLedgerIsSupported(changedTimestamp), 'A changed baseline timestamp was accepted.');

const oldLedger = [
	{
		hash: '4369fb1e5372f44e88f5c02eecebef3a4d6aec3038a7d5794da0da0c8d4bfc6c',
		createdAt: '1784689856796'
	}
];
assert(!migrationLedgerIsSupported(oldLedger), 'A retired migration ledger was accepted.');
assert(
	restoredLedgerState(oldLedger, migrationIntegrity) === 'unsupported',
	'A retired backup lineage was accepted.'
);

console.log('Clean-install baseline and fail-closed ledger policy verified.');

function assert(condition, message) {
	if (!condition) throw new Error(message);
}
