import { describe, expect, test } from 'vitest';
import { isCurrentPainReportDate } from './profiles';

describe('current pain report window', () => {
	test('holds recent reports for explicit health-context review without treating older records as current', () => {
		expect(isCurrentPainReportDate('2026-07-22', '2026-07-22')).toBe(true);
		expect(isCurrentPainReportDate('2026-07-15', '2026-07-22')).toBe(true);
		expect(isCurrentPainReportDate('2026-07-14', '2026-07-22')).toBe(false);
	});
});
