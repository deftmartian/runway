import { describe, expect, it } from 'vitest';
import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
import type { ConsequenceResult } from '$lib/training/types';
import type { CalendarEvent } from './calendar-types';
import {
	actualDistance,
	formForEvent,
	formatDuration,
	planDecisionUnavailable,
	plannedPrescription,
	previewPlanDecision,
	timedWorkoutSteps
} from './event-detail-model';

function workout(overrides: Partial<TrainingCalendarWorkout> = {}): TrainingCalendarWorkout {
	return {
		id: 'workout-1',
		weekId: 'week-1',
		weekNumber: 1,
		scheduledDate: '2026-07-20',
		type: 'easy',
		status: 'planned',
		targetDistanceMeters: 0,
		targetDurationSeconds: 1_740,
		prescriptionKind: 'timed',
		intervalStructure: {
			warmupSeconds: 300,
			cooldownSeconds: 300,
			blocks: [
				{
					repetitions: 6,
					segments: [
						{ kind: 'run', durationSeconds: 120 },
						{ kind: 'walk', durationSeconds: 90 }
					]
				}
			]
		},
		intensity: 'easy',
		purpose: 'Run/walk',
		reason: '',
		sourceRefs: [],
		isRemoved: false,
		weekTargetDistanceMeters: 0,
		adjustment: null,
		recommended: null,
		isEdited: false,
		...overrides
	};
}

function event(selectedWorkout = workout()): CalendarEvent {
	return {
		id: 'event-1',
		date: selectedWorkout.scheduledDate,
		kind: 'planned',
		title: selectedWorkout.purpose,
		workout: selectedWorkout,
		activity: null,
		feedback: null,
		week: null,
		isRecordable: true,
		isToday: false,
		isFuture: true
	};
}

const timedConsequence: ConsequenceResult = {
	kind: 'hard_effort',
	deviation: 'near_plan',
	metric: 'duration',
	actualDifference: 0,
	weeklyLoadDelta: { metric: 'duration', value: 0 },
	nextRunAdjustment: { metric: 'duration', value: -300 },
	weeklyDistanceDeltaMeters: 0,
	nextRunAdjustmentMeters: 0,
	risk: 'moderate',
	recommendedDecision: 'reduce_next',
	options: ['keep_plan', 'reduce_next'],
	appliedDecision: null
};

describe('event detail presentation model', () => {
	it('keeps timed prescriptions and interval steps in duration units', () => {
		const selected = workout();
		expect(plannedPrescription(selected)).toBe('29 min');
		expect(timedWorkoutSteps(selected)).toEqual([
			'Warm up · walk 5 min',
			'6× run 2 min / walk 1 min 30 sec',
			'Cool down · walk 5 min'
		]);
		expect(formatDuration(3_900)).toBe('1h 05m');
	});

	it('does not invent a distance for time-only feedback', () => {
		const selectedEvent = {
			...event(),
			feedback: {
				completedDistanceMeters: null,
				completedDurationSeconds: 1_500
			}
		} as CalendarEvent;
		expect(actualDistance(selectedEvent)).toBe('Recorded');
	});

	it('only exposes an enhanced action result to its owning event', () => {
		const selectedEvent = event();
		const matching = {
			message: 'Saved',
			scope: { action: 'recordFeedback', workoutId: 'workout-1' }
		} as const;
		const other = {
			message: 'Saved',
			scope: { action: 'recordFeedback', workoutId: 'workout-2' }
		} as const;
		expect(formForEvent(matching, selectedEvent, null)).toBe(matching);
		expect(formForEvent(other, selectedEvent, null)).toBeNull();
	});

	it('previews a timed next-run decision in duration units', () => {
		const selectedEvent = event();
		const decisionRecord = {
			source: 'feedback' as const,
			sourceId: 'feedback-1',
			consequence: timedConsequence
		};
		const futureWorkouts = [
			workout({ id: 'workout-2', scheduledDate: '2026-07-22', targetDurationSeconds: 1_800 })
		];

		expect(
			previewPlanDecision({
				decision: 'reduce_next',
				event: selectedEvent,
				futureWorkouts,
				decisionRecord
			})
		).toContain('changes from 30 min to 25 min');
		expect(
			planDecisionUnavailable({
				decision: 'rebalance_week',
				event: selectedEvent,
				futureWorkouts,
				decisionRecord
			})
		).toBe(false);
	});
});
