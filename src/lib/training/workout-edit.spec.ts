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
		expect(preview.workoutChanges).toHaveLength(2);
		expect(preview.workoutChanges[0]).toMatchObject({
			workoutId: 'workout-1',
			isSelected: true,
			relativeChangePercent: 33.3,
			changeShareOfWeekPercent: 16.7,
			risk: 'aggressive'
		});
		expect(preview.workoutChanges[0]?.before.targetDistanceMeters).toBe(3_000);
		expect(preview.workoutChanges[0]?.after.targetDistanceMeters).toBe(4_000);
		expect(preview.workoutChanges[1]).toMatchObject({
			workoutId: 'workout-2',
			isSelected: false,
			relativeChangePercent: 33.3,
			changeShareOfWeekPercent: 16.7,
			risk: 'aggressive'
		});
		expect(preview.workoutChanges[1]?.before.targetDistanceMeters).toBe(3_000);
		expect(preview.workoutChanges[1]?.after.targetDistanceMeters).toBe(2_000);
		expect(preview.weekChanges[0]).toEqual(
			expect.objectContaining({ distanceBeforeMeters: 6_000, distanceAfterMeters: 6_000 })
		);
		expect(preview.weeklyLoadChangePercent).toBe(16.7);
		expect(preview.risk).toBe('aggressive');
		expect(preview.requiresConfirmation).toBe(true);
	});

	it('assesses an edit by its share of weekly load, not its relative workout change', () => {
		const otherRuns = [
			{ ...base, id: 'workout-2', scheduledDate: '2026-07-23', targetDistanceMeters: 9_000 },
			{ ...base, id: 'workout-3', scheduledDate: '2026-07-25', targetDistanceMeters: 9_000 },
			{ ...base, id: 'workout-4', scheduledDate: '2026-07-26', targetDistanceMeters: 9_000 }
		];
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: { ...proposalFromWorkout(base), targetDistanceMeters: 3_400 },
			workouts: [base, ...otherRuns],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.workoutChanges[0]).toMatchObject({
			relativeChangePercent: 13.3,
			changeShareOfWeekPercent: 1.3,
			risk: 'conservative'
		});
		expect(preview.weeklyLoadChangePercent).toBe(1.3);
		expect(preview.risk).toBe('conservative');
		expect(preview.requiresConfirmation).toBe(false);
	});

	it.each([
		[3_000, 'conservative'],
		[3_100, 'moderate'],
		[4_600, 'aggressive'],
		[7_600, 'unsafe']
	] as const)(
		'uses the documented weekly-load bands for a %s meter change',
		(changeMeters, expectedRisk) => {
			const other = { ...base, id: 'workout-2', targetDistanceMeters: 27_000 };
			const preview = previewWorkoutEdit({
				current: base,
				recommended: proposalFromWorkout(base),
				proposed: {
					...proposalFromWorkout(base),
					targetDistanceMeters: base.targetDistanceMeters + changeMeters
				},
				workouts: [base, other],
				weeks,
				today: '2026-07-16',
				rebalance: false
			});

			expect(preview.risk).toBe(expectedRisk);
		}
	);

	it('keeps projected plan ramp separate from the edit-share assessment', () => {
		const priorWeek = {
			...base,
			id: 'workout-prior',
			weekId: 'week-1',
			targetDistanceMeters: 10_000
		};
		const selected = { ...base, weekId: 'week-2', scheduledDate: '2026-07-28' };
		const other = {
			...base,
			id: 'workout-other',
			weekId: 'week-2',
			scheduledDate: '2026-07-30',
			targetDistanceMeters: 27_000
		};
		const preview = previewWorkoutEdit({
			current: selected,
			recommended: proposalFromWorkout(selected),
			proposed: { ...proposalFromWorkout(selected), targetDistanceMeters: 3_400 },
			workouts: [priorWeek, selected, other],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.projectedRampPercent).toBe(204);
		expect(preview.projectedRampRisk).toBe('unsafe');
		expect(preview.weeklyLoadChangePercent).toBe(1.3);
		expect(preview.risk).toBe('conservative');
	});

	it('uses injury-adjusted thresholds for the projected ramp', () => {
		const prior = { ...base, targetDistanceMeters: 10_000 };
		const selected = {
			...base,
			id: 'workout-week-2',
			weekId: 'week-2',
			scheduledDate: '2026-07-28',
			targetDistanceMeters: 10_000
		};
		const preview = previewWorkoutEdit({
			current: selected,
			recommended: proposalFromWorkout(selected),
			proposed: { ...proposalFromWorkout(selected), targetDistanceMeters: 10_700 },
			workouts: [prior, selected],
			weeks,
			today: '2026-07-16',
			rebalance: false,
			hasInjuryRisk: true
		});

		expect(preview.projectedRampPercent).toBe(7);
		expect(preview.projectedRampRisk).toBe('moderate');
	});

	it('assesses a small added workout as a share of existing weekly load', () => {
		const existing = {
			...base,
			id: 'workout-existing',
			scheduledDate: '2026-07-24',
			targetDistanceMeters: 30_000
		};
		const added = { ...base, id: 'new-workout', targetDistanceMeters: 100, isRemoved: true };
		const preview = previewWorkoutEdit({
			current: added,
			recommended: null,
			proposed: { ...proposalFromWorkout(added), isRemoved: false },
			workouts: [existing, added],
			weeks,
			today: '2026-07-16',
			rebalance: false,
			operation: 'add'
		});

		expect(preview.operation).toBe('add');
		expect(preview.workoutChanges[0]).toMatchObject({
			relativeChangePercent: null,
			changeShareOfWeekPercent: 0.3,
			risk: 'conservative'
		});
		expect(preview.risk).toBe('conservative');
		expect(preview.requiresConfirmation).toBe(false);
	});

	it('labels a distance-to-duration conversion as a separate comparison guardrail', () => {
		const timedProposal = {
			...proposalFromWorkout(base),
			prescriptionKind: 'timed' as const,
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_200,
			intervalStructure: {
				warmupSeconds: 0,
				cooldownSeconds: 480,
				blocks: [{ repetitions: 6, segments: [{ kind: 'run' as const, durationSeconds: 120 }] }]
			}
		};
		const preview = previewWorkoutEdit({
			current: base,
			recommended: proposalFromWorkout(base),
			proposed: timedProposal,
			workouts: [base],
			weeks,
			today: '2026-07-16',
			rebalance: false
		});

		expect(preview.guardrails).toEqual([
			expect.objectContaining({
				kind: 'prescription_basis_change',
				label: 'Prescription basis changed'
			})
		]);
		expect(preview.requiresConfirmation).toBe(true);
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
