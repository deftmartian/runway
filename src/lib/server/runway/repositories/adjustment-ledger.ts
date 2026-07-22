import { and, asc, desc, eq, inArray, isNull, notInArray, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { planAdjustment, workout } from '$lib/server/db/schema';
import {
	rebaseWorkoutAdjustments,
	replayWorkoutAdjustments
} from '$lib/server/runway/adjustment-replay';
import type { TrainingCalendarWorkout, TrainingPlanAdjustment } from '$lib/training/calendar-view';
import type { ConsequenceResult } from '$lib/training/types';
import type { RunwayTransaction } from './transaction';
import type { WorkoutAdjustmentState } from './workout-state';

export type PlanAdjustmentTrigger = (typeof planAdjustment.$inferSelect)['triggerType'];

export type PlanAdjustmentInput = {
	userId: string;
	planId: string;
	workoutId: string;
	triggerType: PlanAdjustmentTrigger;
	triggerId?: string | null;
	previousState: WorkoutAdjustmentState;
	newState: WorkoutAdjustmentState;
	consequence?: ConsequenceResult | null;
	reason: string;
};

export async function getLatestWorkoutAdjustments(userId: string, workoutIds: string[]) {
	if (workoutIds.length === 0) return new Map<string, TrainingPlanAdjustment>();

	const rows = await db
		.select()
		.from(planAdjustment)
		.where(
			and(
				eq(planAdjustment.userId, userId),
				inArray(planAdjustment.workoutId, workoutIds),
				isNull(planAdjustment.reversedAt)
			)
		)
		.orderBy(desc(planAdjustment.createdAt));
	const latest = new Map<string, TrainingPlanAdjustment>();
	for (const row of rows) {
		if (!latest.has(row.workoutId)) latest.set(row.workoutId, row);
	}
	return latest;
}

export async function getWorkoutRecommendationTraces(userId: string, workoutIds: string[]) {
	const traces = new Map<
		string,
		{
			recommended: TrainingCalendarWorkout['recommended'];
			isEdited: boolean;
		}
	>();
	if (workoutIds.length === 0) return traces;
	const rows = await db
		.select({
			workoutId: planAdjustment.workoutId,
			triggerType: planAdjustment.triggerType,
			previousState: planAdjustment.previousState,
			reversedAt: planAdjustment.reversedAt
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, userId), inArray(planAdjustment.workoutId, workoutIds)))
		.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id));
	const manualTriggers: PlanAdjustmentTrigger[] = [
		'manual_edit',
		'manual_add',
		'manual_remove',
		'rebalance'
	];
	for (const row of rows) {
		const current = traces.get(row.workoutId);
		const firstRecommendation = current
			? current.recommended
			: row.triggerType === 'manual_add'
				? null
				: {
						scheduledDate: row.previousState.scheduledDate,
						type: row.previousState.type,
						prescriptionKind: row.previousState.prescriptionKind,
						targetDistanceMeters: row.previousState.targetDistanceMeters,
						targetDurationSeconds: row.previousState.targetDurationSeconds,
						intervalStructure: row.previousState.intervalStructure,
						purpose: row.previousState.purpose
					};
		traces.set(row.workoutId, {
			recommended: firstRecommendation,
			isEdited:
				(current?.isEdited ?? false) ||
				(row.reversedAt === null && manualTriggers.includes(row.triggerType))
		});
	}
	return traces;
}

export async function recordPlanAdjustment(tx: RunwayTransaction, input: PlanAdjustmentInput) {
	const [counts] = await tx
		.select({
			planCount: sql<number>`count(*)::int`,
			workoutCount: sql<number>`count(*) filter (where ${planAdjustment.workoutId} = ${input.workoutId})::int`
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, input.userId), eq(planAdjustment.planId, input.planId)));
	if ((counts?.planCount ?? 0) >= 10_000 || (counts?.workoutCount ?? 0) >= 100) {
		throw new Error('This plan has reached its adjustment ledger limit.');
	}
	await tx.insert(planAdjustment).values({
		userId: input.userId,
		planId: input.planId,
		workoutId: input.workoutId,
		triggerType: input.triggerType,
		triggerId: input.triggerId ?? null,
		previousTargetDistanceMeters: input.previousState.targetDistanceMeters,
		newTargetDistanceMeters: input.newState.targetDistanceMeters,
		previousScheduledDate: input.previousState.scheduledDate,
		newScheduledDate: input.newState.scheduledDate,
		previousState: input.previousState,
		newState: input.newState,
		consequence: input.consequence ?? null,
		reason: input.reason
	});
}

