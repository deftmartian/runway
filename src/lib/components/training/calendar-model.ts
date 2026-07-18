import type {
	TrainingCalendarPayload,
	TrainingCalendarWeek,
	TrainingCalendarWorkout
} from '$lib/training/calendar-view';
import { shouldCollapseEarlierCalendarWeek } from './calendar-presentation';
import type { CalendarDay, CalendarEvent } from './calendar-types';

export type CalendarWeekLoad = {
	id: string;
	label: string;
	week: TrainingCalendarWeek;
	rampValue: number;
	completionValue: number;
	isCurrent: boolean;
};

export type CalendarWeekRow = {
	id: string;
	label: string;
	load: CalendarWeekLoad | null;
	days: CalendarDay[];
	isQuietEarlier: boolean;
};

export function addIsoDays(date: string, days: number): string {
	const timestamp = Date.parse(`${date}T00:00:00.000Z`);
	return new Date(timestamp + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

export function buildTrainingCalendarModel(
	payload: TrainingCalendarPayload,
	options: { hasActivePlan: boolean; targetDate: string | null }
): { events: CalendarEvent[]; days: CalendarDay[]; rows: CalendarWeekRow[] } {
	const events = buildEvents(payload);
	const days = buildCalendarDays(payload, events, options);
	return { events, days, rows: buildCalendarRows(payload, days) };
}

function weekForDate(payload: TrainingCalendarPayload, date: string) {
	return (
		payload.weeks.find((week) => date >= week.startDate && date <= addIsoDays(week.startDate, 6)) ??
		null
	);
}

function weekForWorkout(payload: TrainingCalendarPayload, workout: TrainingCalendarWorkout) {
	return (
		payload.weeks.find((week) => week.id === workout.weekId) ??
		weekForDate(payload, workout.scheduledDate)
	);
}

function canRecordWorkout(payload: TrainingCalendarPayload, workout: TrainingCalendarWorkout) {
	return (
		workout.type !== 'rest' &&
		workout.prescriptionKind !== 'rest' &&
		!workout.isRemoved &&
		workout.status === 'planned' &&
		workout.scheduledDate <= payload.today
	);
}

function buildEvents(payload: TrainingCalendarPayload): CalendarEvent[] {
	const feedbackByWorkout = new Map(payload.feedback.map((record) => [record.workoutId, record]));
	const activitiesByWorkout = new Map<string, (typeof payload.activities)[number][]>();
	for (const record of payload.activities) {
		if (!record.workoutId) continue;
		const records = activitiesByWorkout.get(record.workoutId) ?? [];
		records.push(record);
		activitiesByWorkout.set(record.workoutId, records);
	}

	const workoutIds = new Set(payload.workouts.map((workout) => workout.id));
	const events: CalendarEvent[] = payload.workouts.map((workout) => {
		const activity = activitiesByWorkout.get(workout.id)?.[0] ?? null;
		const feedback = feedbackByWorkout.get(workout.id) ?? null;
		const date = activity?.occurredDate ?? workout.scheduledDate;
		return {
			id: `workout-${workout.id}`,
			date,
			kind:
				workout.status === 'skipped' && feedback
					? 'review'
					: activity || feedback
						? 'actual'
						: workout.type === 'rest'
							? 'rest'
							: 'planned',
			title: workout.type === 'rest' ? 'Rest' : workout.purpose,
			workout,
			activity,
			feedback,
			week: weekForWorkout(payload, workout),
			isRecordable: canRecordWorkout(payload, workout),
			isToday: date === payload.today,
			isFuture: date > payload.today
		};
	});

	for (const record of payload.activities) {
		if (record.workoutId && workoutIds.has(record.workoutId)) continue;
		events.push({
			id: `activity-${record.id}`,
			date: record.occurredDate,
			kind: 'actual',
			title: record.matchedWorkoutPurpose ?? 'Imported run',
			workout: null,
			activity: record,
			feedback: null,
			week: weekForDate(payload, record.occurredDate),
			isRecordable: false,
			isToday: record.occurredDate === payload.today,
			isFuture: record.occurredDate > payload.today
		});
	}

	return events.sort((left, right) => {
		if (left.date !== right.date) return left.date.localeCompare(right.date);
		return eventPriority(left) - eventPriority(right);
	});
}

function eventPriority(event: CalendarEvent): number {
	if (event.kind === 'actual') return 0;
	if (event.isRecordable) return 1;
	if (event.kind === 'planned') return 2;
	return 3;
}

function buildCalendarDays(
	payload: TrainingCalendarPayload,
	events: CalendarEvent[],
	options: { hasActivePlan: boolean; targetDate: string | null }
): CalendarDay[] {
	const eventMap = new Map<string, CalendarEvent[]>();
	for (const event of events) {
		const records = eventMap.get(event.date) ?? [];
		records.push(event);
		eventMap.set(event.date, records);
	}

	const days: CalendarDay[] = [];
	for (
		let currentDate = payload.rangeStart;
		currentDate <= payload.rangeEnd;
		currentDate = addIsoDays(currentDate, 1)
	) {
		const dayEvents = eventMap.get(currentDate) ?? [];
		days.push({
			date: currentDate,
			weekday: new Date(`${currentDate}T00:00:00`).toLocaleDateString(undefined, {
				weekday: 'short'
			}),
			dayNumber: new Date(`${currentDate}T00:00:00`).toLocaleDateString(undefined, {
				day: 'numeric'
			}),
			inSelectedMonth: currentDate.startsWith(`${payload.month}-`),
			isToday: currentDate === payload.today,
			events:
				dayEvents.length === 0 &&
				(currentDate <= payload.today ||
					(options.hasActivePlan &&
						currentDate >= payload.today &&
						(!options.targetDate || currentDate <= options.targetDate)))
					? [openDayEvent(payload, currentDate)]
					: dayEvents
		});
	}
	return days;
}

function openDayEvent(payload: TrainingCalendarPayload, date: string): CalendarEvent {
	return {
		id: `open-${date}`,
		date,
		kind: 'open',
		title: 'Open day',
		workout: null,
		activity: null,
		feedback: null,
		week: weekForDate(payload, date),
		isRecordable: false,
		isToday: date === payload.today,
		isFuture: date > payload.today
	};
}

function buildCalendarRows(
	payload: TrainingCalendarPayload,
	days: CalendarDay[]
): CalendarWeekRow[] {
	const loadByWeekStart = new Map(
		buildWeekLoads(payload).map((load) => [load.week.startDate, load])
	);
	const rows: CalendarWeekRow[] = [];
	for (let index = 0; index < days.length; index += 7) {
		const rowDays = days.slice(index, index + 7);
		const startDate = rowDays[0]?.date ?? payload.rangeStart;
		const load = loadByWeekStart.get(startDate) ?? null;
		rows.push({
			id: startDate,
			label: load?.label ?? `Week of ${startDate}`,
			load,
			days: rowDays,
			isQuietEarlier: shouldCollapseEarlierCalendarWeek({
				selectedMonth: payload.month,
				currentMonth: payload.currentMonth,
				today: payload.today,
				hasPlanSummary: Boolean(load),
				days: rowDays
			})
		});
	}
	return rows;
}

function buildWeekLoads(payload: TrainingCalendarPayload): CalendarWeekLoad[] {
	if (payload.weeks.length === 0 || !payload.planScale) return [];
	const usesDuration = payload.weeks.some(
		(week) => week.targetDurationSeconds > 0 && week.targetDistanceMeters === 0
	);
	const peak = Math.max(
		1,
		usesDuration
			? Math.max(...payload.weeks.map((week) => week.targetDurationSeconds))
			: payload.planScale.peakMeters
	);
	return payload.weeks.map((week) => ({
		id: week.id,
		label: `Week ${week.weekNumber}`,
		week,
		rampValue: Math.max(
			8,
			Math.min(
				100,
				Math.round(
					((usesDuration ? week.targetDurationSeconds : week.targetDistanceMeters) / peak) * 100
				)
			)
		),
		completionValue: Math.min(
			100,
			Math.round(
				((usesDuration ? week.completedDurationSeconds : week.completedDistanceMeters) / peak) * 100
			)
		),
		isCurrent: payload.today >= week.startDate && payload.today <= addIsoDays(week.startDate, 6)
	}));
}
