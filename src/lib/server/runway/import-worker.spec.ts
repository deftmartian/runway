import { describe, expect, test, vi } from 'vitest';
import {
	groupImportSourcesByUser,
	mapWithBoundedConcurrency,
	runScheduledMaintenanceTasks
} from './import-worker';

describe('scheduled import worker batching', () => {
	test('keeps one account ordered while exposing independent user groups', () => {
		expect(
			groupImportSourcesByUser([
				{ userId: 'first', id: 'a' },
				{ userId: 'second', id: 'b' },
				{ userId: 'first', id: 'c' }
			])
		).toEqual([
			[
				{ userId: 'first', id: 'a' },
				{ userId: 'first', id: 'c' }
			],
			[{ userId: 'second', id: 'b' }]
		]);
	});

	test('bounds parallel work and retains result order', async () => {
		let active = 0;
		let maximumActive = 0;
		const results = await mapWithBoundedConcurrency([1, 2, 3, 4, 5, 6], 3, async (item) => {
			active += 1;
			maximumActive = Math.max(maximumActive, active);
			await new Promise((resolve) => setTimeout(resolve, 5));
			active -= 1;
			return item * 2;
		});

		expect(maximumActive).toBe(3);
		expect(results).toEqual([2, 4, 6, 8, 10, 12]);
	});

	test('rejects invalid concurrency instead of running an unbounded fallback', async () => {
		await expect(
			mapWithBoundedConcurrency([1], 0, (item) => Promise.resolve(item))
		).rejects.toThrow('Worker concurrency must be a positive integer.');
	});

	test('runs database retention on every maintenance pass', async () => {
		const tasks = {
			syncImports: vi.fn(() => Promise.resolve()),
			purgeAuditEvents: vi.fn(() => Promise.resolve()),
			purgeOperationalRecords: vi.fn(() => Promise.resolve())
		};

		await runScheduledMaintenanceTasks(tasks);

		expect(tasks.syncImports).toHaveBeenCalledOnce();
		expect(tasks.purgeAuditEvents).toHaveBeenCalledOnce();
		expect(tasks.purgeOperationalRecords).toHaveBeenCalledOnce();
	});

	test('waits for every maintenance task before reporting a failure', async () => {
		const purgeOperationalRecords = vi.fn(() => Promise.resolve());

		await expect(
			runScheduledMaintenanceTasks({
				syncImports: () => Promise.reject(new Error('sync failed')),
				purgeAuditEvents: () => Promise.resolve(),
				purgeOperationalRecords
			})
		).rejects.toThrow('scheduled maintenance tasks failed');
		expect(purgeOperationalRecords).toHaveBeenCalledOnce();
	});
});
