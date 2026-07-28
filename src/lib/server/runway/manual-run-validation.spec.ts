import { describe, expect, test } from 'vitest';
import { manualRunSchema } from './validation';

const base = {
	occurredDate: '2026-07-28',
	feltHard: false,
	pain: false
};

describe('manual run measurement boundary', () => {
	test('accepts distance, duration, or both', () => {
		expect(manualRunSchema.safeParse({ ...base, distanceKm: 5 }).success).toBe(true);
		expect(manualRunSchema.safeParse({ ...base, durationMinutes: 30 }).success).toBe(true);
		expect(
			manualRunSchema.safeParse({
				...base,
				distanceKm: 5,
				durationMinutes: 30
			}).success
		).toBe(true);
	});

	test('rejects a run with no recorded amount', () => {
		const parsed = manualRunSchema.safeParse({
			...base,
			distanceKm: '',
			durationMinutes: ''
		});

		expect(parsed.success).toBe(false);
		if (!parsed.success) {
			expect(parsed.error.issues[0]?.message).toBe('Enter the distance, duration, or both.');
		}
	});
});
