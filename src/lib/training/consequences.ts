import type {
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
	const repeatedMiss = (input.recentMissedWorkouts ?? 0) > 0;
	const weeklyDistanceDeltaMeters =
		comparison.metric === 'distance' ? comparison.actualDifference : 0;

	if (input.pain) {
		return result({
			kind: 'pain_reported',
			...comparison,
			weeklyDistanceDeltaMeters,
			nextRunAdjustmentMeters: -Math.max(input.targetDistanceMeters, 1_000),
			risk: 'unsafe',
			recommendedDecision: 'next_rest'
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
			weeklyDistanceDeltaMeters,
			nextRunAdjustmentMeters:
				comparison.metric === 'distance'
					? -Math.max(1_000, Math.round(comparison.actualDifference * 0.5))
					: 0,
			risk,
			recommendedDecision: crossesGuardrail ? 'reduce_next' : 'keep_plan'
		});
	}

	if (comparison.deviation === 'short') {
		const largeShortfall =
			Math.abs(comparison.actualDifference) > comparison.target * 0.4 || comparison.actual === 0;
		return result({
			kind: repeatedMiss ? 'repeated_shortfall' : 'shortfall',
			...comparison,
			weeklyDistanceDeltaMeters,
			nextRunAdjustmentMeters:
				comparison.metric === 'distance'
					? -Math.max(
							500,
							Math.round(
								Math.abs(comparison.actualDifference) *
									(input.feltHard || repeatedMiss ? 0.35 : 0.25)
							)
						)
					: 0,
			risk: largeShortfall || input.feltHard || repeatedMiss ? 'moderate' : 'conservative',
			recommendedDecision: input.feltHard || repeatedMiss ? 'repeat_prescription' : 'keep_plan'
		});
	}

	if (comparison.deviation === 'skipped') {
		return result({
			kind: repeatedMiss
				? 'repeated_miss'
				: input.choice === 'reduce_next'
					? 'skip_reduce'
					: 'skip_continue',
			...comparison,
			weeklyDistanceDeltaMeters,
			nextRunAdjustmentMeters:
				comparison.metric === 'distance'
					? -Math.max(500, Math.round(input.targetDistanceMeters * (input.feltHard ? 0.3 : 0.2)))
					: 0,
			risk: input.feltHard || repeatedMiss ? 'moderate' : 'conservative',
			recommendedDecision: repeatedMiss || input.feltHard ? 'repeat_prescription' : 'keep_plan'
		});
	}

	if (input.feltHard) {
		return result({
			kind: 'hard_effort',
			...comparison,
			weeklyDistanceDeltaMeters,
			nextRunAdjustmentMeters:
				comparison.metric === 'distance'
					? -Math.max(Math.round(input.targetDistanceMeters * 0.15), 1_000)
					: 0,
			risk: 'moderate',
			recommendedDecision: 'reduce_next'
		});
	}

	return result({
		kind: 'completed_as_planned',
		...comparison,
		weeklyDistanceDeltaMeters,
		nextRunAdjustmentMeters: 0,
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
	input: Omit<ConsequenceResult, 'options' | 'appliedDecision'> & {
		target: number;
		actual: number;
	}
): ConsequenceResult {
	const consequence: Omit<ConsequenceResult, 'options' | 'appliedDecision'> = {
		kind: input.kind,
		deviation: input.deviation,
		metric: input.metric,
		actualDifference: input.actualDifference,
		weeklyDistanceDeltaMeters: input.weeklyDistanceDeltaMeters,
		nextRunAdjustmentMeters: input.nextRunAdjustmentMeters,
		risk: input.risk,
		recommendedDecision: input.recommendedDecision
	};
	return {
		...consequence,
		options:
			consequence.deviation === 'near_plan' && consequence.kind === 'completed_as_planned'
				? ['keep_plan']
				: [...allMaterialOptions],
		appliedDecision: null
	};
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
	if (
		input.recentMissedWorkouts !== undefined &&
		(!Number.isInteger(input.recentMissedWorkouts) || input.recentMissedWorkouts < 0)
	) {
		throw new Error('Recent missed-workout count is invalid.');
	}
}
