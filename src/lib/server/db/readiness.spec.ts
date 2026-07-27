import { describe, expect, it } from 'vitest';
import migrationIntegrity from '../../../../drizzle/migration-integrity.json';
import { ledgerIsFinal } from './readiness';

describe('migration readiness integrity', () => {
	it('accepts every exact released final ledger', () => {
		const compatibilityMigrationIndex = migrationIntegrity.canonical.findIndex(
			(entry) => entry.tag === '0022_forward_compatible_upgrade'
		);
		if (compatibilityMigrationIndex < 0) {
			throw new Error('Expected a compatibility migration fixture.');
		}
		const forwardMigrations = migrationIntegrity.canonical.slice(compatibilityMigrationIndex);
		const releasedV001ForwardMigrationIndex = migrationIntegrity.canonical.findIndex(
			(entry) => entry.tag === migrationIntegrity.releasedV001.forwardFrom
		);
		if (releasedV001ForwardMigrationIndex !== migrationIntegrity.releasedV001.entries.length) {
			throw new Error('Expected a released v0.0.1 forward migration fixture.');
		}
		const latestMigration = forwardMigrations.at(-1);
		if (!latestMigration) throw new Error('Expected a forward migration fixture.');
		expect(ledgerIsFinal(migrationIntegrity.canonical)).toBe(true);
		expect(
			ledgerIsFinal([
				...migrationIntegrity.releasedV001.entries,
				...migrationIntegrity.canonical.slice(releasedV001ForwardMigrationIndex)
			])
		).toBe(true);
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
		expect(
			ledgerIsFinal([
				...migrationIntegrity.releasedV001.entries.map((entry, index) =>
					index === 3 ? { ...entry, hash: migrationIntegrity.canonical[index]?.hash ?? '' } : entry
				),
				...migrationIntegrity.canonical.slice(migrationIntegrity.releasedV001.entries.length)
			])
		).toBe(false);
	});
});
