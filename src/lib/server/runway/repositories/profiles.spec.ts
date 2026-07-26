import { beforeEach, describe, expect, test, vi } from 'vitest';
import { isCurrentPainReportDate, updateRouteDataMode } from './profiles';

const mocks = vi.hoisted(() => ({
	lockOwner: vi.fn(),
	transaction: vi.fn()
}));

vi.mock('$lib/server/db', () => ({
	db: { transaction: mocks.transaction }
}));
vi.mock('./mutation-locks', () => ({
	lockActivityOwner: mocks.lockOwner
}));

describe('current pain report window', () => {
	beforeEach(() => {
		mocks.lockOwner.mockReset();
		mocks.transaction.mockReset();
	});

	test('holds recent reports for explicit health-context review without treating older records as current', () => {
		expect(isCurrentPainReportDate('2026-07-22', '2026-07-22')).toBe(true);
		expect(isCurrentPainReportDate('2026-07-15', '2026-07-22')).toBe(true);
		expect(isCurrentPainReportDate('2026-07-14', '2026-07-22')).toBe(false);
	});
});

describe('route privacy erasure', () => {
	test('takes the same account lock as Health Connect correction acceptance before clearing traces', async () => {
		const firstInsert = {
			values: vi.fn(() => ({ onConflictDoUpdate: vi.fn(() => Promise.resolve()) }))
		};
		const auditInsert = { values: vi.fn(() => Promise.resolve()) };
		const tx = {
			insert: vi.fn().mockReturnValueOnce(firstInsert).mockReturnValueOnce(auditInsert),
			update: vi.fn(() => ({
				set: vi.fn(() => ({
					where: vi.fn(() => ({ returning: vi.fn(() => Promise.resolve([])) }))
				}))
			}))
		};
		mocks.transaction.mockImplementationOnce((callback: (transaction: typeof tx) => unknown) =>
			callback(tx)
		);

		await updateRouteDataMode('user', 'discard');

		expect(mocks.lockOwner).toHaveBeenCalledWith(tx, 'user');
		expect(mocks.lockOwner).toHaveBeenCalledBefore(firstInsert.values);
		expect(firstInsert.values).toHaveBeenCalledBefore(tx.update);
		expect(tx.update).toHaveBeenCalledTimes(2);
	});
});
