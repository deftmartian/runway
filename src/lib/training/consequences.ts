import type {
	ConsequenceMetric,
	ConsequenceMetricDelta,
	ConsequenceResult,
	DeviationClassification,
	PlanDecision,
	RiskRating,
	WorkoutFeedbackInput
} from './types';

const allMaterialOptions: PlanDecision[] = [
	'keep_plan',
	'reduce_next',
	'next_rest',
	'repeat_prescription',
	'rebalance_week'
];

export function calculateConsequence(input: WorkoutFeedbackInput): ConsequenceResult {
	assertFeedbackInvariants(input);
	const comparison = comparePrescription(input);
	const repeatedSkip = (input.recentSkippedWorkouts ?? 0) > 0;
	const repeatedShortfall =
		(input.recentSkippedWorkouts ?? 0) + (input.recentShortenedWorkouts ?? 0) > 0;
	const weeklyLoadDelta = metricDelta(comparison.metric, comparison.actualDifference);

	if (input.pain) {
		return result({
			kind: 'pain_reported',
			...comparison,
			weeklyLoadDelta,
			nextRunAdjustment: metricDelta(
				comparison.metric,
				comparison.metric === 'duration'
					? -Math.max(input.targetDurationSeconds ?? 0, 300)
					: -Math.max(input.targetDistanceMeters, 1_000)
			),
			risk: 'unsafe',
			recommendedDecision: 'next_rest',
			options: ['keep_plan', 'reduce_next', 'next_rest', 'rebalance_week']
		});
	}

	if (comparison.deviation === 'over') {
		const guardrailBase = Math.max(input.weekTargetDistanceMeters, input.targetDistanceMeters, 1);
		const crossesGuardrail =
			comparison.metric === 'distance'
				? comparison.actualDifference / guardrailBase > 0.1
				: comparison.actualDifference / Math.max(comparison.target, 1) > 0.1;
		const severeSpike = comparison.actual >= comparison.target * 2;
		const risk: RiskRating =
			input.feltHard && crossesGuardrail && severeSpike
				? 'unsafe'
				: crossesGuardrail
					? 'aggressive'
					: 'moderate';
		return result({
			kind: 'load_spike',
			...comparison,
			weeklyLoadDelta,
			nextRunAdjustment: metricDelta(
				comparison.metric,
				comparison.metric === 'duration'
					? -Math.max(300, Math.round(comparison.actualDifference * 0.5))
					: -Math.max(1_000, Math.round(comparison.actualDifference * 0.5))
			),
			risk,
			recommendedDecision: crossesGuardrail ? 'reduce_next' : 'keep_plan'
		});
	}

	if (comparison.deviation === 'short') {
		const largeShortfall =
			Math.abs(comparison.actualDifference) > comparison.target * 0.4 || comparison.actual === 0;
		return result({
			kind: repeatedShortfall ? 'repeated_shortfall' : 'shortfall',
			...comparison,
			weeklyLoadDelta,
			nextRunAdjustment: metricDelta(
				comparison.metric,
				-Math.max(
					comparison.metric === 'duration' ? 300 : 500,
					Math.round(
						Math.abs(comparison.actualDifference) *
							(input.feltHard || repeatedShortfall ? 0.35 : 0.25)
					)
				)
			),
			risk: largeShortfall || input.feltHard || repeatedShortfall ? 'moderate' : 'conservative',
			recommendedDecision: input.feltHard || repeatedShortfall ? 'repeat_prescription' : 'keep_plan'
		});
	}

	if (comparison.deviation === 'skipped') {
		return result({
			kind: repeatedSkip
				? 'repeated_skip'
				: input.choice === 'reduce_next'
					? 'skip_reduce'
					: 'skip_continue',
			...comparison,
			weeklyLoadDelta,
			nextRunAdjustment: metricDelta(
				comparison.metric,
				-Math.max(
					comparison.metric === 'duration' ? 300 : 500,
					Math.round(comparison.target * (input.feltHard ? 0.3 : 0.2))
				)
			),
			risk: input.feltHard || repeatedSkip ? 'moderate' : 'conservative',
			recommendedDecision: repeatedSkip || input.feltHard ? 'repeat_prescription' : 'keep_plan'
		});
	}

	if (input.feltHard) {
		return result({
			kind: 'hard_effort',
			...comparison,
			weeklyLoadDelta,
			nextRunAdjustment: metricDelta(
				comparison.metric,
				-Math.max(
					Math.round(comparison.target * 0.15),
					comparison.metric === 'duration' ? 300 : 1_000
				)
			),
			risk: 'moderate',
			recommendedDecision: 'reduce_next'
		});
	}

	return result({
		kind: 'completed_as_planned',
		...comparison,
		weeklyLoadDelta,
		nextRunAdjustment: metricDelta(comparison.metric, 0),
		risk: 'conservative',
		recommendedDecision: 'keep_plan'
	});
}

