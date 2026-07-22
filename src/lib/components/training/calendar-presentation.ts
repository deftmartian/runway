import type { CalendarDay, CalendarEvent } from './calendar-types';
import {
	presentConsequenceAssessment,
	presentLoadChangeAssessment,
	presentMixedPrescriptionAssessment,
	presentRampAssessment,
	type TrainingAssessmentPresentation
} from '$lib/training/training-assessment';
import type { TrainingCalendarWeek } from '$lib/training/calendar-view';
import type { ConsequenceResult, RiskRating } from '$lib/training/types';

export type CalendarPresentationState =
	| 'planned'
	| 'completed'
	| 'shortened'
	| 'skipped'
	| 'missed'
	| 'rest'
	| 'removed'
	| 'needs_review';

export type CalendarStateFlag =
	| 'imported'
	| 'linked'
	| 'counted_extra'
	| 'edited'
	| 'hard_effort'
	| 'pain';

export type CalendarEventPresentation = {
	state: CalendarPresentationState | null;
	label: string;
	compactLabel: string;
	flags: CalendarStateFlag[];
};

type CalendarWeekVisibilityInput = {
	selectedMonth: string;
	currentMonth: string;
	today: string;
	hasPlanSummary: boolean;
	days: Pick<CalendarDay, 'date' | 'inSelectedMonth' | 'events'>[];
};

export type CalendarTrainingAssessment = {
	heading: 'Ramp assessment' | 'Feedback review' | 'Load assessment';
	sourceLabel: 'Current plan' | 'Recent feedback' | 'Recent activity';
	presentation: TrainingAssessmentPresentation;
};

export type CalendarWeekAssessment = {
	presentation: TrainingAssessmentPresentation | null;
	evidence: string;
	phaseLabel: 'Taper' | 'Down week' | null;
};

const stateLabels: Record<CalendarPresentationState, { label: string; compactLabel: string }> = {
	planned: { label: 'Planned', compactLabel: 'Planned' },
	completed: { label: 'Completed', compactLabel: 'Done' },
	shortened: { label: 'Shortened', compactLabel: 'Short' },
	skipped: { label: 'Skipped', compactLabel: 'Skipped' },
	missed: { label: 'Missed', compactLabel: 'Missed' },
	rest: { label: 'Rest', compactLabel: 'Rest' },
	removed: { label: 'Removed', compactLabel: 'Removed' },
	needs_review: { label: 'Needs review', compactLabel: 'Review' }
};

export function presentCalendarEvent(event: CalendarEvent): CalendarEventPresentation {
	const flags: CalendarStateFlag[] = [];
	if (event.activity?.source === 'gpx') flags.push('imported');
	if (event.activity?.workoutId) flags.push('linked');
	if (event.activity?.extraPlanImpactConfirmed && !event.activity.workoutId) {
		flags.push('counted_extra');
	}
	if (event.workout?.isEdited) flags.push('edited');
	if (event.activity?.feltHard || event.feedback?.feltHard) flags.push('hard_effort');
	if (event.activity?.pain || event.feedback?.pain) flags.push('pain');

	let state: CalendarPresentationState | null;
	if (event.workout?.isRemoved) {
		state = 'removed';
	} else if (event.kind === 'rest') {
		state = 'rest';
	} else if (event.activity?.reviewState === 'review') {
		state = 'needs_review';
	} else if (event.workout?.status === 'done' || (event.activity && !event.workout)) {
		state = 'completed';
	} else if (event.workout?.status === 'shortened') {
		state = 'shortened';
	} else if (event.workout?.status === 'skipped') {
		state = 'skipped';
	} else if (event.kind === 'open') {
		return {
			state: null,
			label: 'Open day',
			compactLabel: event.isFuture ? 'Plan' : 'Record',
			flags
		};
	} else if (event.isRecordable && !event.isToday) {
		state = 'missed';
	} else {
		state = 'planned';
	}

	return { state, ...stateLabels[state], flags };
}

