import { describe, expect, it } from 'vitest';
import { presentConsequence, presentConsequenceFacts } from './consequence-presentation';
import type { ConsequenceResult } from './types';

describe('presentConsequence', () => {
	it('states an exact shortfall and next-run reduction', () => {
		expect(
			presentConsequence({
				kind: 'shortfall',
				deviation: 'short',
				metric: 'distance',
				actualDifference: -1_500,
				weeklyLoadDelta: { metric: 'distance', value: -1_500 },
				nextRunAdjustment: { metric: 'distance', value: -800 },
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
			weeklyLoadDelta: { metric: 'distance', value: -2_000 },
			nextRunAdjustment: { metric: 'distance', value: -3_000 },
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

	it('presents timed changes in minutes without falling back to kilometres', () => {
		const consequence: ConsequenceResult = {
			kind: 'hard_effort',
			deviation: 'near_plan',
			metric: 'duration',
			actualDifference: 0,
			weeklyLoadDelta: { metric: 'duration', value: 0 },
			nextRunAdjustment: { metric: 'duration', value: -300 },
			weeklyDistanceDeltaMeters: 0,
			nextRunAdjustmentMeters: 0,
			risk: 'moderate',
			recommendedDecision: 'reduce_next',
			options: ['keep_plan', 'reduce_next'],
			appliedDecision: 'reduce_next'
		};

		expect(presentConsequence(consequence).planChange).toBe('Next run reduced by 5 min.');
		expect(presentConsequenceFacts(consequence)).toEqual({
			weekImpact: 'Week matched planned duration',
			nextRunImpact: 'Next run −5 min'
		});
	});

	it('includes the timed reduction in an unapplied recommendation', () => {
		const consequence: ConsequenceResult = {
			kind: 'load_spike',
			deviation: 'over',
			metric: 'duration',
			actualDifference: 600,
			weeklyLoadDelta: { metric: 'duration', value: 600 },
			nextRunAdjustment: { metric: 'duration', value: -300 },
			weeklyDistanceDeltaMeters: 0,
			nextRunAdjustmentMeters: 0,
			risk: 'aggressive',
			recommendedDecision: 'reduce_next',
			options: ['keep_plan', 'reduce_next'],
			appliedDecision: null
		};

		expect(presentConsequence(consequence).planChange).toContain('reduce the next run by 5 min');
		expect(presentConsequenceFacts(consequence).weekImpact).toBe('Week +10 min');
	});
});
