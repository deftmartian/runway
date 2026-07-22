import { beforeEach, describe, expect, test, vi } from 'vitest';
import {
	operationalPurgeBatchSize,
	operationalRecordRetentionDays,
	operationalRetentionCutoff,
	purgeExpiredOperationalRecords,
	securityRateLimitRetentionGraceMs
} from './operational-retention';

const database = vi.hoisted(() => {
	const selectResults: ({ id: string } | { keyHash: string })[][] = [];
	const deleteResults: ({ id: string } | { keyHash: string })[][] = [];
	const select = vi.fn(() => {
		const query = {
			from: () => query,
			where: () => query,
			orderBy: () => query,
			limit: () => Promise.resolve(selectResults.shift() ?? [])
		};
		return query;
	});
	const deleteRecords = vi.fn(() => {
		const query = {
			where: () => query,
			returning: () => Promise.resolve(deleteResults.shift() ?? [])
		};
		return query;
	});
	return { deleteRecords, deleteResults, select, selectResults };
});

vi.mock('$lib/server/db', () => ({
	db: { delete: database.deleteRecords, select: database.select }
}));

describe('operational record retention', () => {
	beforeEach(() => {
		database.selectResults.length = 0;
		database.deleteResults.length = 0;
		database.select.mockClear();
		database.deleteRecords.mockClear();
	});

	test('uses a finite retention window and bounded purge batches', () => {
		expect(operationalRecordRetentionDays).toBe(30);
		expect(operationalPurgeBatchSize).toBe(500);
		expect(securityRateLimitRetentionGraceMs).toBe(86_400_000);
	});

	test('computes the cutoff as an exact elapsed-day boundary', () => {
		expect(operationalRetentionCutoff(new Date('2026-07-22T03:00:00.000Z')).toISOString()).toBe(
			'2026-06-22T03:00:00.000Z'
		);
	});

	test('purges expired reset records and completed Android receipts in bounded batches', async () => {
		database.selectResults.push(
			[{ id: 'reset-record' }],
			[{ id: 'android-receipt' }],
			[{ keyHash: 'security-bucket' }]
		);
		database.deleteResults.push(
			[{ id: 'reset-record' }],
			[{ id: 'android-receipt' }],
			[{ keyHash: 'security-bucket' }]
		);

		await expect(
			purgeExpiredOperationalRecords(new Date('2026-07-22T03:00:00.000Z'))
		).resolves.toEqual({
			passwordResetTokens: 1,
			androidImportRequests: 1,
			securityRateLimits: 1
		});
		expect(database.select).toHaveBeenCalledTimes(3);
		expect(database.deleteRecords).toHaveBeenCalledTimes(3);
	});

	test('does not issue delete statements when no records have expired', async () => {
		database.selectResults.push([], [], []);

		await expect(purgeExpiredOperationalRecords()).resolves.toEqual({
			passwordResetTokens: 0,
			androidImportRequests: 0,
			securityRateLimits: 0
		});
		expect(database.deleteRecords).not.toHaveBeenCalled();
	});
});
