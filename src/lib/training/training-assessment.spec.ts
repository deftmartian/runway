import { describe, expect, test } from 'vitest';
import {
	formatRampEvidence,
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

	test('formats exact ramp arithmetic without claiming medical safety', () => {
		expect(formatRampEvidence(9.44, 7.5)).toBe(
			"Weekly distance would rise 9.4%. runway's default for this plan is 7.5%."
		);
		expect(formatRampEvidence(12)).toBe('Required weekly increase: 12%.');
	});

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
