import { beforeEach, describe, expect, test, vi } from 'vitest';
import { defaultHeartRateSettings } from '$lib/training/heart-rate';
import { healthConnectChangesSchema } from './validation';
import {
	applyHealthConnectRoutePrivacy,
	buildHealthConnectActivityValues,
	getHealthConnectConnectionStatus,
	getSettingsHealthConnectConnectionStatus,
	isHealthConnectUpsertDuplicate,
	requireActiveHealthConnectDevice,
	requireHealthConnectGeneration,
	requireHealthConnectReplayPayload,
	resolveHealthConnectDuplicate,
	resolveHealthConnectRecord
} from './health-connect';

const mocks = vi.hoisted(() => ({
	deleteActivity: vi.fn(),
	lockOwner: vi.fn(),
	transaction: vi.fn()
}));

vi.mock('$lib/server/db', () => ({
	db: { transaction: mocks.transaction }
}));
vi.mock('./repositories/activity-mutations', () => ({
	deleteActivityRecordInTransaction: mocks.deleteActivity
}));
vi.mock('./repositories/mutation-locks', () => ({
	lockActivityOwner: mocks.lockOwner
}));

type QueryResult = Record<string, unknown>[];

function transactionWith(selectResults: QueryResult[]) {
	const updates: Record<string, unknown>[] = [];
	let forUpdateCount = 0;
	const tx = {
		select: vi.fn(() => {
			const result = selectResults.shift() ?? [];
			const query = {
				from: () => query,
				where: () => query,
				limit: () => query,
				for: () => {
					forUpdateCount += 1;
					return Promise.resolve(result);
				},
				then: (resolve: (value: QueryResult) => unknown, reject?: (reason: unknown) => unknown) =>
					Promise.resolve(result).then(resolve, reject)
			};
			return query;
		}),
		update: vi.fn(() => ({
			set: (value: Record<string, unknown>) => {
				updates.push(value);
				return { where: () => Promise.resolve([]) };
			}
		}))
	};
	mocks.transaction.mockImplementationOnce((callback: (transaction: typeof tx) => unknown) =>
		callback(tx)
	);
	return { forUpdateCount: () => forUpdateCount, tx, updates };
}

const pendingCorrection = {
	occurredAt: '2026-07-20T23:30:00.000Z',
	activityDate: '2026-07-20',
	distanceMeters: 5_100,
	durationSeconds: 1_800,
	averagePaceSecondsPerKm: 352.94,
	averageHeartRate: 150,
	maxHeartRate: 175,
	heartRateSummary: {
		effort: 'unknown' as const,
		highSeconds: 300,
		highShare: 0.17,
		secondsByZone: { z1: 0, z2: 600, z3: 900, z4: 300, z5: 0 },
		settingsSource: 'custom' as const
	},
	averageCadence: 170,
	heartRateSeries: null,
	routeTrace: null,
	routeSummary: {
		pointCount: 0,
		startEndRedacted: true,
		hasElevation: false,
		traceRetained: false
	}
};

