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

const recommendedWorkout: NonNullable<TrainingCalendarWorkout['recommended']> = {
	scheduledDate: workout.scheduledDate,
	type: workout.type,
	prescriptionKind: workout.prescriptionKind,
	targetDistanceMeters: workout.targetDistanceMeters,
	targetDurationSeconds: workout.targetDurationSeconds,
	intervalStructure: workout.intervalStructure,
	purpose: workout.purpose
};

const week = {
	id: 'week-1',
	weekNumber: 1,
	startDate: '2026-07-13',
	generatedDistanceMeters: 9_000,
	generatedDurationSeconds: 0,
	targetDistanceMeters: 9_000,
	targetDurationSeconds: 0,
	hasMixedLoad: false,
	eventDistanceMeters: 0,
	totalScheduledDistanceMeters: 9_000,
	longRunMeters: 4_000,
	risk: 'conservative' as const,
	isDownWeek: false,
	isTaper: false,
	completedDistanceMeters: 3_100,
	completedDurationSeconds: 1_800,
	eventCompletedDistanceMeters: 0,
	completedRuns: 1,
	plannedRuns: 3,
	painFlags: 0,
	hardFlags: 0
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

	test('keeps generated, current, and actual weekly load distinct after an edit', () => {
		const model = buildTrainingCalendarModel(
			payload({
				weeks: [{ ...week, targetDistanceMeters: 7_500 }],
				workouts: [
					{
						...workout,
						targetDistanceMeters: 2_500,
						isEdited: true,
						recommended: recommendedWorkout
					}
				],
				planScale: { baselineMeters: 6_000, peakMeters: 12_000 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.rows[0]?.load).toMatchObject({
			metric: 'distance',
			generatedValue: 9_000,
			currentValue: 7_500,
			actualValue: 3_100,
			generatedPercent: 75,
			currentPercent: 63,
			actualPercent: 26,
			isEdited: true
		});
	});

	test('compares generated, current, and actual duration for a timed-only week', () => {
		const model = buildTrainingCalendarModel(
			payload({
				weeks: [
					{
						...week,
						generatedDistanceMeters: 0,
						generatedDurationSeconds: 1_200,
						targetDistanceMeters: 0,
						targetDurationSeconds: 900,
						totalScheduledDistanceMeters: 0,
						completedDistanceMeters: 0,
						completedDurationSeconds: 1_000
					}
				],
				workouts: [
					{
						...workout,
						targetDistanceMeters: 0,
						targetDurationSeconds: 900,
						prescriptionKind: 'timed',
						isEdited: true,
						recommended: {
							...recommendedWorkout,
							prescriptionKind: 'timed',
							targetDistanceMeters: 0,
							targetDurationSeconds: 1_200
						}
					}
				],
				planScale: { baselineMeters: 0, peakMeters: 0 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.rows[0]?.load).toMatchObject({
			metric: 'duration',
			generatedValue: 1_200,
			currentValue: 900,
			actualValue: 1_000,
			generatedPercent: 100,
			currentPercent: 75,
			actualPercent: 83,
			isEdited: true
		});
	});

	test('marks a runner-added workout even when the weekly aggregate balances', () => {
		const model = buildTrainingCalendarModel(
			payload({
				weeks: [week],
				workouts: [{ ...workout, id: 'manual-workout', recommended: null }],
				planScale: { baselineMeters: 6_000, peakMeters: 12_000 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.rows[0]?.load).toMatchObject({
			generatedValue: 9_000,
			currentValue: 9_000,
			isEdited: true
		});
	});

	test('marks a removed prescription even when the weekly aggregate balances', () => {
		const model = buildTrainingCalendarModel(
			payload({
				weeks: [week],
				workouts: [
					{
						...workout,
						id: 'removed-workout',
						isRemoved: true,
						recommended: recommendedWorkout
					}
				],
				planScale: { baselineMeters: 6_000, peakMeters: 12_000 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.rows[0]?.load).toMatchObject({
			generatedValue: 9_000,
			currentValue: 9_000,
			isEdited: true
		});
	});

	test('attributes a moved workout change marker to its destination week', () => {
		const model = buildTrainingCalendarModel(
			payload({
				rangeEnd: '2026-07-26',
				weeks: [
					week,
					{
						...week,
						id: 'week-2',
						weekNumber: 2,
						startDate: '2026-07-20'
					}
				],
				workouts: [
					{
						...workout,
						id: 'moved-workout',
						weekId: 'week-2',
						weekNumber: 2,
						scheduledDate: '2026-07-21',
						isEdited: true,
						recommended: recommendedWorkout
					}
				],
				planScale: { baselineMeters: 6_000, peakMeters: 12_000 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-26' }
		);

		expect(model.rows.map((row) => row.load?.isEdited)).toEqual([false, true]);
	});

	test('does not collapse mixed distance and timed prescriptions into a false scalar', () => {
		const model = buildTrainingCalendarModel(
			payload({
				weeks: [
					{
						...week,
						generatedDurationSeconds: 1_200,
						targetDurationSeconds: 1_200,
						hasMixedLoad: true
					}
				],
				planScale: { baselineMeters: 6_000, peakMeters: 12_000 }
			}),
			{ hasActivePlan: true, targetDate: '2026-07-19' }
		);

		expect(model.rows[0]?.load).toMatchObject({
			metric: 'mixed',
			generatedPercent: 0,
			currentPercent: 0,
			actualPercent: 0
		});
	});
});
