import { describe, expect, it } from 'vitest';
import type { CalendarEvent } from './calendar-types';
import { presentCalendarEvent } from './calendar-presentation';

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
});
