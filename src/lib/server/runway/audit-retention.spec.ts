import { describe, expect, test } from 'vitest';
import {
	auditRetentionCutoff,
	defaultAuditRetentionDays,
	readAuditRetentionPolicy
} from './audit-retention';

describe('audit retention policy', () => {
	test('uses a finite 365-day default when the variable is absent or blank', () => {
		expect.assertions(2);

		expect(readAuditRetentionPolicy(undefined)).toEqual({
			enabled: true,
			retentionDays: defaultAuditRetentionDays
		});
		expect(readAuditRetentionPolicy('  ')).toEqual({ enabled: true, retentionDays: 365 });
	});

	test('accepts bounded overrides and only an explicit disabled value', () => {
		expect.assertions(3);

		expect(readAuditRetentionPolicy('90')).toEqual({ enabled: true, retentionDays: 90 });
		expect(readAuditRetentionPolicy(' disabled ')).toEqual({
			enabled: false,
			retentionDays: null
		});
		expect(() => readAuditRetentionPolicy('0')).toThrow(/integer from 1 to 3650/);
	});

	test('rejects ambiguous or unbounded values instead of silently changing policy', () => {
		expect.assertions(4);

		for (const value of ['false', '365.5', '-1', '3651']) {
			expect(() => readAuditRetentionPolicy(value)).toThrow(/integer from 1 to 3650/);
		}
	});

	test('computes the cutoff as an exact elapsed-day boundary', () => {
		expect.assertions(1);

		expect(auditRetentionCutoff(new Date('2026-07-15T12:00:00.000Z'), 365).toISOString()).toBe(
			'2025-07-15T12:00:00.000Z'
		);
	});
});