describe('Health Connect server lifecycle', () => {
	beforeEach(() => {
		mocks.deleteActivity.mockReset();
		mocks.lockOwner.mockReset();
		mocks.transaction.mockReset();
	});

	test('keeps the Settings connection status available when its optional query fails', async () => {
		const readStatus = vi.fn().mockRejectedValue(new Error('private database detail'));
		const errorLog = vi.spyOn(console, 'error').mockImplementation(() => undefined);

		try {
			await expect(getSettingsHealthConnectConnectionStatus('user-1', readStatus)).resolves.toEqual(
				{
					state: 'unavailable',
					deviceLabel: null,
					lastSyncedAt: null,
					message: 'Health Connect status is temporarily unavailable. Check the data service.'
				}
			);
			expect(readStatus).toHaveBeenCalledExactlyOnceWith('user-1');
			expect(errorLog).toHaveBeenCalledExactlyOnceWith(
				'Health Connect status could not be loaded for Settings.'
			);
			expect(errorLog).not.toHaveBeenCalledWith(expect.stringContaining('private database detail'));
		} finally {
			errorLog.mockRestore();
		}
	});

	test('passes a healthy Settings connection status through unchanged', async () => {
		const status = {
			state: 'connected' as const,
			deviceLabel: 'Pixel',
			lastSyncedAt: new Date('2026-07-27T02:00:00.000Z'),
			message: null
		};

		await expect(
			getSettingsHealthConnectConnectionStatus('user-1', vi.fn().mockResolvedValue(status))
		).resolves.toBe(status);
	});

	test('does not hide a direct Health Connect database query failure', async () => {
		await expect(getHealthConnectConnectionStatus('user-1')).rejects.toThrow();
	});

	test('distinguishes an idempotent replay from a conflicting request body', () => {
		expect(() => {
			requireHealthConnectReplayPayload('same', 'same');
		}).not.toThrow();
		expect(() => {
			requireHealthConnectReplayPayload('stored', 'different');
		}).toThrow('request-conflict');
	});

	test('rejects a stale import generation', () => {
		expect(() => {
			requireHealthConnectGeneration(4, 4);
		}).not.toThrow();
		expect(() => {
			requireHealthConnectGeneration(5, 4);
		}).toThrow('generation-changed');
	});

	test('rejects a revoked, expired, or missing device at the write transaction boundary', () => {
		const now = new Date('2026-07-26T12:00:00.000Z');
		expect(() => {
			requireActiveHealthConnectDevice(
				{ expiresAt: new Date('2026-07-27T12:00:00.000Z'), revokedAt: null },
				now
			);
		}).not.toThrow();
		expect(() => {
			requireActiveHealthConnectDevice(undefined, now);
		}).toThrow('device-revoked');
		expect(() => {
			requireActiveHealthConnectDevice(
				{
					expiresAt: new Date('2026-07-27T12:00:00.000Z'),
					revokedAt: new Date('2026-07-26T11:59:00.000Z')
				},
				now
			);
		}).toThrow('device-revoked');
		expect(() => {
			requireActiveHealthConnectDevice(
				{ expiresAt: new Date('2026-07-26T12:00:00.000Z'), revokedAt: null },
				now
			);
		}).toThrow('device-revoked');
	});

	test('defensively strips a pending route when current privacy is discard', () => {
		const pending = {
			...pendingCorrection,
			routeTrace: { version: 1, sourcePointCount: 2, points: [{ private: true }] },
			routeSummary: {
				pointCount: 2,
				startEndRedacted: false,
				hasElevation: false,
				traceRetained: true
			}
		};

		expect(applyHealthConnectRoutePrivacy(pending, 'private')).toBe(pending);
		expect(applyHealthConnectRoutePrivacy(pending, 'discard')).toMatchObject({
			routeTrace: null,
			routeSummary: { pointCount: 2, startEndRedacted: true, traceRetained: false }
		});
	});

	test('treats a matching fingerprint or deletion tombstone as a duplicate', () => {
		expect(
			isHealthConnectUpsertDuplicate({ fingerprint: 'same', deletedAt: null }, undefined, 'same')
		).toBe(true);
		expect(isHealthConnectUpsertDuplicate(undefined, { id: 'tombstone' }, 'new')).toBe(true);
		expect(
			isHealthConnectUpsertDuplicate(
				{ fingerprint: 'same', deletedAt: new Date() },
				undefined,
				'same'
			)
		).toBe(false);
	});

	test('derives date and heart-rate zones while discarding route points and never inferring feltHard', () => {
		const values = buildHealthConnectActivityValues(
			{
				timeZone: 'America/Halifax',
				routeDataMode: 'discard',
				heartRateSettings: defaultHeartRateSettings(35)
			},
			{
				op: 'upsert',
				recordId: 'record',
				originKey: 'origin',
				originLabel: 'Watch',
				startedAt: new Date('2026-07-21T01:30:00.000Z'),
				durationSeconds: 120,
				distanceMeters: 500,
				heartRateSeries: {
					version: 1,
					sourceSampleCount: 3,
					points: [
						{ elapsedSeconds: 0, bpm: 100 },
						{ elapsedSeconds: 60, bpm: 170 },
						{ elapsedSeconds: 120, bpm: 170 }
					]
				},
				routeTrace: {
					version: 1,
					sourcePointCount: 2,
					points: [
						{
							latitudeE6: 45_000_000,
							longitudeE6: -63_000_000,
							elapsedSeconds: 0,
							segmentIndex: 0,
							speedMetersPerSecond: null
						},
						{
							latitudeE6: 45_000_100,
							longitudeE6: -63_000_100,
							elapsedSeconds: 120,
							segmentIndex: 0,
							speedMetersPerSecond: null
						}
					]
				}
			}
		);

		expect(values.activityDate).toBe('2026-07-20');
		expect(values.routeTrace).toBeNull();
		expect(values.routeSummary).toMatchObject({
			pointCount: 2,
			startEndRedacted: true,
			traceRetained: false
		});
		expect(values.heartRateSummary).toMatchObject({
			effort: 'unknown',
			highSeconds: 60,
			highShare: 0.5,
			settingsSource: 'estimated'
		});
		expect(values).not.toHaveProperty('feltHard');
	});

	test('rejects heart-rate samples outside the activity or out of order', () => {
		const result = healthConnectChangesSchema.safeParse({
			version: 1,
			changes: [
				{
					op: 'upsert',
					recordId: 'record',
					originKey: 'origin',
					originLabel: 'Watch',
					startedAt: '2026-07-20T12:00:00.000Z',
					durationSeconds: 120,
					distanceMeters: 500,
					heartRateSeries: {
						version: 1,
						sourceSampleCount: 3,
						points: [
							{ elapsedSeconds: 0, bpm: 120 },
							{ elapsedSeconds: 130, bpm: 130 },
							{ elapsedSeconds: 60, bpm: 140 }
						]
					}
				}
			]
		});

		expect(result.success).toBe(false);
		if (!result.success) {
			expect(result.error.issues.map(({ message }) => message)).toContain(
				'Health Connect heart-rate samples must be ordered within the activity.'
			);
		}
	});

	test('rejects understated source counts and unordered route traces', () => {
		const result = healthConnectChangesSchema.safeParse({
			version: 1,
			changes: [
				{
					op: 'upsert',
					recordId: 'record',
					originKey: 'origin',
					originLabel: 'Watch',
					startedAt: '2026-07-20T12:00:00.000Z',
					durationSeconds: 120,
					distanceMeters: 500,
					heartRateSeries: {
						version: 1,
						sourceSampleCount: 1,
						points: [
							{ elapsedSeconds: 0, bpm: 120 },
							{ elapsedSeconds: 60, bpm: 130 }
						]
					},
					routeTrace: {
						version: 1,
						sourcePointCount: 2,
						points: [
							{
								latitudeE6: 45_000_000,
								longitudeE6: -63_000_000,
								elapsedSeconds: 0,
								segmentIndex: 0,
								speedMetersPerSecond: null
							},
							{
								latitudeE6: 45_000_100,
								longitudeE6: -63_000_100,
								elapsedSeconds: 60,
								segmentIndex: 1,
								speedMetersPerSecond: null
							},
							{
								latitudeE6: 45_000_200,
								longitudeE6: -63_000_200,
								elapsedSeconds: 30,
								segmentIndex: 0,
								speedMetersPerSecond: null
							}
						]
					}
				}
			]
		});

		expect(result.success).toBe(false);
		if (!result.success) {
			const messages = result.error.issues.map(({ message }) => message);
			expect(messages).toContain(
				'Health Connect heart-rate source count cannot be below retained samples.'
			);
			expect(messages).toContain(
				'Health Connect route points must be ordered within the activity and its segments.'
			);
			expect(messages).toContain(
				'Health Connect route source count cannot be below retained points.'
			);
		}
	});

	test('applies an unlinked correction with its corrected local date and heart-rate summary', async () => {
		const state = transactionWith([
			[
				{
					id: 'mapping',
					activityId: 'health-activity',
					pendingAction: 'correction',
					pendingActivity: pendingCorrection
				}
			],
			[{ routeDataMode: 'private' }],
			[{ workoutId: null, extraPlanImpactConfirmed: false }]
		]);

		await resolveHealthConnectRecord('user', 'mapping', 'accept_correction');

		expect(state.forUpdateCount()).toBe(2);
		expect(state.updates[0]).toMatchObject({
			occurredAt: new Date(pendingCorrection.occurredAt),
			activityDate: pendingCorrection.activityDate,
			heartRateSummary: pendingCorrection.heartRateSummary
		});
		expect(state.updates[1]).toMatchObject({
			pendingAction: 'none',
			pendingActivity: null
		});
	});

	test.each([
		[{ workoutId: 'workout', extraPlanImpactConfirmed: false }, 'Unlink this accepted activity'],
		[{ workoutId: null, extraPlanImpactConfirmed: true }, 'Stop counting this activity as extra']
	])('rejects a correction whose plan effects were not reversed', async (target, message) => {
		transactionWith([
			[
				{
					id: 'mapping',
					activityId: 'health-activity',
					pendingAction: 'correction',
					pendingActivity: pendingCorrection
				}
			],
			[{ routeDataMode: 'private' }],
			[target]
		]);

		await expect(
			resolveHealthConnectRecord('user', 'mapping', 'accept_correction')
		).rejects.toThrow(message);
	});

	test('runs source deletion through reversal-aware erasure in the same locked transaction', async () => {
		const state = transactionWith([
			[
				{
					id: 'mapping',
					activityId: 'health-activity',
					pendingAction: 'source_delete',
					pendingActivity: null
				}
			]
		]);

		await resolveHealthConnectRecord('user', 'mapping', 'delete_from_runway');

		expect(state.forUpdateCount()).toBe(1);
		expect(mocks.lockOwner).toHaveBeenCalledWith(state.tx, 'user');
		expect(mocks.deleteActivity).toHaveBeenCalledWith(state.tx, 'user', 'health-activity');
	});

	test('uses the existing duplicate and remaps atomically after reversal-aware erasure', async () => {
		const state = transactionWith([
			[
				{
					id: 'mapping',
					activityId: 'health-activity',
					duplicateCandidateActivityId: 'existing-activity'
				}
			],
			[{ id: 'health-activity' }, { id: 'existing-activity' }]
		]);

		await resolveHealthConnectDuplicate('user', 'mapping', 'use_existing');

		expect(state.forUpdateCount()).toBe(2);
		expect(mocks.deleteActivity).toHaveBeenCalledWith(state.tx, 'user', 'health-activity');
		const lastUpdate = state.updates.at(-1);
		expect(lastUpdate).toMatchObject({
			activityId: 'existing-activity',
			duplicateCandidateActivityId: null,
			pendingAction: 'none'
		});
		expect(lastUpdate?.['deletedAt']).toBeInstanceOf(Date);
	});
});
