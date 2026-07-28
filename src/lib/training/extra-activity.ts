import type { ConsequenceResult } from './types';
import { addDays } from './date';

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

/** The automatic-adjustment window is inclusive at seven days. */
export function isHistoricalExtraActivity(activityDate: string, today: string): boolean {
	return activityDate < addDays(today, -7);
}

/**
 * Older accepted activity remains visible against the active plan, but it
 * cannot propose a retroactive automatic change to today's schedule.
 */
export function historicalExtraActivityReview(consequence: ConsequenceResult): ConsequenceResult {
	return {
		...consequence,
		nextRunAdjustment: null,
		nextRunAdjustmentMeters: 0,
		planChangeAvailable: false,
		options: []
	};
}

/**
 * Evaluates unplanned work in the prescription's native unit. Timed phases
 * never invent a distance ratio from their intentionally zero-distance plan.
 */
export function calculateExtraActivityConsequence(
	input: ExtraActivityInput,
	targets: ExtraActivityTargets
): ConsequenceResult {
	const timedPlan = (targets.nextRunTargetDurationSeconds ?? 0) > 0;
	const nativeMeasurementMissing = timedPlan
		? (input.durationSeconds ?? 0) <= 0
		: input.distanceMeters <= 0;
	if (nativeMeasurementMissing) {
		const nextTarget = timedPlan
			? (targets.nextRunTargetDurationSeconds ?? 0)
			: targets.nextRunTargetDistanceMeters;
		const adjustmentMetric = timedPlan ? ('duration' as const) : ('distance' as const);
		const minimumAdjustment = timedPlan ? 300 : 1_000;
		const nextRunAdjustment = input.pain
			? null
			: input.feltHard
				? {
						metric: adjustmentMetric,
						value: -Math.max(minimumAdjustment, Math.round(nextTarget * 0.15))
					}
				: null;
		return {
			kind: input.pain ? 'pain_reported' : 'extra_activity',
			comparisonStatus: 'not_comparable',
			deviation: 'unplanned',
			metric: 'none',
			actualDifference: 0,
			weeklyLoadDelta: null,
			nextRunAdjustment,
			weeklyDistanceDeltaMeters: input.distanceMeters,
			nextRunAdjustmentMeters:
				nextRunAdjustment?.metric === 'distance' ? nextRunAdjustment.value : 0,
			risk: input.pain ? 'unsafe' : 'moderate',
			recommendedDecision: input.pain ? 'next_rest' : input.feltHard ? 'reduce_next' : 'keep_plan',
			options: input.pain
				? ['keep_plan', 'next_rest']
				: nextRunAdjustment
					? ['keep_plan', 'reduce_next', 'next_rest']
					: ['keep_plan', 'next_rest'],
			appliedDecision: null
		};
	}
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
	const nextRunAdjustmentSeconds = usesDuration
		? -Math.min(
				targets.nextRunTargetDurationSeconds ?? 0,
				Math.max(300, Math.round(actualDifference * (input.feltHard ? 0.6 : 0.5)))
			)
		: 0;

	if (input.pain) {
		return {
			kind: 'pain_reported',
			deviation: 'unplanned',
			metric,
			actualDifference,
			weeklyLoadDelta: { metric, value: actualDifference },
			nextRunAdjustment: {
				metric,
				value: usesDuration
					? -Math.max(
							300,
							targets.nextRunTargetDurationSeconds ?? 0,
							Math.round(actualDifference * 0.5)
						)
					: -Math.max(
							1_000,
							targets.nextRunTargetDistanceMeters,
							Math.round(input.distanceMeters * 0.5)
						)
			},
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
		weeklyLoadDelta: { metric, value: actualDifference },
		nextRunAdjustment: {
			metric,
			value: usesDuration ? nextRunAdjustmentSeconds : nextRunAdjustmentMeters
		},
		weeklyDistanceDeltaMeters: input.distanceMeters,
		nextRunAdjustmentMeters,
		risk,
		recommendedDecision: increaseShare > 0.1 || input.feltHard ? 'reduce_next' : 'keep_plan',
		options: ['keep_plan', 'reduce_next', 'next_rest', 'rebalance_week'],
		appliedDecision: null
	};
}
