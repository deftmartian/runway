import { describe, expect, it } from 'vitest';
import {
	CALENDAR_ACTIVITY_LIMIT,
	boundCalendarActivities,
	buildTrainingCalendarPayload,
	calendarMonthRange,
	parseCalendarMonth,
	shiftCalendarMonth,
	weekStartForIsoDate
} from './calendar';

describe('calendar month boundaries', () => {
	it('accepts a real requested month and falls back for malformed values', () => {
		expect(parseCalendarMonth('2026-08', '2026-07-18')).toBe('2026-08');
		expect(parseCalendarMonth('2026-13', '2026-07-18')).toBe('2026-07');
		expect(parseCalendarMonth('August', '2026-07-18')).toBe('2026-07');
		expect(parseCalendarMonth(null, '2026-07-18')).toBe('2026-07');
	});

	it('shifts across year boundaries', () => {
		expect(shiftCalendarMonth('2026-01', -1)).toBe('2025-12');
		expect(shiftCalendarMonth('2026-12', 1)).toBe('2027-01');
	});

	it('pads a month to complete Monday-through-Sunday weeks', () => {
		expect(calendarMonthRange('2026-08')).toEqual({
			rangeStart: '2026-07-27',
			rangeEnd: '2026-09-06'
		});
	});

	it('uses Monday as the week boundary, including Sundays', () => {
		expect(weekStartForIsoDate('2026-07-20')).toBe('2026-07-20');
		expect(weekStartForIsoDate('2026-07-26')).toBe('2026-07-20');
	});

	it('keeps the newest bounded activity rows and restores chronological presentation order', () => {
		const newestFirst = Array.from({ length: CALENDAR_ACTIVITY_LIMIT + 1 }, (_, index) => ({
			id: `activity-${CALENDAR_ACTIVITY_LIMIT - index}`
		}));
		const result = boundCalendarActivities(newestFirst);

		expect(result.activityOverflow).toEqual({
			limit: CALENDAR_ACTIVITY_LIMIT,
			truncated: true
		});
		expect(result.activities).toHaveLength(CALENDAR_ACTIVITY_LIMIT);
		expect(result.activities[0]?.id).toBe('activity-1');
		expect(result.activities.at(-1)?.id).toBe(`activity-${CALENDAR_ACTIVITY_LIMIT}`);
		expect(result.activities.some((activity) => activity.id === 'activity-0')).toBe(false);
	});

	it('reports a complete response when the density is within the cap', () => {
		const result = boundCalendarActivities([{ id: 'newer' }, { id: 'older' }]);
		expect(result).toEqual({
			activities: [{ id: 'older' }, { id: 'newer' }],
			activityOverflow: { limit: CALENDAR_ACTIVITY_LIMIT, truncated: false }
		});
	});

	it('keeps calendar activities summary-only', () => {
		const payload = buildTrainingCalendarPayload({
			today: '2026-07-18',
			month: '2026-07',
			previousMonth: '2026-06',
			nextMonth: '2026-08',
			currentMonth: '2026-07',
			rangeStart: '2026-06-29',
			rangeEnd: '2026-08-02',
			weeks: [
				{
					id: 'week-1',
					weekNumber: 1,
					startDate: '2026-07-13',
					targetDistanceMeters: 5_000,
					targetDurationSeconds: 0,
					eventDistanceMeters: 0,
					totalScheduledDistanceMeters: 5_000,
					longRunMeters: 0,
					risk: 'conservative',
					isDownWeek: false,
					isTaper: false
				}
			],
			workouts: [],
			feedback: [],
			activities: [
				{
					id: 'activity-1',
					workoutId: null,
					source: 'gpx',
					reviewState: 'accepted',
					occurredAt: new Date('2026-07-18T12:00:00Z'),
					occurredDate: '2026-07-18',
					distanceMeters: 5_000,
					durationSeconds: 1_800,
					averagePaceSecondsPerKm: 360,
					averageHeartRate: 140,
					maxHeartRate: 165,
					heartRateSummary: null,
					hasHeartRateSeries: true,
					hasRouteTrace: true,
					averageCadence: null,
					feltHard: true,
					pain: true,
					extraPlanImpactConfirmed: true,
					consequence: null,
					routeSummary: {
						pointCount: 500,
						startEndRedacted: true,
						hasElevation: false,
						traceRetained: true
					},
					matchedWorkoutPurpose: null,
					matchedWorkoutDate: null
				}
			],
			activityOverflow: { limit: CALENDAR_ACTIVITY_LIMIT, truncated: false }
		});

		expect(payload.activities[0]).toMatchObject({
			id: 'activity-1',
			hasHeartRateSeries: true,
			hasRouteTrace: true
		});
		expect(payload.activities[0]).not.toHaveProperty('heartRateSeries');
		expect(payload.activities[0]).not.toHaveProperty('routeTrace');
		expect(payload.weeks[0]).toMatchObject({ hardFlags: 1, painFlags: 1, completedRuns: 1 });
	});
});
