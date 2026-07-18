import { describe, expect, it } from 'vitest';
import { isoWeekStart } from './schedule-queries';

describe('ISO week schedule boundaries', () => {
	it.each([
		['2026-07-20', '2026-07-20'],
		['2026-07-22', '2026-07-20'],
		['2026-07-26', '2026-07-20'],
		['2026-07-27', '2026-07-27']
	])('maps %s to Monday %s', (date, expected) => {
		expect(isoWeekStart(date)).toBe(expected);
	});
});
