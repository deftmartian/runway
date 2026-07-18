import { describe, expect, it } from 'vitest';
import type { CalendarEvent } from './calendar-types';
import {
	canRecordUnplannedRun,
	isQuietCalendarDay,
	presentCalendarEvent,
	shouldCollapseEarlierCalendarWeek
} from './calendar-presentation';

function calendarEvent(overrides: Partial<CalendarEvent> = {}): CalendarEvent {
	return {
		id: 'event-1',
		date: '2026-07-14',
		kind: 'planned',
		title: 'Easy run',
		workout: null,
		activity: null,
		feedback: null,
		week: null,
		isRecordable: true,
		isToday: false,
		isFuture: false,
		...overrides
	};
}

describe('presentCalendarEvent', () => {
	it('keeps an explicit skip distinct from a missed workout', () => {
		const skipped = presentCalendarEvent(
			calendarEvent({
				workout: {
					id: 'workout-1',
					weekId: 'week-1',
					weekNumber: 1,
					scheduledDate: '2026-07-14',
					type: 'easy',
					status: 'skipped',
					targetDistanceMeters: 3_000,
					targetDurationSeconds: null,
					prescriptionKind: 'distance',
					intervalStructure: null,
					intensity: 'Easy',
					purpose: 'Easy run',
					reason: 'Scheduled training',
					sourceRefs: [],
					isRemoved: false,
					weekTargetDistanceMeters: 9_000,
					adjustment: null,
					recommended: null,
					isEdited: false
				}
			})
		);
		const missed = presentCalendarEvent(calendarEvent());

		expect(skipped.state).toBe('skipped');
		expect(missed.state).toBe('missed');
	});

	it('keeps pain and hard effort as flags on the completed state', () => {
		const presentation = presentCalendarEvent(
			calendarEvent({
				kind: 'actual',
				isRecordable: false,
				activity: {
					id: 'activity-1',
					workoutId: null,
					source: 'gpx',
					reviewState: 'accepted',
					occurredAt: new Date('2026-07-14T12:00:00Z'),
					occurredDate: '2026-07-14',
					distanceMeters: 4_000,
					durationSeconds: 1_800,
					averagePaceSecondsPerKm: 450,
					averageHeartRate: null,
					maxHeartRate: null,
					heartRateSummary: null,
					heartRateSeries: null,
					routeTrace: null,
					averageCadence: null,
					feltHard: true,
					pain: true,
					extraPlanImpactConfirmed: true,
					consequence: null,
					routeSummary: { pointCount: 10, startEndRedacted: true, hasElevation: false },
					matchedWorkoutPurpose: null,
					matchedWorkoutDate: null
				}
			})
		);

		expect(presentation.state).toBe('completed');
		expect(presentation.flags).toEqual(['imported', 'counted_extra', 'hard_effort', 'pain']);
	});

	it('labels open days by the available action', () => {
		expect(presentCalendarEvent(calendarEvent({ kind: 'open' })).compactLabel).toBe('Record');
		expect(presentCalendarEvent(calendarEvent({ kind: 'open', isFuture: true })).compactLabel).toBe(
			'Plan'
		);
	});

	it('allows another unplanned run on any nonfuture event date', () => {
		const today = '2026-07-14';
		expect(canRecordUnplannedRun(calendarEvent({ kind: 'planned' }), today)).toBe(true);
		expect(canRecordUnplannedRun(calendarEvent({ kind: 'actual' }), today)).toBe(true);
		expect(
			canRecordUnplannedRun(calendarEvent({ date: '2026-07-15', isFuture: true }), today)
		).toBe(false);
	});
});

describe('calendar week visibility', () => {
	const weekDays = (event?: CalendarEvent) =>
		Array.from({ length: 7 }, (_, index) => ({
			date: `2026-07-${String(6 + index).padStart(2, '0')}`,
			inSelectedMonth: true,
			events: event && index === 1 ? [event] : []
		}));

	it('collapses only quiet weeks before the current week in the current month', () => {
		expect(
			shouldCollapseEarlierCalendarWeek({
				selectedMonth: '2026-07',
				currentMonth: '2026-07',
				today: '2026-07-18',
				hasPlanSummary: false,
				days: weekDays()
			})
		).toBe(true);
	});

	it.each([
		['planned work', calendarEvent({ kind: 'planned' })],
		['recorded history', calendarEvent({ kind: 'actual', isRecordable: false })],
		['review work', calendarEvent({ kind: 'review' })]
	])('keeps a prior week visible when it contains %s', (_, event) => {
		expect(
			shouldCollapseEarlierCalendarWeek({
				selectedMonth: '2026-07',
				currentMonth: '2026-07',
				today: '2026-07-18',
				hasPlanSummary: false,
				days: weekDays(event)
			})
		).toBe(false);
	});

	it('keeps plan summaries and noncurrent month views expanded', () => {
		const input = {
			selectedMonth: '2026-07',
			currentMonth: '2026-07',
			today: '2026-07-18',
			hasPlanSummary: true,
			days: weekDays()
		};
		expect(shouldCollapseEarlierCalendarWeek(input)).toBe(false);
		expect(
			shouldCollapseEarlierCalendarWeek({
				...input,
				selectedMonth: '2026-06',
				hasPlanSummary: false
			})
		).toBe(false);
	});

	it('keeps the current week expanded even when it is quiet', () => {
		const days = weekDays().map((day, index) => ({
			...day,
			date: `2026-07-${String(13 + index).padStart(2, '0')}`
		}));
		expect(
			shouldCollapseEarlierCalendarWeek({
				selectedMonth: '2026-07',
				currentMonth: '2026-07',
				today: '2026-07-18',
				hasPlanSummary: false,
				days
			})
		).toBe(false);
	});

	it('treats open placeholders as quiet without hiding meaningful events', () => {
		expect(isQuietCalendarDay({ events: [] })).toBe(true);
		expect(
			isQuietCalendarDay({ events: [calendarEvent({ kind: 'open', isRecordable: false })] })
		).toBe(true);
		expect(isQuietCalendarDay({ events: [calendarEvent({ kind: 'actual' })] })).toBe(false);
		expect(
			isQuietCalendarDay({
				events: [calendarEvent({ kind: 'open', isRecordable: true })]
			})
		).toBe(false);
	});
});