export async function reverseLedgerAdjustmentsForTrigger(
	tx: RunwayTransaction,
	input: {
		userId: string;
		triggerId: string;
		originalTriggerTypes: PlanAdjustmentTrigger[];
		reason: string;
		excludeWorkoutIds?: string[];
	}
) {
	if (input.originalTriggerTypes.length === 0) return;
	const reversed = await tx
		.update(planAdjustment)
		.set({ reversedAt: new Date(), reversalReason: input.reason })
		.where(
			and(
				eq(planAdjustment.userId, input.userId),
				eq(planAdjustment.triggerId, input.triggerId),
				inArray(planAdjustment.triggerType, input.originalTriggerTypes),
				isNull(planAdjustment.reversedAt),
				...(input.excludeWorkoutIds && input.excludeWorkoutIds.length > 0
					? [notInArray(planAdjustment.workoutId, input.excludeWorkoutIds)]
					: [])
			)
		)
		.returning({ workoutId: planAdjustment.workoutId });

	await replayWorkoutLedgers(
		tx,
		input.userId,
		Array.from(new Set(reversed.map((row) => row.workoutId)))
	);
}

export async function replayWorkoutLedgers(
	tx: RunwayTransaction,
	userId: string,
	workoutIds: string[]
) {
	if (workoutIds.length === 0) return;
	const adjustments = await tx
		.select({
			workoutId: planAdjustment.workoutId,
			previousState: planAdjustment.previousState,
			newState: planAdjustment.newState,
			reversedAt: planAdjustment.reversedAt
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, userId), inArray(planAdjustment.workoutId, workoutIds)))
		.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id));
	for (const workoutId of workoutIds) {
		const state = replayWorkoutAdjustments(
			adjustments.filter((row) => row.workoutId === workoutId)
		);
		if (!state) continue;
		await tx
			.update(workout)
			.set({ ...state, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)));
	}
}

export async function eraseLedgerAdjustments(
	tx: RunwayTransaction,
	input: {
		userId: string;
		targets: { id: string; workoutId: string }[];
	}
): Promise<void> {
	if (input.targets.length === 0) return;
	const targetIds = new Set(input.targets.map(({ id }) => id));
	const workoutIds = Array.from(new Set(input.targets.map(({ workoutId }) => workoutId)));
	const adjustments = await tx
		.select({
			id: planAdjustment.id,
			workoutId: planAdjustment.workoutId,
			previousState: planAdjustment.previousState,
			newState: planAdjustment.newState,
			reversedAt: planAdjustment.reversedAt
		})
		.from(planAdjustment)
		.where(
			and(eq(planAdjustment.userId, input.userId), inArray(planAdjustment.workoutId, workoutIds))
		)
		.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id));
	const now = new Date();

	for (const workoutId of workoutIds) {
		const rebased = rebaseWorkoutAdjustments(
			adjustments.filter((row) => row.workoutId === workoutId),
			(row) => targetIds.has(row.id)
		);
		if (!rebased) continue;
		for (const row of rebased.adjustments) {
			await tx
				.update(planAdjustment)
				.set({
					previousTargetDistanceMeters: row.previousState.targetDistanceMeters,
					newTargetDistanceMeters: row.newState.targetDistanceMeters,
					previousScheduledDate: row.previousState.scheduledDate,
					newScheduledDate: row.newState.scheduledDate,
					previousState: row.previousState,
					newState: row.newState
				})
				.where(and(eq(planAdjustment.userId, input.userId), eq(planAdjustment.id, row.id)));
		}
		await tx
			.update(workout)
			.set({ ...rebased.state, updatedAt: now })
			.where(and(eq(workout.userId, input.userId), eq(workout.id, workoutId)));
	}

	await tx
		.delete(planAdjustment)
		.where(
			and(
				eq(planAdjustment.userId, input.userId),
				inArray(planAdjustment.id, Array.from(targetIds))
			)
		);
}
