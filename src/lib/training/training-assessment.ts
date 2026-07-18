import type { RiskRating } from './types';

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
	| 'unsupported';

export type TrainingAssessmentAttention = 'none' | 'review' | 'high' | 'blocked';

export type TrainingAssessmentPresentation = {
	assessment: TrainingAssessment;
	label: string;
	description: string;
	attention: TrainingAssessmentAttention;
};

const assessmentByRisk: Record<RiskRating, TrainingAssessment> = {
	conservative: 'within_default',
	moderate: 'above_default',
	aggressive: 'high_increase',
	unsafe: 'unsupported'
};

const rampPresentation: Record<
	TrainingAssessment,
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
	TrainingAssessment,
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

export function trainingAssessmentFromRisk(risk: RiskRating): TrainingAssessment {
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

export function formatRampEvidence(
	requiredWeeklyIncreasePercent: number,
	defaultWeeklyIncreasePercent?: number
): string {
	const required = formatPercent(requiredWeeklyIncreasePercent);
	if (defaultWeeklyIncreasePercent === undefined) {
		return `Required weekly increase: ${required}.`;
	}
	return `Weekly distance would rise ${required}. runway's default for this plan is ${formatPercent(defaultWeeklyIncreasePercent)}.`;
}

function formatPercent(value: number): string {
	if (!Number.isFinite(value)) throw new Error('Ramp evidence must be a finite percentage.');
	return `${Math.round(value * 10) / 10}%`;
}
