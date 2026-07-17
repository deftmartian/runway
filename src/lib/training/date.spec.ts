import { describe, expect, it } from 'vitest';
import { isValidTimeZone, localDateAtNoon, parseIsoDate, toIsoDateInTimeZone } from './date';

describe('athlete-local dates', () => {
	it('derives a stable local date from an activity instant', () => {
		const instant = new Date('2026-07-15T01:30:00.000Z');
		expect(toIsoDateInTimeZone(instant, 'America/Halifax')).toBe('2026-07-14');
		expect(toIsoDateInTimeZone(instant, 'Pacific/Auckland')).toBe('2026-07-15');
	});

	it('stores date-only manual runs at local noon across daylight-saving changes', () => {
		for (const date of ['2026-01-15', '2026-07-15']) {
			const instant = localDateAtNoon(date, 'America/Halifax');
			expect(toIsoDateInTimeZone(instant, 'America/Halifax')).toBe(date);
		}
	});

	it('rejects non-IANA timezone identifiers', () => {
		expect(isValidTimeZone('America/Halifax')).toBe(true);
		expect(isValidTimeZone('not/a-zone')).toBe(false);
	});

	it('rejects impossible and non-canonical ISO dates instead of normalizing them', () => {
		expect(parseIsoDate('2026-02-28').toISOString()).toBe('2026-02-28T00:00:00.000Z');
		expect(() => parseIsoDate('2026-02-31')).toThrow(/invalid date/i);
		expect(() => parseIsoDate('2026-2-8')).toThrow(/invalid date/i);
		expect(() => parseIsoDate('2026-02-28T00:00:00Z')).toThrow(/invalid date/i);
	});
});
