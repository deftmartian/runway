import type { ConsequenceMetricDelta, ConsequenceResult, PlanDecision } from './types';

export type ConsequencePresentation = {
	outcome: string;
	planChange: string;
	safety?: string;
};

export type ConsequenceFacts = {
	weekImpact: string;
	nextRunImpact: string;
};

export function formatDistanceChange(meters: number): string {
	const kilometers = Math.round((Math.abs(meters) / 1_000) * 10) / 10;
	return `${kilometers} km`;
}

export function formatDurationChange(seconds: number): string {
	const totalSeconds = Math.round(Math.abs(seconds));
	const minutes = Math.floor(totalSeconds / 60);
	const remainder = totalSeconds % 60;
	if (minutes === 0) return `${remainder} sec`;
	if (remainder === 0) return `${minutes} min`;
	return `${minutes} min ${remainder} sec`;
}

export function formatConsequenceDelta(delta: ConsequenceMetricDelta, signed = false): string {
	const amount =
		delta.metric === 'duration'
			? formatDurationChange(delta.value)
			: formatDistanceChange(delta.value);
	if (!signed || delta.value === 0) return amount;
	return `${delta.value > 0 ? '+' : '−'}${amount}`;
}

export function presentConsequenceFacts(result: ConsequenceResult): ConsequenceFacts {
	if (result.comparisonStatus === 'not_comparable') {
		return {
			weekImpact: 'Timed load comparison unavailable without a recorded duration',
			nextRunImpact:
				result.nextRunAdjustment === null
					? 'No calculated next-run reduction'
					: `Next run ${formatConsequenceDelta(result.nextRunAdjustment, true)}`
		};
	}
	const weekly = result.weeklyLoadDelta;
	const nextRun = result.nextRunAdjustment;
	return {
		weekImpact:
			weekly === null
				? 'No planned weekly-load comparison'
				: weekly.value === 0
					? `Week matched planned ${weekly.metric}`
					: `Week ${formatConsequenceDelta(weekly, true)}`,
		nextRunImpact:
			nextRun === null || nextRun.value === 0
				? 'No next-run change'
				: `Next run ${formatConsequenceDelta(nextRun, true)}`
	};
}

export function presentConsequence(result: ConsequenceResult): ConsequencePresentation {
	const exactDifference =
		result.metric === 'duration'
			? formatDurationChange(result.actualDifference)
			: result.metric === 'distance'
				? formatDistanceChange(result.actualDifference)
				: 'the recorded amount';
	const direction = result.actualDifference < 0 ? 'below' : 'above';
	const outcome = (() => {
		if (result.kind === 'pain_reported') return 'Pain was reported for this run.';
		if (result.kind === 'historical_link') return 'Activity linked as historical training.';
		if (result.kind === 'extra_activity' || result.deviation === 'unplanned') {
			if (result.comparisonStatus === 'not_comparable') {
				return `Unplanned run added ${formatDistanceChange(result.weeklyDistanceDeltaMeters)} to actual training; duration is needed to compare it with this timed week.`;
			}
			return `Unplanned run added ${exactDifference} to actual training load.`;
		}
		if (result.deviation === 'skipped') return 'Workout skipped.';
		if (result.deviation === 'short') return `Completed ${exactDifference} below plan.`;
		if (result.deviation === 'over') return `Completed ${exactDifference} above plan.`;
		if (result.deviation === 'near_plan') {
			return result.actualDifference === 0
				? 'Completed at the planned amount.'
				: `Completed ${exactDifference} ${direction} plan, within the material threshold.`;
		}
		return 'Activity recorded.';
	})();

	const planChange = result.appliedDecision
		? appliedDecisionLabel(result)
		: `No future plan change applied. Recommended: ${recommendedDecisionLabel(result)}.`;

	return {
		outcome,
		planChange,
		...(result.kind === 'pain_reported'
			? {
					safety:
						'Do not treat a plan adjustment as clearance to continue. Seek qualified guidance if pain is sharp, persists, worsens, or changes your gait.'
				}
			: {})
	};
}

export function formatConsequenceSummary(result: ConsequenceResult): string {
	const presentation = presentConsequence(result);
	return [presentation.outcome, presentation.planChange, presentation.safety]
		.filter((part): part is string => Boolean(part))
		.join(' ');
}

export function formatConsequenceAuditReason(result: ConsequenceResult): string {
	const presentation = presentConsequence(result);
	return `${presentation.outcome} ${presentation.planChange}`;
}

export function decisionLabel(decision: PlanDecision): string {
	switch (decision) {
		case 'keep_plan':
			return 'keep the remaining plan';
		case 'reduce_next':
			return 'reduce the next run';
		case 'next_rest':
			return 'make the next workout rest';
		case 'repeat_prescription':
			return 'repeat this prescription';
		case 'rebalance_week':
			return 'rebalance the remaining week';
	}
}

function appliedDecisionLabel(result: ConsequenceResult): string {
	switch (result.appliedDecision) {
		case 'keep_plan':
			return 'Remaining plan kept unchanged.';
		case 'reduce_next':
			return result.nextRunAdjustment && result.nextRunAdjustment.value < 0
				? `Next run reduced by ${formatConsequenceDelta(result.nextRunAdjustment)}.`
				: 'Next workout reduced.';
		case 'next_rest':
			return 'Next workout changed to rest.';
		case 'repeat_prescription':
			return 'This prescription will be repeated.';
		case 'rebalance_week':
			return 'Remaining week rebalanced.';
		case null:
			return 'No future plan change applied.';
	}
}

function recommendedDecisionLabel(result: ConsequenceResult): string {
	if (
		result.recommendedDecision === 'reduce_next' &&
		result.nextRunAdjustment &&
		result.nextRunAdjustment.value < 0
	) {
		return `reduce the next run by ${formatConsequenceDelta(result.nextRunAdjustment)}`;
	}
	return decisionLabel(result.recommendedDecision);
}
