import { describe, expect, it } from 'vitest';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { ledgerIsFinal } from './readiness';

describe('migration readiness integrity', () => {
	it('accepts the canonical and forward-compatible v0.1.1 ledgers', () => {
		const compatibilityMigration = migrationIntegrity.canonical.at(-1);
		if (!compatibilityMigration) throw new Error('Expected a compatibility migration fixture.');
		expect(ledgerIsFinal(migrationIntegrity.canonical)).toBe(true);
		expect(ledgerIsFinal([...migrationIntegrity.rebasedV011, compatibilityMigration])).toBe(true);
	});

	it('rejects a forged latest timestamp, missing entry, or changed hash', () => {
		const latest = migrationIntegrity.canonical.at(-1);
		if (!latest) throw new Error('Expected a compatibility migration fixture.');
		expect(ledgerIsFinal([{ ...latest, hash: 'arbitrary' }])).toBe(false);
		expect(ledgerIsFinal(migrationIntegrity.canonical.slice(1))).toBe(false);
		expect(
			ledgerIsFinal(
				migrationIntegrity.canonical.map((entry, index) =>
					index === 0 ? { ...entry, hash: 'changed' } : entry
				)
			)
		).toBe(false);
	});
});
