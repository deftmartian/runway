import type { ConsequenceResult, PlanDecision } from './types';

export type ConsequencePresentation = {
	outcome: string;
	planChange: string;
	safety?: string;
};

export function formatDistanceChange(meters: number): string {
	const kilometers = Math.round((Math.abs(meters) / 1_000) * 10) / 10;
	return `${kilometers} km`;
}

export function formatDurationChange(seconds: number): string {
	const minutes = Math.round(Math.abs(seconds) / 60);
	return `${minutes} min`;
}

export function presentConsequence(result: ConsequenceResult): ConsequencePresentation {
	const exactDifference =
		result.metric === 'duration'
			? formatDurationChange(result.actualDifference)
			: formatDistanceChange(result.actualDifference);
	const direction = result.actualDifference < 0 ? 'below' : 'above';
	const outcome = (() => {
		if (result.kind === 'pain_reported') return 'Pain was reported for this run.';
		if (result.kind === 'historical_link') return 'Activity linked as historical training.';
		if (result.kind === 'extra_activity' || result.deviation === 'unplanned') {
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
		: `No future plan change applied. Recommended: ${decisionLabel(result.recommendedDecision)}.`;

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
			return result.nextRunAdjustmentMeters < 0
				? `Next run reduced by ${formatDistanceChange(result.nextRunAdjustmentMeters)}.`
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
