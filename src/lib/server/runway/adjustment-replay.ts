import type { TimedIntervalStructure, WorkoutStatus, WorkoutType } from '$lib/training/types';

export type ReplayableWorkoutState = {
	weekId: string;
	scheduledDate: string;
	type: WorkoutType;
	status: WorkoutStatus;
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	prescriptionKind: 'distance' | 'timed' | 'rest';
	intervalStructure: TimedIntervalStructure | null;
	intensity: string;
	purpose: string;
	reason: string;
	sourceRefs: string[];
	isRemoved: boolean;
};

export type ReplayableAdjustment = {
	previousState: ReplayableWorkoutState;
	newState: ReplayableWorkoutState;
	reversedAt: Date | null;
};

const replaceFields = [
	'weekId',
	'scheduledDate',
	'type',
	'status',
	'targetDurationSeconds',
	'prescriptionKind',
	'intervalStructure',
	'intensity',
	'purpose',
	'reason',
	'sourceRefs',
	'isRemoved'
] as const;

export function replayWorkoutAdjustments(
	chronologicalAdjustments: ReplayableAdjustment[]
): ReplayableWorkoutState | null {
	const first = chronologicalAdjustments[0];
	if (!first) return null;
	const state = structuredClone(first.previousState);
	state.isRemoved ??= false;

	for (const adjustment of chronologicalAdjustments) {
		if (adjustment.reversedAt) continue;
		state.targetDistanceMeters = Math.max(
			0,
			state.targetDistanceMeters +
				(adjustment.newState.targetDistanceMeters - adjustment.previousState.targetDistanceMeters)
		);
		for (const field of replaceFields) {
			if (!sameValue(adjustment.previousState[field], adjustment.newState[field])) {
				assignField(state, field, adjustment.newState[field]);
			}
		}
	}

	return state;
}

function sameValue(left: unknown, right: unknown) {
	return Array.isArray(left) || Array.isArray(right)
		? JSON.stringify(left) === JSON.stringify(right)
		: left === right;
}

function assignField<K extends (typeof replaceFields)[number]>(
	state: ReplayableWorkoutState,
	field: K,
	value: ReplayableWorkoutState[K]
) {
	state[field] = structuredClone(value);
}
