import { describe, expect, it } from 'vitest';
import type { ConsequenceResult } from '$lib/training/types';
import {
	currentSignalReasonsFor,
	healthNoticeFor,
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

	it('keeps stored health warnings out of the numeric ramp reasons', () => {
		expect(
			currentSignalReasonsFor({
				planRisk: 'conservative',
				planWarnings: [
					'Injury recovery or recurring pain is included in the ramp assessment. Get qualified guidance if pain persists.',
					'The available weeks do not allow the usual peak distance.'
				],
				selectedConsequence: null
			})
		).toEqual(['The available weeks do not allow the usual peak distance.']);
	});
});

describe('current training evidence selection', () => {
	it('keeps newer normal feedback as the current load signal while health context remains separate', () => {
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

	it('keeps an explicitly current pain context separate from later normal load evidence', () => {
		const current = selectCurrentTrainingSignal({
			planRisk: 'conservative',
			planWarnings: [],
			recordedEvidence: [
				evidence({ consequence: painConsequence, evidenceDate: '2026-07-14' }),
				evidence({ evidenceDate: '2026-07-16' })
			],
			healthNotice: healthNoticeFor({
				recentInjury: false,
				currentPain: true,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			})
		});

		expect(current.risk).toBe('conservative');
		expect(current.healthNotice).toMatchObject({ level: 'paused', heading: 'Pain is present now' });
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
			source: 'plan',
			planComparisonStatus: 'comparable',
			healthNotice: null
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

describe('current training health context', () => {
	const healthy = {
		recentInjury: false,
		currentPain: false,
		recurringPain: false,
		medicalRestriction: false,
		notes: ''
	};

	it('keeps active pain separate from the numeric training signal', () => {
		const healthNotice = healthNoticeFor({ ...healthy, currentPain: true });
		const current = selectCurrentTrainingSignal({
			planRisk: 'conservative',
			planWarnings: [],
			recordedEvidence: [],
			healthNotice
		});

		expect(current.risk).toBe('conservative');
		expect(current.healthNotice).toMatchObject({ level: 'paused', heading: 'Pain is present now' });
	});

	it('marks mixed prescriptions as non-comparable without changing the numeric risk', () => {
		const current = selectCurrentTrainingSignal({
			planRisk: 'moderate',
			planWarnings: ["Weekly distance growth above 10% is outside runway's default."],
			recordedEvidence: [],
			planHasMixedLoad: true
		});

		expect(current).toMatchObject({
			risk: 'moderate',
			planComparisonStatus: 'mixed',
			reasons: []
		});
	});
});
