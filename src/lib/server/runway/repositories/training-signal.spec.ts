import { describe, expect, it } from 'vitest';
import type { ConsequenceResult } from '$lib/training/types';
import {
	currentSignalReasonsFor,
	selectCurrentTrainingSignal,
	type RecordedTrainingEvidence
} from './training-signal';

const consequence: ConsequenceResult = {
	kind: 'shortfall',
	deviation: 'short',
	metric: 'distance',
	actualDifference: -1_000,
	weeklyLoadDelta: { metric: 'distance', value: -1_000 },
	nextRunAdjustment: { metric: 'distance', value: -500 },
	weeklyDistanceDeltaMeters: -1_000,
	nextRunAdjustmentMeters: -500,
	risk: 'moderate',
	recommendedDecision: 'reduce_next',
	options: ['keep_plan', 'reduce_next'],
	appliedDecision: 'reduce_next'
};

const painConsequence: ConsequenceResult = {
	kind: 'pain_reported',
	deviation: 'near_plan',
	metric: 'distance',
	actualDifference: 0,
	weeklyLoadDelta: { metric: 'distance', value: 0 },
	nextRunAdjustment: { metric: 'distance', value: -3_000 },
	weeklyDistanceDeltaMeters: 0,
	nextRunAdjustmentMeters: -3_000,
	risk: 'unsafe',
	recommendedDecision: 'next_rest',
	options: ['keep_plan', 'next_rest'],
	appliedDecision: null
};

const normalConsequence: ConsequenceResult = {
	kind: 'completed_as_planned',
	deviation: 'near_plan',
	metric: 'distance',
	actualDifference: 0,
	weeklyLoadDelta: { metric: 'distance', value: 0 },
	nextRunAdjustment: { metric: 'distance', value: 0 },
	weeklyDistanceDeltaMeters: 0,
	nextRunAdjustmentMeters: 0,
	risk: 'conservative',
	recommendedDecision: 'keep_plan',
	options: ['keep_plan'],
	appliedDecision: null
};

function evidence(overrides: Partial<RecordedTrainingEvidence> = {}): RecordedTrainingEvidence {
	return {
		consequence: normalConsequence,
		appliedDecision: null,
		createdAt: new Date('2026-07-16T12:00:00.000Z'),
		evidenceDate: '2026-07-16',
		source: 'feedback',
		...overrides
	};
}

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

describe('current training evidence selection', () => {
	it('lets newer normal feedback replace an older pain report regardless of severity', () => {
		const current = selectCurrentTrainingSignal({
			planRisk: 'conservative',
			planWarnings: [],
			recordedEvidence: [
				evidence({
					consequence: painConsequence,
					evidenceDate: '2026-07-14',
					createdAt: new Date('2026-07-18T12:00:00.000Z')
				}),
				evidence({
					evidenceDate: '2026-07-16',
					createdAt: new Date('2026-07-16T13:00:00.000Z')
				})
			]
		});

		expect(current).toMatchObject({
			risk: 'conservative',
			source: 'feedback',
			consequence: normalConsequence
		});
		expect(current.reasons).toEqual([
			'Completed at the planned amount. No future plan change applied. Recommended: keep the remaining plan.'
		]);
	});

	it('treats an applied decision on the latest evidence as a resolution boundary', () => {
		const current = selectCurrentTrainingSignal({
			planRisk: 'conservative',
			planWarnings: [],
			recordedEvidence: [
				evidence({ consequence: painConsequence, evidenceDate: '2026-07-14' }),
				evidence({
					consequence: { ...normalConsequence, appliedDecision: 'keep_plan' },
					appliedDecision: 'keep_plan',
					evidenceDate: '2026-07-16'
				})
			]
		});

		expect(current).toEqual({
			risk: 'conservative',
			consequence: null,
			reasons: [],
			source: 'plan'
		});
	});

	it('keeps a newer unresolved concern after an older resolved result', () => {
		const current = selectCurrentTrainingSignal({
			planRisk: 'conservative',
			planWarnings: [],
			recordedEvidence: [
				evidence({
					consequence: { ...normalConsequence, appliedDecision: 'keep_plan' },
					appliedDecision: 'keep_plan',
					evidenceDate: '2026-07-14'
				}),
				evidence({
					consequence: painConsequence,
					evidenceDate: '2026-07-16',
					source: 'activity'
				})
			]
		});

		expect(current).toMatchObject({
			risk: 'unsafe',
			source: 'activity',
			consequence: painConsequence
		});
	});
});
