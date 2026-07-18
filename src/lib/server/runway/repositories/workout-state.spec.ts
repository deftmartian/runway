import { describe, expect, it } from 'vitest';
import {
	changedWorkoutState,
	proposalFromAdjustment,
	workoutAdjustmentState,
	type WorkoutStateRecord
} from './workout-state';

const base: WorkoutStateRecord = {
	weekId: 'week-1',
	scheduledDate: '2026-07-20',
	type: 'easy',
	status: 'planned',
	targetDistanceMeters: 5_000,
	targetDurationSeconds: null,
	intensity: 'Easy',
	purpose: 'Aerobic work',
	reason: 'Base plan',
	sourceRefs: ['base']
};

describe('workout state adapters', () => {
	it('preserves the legacy prescription defaults used by stored workout rows', () => {
		expect(workoutAdjustmentState(base)).toMatchObject({
			prescriptionKind: 'distance',
			intervalStructure: null,
			isRemoved: false
		});
		expect(
			workoutAdjustmentState({
				...base,
				targetDistanceMeters: 0,
				targetDurationSeconds: 1_800
			}).prescriptionKind
		).toBe('timed');
		expect(
			workoutAdjustmentState({
				...base,
				type: 'rest',
				targetDistanceMeters: 0
			}).prescriptionKind
		).toBe('rest');
	});

	it('applies a partial change without mutating the input record', () => {
		const changed = changedWorkoutState(base, {
			scheduledDate: '2026-07-21',
			targetDistanceMeters: 4_000
		});

		expect(changed).toMatchObject({
			scheduledDate: '2026-07-21',
			targetDistanceMeters: 4_000
		});
		expect(base).toMatchObject({
			scheduledDate: '2026-07-20',
			targetDistanceMeters: 5_000
		});
	});

	it('copies adjustment arrays when rebuilding an edit proposal', () => {
		const state = workoutAdjustmentState(base);
		const proposal = proposalFromAdjustment(state);
		proposal.sourceRefs.push('manual');

		expect(state.sourceRefs).toEqual(['base']);
		expect(proposal.sourceRefs).toEqual(['base', 'manual']);
	});
});
