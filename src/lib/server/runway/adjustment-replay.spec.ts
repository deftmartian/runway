import { describe, expect, it } from 'vitest';
import {
	rebaseWorkoutAdjustments,
	replayWorkoutAdjustments,
	type ReplayableAdjustment,
	type ReplayableWorkoutState
} from './adjustment-replay';

const base: ReplayableWorkoutState = {
	weekId: 'week-1',
	scheduledDate: '2026-07-20',
	type: 'easy',
	status: 'planned',
	targetDistanceMeters: 10_000,
	targetDurationSeconds: null,
	prescriptionKind: 'distance',
	intervalStructure: null,
	intensity: 'Easy',
	purpose: 'Aerobic work',
	reason: 'Base plan',
	sourceRefs: ['base'],
	isRemoved: false
};

describe('workout adjustment replay', () => {
	it('removes an older adjustment without erasing a later distance reduction', () => {
		const first = adjustment(base, { ...base, targetDistanceMeters: 9_000 }, new Date());
		const second = adjustment(
			{ ...base, targetDistanceMeters: 9_000 },
			{ ...base, targetDistanceMeters: 8_000 }
		);
		expect(replayWorkoutAdjustments([first, second])?.targetDistanceMeters).toBe(9_000);
	});

	it('replays only the fields each active adjustment actually changed', () => {
		const recovery = {
			...base,
			type: 'recovery' as const,
			intensity: 'Very easy',
			targetDistanceMeters: 8_000
		};
		const moved = { ...recovery, weekId: 'week-2', scheduledDate: '2026-07-27' };
		const first = adjustment(base, recovery, new Date());
		const second = adjustment(recovery, moved);
		expect(replayWorkoutAdjustments([first, second])).toEqual({
			...base,
			weekId: 'week-2',
			scheduledDate: '2026-07-27'
		});
	});

	it('is idempotent when every adjustment is already reversed', () => {
		const changed = { ...base, targetDistanceMeters: 8_000 };
		const row = adjustment(base, changed, new Date());
		expect(replayWorkoutAdjustments([row])).toEqual(base);
		expect(replayWorkoutAdjustments([row])).toEqual(base);
	});

	it('rebases later adjustments without retaining the erased activity state', () => {
		const completedByActivity = {
			...base,
			scheduledDate: '2026-07-21',
			status: 'done' as const,
			sourceRefs: [...base.sourceRefs, 'private-activity-id']
		};
		const manuallyReduced = { ...completedByActivity, targetDistanceMeters: 8_000 };
		const activity = { id: 'activity', ...adjustment(base, completedByActivity) };
		const manual = { id: 'manual', ...adjustment(completedByActivity, manuallyReduced) };

		const rebased = rebaseWorkoutAdjustments([activity, manual], (row) => row.id === 'activity');

		expect(rebased).toEqual({
			state: { ...base, targetDistanceMeters: 8_000 },
			adjustments: [
				{
					id: manual.id,
					previousState: base,
					newState: { ...base, targetDistanceMeters: 8_000 }
				}
			]
		});
		expect(JSON.stringify(rebased)).not.toContain('private-activity-id');
		expect(JSON.stringify(rebased)).not.toContain('2026-07-21');
	});
});

function adjustment(
	previousState: ReplayableWorkoutState,
	newState: ReplayableWorkoutState,
	reversedAt: Date | null = null
): ReplayableAdjustment {
	return { previousState, newState, reversedAt };
}