export function presentCalendarTrainingAssessment(
	risk: RiskRating,
	source: 'plan' | 'feedback' | 'activity' = 'plan',
	consequence: ConsequenceResult | null = null,
	planHasMixedLoad = false
): CalendarTrainingAssessment {
	if (source === 'feedback') {
		return {
			heading: 'Feedback review',
			sourceLabel: 'Recent feedback',
			presentation: consequence
				? presentConsequenceAssessment(consequence)
				: presentLoadChangeAssessment(risk)
		};
	}
	if (source === 'activity') {
		return {
			heading: 'Load assessment',
			sourceLabel: 'Recent activity',
			presentation: consequence
				? presentConsequenceAssessment(consequence)
				: presentLoadChangeAssessment(risk)
		};
	}
	return {
		heading: 'Ramp assessment',
		sourceLabel: 'Current plan',
		presentation: planHasMixedLoad
			? presentMixedPrescriptionAssessment()
			: presentRampAssessment(risk)
	};
}

export function presentCalendarWeekAssessment(input: {
	week: TrainingCalendarWeek;
	previousWeek: TrainingCalendarWeek | null;
	baselineMeters: number | null;
	defaultWeeklyIncreasePercent: number | null;
}): CalendarWeekAssessment {
	const phaseLabel = input.week.isTaper ? 'Taper' : input.week.isDownWeek ? 'Down week' : null;
	if (input.week.hasMixedLoad) {
		return {
			presentation: null,
			evidence: 'Mixed distance and timed prescriptions · review each prescription separately',
			phaseLabel
		};
	}
	const usesDuration =
		input.week.targetDurationSeconds > 0 && input.week.targetDistanceMeters === 0;
	const currentWeeklyLoad = usesDuration
		? input.week.targetDurationSeconds
		: input.week.targetDistanceMeters;
	const previousWeeklyLoad = input.previousWeek
		? usesDuration
			? input.previousWeek.targetDurationSeconds
			: input.previousWeek.targetDistanceMeters
		: usesDuration
			? 0
			: input.week.weekNumber === 1
				? (input.baselineMeters ?? 0)
				: 0;

	if (previousWeeklyLoad <= 0 || input.defaultWeeklyIncreasePercent === null) {
		return {
			presentation: null,
			evidence: previousWeeklyLoad <= 0 ? 'Opening week' : 'No ramp cap for this timed phase',
			phaseLabel
		};
	}

	const weeklyChangePercent = roundOneDecimal(
		((currentWeeklyLoad - previousWeeklyLoad) / previousWeeklyLoad) * 100
	);
	const evidence =
		weeklyChangePercent < 0
			? `${Math.abs(weeklyChangePercent)}% weekly reduction · ${input.defaultWeeklyIncreasePercent}% plan cap`
			: `${weeklyChangePercent}% weekly increase · ${input.defaultWeeklyIncreasePercent}% plan cap`;

	return {
		presentation: presentRampAssessment(input.week.risk),
		evidence,
		phaseLabel
	};
}

function roundOneDecimal(value: number): number {
	return Math.round(value * 10) / 10;
}

export function canRecordUnplannedRun(event: CalendarEvent, today: string): boolean {
	return event.date <= today;
}

export function isQuietCalendarDay(day: Pick<CalendarDay, 'events'>): boolean {
	return (
		day.events.length === 0 ||
		day.events.every(
			(event) =>
				event.kind === 'open' &&
				!event.workout &&
				!event.activity &&
				!event.feedback &&
				!event.isRecordable
		)
	);
}

export function shouldCollapseEarlierCalendarWeek({
	selectedMonth,
	currentMonth,
	today,
	hasPlanSummary,
	days
}: CalendarWeekVisibilityInput): boolean {
	if (selectedMonth !== currentMonth || hasPlanSummary) return false;
	if (!days.some((day) => day.inSelectedMonth)) return false;

	const todayIndex = new Date(`${today}T00:00:00.000Z`).getUTCDay();
	const daysSinceMonday = (todayIndex + 6) % 7;
	const currentWeekStart = new Date(
		Date.parse(`${today}T00:00:00.000Z`) - daysSinceMonday * 24 * 60 * 60 * 1000
	)
		.toISOString()
		.slice(0, 10);

	return days.every((day) => day.date < currentWeekStart) && days.every(isQuietCalendarDay);
}

export function calendarFlagLabel(flag: CalendarStateFlag): string {
	switch (flag) {
		case 'imported':
			return 'Imported';
		case 'linked':
			return 'Linked';
		case 'counted_extra':
			return 'Counted as extra';
		case 'edited':
			return 'Edited';
		case 'hard_effort':
			return 'Hard effort';
		case 'pain':
			return 'Pain';
	}
}
