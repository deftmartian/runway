import { describe, expect, it } from 'vitest';
import {
	averagePaceFromPairedResults,
	indexPlanHistoryEvidence,
	maxHistoryPlanWorkouts
} from './history';

describe('weekly history pace', () => {
	it('uses only duration and distance recorded on the same result', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: null, durationSeconds: 30 * 60 },
				{ distanceMeters: 1_000, durationSeconds: 5 * 60 }
			])
		).toBe(5 * 60);
	});

	it('ignores distance-only, duration-only, zero, and invalid results', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: 5_000, durationSeconds: null },
				{ distanceMeters: null, durationSeconds: 1_800 },
				{ distanceMeters: 0, durationSeconds: 300 },
				{ distanceMeters: 1_000, durationSeconds: -1 }
			])
		).toBeNull();
	});

	it('weights valid results by their paired distance and duration totals', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: 1_000, durationSeconds: 300 },
				{ distanceMeters: 2_000, durationSeconds: 720 }
			])
		).toBe(340);
	});
});

describe('dense plan history evidence', () => {
	it('retains feedback and linked activities for every workout in the largest valid plan', () => {
		const feedbackRows = Array.from({ length: maxHistoryPlanWorkouts }, (_, index) => ({
			workoutId: `workout-${index}`,
			completedDistanceMeters: 1_000 + index
		}));
		const linkedActivityRows = Array.from({ length: maxHistoryPlanWorkouts }, (_, index) => ({
			workoutId: `workout-${index}`
		}));

		const { latestFeedbackByWorkout, activityWorkoutIds } = indexPlanHistoryEvidence(
			feedbackRows,
			linkedActivityRows
		);
		const finalWorkoutId = `workout-${maxHistoryPlanWorkouts - 1}`;

		expect(maxHistoryPlanWorkouts).toBe(728);
		expect(latestFeedbackByWorkout.size).toBe(maxHistoryPlanWorkouts);
		expect(activityWorkoutIds.size).toBe(maxHistoryPlanWorkouts);
		expect(latestFeedbackByWorkout.get(finalWorkoutId)?.completedDistanceMeters).toBe(1_727);
		expect(activityWorkoutIds.has(finalWorkoutId)).toBe(true);
	});

	it('keeps the newest feedback when defensive duplicate rows are supplied', () => {
		const { latestFeedbackByWorkout } = indexPlanHistoryEvidence(
			[
				{ workoutId: 'workout-1', result: 'newest' },
				{ workoutId: 'workout-1', result: 'older' }
			],
			[]
		);

		expect(latestFeedbackByWorkout.get('workout-1')?.result).toBe('newest');
	});
});
