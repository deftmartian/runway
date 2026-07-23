import { describe, expect, it, test } from 'vitest';
import {
	calculateExtraActivityConsequence,
	historicalExtraActivityReview,
	isHistoricalExtraActivity
} from './extra-activity';

describe('extra activity consequences', () => {
	test('keeps the seven-day boundary current and makes older activity acknowledge-only', () => {
		expect(isHistoricalExtraActivity('2026-07-15', '2026-07-22')).toBe(false);
		expect(isHistoricalExtraActivity('2026-07-14', '2026-07-22')).toBe(true);

		const calculated = calculateExtraActivityConsequence(
			{ distanceMeters: 4_000, feltHard: false, pain: false },
			{
				nextRunTargetDistanceMeters: 5_000,
				nextRunTargetDurationSeconds: null,
				weekTargetDistanceMeters: 15_000,
				weekTargetDurationSeconds: 0
			}
		);
		expect(historicalExtraActivityReview(calculated)).toMatchObject({
			kind: 'extra_activity',
			nextRunAdjustment: null,
			nextRunAdjustmentMeters: 0,
			planChangeAvailable: false,
			options: []
		});
	});
	it('uses duration load for an extra run during a timed phase', () => {
		const consequence = calculateExtraActivityConsequence(
			{ distanceMeters: 2_000, durationSeconds: 1_200, feltHard: false, pain: false },
			{
				nextRunTargetDistanceMeters: 0,
				nextRunTargetDurationSeconds: 1_200,
				weekTargetDistanceMeters: 0,
				weekTargetDurationSeconds: 3_600
			}
		);

		expect(consequence.metric).toBe('duration');
		expect(consequence.actualDifference).toBe(1_200);
		expect(consequence.weeklyLoadDelta).toEqual({ metric: 'duration', value: 1_200 });
		expect(consequence.nextRunAdjustment).toEqual({ metric: 'duration', value: -600 });
		expect(consequence.risk).toBe('aggressive');
		expect(consequence.nextRunAdjustmentMeters).toBe(0);
	});

	it('marks a timed-phase activity without duration as non-comparable', () => {
		const consequence = calculateExtraActivityConsequence(
			{ distanceMeters: 2_000, feltHard: false, pain: false },
			{
				nextRunTargetDistanceMeters: 0,
				nextRunTargetDurationSeconds: 1_200,
				weekTargetDistanceMeters: 0,
				weekTargetDurationSeconds: 3_600
			}
		);

		expect(consequence.metric).toBe('none');
		expect(consequence.comparisonStatus).toBe('not_comparable');
		expect(consequence.weeklyLoadDelta).toBeNull();
		expect(consequence.risk).toBe('moderate');
		expect(consequence.recommendedDecision).toBe('keep_plan');
		expect(consequence.options).not.toContain('rebalance_week');
	});

	it('keeps pain authoritative without inventing a distance reduction for timed work', () => {
		const consequence = calculateExtraActivityConsequence(
			{ distanceMeters: 2_000, durationSeconds: 1_200, feltHard: false, pain: true },
			{
				nextRunTargetDistanceMeters: 0,
				nextRunTargetDurationSeconds: 1_200,
				weekTargetDistanceMeters: 0,
				weekTargetDurationSeconds: 3_600
			}
		);

		expect(consequence.risk).toBe('unsafe');
		expect(consequence.recommendedDecision).toBe('next_rest');
		expect(consequence.nextRunAdjustment).toEqual({ metric: 'duration', value: -1_200 });
		expect(consequence.nextRunAdjustmentMeters).toBe(0);
	});
});
