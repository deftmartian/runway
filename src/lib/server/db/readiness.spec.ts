import { describe, expect, it } from 'vitest';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { ledgerIsFinal } from './readiness';

describe('migration readiness integrity', () => {
	it('accepts only the exact clean-install baseline', () => {
		expect(ledgerIsFinal(migrationIntegrity.canonical)).toBe(true);
		expect(ledgerIsFinal([])).toBe(false);
	});

	it('rejects changed, missing, or additional entries', () => {
		const baseline = migrationIntegrity.canonical[0];
		if (!baseline) throw new Error('Expected a clean-install baseline migration.');

		expect(ledgerIsFinal([{ ...baseline, hash: 'changed' }])).toBe(false);
		expect(ledgerIsFinal([{ ...baseline, createdAt: 'changed' }])).toBe(false);
		expect(ledgerIsFinal([baseline, baseline])).toBe(false);
	});
});
