import { describe, expect, it } from 'vitest';
import {
	calculateConsequence,
	calculateConsequenceDecisionEffect,
	isConsequenceDecisionTargetCompatible
} from './consequences';
import type { WorkoutFeedbackInput } from './types';

const timedBase: WorkoutFeedbackInput = {
	status: 'done',
	choice: 'skip_continue',
	targetDistanceMeters: 0,
	targetDurationSeconds: 1_200,
	completedDurationSeconds: 1_200,
	pain: false,
	feltHard: false,
	weekTargetDistanceMeters: 0
};

describe('timed workout consequences', () => {
	it.each([
		{
			name: 'pain',
			input: (): WorkoutFeedbackInput => ({ ...timedBase, pain: true }),
			kind: 'pain_reported',
			weeklyDelta: 0,
			nextAdjustment: -1_200
		},
		{
			name: 'overrun',
			input: (): WorkoutFeedbackInput => ({ ...timedBase, completedDurationSeconds: 1_800 }),
			kind: 'load_spike',
			weeklyDelta: 600,
			nextAdjustment: -300
		},
		{
			name: 'shortfall',
			input: (): WorkoutFeedbackInput => ({
				...timedBase,
				status: 'shortened',
				completedDurationSeconds: 600
			}),
			kind: 'shortfall',
			weeklyDelta: -600,
			nextAdjustment: -300
		},
		{
			name: 'skip',
			input: (): WorkoutFeedbackInput => ({
				status: 'skipped',
				choice: 'skip_continue',
				targetDistanceMeters: 0,
				targetDurationSeconds: 1_200,
				pain: false,
				feltHard: false,
				weekTargetDistanceMeters: 0
			}),
			kind: 'skip_continue',
			weeklyDelta: -1_200,
			nextAdjustment: -300
		},
		{
			name: 'hard effort',
			input: (): WorkoutFeedbackInput => ({ ...timedBase, feltHard: true }),
			kind: 'hard_effort',
			weeklyDelta: 0,
			nextAdjustment: -300
		}
	])('keeps $name effects in duration', ({ input, kind, weeklyDelta, nextAdjustment }) => {
		const result = calculateConsequence(input());

		expect(result).toMatchObject({
			kind,
			metric: 'duration',
			weeklyLoadDelta: { metric: 'duration', value: weeklyDelta },
			nextRunAdjustment: { metric: 'duration', value: nextAdjustment },
			weeklyDistanceDeltaMeters: 0,
			nextRunAdjustmentMeters: 0
		});
	});

	it('uses the same timed adjustment for a decision target', () => {
		const consequence = calculateConsequence({
			...timedBase,
			completedDurationSeconds: 1_800
		});

		expect(
			calculateConsequenceDecisionEffect({
				consequence,
				decision: 'reduce_next',
				target: { targetDistanceMeters: 0, targetDurationSeconds: 1_800 }
			})
		).toEqual({
			metric: 'duration',
			previousTarget: 1_800,
			adjustment: -300,
			newTarget: 1_500
		});
	});

	it('shares the calculated duration reduction when rebalancing', () => {
		const consequence = calculateConsequence({ ...timedBase, feltHard: true });

		expect(
			calculateConsequenceDecisionEffect({
				consequence,
				decision: 'rebalance_week',
				target: { targetDistanceMeters: 0, targetDurationSeconds: 1_800 },
				shareCount: 2
			})
		).toMatchObject({ metric: 'duration', adjustment: -150, newTarget: 1_650 });
	});

	it('limits rebalance candidates to the consequence native unit', () => {
		const consequence = calculateConsequence({ ...timedBase, feltHard: true });
		expect(
			isConsequenceDecisionTargetCompatible(consequence, {
				targetDistanceMeters: 0,
				targetDurationSeconds: 1_200
			})
		).toBe(true);
		expect(
			isConsequenceDecisionTargetCompatible(consequence, {
				targetDistanceMeters: 3_000,
				targetDurationSeconds: null
			})
		).toBe(false);
	});

	it('reports the clamped reduction that persistence will actually apply', () => {
		const consequence = calculateConsequence({ ...timedBase, pain: true });

		expect(
			calculateConsequenceDecisionEffect({
				consequence,
				decision: 'reduce_next',
				target: { targetDistanceMeters: 0, targetDurationSeconds: 900 }
			})
		).toEqual({
			metric: 'duration',
			previousTarget: 900,
			adjustment: -300,
			newTarget: 600
		});
	});

	it('does not offer repeating the same prescription after pain', () => {
		const consequence = calculateConsequence({ ...timedBase, pain: true });

		expect(consequence.recommendedDecision).toBe('next_rest');
		expect(consequence.options).toContain('next_rest');
		expect(consequence.options).not.toContain('repeat_prescription');
	});

	it('calls a skip repeated only when an earlier run was also skipped', () => {
		const skip = {
			status: 'skipped' as const,
			choice: 'skip_continue' as const,
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_200,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 0
		};

		expect(calculateConsequence({ ...skip, recentShortenedWorkouts: 1 }).kind).toBe(
			'skip_continue'
		);
		expect(calculateConsequence({ ...skip, recentSkippedWorkouts: 1 }).kind).toBe('repeated_skip');
	});

	it('preserves distance-based reductions', () => {
		const consequence = calculateConsequence({
			status: 'done',
			choice: 'skip_continue',
			targetDistanceMeters: 5_000,
			completedDistanceMeters: 12_000,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		});

		expect(
			calculateConsequenceDecisionEffect({
				consequence,
				decision: 'reduce_next',
				target: { targetDistanceMeters: 6_000, targetDurationSeconds: null }
			})
		).toEqual({
			metric: 'distance',
			previousTarget: 6_000,
			adjustment: -3_500,
			newTarget: 2_500
		});
	});
});
