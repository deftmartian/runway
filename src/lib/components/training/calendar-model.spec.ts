import { describe, expect, test } from 'vitest';
import type { TrainingCalendarPayload, TrainingCalendarWorkout } from '$lib/training/calendar-view';
import { buildTrainingCalendarModel } from './calendar-model';

const workout: TrainingCalendarWorkout = {
	id: 'workout-1',
	weekId: 'week-1',
	weekNumber: 1,
	scheduledDate: '2026-07-14',
	type: 'easy',
	status: 'planned',
	targetDistanceMeters: 3_000,
	targetDurationSeconds: null,
	prescriptionKind: 'distance',
	intervalStructure: null,
	intensity: 'easy',
	purpose: 'Easy run',
	reason: 'Build consistency.',
	sourceRefs: [],
	isRemoved: false,
	weekTargetDistanceMeters: 9_000,
	adjustment: null,
	recommended: null,
	isEdited: false
};

function payload(overrides: Partial<TrainingCalendarPayload> = {}): TrainingCalendarPayload {
	return {
		today: '2026-07-15',
		month: '2026-07',
		previousMonth: '2026-06',
		nextMonth: '2026-08',
		currentMonth: '2026-07',
		rangeStart: '2026-07-13',
		rangeEnd: '2026-07-19',
		weeks: [],
		workouts: [],
		activities: [],
		feedback: [],
		planScale: null,
		activityOverflow: { limit: 500, truncated: false },
		...overrides
	};
}

describe('training calendar view model', () => {
	test('keeps future open-day actions inside the active plan boundary', () => {
		const model = buildTrainingCalendarModel(payload(), {
			hasActivePlan: true,
			targetDate: '2026-07-17'
		});

		expect(model.days.find((day) => day.date === '2026-07-17')?.events[0]?.kind).toBe('open');
		expect(model.days.find((day) => day.date === '2026-07-18')?.events).toEqual([]);
		expect(model.rows).toHaveLength(1);
	});

	test('presents a linked activity once on the day it occurred', () => {
		const model = buildTrainingCalendarModel(
			payload({
				workouts: [workout],
				activities: [
					{
						id: 'activity-1',
						workoutId: workout.id,
						source: 'gpx',
						reviewState: 'accepted',
						occurredAt: new Date('2026-07-15T10:00:00Z'),
						occurredDate: '2026-07-15',
						distanceMeters: 3_100,
						durationSeconds: 1_800,
						averagePaceSecondsPerKm: null,
						averageHeartRate: null,
						maxHeartRate: null,
						heartRateSummary: null,
						hasHeartRateSeries: false,
						hasRouteTrace: false,
						averageCadence: null,
						feltHard: false,
						pain: false,
						extraPlanImpactConfirmed: false,
						consequence: null,
						routeSummary: { pointCount: 10, startEndRedacted: true, hasElevation: false },
						matchedWorkoutPurpose: workout.purpose,
						matchedWorkoutDate: workout.scheduledDate
					}
				]
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.events).toHaveLength(1);
		expect(model.events[0]).toMatchObject({
			id: `workout-${workout.id}`,
			date: '2026-07-15',
			kind: 'actual'
		});
	});
});
