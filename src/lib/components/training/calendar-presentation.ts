import type { CalendarEvent } from './calendar-types';

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

export function canRecordUnplannedRun(event: CalendarEvent, today: string): boolean {
	return event.date <= today;
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
