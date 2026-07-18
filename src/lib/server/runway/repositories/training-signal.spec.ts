import { describe, expect, it } from 'vitest';
import type { ConsequenceResult } from '$lib/training/types';
import { currentSignalReasonsFor } from './training-signal';

const consequence: ConsequenceResult = {
	kind: 'shortfall',
	deviation: 'short',
	metric: 'distance',
	actualDifference: -1_000,
	weeklyDistanceDeltaMeters: -1_000,
	nextRunAdjustmentMeters: -500,
	risk: 'moderate',
	recommendedDecision: 'reduce_next',
	options: ['keep_plan', 'reduce_next'],
	appliedDecision: 'reduce_next'
};

describe('current training signal reasons', () => {
	it('keeps a conservative plan quiet when there is no recorded concern', () => {
		expect(
			currentSignalReasonsFor({
				planRisk: 'conservative',
				planWarnings: [],
				selectedConsequence: null
			})
		).toEqual([]);
	});

	it('retains the saved-plan fallback when the ramp is above the default', () => {
		expect(
			currentSignalReasonsFor({
				planRisk: 'moderate',
				planWarnings: [],
				selectedConsequence: null
			})
		).toEqual(["The saved plan is above runway's default ramp."]);
	});

	it('includes an equally serious recorded consequence before plan warnings', () => {
		expect(
			currentSignalReasonsFor({
				planRisk: 'moderate',
				planWarnings: ['Leave more recovery time.'],
				selectedConsequence: consequence
			})
		).toEqual([
			'Completed 1 km below plan. Next run reduced by 0.5 km.',
			'Leave more recovery time.'
		]);
	});

	it('deduplicates and bounds plan warnings', () => {
		expect(
			currentSignalReasonsFor({
				planRisk: 'aggressive',
				planWarnings: ['One', 'One', 'Two', 'Three', 'Four'],
				selectedConsequence: null
			})
		).toEqual(['One', 'Two', 'Three']);
	});
});
