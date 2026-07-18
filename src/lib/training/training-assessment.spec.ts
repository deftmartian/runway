import { describe, expect, test } from 'vitest';
import type { ConsequenceResult } from './types';
import {
	formatLoadChangeEvidence,
	formatRampEvidence,
	presentConsequenceAssessment,
	presentLoadChangeAssessment,
	presentRampAssessment,
	trainingAssessmentFromRisk
} from './training-assessment';

describe('training assessment presentation', () => {
	test.each([
		['conservative', 'within_default', 'Within default', 'none'],
		['moderate', 'above_default', 'Above default', 'review'],
		['aggressive', 'high_increase', 'High increase', 'high'],
		['unsafe', 'unsupported', 'Unsupported', 'blocked']
	] as const)('maps internal %s arithmetic to %s', (risk, assessment, label, attention) => {
		expect(trainingAssessmentFromRisk(risk)).toBe(assessment);
		expect(presentRampAssessment(risk)).toMatchObject({ assessment, label, attention });
	});

	test('does not call a valid runner-controlled change unsupported', () => {
		expect(presentLoadChangeAssessment('unsafe')).toEqual({
			assessment: 'unsupported',
			label: 'Outside default',
			description: "This change is outside runway's default range and needs explicit confirmation.",
			attention: 'high'
		});
	});

	test('separates pain and non-comparable records from numeric load labels', () => {
		const common: Omit<ConsequenceResult, 'kind' | 'risk'> = {
			deviation: 'unplanned' as const,
			metric: 'none' as const,
			actualDifference: 0,
			weeklyLoadDelta: null,
			nextRunAdjustment: null,
			weeklyDistanceDeltaMeters: 2_000,
			nextRunAdjustmentMeters: 0,
			recommendedDecision: 'keep_plan' as const,
			options: ['keep_plan'],
			appliedDecision: null
		};
		expect(
			presentConsequenceAssessment({
				...common,
				kind: 'extra_activity',
				comparisonStatus: 'not_comparable',
				risk: 'moderate'
			})
		).toMatchObject({ label: 'Needs review', assessment: 'needs_review' });
		expect(
			presentConsequenceAssessment({ ...common, kind: 'pain_reported', risk: 'unsafe' })
		).toMatchObject({ label: 'Pain review', assessment: 'pain_review' });
	});

	test('formats exact ramp arithmetic without claiming medical safety', () => {
		expect(formatRampEvidence(9.44, 7.5)).toBe('9.4% required · 7.5% generated-week cap');
		expect(formatRampEvidence(12)).toBe('12% required weekly increase');
	});

	test.each([
		[1.3, 'conservative', '1.3% of weekly load; default up to 10%.'],
		[10.3, 'moderate', '10.3% of weekly load; default up to 10%.'],
		[15.3, 'aggressive', '15.3% of weekly load; high-change boundary 15%.'],
		[25.3, 'unsafe', '25.3% of weekly load; outside-default boundary 25%.']
	] as const)(
		'formats %s load evidence against the relevant boundary',
		(change, risk, expected) => {
			expect(formatLoadChangeEvidence(change, risk)).toBe(expected);
		}
	);

	test('rejects invalid arithmetic instead of presenting it as evidence', () => {
		expect(() => formatRampEvidence(Number.NaN)).toThrow(/finite percentage/i);
		expect(() => formatRampEvidence(8, Number.POSITIVE_INFINITY)).toThrow(/finite percentage/i);
	});

	test.each(['conservative', 'moderate', 'aggressive', 'unsafe'] as const)(
		'keeps medical safety language out of the %s ramp presentation',
		(risk) => {
			const presentation = presentRampAssessment(risk);
			expect(`${presentation.label} ${presentation.description}`).not.toMatch(/safe|risk/i);
		}
	);
});
