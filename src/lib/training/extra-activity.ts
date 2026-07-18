import type { ConsequenceResult } from './types';

export type ExtraActivityInput = {
	distanceMeters: number;
	durationSeconds?: number | null;
	feltHard: boolean;
	pain: boolean;
};

export type ExtraActivityTargets = {
	nextRunTargetDistanceMeters: number;
	nextRunTargetDurationSeconds: number | null;
	weekTargetDistanceMeters: number;
	weekTargetDurationSeconds: number;
};

/**
 * Evaluates unplanned work in the prescription's native unit. Timed phases
 * never invent a distance ratio from their intentionally zero-distance plan.
 */
export function calculateExtraActivityConsequence(
	input: ExtraActivityInput,
	targets: ExtraActivityTargets
): ConsequenceResult {
	const usesDuration =
		(targets.nextRunTargetDurationSeconds ?? 0) > 0 && (input.durationSeconds ?? 0) > 0;
	const metric = usesDuration ? 'duration' : 'distance';
	const actualDifference = usesDuration ? (input.durationSeconds ?? 0) : input.distanceMeters;
	const increaseShare = usesDuration
		? actualDifference / Math.max(1, targets.weekTargetDurationSeconds)
		: targets.weekTargetDistanceMeters > 0
			? input.distanceMeters / targets.weekTargetDistanceMeters
			: 0;
	const nextRunAdjustmentMeters = usesDuration
		? 0
		: -Math.min(
				targets.nextRunTargetDistanceMeters,
				Math.max(1_000, Math.round(input.distanceMeters * (input.feltHard ? 0.6 : 0.5)))
			);

	if (input.pain) {
		return {
			kind: 'pain_reported',
			deviation: 'unplanned',
			metric,
			actualDifference,
			weeklyDistanceDeltaMeters: input.distanceMeters,
			nextRunAdjustmentMeters: usesDuration
				? 0
				: -Math.max(
						1_000,
						targets.nextRunTargetDistanceMeters,
						Math.round(input.distanceMeters * 0.5)
					),
			risk: 'unsafe',
			recommendedDecision: 'next_rest',
			options: ['keep_plan', 'reduce_next', 'next_rest', 'rebalance_week'],
			appliedDecision: null
		};
	}

	const risk: ConsequenceResult['risk'] =
		input.feltHard && increaseShare > 0.2
			? 'unsafe'
			: increaseShare > 0.1
				? 'aggressive'
				: input.feltHard
					? 'moderate'
					: 'conservative';
	return {
		kind: 'extra_activity',
		deviation: 'unplanned',
		metric,
		actualDifference,
		weeklyDistanceDeltaMeters: input.distanceMeters,
		nextRunAdjustmentMeters,
		risk,
		recommendedDecision: increaseShare > 0.1 || input.feltHard ? 'reduce_next' : 'keep_plan',
		options: ['keep_plan', 'reduce_next', 'next_rest', 'rebalance_week'],
		appliedDecision: null
	};
}
