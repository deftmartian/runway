import { describe, expect, it } from 'vitest';
import { presentConsequence } from './consequence-presentation';

describe('presentConsequence', () => {
	it('states an exact shortfall and next-run reduction', () => {
		expect(
			presentConsequence({
				kind: 'shortfall',
				deviation: 'short',
				metric: 'distance',
				actualDifference: -1_500,
				weeklyDistanceDeltaMeters: -1_500,
				nextRunAdjustmentMeters: -800,
				risk: 'moderate',
				recommendedDecision: 'keep_plan',
				options: ['keep_plan', 'reduce_next'],
				appliedDecision: 'reduce_next'
			})
		).toEqual({
			outcome: 'Completed 1.5 km below plan.',
			planChange: 'Next run reduced by 0.8 km.'
		});
	});

	it('keeps safety guidance separate from the calculated plan change', () => {
		const presentation = presentConsequence({
			kind: 'pain_reported',
			deviation: 'short',
			metric: 'distance',
			actualDifference: -2_000,
			weeklyDistanceDeltaMeters: -2_000,
			nextRunAdjustmentMeters: -3_000,
			risk: 'unsafe',
			recommendedDecision: 'next_rest',
			options: ['keep_plan', 'next_rest'],
			appliedDecision: 'next_rest'
		});

		expect(presentation.outcome).toBe('Pain was reported for this run.');
		expect(presentation.planChange).toBe('Next workout changed to rest.');
		expect(presentation.safety).toContain('qualified guidance');
	});
});
