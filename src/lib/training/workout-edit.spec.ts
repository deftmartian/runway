import { describe, expect, it } from 'vitest';
import {
	previewWorkoutEdit,
	proposalFromWorkout,
	type EditableWorkoutState,
	type WorkoutEditWeek
} from './workout-edit';

const weeks: WorkoutEditWeek[] = [
	{ id: 'week-1', weekNumber: 1, startDate: '2026-07-20' },
	{ id: 'week-2', weekNumber: 2, startDate: '2026-07-27' }
];

const base: EditableWorkoutState = {
	id: 'workout-1',
	weekId: 'week-1',
	scheduledDate: '2026-07-21',
	type: 'easy',
	status: 'planned',
	prescriptionKind: 'distance',
	targetDistanceMeters: 3_000,
	targetDurationSeconds: null,
	intervalStructure: null,
	intensity: 'easy',
	purpose: 'Easy run',
	reason: 'Generated plan',
	sourceRefs: [],
	isRemoved: false
};

describe('workout edit previews', () => {
	it('shows source and destination week load when a workout moves', () => {
		const second = { ...base, id: 'workout-2', scheduledDate: '2026-07-24' };
		const proposed = {
			...proposalFromWorkout(base),
			weekId: 'week-2',
			scheduledDate: '2026-07-28'
		};
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed,
			workouts: [base, second],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.weekChanges).toEqual([
			expect.objectContaining({
				weekId: 'week-1',
				distanceBeforeMeters: 6_000,
				distanceAfterMeters: 3_000
			}),
			expect.objectContaining({
				weekId: 'week-2',
				distanceBeforeMeters: 0,
				distanceAfterMeters: 3_000
			})
		]);
		expect(Number.isFinite(preview.projectedRampPercent)).toBe(true);
	});

	it('reports recovery-spacing conflicts without blocking the proposal', () => {
		const adjacent = { ...base, id: 'workout-2', scheduledDate: '2026-07-22' };
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: { ...proposalFromWorkout(base), targetDistanceMeters: 3_200 },
			workouts: [base, adjacent],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.spacingConflicts).toHaveLength(1);
		expect(preview.requiresConfirmation).toBe(true);
	});

	it('rebalances only when explicitly requested and lists every affected workout', () => {
		const second = { ...base, id: 'workout-2', scheduledDate: '2026-07-24' };
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: { ...proposalFromWorkout(base), targetDistanceMeters: 4_000 },
			workouts: [base, second],
			weeks,
			today: '2026-07-16',
			rebalance: true
		});

		expect(preview.affectedFutureWorkoutIds).toEqual(['workout-2']);
		expect(preview.weekChanges[0]).toEqual(
			expect.objectContaining({ distanceBeforeMeters: 6_000, distanceAfterMeters: 6_000 })
		);
	});

	it('never creates infinite risk output from a zero baseline', () => {
		const timed: EditableWorkoutState = {
			...base,
			prescriptionKind: 'timed',
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_200,
			intervalStructure: {
				warmupSeconds: 0,
				cooldownSeconds: 480,
				blocks: [{ repetitions: 6, segments: [{ kind: 'run', durationSeconds: 120 }] }]
			}
		};
		const preview = previewWorkoutEdit({
			current: timed,
			recommended: proposalFromWorkout(timed),
			proposed: {
				...proposalFromWorkout(timed),
				targetDurationSeconds: 1_500,
				intervalStructure: {
					warmupSeconds: 0,
					cooldownSeconds: 780,
					blocks: [{ repetitions: 6, segments: [{ kind: 'run', durationSeconds: 120 }] }]
				}
			},
			workouts: [timed],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(Number.isFinite(preview.projectedRampPercent)).toBe(true);
		expect(Number.isNaN(preview.projectedRampPercent)).toBe(false);
	});

	it('previews a run-to-rest conversion without treating rest as load', () => {
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: {
				...proposalFromWorkout(base),
				type: 'rest',
				prescriptionKind: 'rest',
				targetDistanceMeters: 0,
				targetDurationSeconds: null,
				intervalStructure: null,
				purpose: 'Recovery day'
			},
			workouts: [base],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.weekChanges[0]).toEqual(
			expect.objectContaining({ distanceBeforeMeters: 3_000, distanceAfterMeters: 0 })
		);
		expect(preview.spacingConflicts).toEqual([]);
	});

	it('permits multiple workouts on a day but requires confirmation for the conflict', () => {
		const sameDay = { ...base, id: 'workout-2', purpose: 'Second easy run' };
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: { ...proposalFromWorkout(base), targetDistanceMeters: 6_000 },
			workouts: [base, sameDay],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.spacingConflicts).toEqual([
			expect.objectContaining({ workoutId: 'workout-2', scheduledDate: base.scheduledDate })
		]);
		expect(preview.risk).toBe('unsafe');
		expect(preview.requiresConfirmation).toBe(true);
	});
});
