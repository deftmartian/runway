import type { ConsequenceResult, RiskRating } from './types';

/**
 * Public assessment terms for the planner's numeric load heuristics.
 *
 * `RiskRating` remains the persisted/internal ordering for now. Do not render it directly: its
 * legacy labels imply medical certainty that the underlying arithmetic does not provide.
 */
export type TrainingAssessment =
	| 'within_default'
	| 'above_default'
	| 'high_increase'
	| 'unsupported'
	| 'needs_review'
	| 'pain_review';

type NumericTrainingAssessment = Exclude<TrainingAssessment, 'needs_review' | 'pain_review'>;

export type TrainingAssessmentAttention = 'none' | 'review' | 'high' | 'blocked';

export type TrainingAssessmentPresentation = {
	assessment: TrainingAssessment;
	label: string;
	description: string;
	attention: TrainingAssessmentAttention;
};

const assessmentByRisk: Record<RiskRating, NumericTrainingAssessment> = {
	conservative: 'within_default',
	moderate: 'above_default',
	aggressive: 'high_increase',
	unsafe: 'unsupported'
};

const rampPresentation: Record<
	NumericTrainingAssessment,
	Omit<TrainingAssessmentPresentation, 'assessment'>
> = {
	within_default: {
		label: 'Within default',
		description: "The calculated increase stays within runway's default ramp.",
		attention: 'none'
	},
	above_default: {
		label: 'Above default',
		description: "The calculated increase is above runway's default ramp.",
		attention: 'review'
	},
	high_increase: {
		label: 'High increase',
		description: "The calculated increase is well above runway's default ramp.",
		attention: 'high'
	},
	unsupported: {
		label: 'Unsupported',
		description: "The calculated increase is outside runway's plan-generation limits.",
		attention: 'blocked'
	}
};

const changePresentation: Record<
	NumericTrainingAssessment,
	Omit<TrainingAssessmentPresentation, 'assessment'>
> = {
	within_default: {
		label: 'Within default',
		description: "This change stays within runway's default load-change range.",
		attention: 'none'
	},
	above_default: {
		label: 'Above default',
		description: "This change is above runway's default load-change range.",
		attention: 'review'
	},
	high_increase: {
		label: 'High change',
		description: "This change adds a high share of the week's planned load.",
		attention: 'high'
	},
	unsupported: {
		label: 'Outside default',
		description: "This change is outside runway's default range and needs explicit confirmation.",
		attention: 'high'
	}
};

export function trainingAssessmentFromRisk(risk: RiskRating): NumericTrainingAssessment {
	return assessmentByRisk[risk];
}

/** Use for generated plans and week-to-week ramp arithmetic. */
export function presentRampAssessment(risk: RiskRating): TrainingAssessmentPresentation {
	const assessment = trainingAssessmentFromRisk(risk);
	return { assessment, ...rampPresentation[assessment] };
}

/** Use for a runner-controlled workout edit or another accepted load change. */
export function presentLoadChangeAssessment(risk: RiskRating): TrainingAssessmentPresentation {
	const assessment = trainingAssessmentFromRisk(risk);
	return { assessment, ...changePresentation[assessment] };
}

export function presentConsequenceAssessment(
	consequence: ConsequenceResult
): TrainingAssessmentPresentation {
	if (consequence.kind === 'pain_reported') {
		return {
			assessment: 'pain_review',
			label: 'Pain review',
			description: 'Pain was reported, so health guidance stays separate from load arithmetic.',
			attention: 'blocked'
		};
	}
	if (consequence.comparisonStatus === 'not_comparable') {
		return {
			assessment: 'needs_review',
			label: 'Needs review',
			description: 'Duration was not recorded, so this run cannot be compared with the timed plan.',
			attention: 'review'
		};
	}
	return presentLoadChangeAssessment(consequence.risk);
}

export function formatRampEvidence(
	requiredWeeklyIncreasePercent: number,
	defaultWeeklyIncreasePercent?: number
): string {
	const required = formatPercent(requiredWeeklyIncreasePercent);
	if (defaultWeeklyIncreasePercent === undefined) {
		return `${required} required weekly increase`;
	}
	return `${required} required · ${formatPercent(defaultWeeklyIncreasePercent)} generated-week cap`;
}

export function formatLoadChangeEvidence(
	changeShareOfWeekPercent: number,
	risk: RiskRating
): string {
	const change = formatPercent(changeShareOfWeekPercent);
	if (risk === 'unsafe') {
		return `${change} of weekly load; outside-default boundary 25%.`;
	}
	if (risk === 'aggressive') {
		return `${change} of weekly load; high-change boundary 15%.`;
	}
	return `${change} of weekly load; default up to 10%.`;
}

function formatPercent(value: number): string {
	if (!Number.isFinite(value)) throw new Error('Ramp evidence must be a finite percentage.');
	return `${Math.round(value * 10) / 10}%`;
}
