import { describe, expect, test } from 'vitest';
import { groupImportSourcesByUser, mapWithBoundedConcurrency } from './import-worker';

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
});
