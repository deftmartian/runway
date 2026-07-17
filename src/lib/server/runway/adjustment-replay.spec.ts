import { describe, expect, it } from 'vitest';
import {
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
});

function adjustment(
	previousState: ReplayableWorkoutState,
	newState: ReplayableWorkoutState,
	reversedAt: Date | null = null
): ReplayableAdjustment {
	return { previousState, newState, reversedAt };
}
