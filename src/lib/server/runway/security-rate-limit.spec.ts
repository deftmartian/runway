import { beforeEach, describe, expect, test, vi } from 'vitest';
import {
	consumeSecurityRateLimit,
	gpxImportRateLimitBuckets,
	nextcloudImportRateLimitBuckets,
	prioritizeAddressBuckets,
	signUpRateLimitBuckets
} from './security-rate-limit';

const database = vi.hoisted(() => {
	const returnedRows: { count: number; resetAt: Date }[] = [];
	const returning = vi.fn(() => Promise.resolve(returnedRows.splice(0, 1)));
	const insert = vi.fn(() => {
		const query = {
			values: () => query,
			onConflictDoUpdate: () => query,
			returning
		};
		return query;
	});
	const transaction = vi.fn((callback: (tx: { insert: typeof insert }) => unknown) =>
		callback({ insert })
	);
	return { insert, returnedRows, returning, transaction };
});

vi.mock('$lib/server/db', () => ({ db: { transaction: database.transaction } }));

describe('import security rate-limit contracts', () => {
	beforeEach(() => {
		database.returnedRows.length = 0;
		database.insert.mockClear();
		database.returning.mockClear();
		database.transaction.mockClear();
	});

	test('bounds GPX parsing by both user and client address', () => {
		expect(gpxImportRateLimitBuckets('user-1', '192.0.2.4')).toEqual([
			{
				name: 'gpx-import:ip',
				subject: '192.0.2.4',
				max: 60,
				windowMs: 600_000
			},
			{ name: 'gpx-import:user', subject: 'user-1', max: 30, windowMs: 600_000 }
		]);
	});

	test('keeps Nextcloud aggregate and action-specific budgets', () => {
		expect(nextcloudImportRateLimitBuckets('connect', 'user-1', '192.0.2.4')).toEqual([
			{
				name: 'nextcloud-import:ip',
				subject: '192.0.2.4',
				max: 40,
				windowMs: 600_000
			},
			{ name: 'nextcloud-import:user', subject: 'user-1', max: 20, windowMs: 600_000 },
			{
				name: 'nextcloud-import:connect:user',
				subject: 'user-1',
				max: 5,
				windowMs: 600_000
			}
		]);
	});

	test('checks the address budget before attacker-controlled account subjects', () => {
		const [address, email] = signUpRateLimitBuckets('new@example.test', '192.0.2.4');
		if (!address || !email) throw new Error('Expected address and email test buckets.');
		expect(prioritizeAddressBuckets([email, address])).toEqual([address, email]);
	});

	test('stops before writing account buckets once the address is blocked', async () => {
		database.returnedRows.push({
			count: 11,
			resetAt: new Date(Date.now() + 60_000)
		});
		const buckets = signUpRateLimitBuckets('unique@example.test', '192.0.2.4').reverse();

		await expect(consumeSecurityRateLimit(buckets)).resolves.toMatchObject({ allowed: false });
		expect(database.insert).toHaveBeenCalledOnce();
	});

	test('writes the stable account bucket only while the address remains allowed', async () => {
		database.returnedRows.push(
			{ count: 1, resetAt: new Date(Date.now() + 60_000) },
			{ count: 1, resetAt: new Date(Date.now() + 60_000) }
		);

		await expect(
			consumeSecurityRateLimit(signUpRateLimitBuckets('known@example.test', '192.0.2.4'))
		).resolves.toEqual({ allowed: true, retryAfterSeconds: 0 });
		expect(database.insert).toHaveBeenCalledTimes(2);
	});
});