export function withAppliedDecision(
	consequence: ConsequenceResult,
	decision: PlanDecision
): ConsequenceResult {
	if (!consequence.options.includes(decision))
		throw new Error('Decision is not available for this result.');
	return { ...consequence, appliedDecision: decision };
}

export type ConsequenceDecisionTarget = {
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
};

export type ConsequenceDecisionEffect = {
	metric: ConsequenceMetric;
	previousTarget: number;
	adjustment: number;
	newTarget: number;
};

export function isConsequenceDecisionTargetCompatible(
	consequence: ConsequenceResult,
	target: ConsequenceDecisionTarget
): boolean {
	const metric: ConsequenceMetric =
		(target.targetDurationSeconds ?? 0) > 0 ? 'duration' : 'distance';
	return consequence.nextRunAdjustment?.metric === metric;
}

/**
 * Calculates the exact target change used by both decision previews and persistence.
 * A cross-metric candidate retains the existing conservative 15% fallback.
 */
export function calculateConsequenceDecisionEffect(input: {
	consequence: ConsequenceResult;
	decision: PlanDecision;
	target: ConsequenceDecisionTarget;
	shareCount?: number;
}): ConsequenceDecisionEffect | null {
	if (input.decision !== 'reduce_next' && input.decision !== 'rebalance_week') return null;
	const metric: ConsequenceMetric =
		(input.target.targetDurationSeconds ?? 0) > 0 ? 'duration' : 'distance';
	const previousTarget =
		metric === 'duration'
			? (input.target.targetDurationSeconds ?? 0)
			: input.target.targetDistanceMeters;
	if (previousTarget <= 0) return null;

	const matchingAdjustment =
		input.consequence.nextRunAdjustment?.metric === metric
			? Math.abs(input.consequence.nextRunAdjustment.value)
			: 0;
	const fallbackAdjustment =
		metric === 'duration' ? Math.max(300, Math.round(previousTarget * 0.15)) : 500;
	const totalReduction = matchingAdjustment || fallbackAdjustment;
	const shareCount = Math.max(1, Math.floor(input.shareCount ?? 1));
	const reduction =
		input.decision === 'rebalance_week' ? Math.ceil(totalReduction / shareCount) : totalReduction;
	const minimumTarget = metric === 'duration' ? 600 : 500;
	const newTarget = Math.max(minimumTarget, previousTarget - reduction);

	return {
		metric,
		previousTarget,
		adjustment: newTarget - previousTarget,
		newTarget
	};
}

function comparePrescription(input: WorkoutFeedbackInput): {
	deviation: DeviationClassification;
	metric: 'distance' | 'duration' | 'none';
	actualDifference: number;
	target: number;
	actual: number;
} {
	if (input.status === 'skipped') {
		const metric = (input.targetDurationSeconds ?? 0) > 0 ? 'duration' : 'distance';
		const target =
			metric === 'duration' ? (input.targetDurationSeconds ?? 0) : input.targetDistanceMeters;
		return { deviation: 'skipped', metric, actualDifference: -target, target, actual: 0 };
	}

	if ((input.targetDurationSeconds ?? 0) > 0) {
		const target = input.targetDurationSeconds ?? 0;
		const actual = input.completedDurationSeconds ?? 0;
		const actualDifference = actual - target;
		return {
			deviation: classifyDifference(actualDifference, Math.max(300, target * 0.15)),
			metric: 'duration',
			actualDifference,
			target,
			actual
		};
	}

	if (input.targetDistanceMeters > 0) {
		const target = input.targetDistanceMeters;
		const actual = input.completedDistanceMeters ?? 0;
		const actualDifference = actual - target;
		return {
			deviation: classifyDifference(actualDifference, Math.max(500, target * 0.15)),
			metric: 'distance',
			actualDifference,
			target,
			actual
		};
	}

	return {
		deviation: 'not_applicable',
		metric: 'none',
		actualDifference: 0,
		target: 0,
		actual: 0
	};
}

