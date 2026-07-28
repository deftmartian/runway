import { describe, expect, it } from 'vitest';
import { calculateConsequence } from '$lib/training/consequences';
import { projectConsequenceDecisionChanges } from './workout-feedback';
import type { WorkoutStateRecord } from './workout-state';

const timedCandidate = (
	scheduledDate: string,
	duration: number
): WorkoutStateRecord & { id: string } => ({
	id: `workout-${scheduledDate}`,
	weekId: 'week-1',
	scheduledDate,
	type: 'easy',
	status: 'planned',
	targetDistanceMeters: 0,
	targetDurationSeconds: duration,
	prescriptionKind: 'timed',
	intervalStructure: null,
	intensity: 'easy',
	purpose: 'Easy timed run',
	reason: 'Plan',
	sourceRefs: [],
	isRemoved: false
});

describe('consequence decision projection', () => {
	it('uses one projection for the timed reduce-next preview and persisted state', () => {
		const consequence = calculateConsequence({
			status: 'done',
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_800,
			completedDurationSeconds: 1_800,
			choice: 'skip_continue',
			feltHard: true,
			pain: false,
			weekTargetDistanceMeters: 0
		});
		const projection = projectConsequenceDecisionChanges({
			decision: 'reduce_next',
			consequence,
			originDate: '2026-07-28',
			originWorkout: null,
			candidates: [timedCandidate('2026-07-30', 1_800)]
		});

		expect(projection.changes).toHaveLength(1);
		expect(projection.changes[0]?.newState).toMatchObject({
			targetDurationSeconds: 1_500,
			prescriptionKind: 'timed'
		});
	});

	it('projects every compatible timed workout for rebalance with the exact persisted states', () => {
		const consequence = calculateConsequence({
			status: 'done',
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_800,
			completedDurationSeconds: 1_800,
			choice: 'skip_continue',
			feltHard: true,
			pain: false,
			weekTargetDistanceMeters: 0
		});
		const projection = projectConsequenceDecisionChanges({
			decision: 'rebalance_week',
			consequence,
			originDate: '2026-07-28',
			originWorkout: null,
			candidates: [
				timedCandidate('2026-07-30', 1_800),
				timedCandidate('2026-08-01', 1_200),
				timedCandidate('2026-08-04', 1_800)
			]
		});

		expect(projection.changes.map((change) => change.candidate.scheduledDate)).toEqual([
			'2026-07-30',
			'2026-08-01'
		]);
		expect(projection.changes.map((change) => change.newState.targetDurationSeconds)).toEqual([
			1_650, 1_050
		]);
	});
});
