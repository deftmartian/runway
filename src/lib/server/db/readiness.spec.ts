import { describe, expect, it } from 'vitest';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { ledgerIsFinal } from './readiness';

describe('migration readiness integrity', () => {
	it('accepts the canonical and forward-compatible v0.1.1 ledgers', () => {
		const compatibilityMigrationIndex = migrationIntegrity.canonical.findIndex(
			(entry) => entry.tag === '0022_forward_compatible_upgrade'
		);
		if (compatibilityMigrationIndex < 0) {
			throw new Error('Expected a compatibility migration fixture.');
		}
		const forwardMigrations = migrationIntegrity.canonical.slice(compatibilityMigrationIndex);
		const latestMigration = forwardMigrations.at(-1);
		if (!latestMigration) throw new Error('Expected a forward migration fixture.');
		expect(ledgerIsFinal(migrationIntegrity.canonical)).toBe(true);
		expect(ledgerIsFinal([...migrationIntegrity.rebasedV011, ...forwardMigrations])).toBe(true);
		expect(ledgerIsFinal([...migrationIntegrity.rebasedV011, latestMigration])).toBe(false);
	});

	it('rejects a forged latest timestamp, missing entry, or changed hash', () => {
		const latest = migrationIntegrity.canonical.at(-1);
		if (!latest) throw new Error('Expected a compatibility migration fixture.');
		expect(ledgerIsFinal([{ ...latest, hash: 'arbitrary' }])).toBe(false);
		expect(ledgerIsFinal(migrationIntegrity.canonical.slice(1))).toBe(false);
		expect(
			ledgerIsFinal(
				migrationIntegrity.canonical.map((entry, index) =>
					index === migrationIntegrity.canonical.length - 1
						? { ...entry, createdAt: 'arbitrary' }
						: entry
				)
			)
		).toBe(false);
		expect(
			ledgerIsFinal(
				migrationIntegrity.canonical.map((entry, index) =>
					index === 0 ? { ...entry, hash: 'changed' } : entry
				)
			)
		).toBe(false);
	});
});