function classifyDifference(
	difference: number,
	materialThreshold: number
): Extract<DeviationClassification, 'near_plan' | 'short' | 'over'> {
	if (difference < -materialThreshold) return 'short';
	if (difference > materialThreshold) return 'over';
	return 'near_plan';
}

function result(
	input: Omit<
		ConsequenceResult,
		'options' | 'appliedDecision' | 'weeklyDistanceDeltaMeters' | 'nextRunAdjustmentMeters'
	> & {
		target: number;
		actual: number;
		options?: PlanDecision[];
	}
): ConsequenceResult {
	const consequence: Omit<ConsequenceResult, 'options' | 'appliedDecision'> = {
		kind: input.kind,
		deviation: input.deviation,
		metric: input.metric,
		actualDifference: input.actualDifference,
		weeklyLoadDelta: input.weeklyLoadDelta,
		nextRunAdjustment: input.nextRunAdjustment,
		weeklyDistanceDeltaMeters:
			input.weeklyLoadDelta?.metric === 'distance' ? input.weeklyLoadDelta.value : 0,
		nextRunAdjustmentMeters:
			input.nextRunAdjustment?.metric === 'distance' ? input.nextRunAdjustment.value : 0,
		risk: input.risk,
		recommendedDecision: input.recommendedDecision
	};
	return {
		...consequence,
		options:
			input.options ??
			(consequence.deviation === 'near_plan' && consequence.kind === 'completed_as_planned'
				? ['keep_plan']
				: [...allMaterialOptions]),
		appliedDecision: null
	};
}

function metricDelta(
	metric: ConsequenceResult['metric'],
	value: number
): ConsequenceMetricDelta | null {
	return metric === 'none' ? null : { metric, value };
}

function assertFeedbackInvariants(input: WorkoutFeedbackInput): void {
	const completedDistance = input.completedDistanceMeters;
	const completedDuration = input.completedDurationSeconds;
	if (!Number.isFinite(input.targetDistanceMeters) || input.targetDistanceMeters < 0) {
		throw new Error('Workout target distance is invalid.');
	}
	if (
		input.targetDurationSeconds !== undefined &&
		(!Number.isFinite(input.targetDurationSeconds) || input.targetDurationSeconds <= 0)
	) {
		throw new Error('Workout target duration is invalid.');
	}
	if (!Number.isFinite(input.weekTargetDistanceMeters) || input.weekTargetDistanceMeters < 0) {
		throw new Error('Weekly target distance is invalid.');
	}
	if (
		completedDistance !== undefined &&
		(!Number.isFinite(completedDistance) || completedDistance < 0)
	) {
		throw new Error('Completed distance is invalid.');
	}
	if (
		completedDuration !== undefined &&
		(!Number.isFinite(completedDuration) || completedDuration <= 0)
	) {
		throw new Error('Completed duration is invalid.');
	}
	if (
		input.status === 'skipped' &&
		((completedDistance !== undefined && completedDistance !== 0) ||
			completedDuration !== undefined)
	) {
		throw new Error('Skipped workouts cannot include completed work.');
	}
	if (input.status === 'shortened') {
		if ((input.targetDurationSeconds ?? 0) > 0) {
			if (
				completedDuration === undefined ||
				completedDuration >= (input.targetDurationSeconds ?? 0)
			) {
				throw new Error('Shortened workout duration must be below the planned duration.');
			}
		} else if (completedDistance === undefined || completedDistance >= input.targetDistanceMeters) {
			throw new Error('Shortened workout distance must be below the planned distance.');
		}
	}
	if (input.status === 'done') {
		if ((input.targetDurationSeconds ?? 0) > 0 && completedDuration === undefined) {
			throw new Error('Completed timed workouts need a recorded duration.');
		}
		if (
			(input.targetDurationSeconds ?? 0) === 0 &&
			input.targetDistanceMeters > 0 &&
			(completedDistance === undefined || completedDistance <= 0)
		) {
			throw new Error('Completed distance workouts need a positive recorded distance.');
		}
	}
	for (const count of [input.recentSkippedWorkouts, input.recentShortenedWorkouts]) {
		if (count !== undefined && (!Number.isInteger(count) || count < 0)) {
			throw new Error('Recent deviation count is invalid.');
		}
	}
}
