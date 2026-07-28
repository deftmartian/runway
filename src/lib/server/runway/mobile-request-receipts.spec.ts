import { beforeEach, describe, expect, test, vi } from 'vitest';

const database = vi.hoisted(() => {
	const createdRows: { id: string }[] = [];
	const existingRows: {
		action: string;
		payloadHash: string;
		state: string;
		responseStatus: number | null;
		responseBody: Record<string, unknown> | null;
		updatedAt: Date;
	}[] = [];
	const completedRows: { id: string }[] = [];
	const insertValues = vi.fn();
	const updateSet = vi.fn();
	const insert = vi.fn(() => {
		const query = {
			values: (value: unknown) => {
				insertValues(value);
				return query;
			},
			onConflictDoNothing: () => query,
			returning: () => Promise.resolve(createdRows.splice(0, 1))
		};
		return query;
	});
	const select = vi.fn(() => {
		const query = {
			from: () => query,
			where: () => query,
			limit: () => Promise.resolve(existingRows.splice(0, 1))
		};
		return query;
	});
	const update = vi.fn(() => {
		const query = {
			set: (value: unknown) => {
				updateSet(value);
				return query;
			},
			where: () => query,
			returning: () => Promise.resolve(completedRows.splice(0, 1))
		};
		return query;
	});
	return {
		completedRows,
		createdRows,
		existingRows,
		insert,
		insertValues,
		select,
		update,
		updateSet
	};
});

vi.mock('$lib/server/db', () => ({
	db: {
		insert: database.insert,
		select: database.select,
		update: database.update
	}
}));

import { claimMobileRequest, completeMobileRequest } from './mobile-request-receipts';

const request = {
	userId: 'runner-1',
	requestId: 'f47ac10b-58cc-4372-a567-0e02b2c3d479',
	action: 'record-feedback',
	payload: Buffer.from('{"outcome":"completed"}')
};

function lastCallArgument(mock: { mock: { calls: unknown[][] } }): unknown {
	return mock.mock.calls.at(-1)?.[0];
}

function requireRecord(value: unknown): Record<string, unknown> {
	if (typeof value !== 'object' || value === null || Array.isArray(value)) {
		throw new Error('Expected a recorded object.');
	}
	return value as Record<string, unknown>;
}

describe('native mutation idempotency receipts', () => {
	beforeEach(() => {
		database.createdRows.length = 0;
		database.existingRows.length = 0;
		database.completedRows.length = 0;
		database.insert.mockClear();
		database.insertValues.mockClear();
		database.select.mockClear();
		database.update.mockClear();
		database.updateSet.mockClear();
	});

	test('claims a new request before an action can mutate state', async () => {
		database.createdRows.push({ id: 'receipt-1' });

		await expect(claimMobileRequest(request)).resolves.toMatchObject({ result: 'claimed' });
		const inserted = requireRecord(lastCallArgument(database.insertValues));
		expect(inserted['userId']).toBe(request.userId);
		expect(inserted['requestId']).toBe(request.requestId);
		expect(inserted['action']).toBe(request.action);
		expect(inserted['payloadHash']).toMatch(/^[a-f0-9]{64}$/);
		expect(database.select).not.toHaveBeenCalled();
	});

	test('replays the exact completed response for the same action and payload', async () => {
		database.existingRows.push({
			action: request.action,
			payloadHash: '12be39fdea3651a12e0975c46c3d4981ae2def5381bc7b330109e36221bb43c4',
			state: 'completed',
			responseStatus: 201,
			responseBody: { ok: true, activityId: 'activity-1' },
			updatedAt: new Date('2026-07-28T12:00:00.000Z')
		});

		await expect(claimMobileRequest(request)).resolves.toEqual({
			result: 'replay',
			response: { status: 201, body: { ok: true, activityId: 'activity-1' } }
		});
	});

	test('rejects a reused request ID when action or bytes differ', async () => {
		database.existingRows.push({
			action: 'delete-activity',
			payloadHash: 'not-the-request-hash',
			state: 'completed',
			responseStatus: 200,
			responseBody: { ok: true },
			updatedAt: new Date('2026-07-28T12:00:00.000Z')
		});

		await expect(claimMobileRequest(request)).resolves.toEqual({ result: 'conflict' });
		expect(database.update).not.toHaveBeenCalled();
	});

	test('keeps a matching unfinished request within its lease protected from duplicate execution', async () => {
		database.existingRows.push({
			action: request.action,
			payloadHash: '12be39fdea3651a12e0975c46c3d4981ae2def5381bc7b330109e36221bb43c4',
			state: 'processing',
			responseStatus: null,
			responseBody: null,
			updatedAt: new Date('2026-07-28T12:00:00.000Z')
		});

		await expect(
			claimMobileRequest({ ...request, now: new Date('2026-07-28T12:04:59.999Z') })
		).resolves.toEqual({ result: 'processing' });
		expect(database.update).not.toHaveBeenCalled();
	});

	test('terminally reclaims an expired processing receipt without rerunning the mutation', async () => {
		database.existingRows.push({
			action: request.action,
			payloadHash: '12be39fdea3651a12e0975c46c3d4981ae2def5381bc7b330109e36221bb43c4',
			state: 'processing',
			responseStatus: null,
			responseBody: null,
			updatedAt: new Date('2026-07-28T12:00:00.000Z')
		});
		database.completedRows.push({ id: 'receipt-1' });

		await expect(
			claimMobileRequest({ ...request, now: new Date('2026-07-28T12:05:00.001Z') })
		).resolves.toMatchObject({
			result: 'recovered',
			response: {
				status: 409,
				body: { error: 'request_outcome_unknown' }
			}
		});
		const recovered = requireRecord(lastCallArgument(database.updateSet));
		expect(recovered['state']).toBe('completed');
		expect(recovered['responseStatus']).toBe(409);
		expect(recovered['completedAt']).toBeInstanceOf(Date);
	});

	test('only finalizes the processing receipt matching the claimed payload', async () => {
		database.completedRows.push({ id: 'receipt-1' });

		await expect(
			completeMobileRequest({
				userId: request.userId,
				requestId: request.requestId,
				payloadHash: '12be39fdea3651a12e0975c46c3d4981ae2def5381bc7b330109e36221bb43c4',
				response: { status: 200, body: { ok: true } }
			})
		).resolves.toBeUndefined();
		const completed = requireRecord(lastCallArgument(database.updateSet));
		expect(completed['state']).toBe('completed');
		expect(completed['responseStatus']).toBe(200);
		expect(completed['responseBody']).toEqual({ ok: true });
		expect(completed['completedAt']).toBeInstanceOf(Date);
	});
});
