import { planAdjustment, workout } from '$lib/server/db/schema';
import type { EditableWorkoutState, WorkoutEditProposal } from '$lib/training/workout-edit';
import type { ReplayableWorkoutState } from '$lib/server/runway/adjustment-replay';

export type WorkoutAdjustmentState = NonNullable<
	(typeof planAdjustment.$inferSelect)['previousState']
>;
export type WorkoutStateRecord = Omit<
	ReplayableWorkoutState,
	'prescriptionKind' | 'intervalStructure' | 'isRemoved'
> &
	Partial<Pick<ReplayableWorkoutState, 'prescriptionKind' | 'intervalStructure' | 'isRemoved'>>;

export type FutureWorkoutEditInput = {
	workoutId: string;
	scheduledDate: string;
	type: Exclude<WorkoutStateRecord['type'], 'race'>;
	prescriptionKind: 'distance' | 'timed' | 'rest';
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	intervalStructure: WorkoutAdjustmentState['intervalStructure'];
	intensity: string;
	purpose: string;
	userReason?: string;
	rebalance: boolean;
	confirmRisk: boolean;
};

export type FutureWorkoutAddInput = Omit<FutureWorkoutEditInput, 'workoutId'>;

export function workoutAdjustmentState(record: WorkoutStateRecord): WorkoutAdjustmentState {
	return {
		weekId: record.weekId,
		scheduledDate: record.scheduledDate,
		type: record.type,
		status: record.status,
		targetDistanceMeters: record.targetDistanceMeters,
		targetDurationSeconds: record.targetDurationSeconds,
		prescriptionKind:
			record.prescriptionKind ??
			(record.type === 'rest' ? 'rest' : record.targetDurationSeconds ? 'timed' : 'distance'),
		intervalStructure: record.intervalStructure ?? null,
		intensity: record.intensity,
		purpose: record.purpose,
		reason: record.reason,
		sourceRefs: record.sourceRefs,
		isRemoved: record.isRemoved ?? false
	};
}

export function changedWorkoutState(
	record: WorkoutStateRecord,
	changes: Partial<WorkoutAdjustmentState>
): WorkoutAdjustmentState {
	return {
		...workoutAdjustmentState(record),
		...changes
	};
}

export function editableWorkout(record: typeof workout.$inferSelect): EditableWorkoutState {
	return {
		id: record.id,
		weekId: record.weekId,
		scheduledDate: record.scheduledDate,
		type: record.type,
		status: record.status,
		prescriptionKind: record.prescriptionKind,
		targetDistanceMeters: record.targetDistanceMeters,
		targetDurationSeconds: record.targetDurationSeconds,
		intervalStructure: structuredClone(record.intervalStructure),
		intensity: record.intensity,
		purpose: record.purpose,
		reason: record.reason,
		sourceRefs: [...record.sourceRefs],
		isRemoved: record.isRemoved
	};
}

export function proposalFromAdjustment(state: WorkoutAdjustmentState): WorkoutEditProposal {
	return {
		weekId: state.weekId,
		scheduledDate: state.scheduledDate,
		type: state.type,
		prescriptionKind: state.prescriptionKind,
		targetDistanceMeters: state.targetDistanceMeters,
		targetDurationSeconds: state.targetDurationSeconds,
		intervalStructure: structuredClone(state.intervalStructure),
		intensity: state.intensity,
		purpose: state.purpose,
		reason: state.reason,
		sourceRefs: [...state.sourceRefs],
		isRemoved: state.isRemoved ?? false
	};
}

export function sameWorkoutProposal(left: WorkoutEditProposal, right: WorkoutEditProposal) {
	return JSON.stringify(left) === JSON.stringify(right);
}
