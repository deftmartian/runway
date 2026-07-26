import { beforeEach, describe, expect, test, vi } from 'vitest';
import { deleteActivityRecordInTransaction } from './activity-mutations';

const ledger = vi.hoisted(() => ({
	erase: vi.fn()
}));

vi.mock('$lib/server/db', () => ({
	db: { transaction: vi.fn() }
}));
vi.mock('./adjustment-ledger', () => ({
	eraseLedgerAdjustments: ledger.erase,
	recordPlanAdjustment: vi.fn(),
	reverseLedgerAdjustmentsForTrigger: vi.fn()
}));

function fakeTransaction() {
	const selectResults = [
		[
			{
				id: 'health-activity',
				workoutId: 'linked-workout',
				source: 'health_connect',
				fileHash: null
			}
		],
		[{ id: 'adjustment', workoutId: 'affected-workout' }],
		[{ id: 'mapping', externalKey: 'opaque-record-key' }]
	];
	const inserted: unknown[] = [];
	const updated: Record<string, unknown>[] = [];
	const deleted: unknown[] = [];
	const tx = {
		select: vi.fn(() => {
			const result = selectResults.shift() ?? [];
			const query = {
				from: () => query,
				leftJoin: () => query,
				where: () => query,
				limit: () => query,
				for: () => Promise.resolve(result),
				then: (
					resolve: (value: Record<string, unknown>[]) => unknown,
					reject?: (reason: unknown) => unknown
				) => Promise.resolve(result).then(resolve, reject)
			};
			return query;
		}),
		insert: vi.fn(() => {
			const query = {
				values: (value: unknown) => {
					inserted.push(value);
					return query;
				},
				onConflictDoNothing: () => Promise.resolve([]),
				then: (resolve: (value: never[]) => unknown, reject?: (reason: unknown) => unknown) =>
					Promise.resolve([]).then(resolve, reject)
			};
			return query;
		}),
		update: vi.fn(() => ({
			set: (value: Record<string, unknown>) => {
				updated.push(value);
				return { where: () => Promise.resolve([]) };
			}
		})),
		delete: vi.fn((table: unknown) => {
			deleted.push(table);
			return { where: () => Promise.resolve([]) };
		})
	};
	return { deleted, inserted, tx, updated };
}

describe('activity deletion transaction seam', () => {
	beforeEach(() => {
		ledger.erase.mockReset();
	});

	test('reverses plan effects, tombstones the provider key, and clears its mapping before deletion', async () => {
		const state = fakeTransaction();

		await deleteActivityRecordInTransaction(
			state.tx as unknown as Parameters<typeof deleteActivityRecordInTransaction>[0],
			'user',
			'health-activity'
		);

		expect(ledger.erase).toHaveBeenCalledWith(state.tx, {
			userId: 'user',
			targets: [{ id: 'adjustment', workoutId: 'affected-workout' }]
		});
		expect(state.inserted[0]).toEqual([{ userId: 'user', externalKey: 'opaque-record-key' }]);
		expect(state.updated[0]).toMatchObject({
			activityId: null,
			pendingAction: 'none',
			pendingActivity: null
		});
		expect(state.updated[0]?.['deletedAt']).toBeInstanceOf(Date);
		expect(state.deleted).toHaveLength(4);
	});
});
