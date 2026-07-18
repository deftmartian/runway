import { and, asc, desc, eq, gt, inArray, isNull } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	athleteProfile,
	auditEvent,
	planAdjustment,
	trainingPlan,
	trainingWeek,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { addDays, todayIsoInTimeZone } from '$lib/training/date';
import { hasInjuryRiskFlags } from '$lib/training/plan';
import {
	previewWorkoutEdit as buildWorkoutEditPreview,
	proposalFromWorkout,
	rebalanceWorkoutStates,
	type EditableWorkoutState,
	type WorkoutEditPreview,
	type WorkoutEditProposal
} from '$lib/training/workout-edit';
import type { RunwayTransaction } from '$lib/server/runway/repositories/transaction';
import {
	changedWorkoutState,
	workoutAdjustmentState,
	editableWorkout,
	proposalFromAdjustment,
	sameWorkoutProposal,
	type FutureWorkoutAddInput,
	type FutureWorkoutEditInput
} from '$lib/server/runway/repositories/workout-state';
import { requireAthleteTimeZoneInTransaction } from '$lib/server/runway/repositories/profiles';
import { planWeekIdForDate } from '$lib/server/runway/repositories/schedule-queries';
import {
	recordPlanAdjustment,
	replayWorkoutLedgers,
	type PlanAdjustmentTrigger
} from '$lib/server/runway/repositories/adjustment-ledger';

export type { FutureWorkoutAddInput, FutureWorkoutEditInput };

export async function previewFutureWorkoutEdit(
	userId: string,
	input: FutureWorkoutEditInput
): Promise<WorkoutEditPreview> {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, input.workoutId, false);
		return buildEditPreview(context, input);
	});
}

export async function applyFutureWorkoutEdit(userId: string, input: FutureWorkoutEditInput) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, input.workoutId, true);
		const preview = buildEditPreview(context, input);
		if (preview.requiresConfirmation && !input.confirmRisk) {
			throw new Error('Review and confirm the elevated edit before applying it.');
		}
		const proposal = preview.proposed;
		if (sameWorkoutProposal(proposalFromWorkout(context.current), proposal)) {
			throw new Error('The proposed workout is unchanged.');
		}
		const editId = crypto.randomUUID();
		const selectedState = workoutAdjustmentState({ ...context.current, ...proposal });
		await tx
			.update(workout)
			.set({ ...selectedState, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, context.current.id)));
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: context.current.id,
			triggerType: input.rebalance ? 'rebalance' : 'manual_edit',
			triggerId: editId,
			previousState: workoutAdjustmentState(context.current),
			newState: selectedState,
			reason: input.userReason?.trim() || 'Runner edited this workout.'
		});

		const rebalanced = input.rebalance
			? rebalanceWorkoutStates({
					selectedId: context.current.id,
					current: context.current,
					proposed: proposal,
					workouts: context.workouts,
					today: context.futureStartDate
				})
			: [];
		for (const change of rebalanced) {
			const candidate = context.workouts.find((record) => record.id === change.workoutId);
			if (!candidate) continue;
			const newState = workoutAdjustmentState({ ...candidate, ...change.proposed });
			await tx
				.update(workout)
				.set({ ...newState, updatedAt: new Date() })
				.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
			await recordPlanAdjustment(tx, {
				userId,
				planId: context.plan.id,
				workoutId: candidate.id,
				triggerType: 'rebalance',
				triggerId: editId,
				previousState: workoutAdjustmentState(candidate),
				newState,
				reason: 'Runner explicitly rebalanced the remaining week.'
			});
		}
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.edited',
			detail: {
				workoutId: context.current.id,
				editId,
				rebalancedWorkoutIds: rebalanced.map((change) => change.workoutId)
			}
		});
		return { preview, editId };
	});
}

export async function previewFutureWorkoutRemoval(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, workoutId, false);
		const proposed = { ...proposalFromWorkout(context.current), isRemoved: true };
		return buildWorkoutEditPreview({
			current: context.current,
			recommended: context.recommended,
			proposed,
			workouts: context.workouts,
			weeks: context.weeks,
			today: context.today,
			rebalance: false,
			operation: 'remove',
			hasInjuryRisk: context.hasInjuryRisk
		});
	});
}

export async function removeFutureWorkout(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, workoutId, true);
		const editId = crypto.randomUUID();
		const previousState = workoutAdjustmentState(context.current);
		const newState = changedWorkoutState(context.current, { isRemoved: true });
		await tx
			.update(workout)
			.set({ isRemoved: true, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, context.current.id)));
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: context.current.id,
			triggerType: 'manual_remove',
			triggerId: editId,
			previousState,
			newState,
			reason: 'Runner removed this future workout.'
		});
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.removed',
			detail: { workoutId: context.current.id, editId }
		});
		return { workoutId: context.current.id, editId };
	});
}

export async function previewFutureWorkoutAdd(userId: string, input: FutureWorkoutAddInput) {
	return db.transaction(async (tx) => {
		const context = await newWorkoutContext(tx, userId, input.scheduledDate, false);
		const proposal = editProposal(input, context.weekId);
		const placeholder: EditableWorkoutState = {
			id: 'new-workout',
			status: 'planned',
			...proposal,
			isRemoved: true
		};
		return buildWorkoutEditPreview({
			current: placeholder,
			recommended: null,
			proposed: proposal,
			workouts: [...context.workouts, placeholder],
			weeks: context.weeks,
			today: context.futureStartDate,
			rebalance: input.rebalance,
			operation: 'add',
			hasInjuryRisk: context.hasInjuryRisk
		});
	});
}

export async function addFutureWorkout(userId: string, input: FutureWorkoutAddInput) {
	return db.transaction(async (tx) => {
		const context = await newWorkoutContext(tx, userId, input.scheduledDate, true);
		const proposal = editProposal(input, context.weekId);
		const placeholder: EditableWorkoutState = {
			id: 'new-workout',
			status: 'planned',
			...proposal,
			isRemoved: true
		};
		const preview = buildWorkoutEditPreview({
			current: placeholder,
			recommended: null,
			proposed: proposal,
			workouts: [...context.workouts, placeholder],
			weeks: context.weeks,
			today: context.futureStartDate,
			rebalance: input.rebalance,
			operation: 'add',
			hasInjuryRisk: context.hasInjuryRisk
		});
		if (preview.requiresConfirmation && !input.confirmRisk) {
			throw new Error('Review and confirm the elevated edit before applying it.');
		}
		const [created] = await tx
			.insert(workout)
			.values({
				userId,
				planId: context.plan.id,
				status: 'planned',
				...proposal
			})
			.returning();
		if (!created) throw new Error('Workout could not be added.');
		const editId = crypto.randomUUID();
		const previousState = workoutAdjustmentState({ ...created, isRemoved: true });
		const newState = workoutAdjustmentState(created);
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: created.id,
			triggerType: 'manual_add',
			triggerId: editId,
			previousState,
			newState,
			reason: input.userReason?.trim() || 'Runner added this future workout.'
		});
		const rebalanced = input.rebalance
			? rebalanceWorkoutStates({
					selectedId: placeholder.id,
					current: placeholder,
					proposed: proposal,
					workouts: [...context.workouts, placeholder],
					today: context.futureStartDate
				})
			: [];
		for (const change of rebalanced) {
			const candidate = context.workouts.find((record) => record.id === change.workoutId);
			if (!candidate) continue;
			const changedState = workoutAdjustmentState({ ...candidate, ...change.proposed });
			await tx
				.update(workout)
				.set({ ...changedState, updatedAt: new Date() })
				.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
			await recordPlanAdjustment(tx, {
				userId,
				planId: context.plan.id,
				workoutId: candidate.id,
				triggerType: 'rebalance',
				triggerId: editId,
				previousState: workoutAdjustmentState(candidate),
				newState: changedState,
				reason: 'Runner explicitly rebalanced the remaining week after adding a workout.'
			});
		}
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.added',
			detail: {
				workoutId: created.id,
				editId,
				rebalancedWorkoutIds: rebalanced.map((change) => change.workoutId)
			}
		});
		return { workoutId: created.id, editId, preview };
	});
}

export async function resetFutureWorkout(userId: string, workoutId: string) {
	return reverseManualWorkoutAdjustments(userId, { workoutId });
}

export async function undoFutureWorkoutAdjustment(userId: string, adjustmentId: string) {
	return reverseManualWorkoutAdjustments(userId, { adjustmentId });
}

async function reverseManualWorkoutAdjustments(
	userId: string,
	input: { workoutId?: string; adjustmentId?: string }
) {
	return db.transaction(async (tx) => {
		const today = todayIsoInTimeZone(await requireAthleteTimeZoneInTransaction(tx, userId));
		const [lockedPlan] = await tx
			.select({ id: trainingPlan.id })
			.from(trainingPlan)
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
			.limit(1)
			.for('update');
		if (!lockedPlan) throw new Error('No reversible workout change was found.');
		const manualTriggers: PlanAdjustmentTrigger[] = [
			'manual_edit',
			'manual_add',
			'manual_remove',
			'rebalance'
		];
		const [selectedAdjustment] = input.adjustmentId
			? await tx
					.select({ triggerId: planAdjustment.triggerId })
					.from(planAdjustment)
					.where(
						and(
							eq(planAdjustment.userId, userId),
							eq(planAdjustment.id, input.adjustmentId),
							inArray(planAdjustment.triggerType, manualTriggers),
							isNull(planAdjustment.reversedAt)
						)
					)
					.limit(1)
			: [];
		if (input.adjustmentId && !selectedAdjustment) {
			throw new Error('No reversible workout change was found.');
		}
		const rows = await tx
			.select({ id: planAdjustment.id, workoutId: planAdjustment.workoutId })
			.from(planAdjustment)
			.innerJoin(
				trainingPlan,
				and(
					eq(planAdjustment.planId, trainingPlan.id),
					eq(trainingPlan.id, lockedPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.innerJoin(
				workout,
				and(
					eq(planAdjustment.workoutId, workout.id),
					eq(workout.userId, userId),
					eq(workout.status, 'planned'),
					gt(workout.scheduledDate, today)
				)
			)
			.where(
				and(
					eq(planAdjustment.userId, userId),
					inArray(planAdjustment.triggerType, manualTriggers),
					isNull(planAdjustment.reversedAt),
					...(input.workoutId ? [eq(planAdjustment.workoutId, input.workoutId)] : []),
					...(input.adjustmentId && selectedAdjustment?.triggerId
						? [eq(planAdjustment.triggerId, selectedAdjustment.triggerId)]
						: input.adjustmentId
							? [eq(planAdjustment.id, input.adjustmentId)]
							: [])
				)
			)
			.orderBy(desc(planAdjustment.createdAt))
			.for('update', { of: planAdjustment });
		if (rows.length === 0) throw new Error('No reversible workout change was found.');
		const reversedRows = await tx
			.update(planAdjustment)
			.set({
				reversedAt: new Date(),
				reversalReason: 'Runner reversed this manual workout change.'
			})
			.where(
				and(
					eq(planAdjustment.userId, userId),
					inArray(
						planAdjustment.id,
						rows.map((row) => row.id)
					),
					isNull(planAdjustment.reversedAt)
				)
			)
			.returning({ id: planAdjustment.id });
		if (reversedRows.length !== rows.length) {
			throw new Error('No reversible workout change was found.');
		}
		const workoutIds = Array.from(new Set(rows.map((row) => row.workoutId)));
		await replayWorkoutLedgers(tx, userId, workoutIds);
		await tx.insert(auditEvent).values({
			userId,
			eventType: input.adjustmentId ? 'workout.adjustment_undone' : 'workout.reset',
			detail: { workoutIds }
		});
		return { workoutIds };
	});
}

type WorkoutEditContext = Awaited<ReturnType<typeof workoutEditContext>>;

async function workoutEditContext(
	tx: RunwayTransaction,
	userId: string,
	workoutId: string,
	lockPlan: boolean
) {
	const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
	const today = todayIsoInTimeZone(timeZone);
	const futureStartDate = addDays(today, 1);
	const [workoutReference] = await tx
		.select({ planId: workout.planId })
		.from(workout)
		.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)))
		.limit(1);
	if (!workoutReference) throw new Error('Future workout not found.');
	const planQuery = tx
		.select({ id: trainingPlan.id })
		.from(trainingPlan)
		.where(
			and(
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.id, workoutReference.planId),
				eq(trainingPlan.status, 'active')
			)
		)
		.limit(1);
	const [activePlan] = lockPlan ? await planQuery.for('update') : await planQuery;
	if (!activePlan) throw new Error('Future workout not found.');
	const recordQuery = tx
		.select({ current: workout, plan: trainingPlan })
		.from(workout)
		.innerJoin(
			trainingPlan,
			and(
				eq(workout.planId, trainingPlan.id),
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.status, 'active')
			)
		)
		.leftJoin(
			workoutFeedback,
			and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
		)
		.leftJoin(activity, and(eq(activity.workoutId, workout.id), eq(activity.userId, userId)))
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.id, workoutId),
				isNull(workoutFeedback.id),
				isNull(activity.id)
			)
		)
		.limit(1);
	const [record] = lockPlan ? await recordQuery.for('update', { of: workout }) : await recordQuery;
	if (!record) throw new Error('Future workout not found.');
	if (record.current.type === 'race') {
		throw new Error('Race events are changed through the goal editor.');
	}
	if (record.current.status !== 'planned' || record.current.scheduledDate < futureStartDate) {
		throw new Error('Only workouts after today can be changed.');
	}
	if (record.current.isRemoved) throw new Error('Reset or undo this removed workout first.');
	const [workouts, weeks, [firstAdjustment], [profile]] = await Promise.all([
		tx
			.select()
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, record.plan.id))),
		tx
			.select({
				id: trainingWeek.id,
				weekNumber: trainingWeek.weekNumber,
				startDate: trainingWeek.startDate
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, record.plan.id)))
			.orderBy(asc(trainingWeek.weekNumber)),
		tx
			.select({
				triggerType: planAdjustment.triggerType,
				previousState: planAdjustment.previousState
			})
			.from(planAdjustment)
			.where(and(eq(planAdjustment.userId, userId), eq(planAdjustment.workoutId, workoutId)))
			.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id))
			.limit(1),
		tx
			.select({ injuryFlags: athleteProfile.injuryFlags })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
	]);
	return {
		current: editableWorkout(record.current),
		plan: record.plan,
		workouts: workouts.map(editableWorkout),
		weeks,
		today,
		futureStartDate,
		hasInjuryRisk: profile ? hasInjuryRiskFlags(profile.injuryFlags) : false,
		recommended:
			firstAdjustment?.triggerType === 'manual_add'
				? null
				: firstAdjustment?.previousState
					? proposalFromAdjustment(firstAdjustment.previousState)
					: proposalFromWorkout(editableWorkout(record.current))
	};
}

async function newWorkoutContext(
	tx: RunwayTransaction,
	userId: string,
	scheduledDate: string,
	lockPlan: boolean
) {
	const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
	const today = todayIsoInTimeZone(timeZone);
	const futureStartDate = addDays(today, 1);
	const planQuery = tx
		.select()
		.from(trainingPlan)
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
		.limit(1);
	const [plan] = lockPlan ? await planQuery.for('update') : await planQuery;
	if (!plan) throw new Error('No active plan is available.');
	if (scheduledDate < futureStartDate || scheduledDate > plan.targetDate) {
		throw new Error('Workout dates must be after today and no later than the active goal date.');
	}
	const weekId = await planWeekIdForDate(tx, userId, plan.id, scheduledDate);
	if (!weekId) throw new Error('The selected date is outside the active plan weeks.');
	const [workouts, weeks, [profile]] = await Promise.all([
		tx
			.select()
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, plan.id))),
		tx
			.select({
				id: trainingWeek.id,
				weekNumber: trainingWeek.weekNumber,
				startDate: trainingWeek.startDate
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, plan.id)))
			.orderBy(asc(trainingWeek.weekNumber)),
		tx
			.select({ injuryFlags: athleteProfile.injuryFlags })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
	]);
	if (workouts.length >= 52 * 14) {
		throw new Error('This plan has reached its workout record limit.');
	}
	const visibleOnDate = workouts.filter(
		(record) => record.scheduledDate === scheduledDate && !record.isRemoved
	).length;
	if (visibleOnDate >= 2) throw new Error('A day can contain at most two planned workouts.');
	const visibleInWeek = workouts.filter(
		(record) => record.weekId === weekId && !record.isRemoved
	).length;
	if (visibleInWeek >= 14)
		throw new Error('A training week can contain at most fourteen workouts.');
	return {
		plan,
		today,
		futureStartDate,
		weekId,
		workouts: workouts.map(editableWorkout),
		weeks,
		hasInjuryRisk: profile ? hasInjuryRiskFlags(profile.injuryFlags) : false
	};
}

function buildEditPreview(context: WorkoutEditContext, input: FutureWorkoutEditInput) {
	if (
		input.scheduledDate < context.futureStartDate ||
		input.scheduledDate > context.plan.targetDate
	) {
		throw new Error('Workout dates must be after today and no later than the active goal date.');
	}
	const destinationWeek = context.weeks.find(
		(week) =>
			input.scheduledDate >= week.startDate && input.scheduledDate <= addDays(week.startDate, 6)
	);
	if (!destinationWeek) throw new Error('The selected date is outside the active plan weeks.');
	const otherVisibleOnDate = context.workouts.filter(
		(record) =>
			record.id !== context.current.id &&
			record.scheduledDate === input.scheduledDate &&
			!record.isRemoved
	).length;
	if (otherVisibleOnDate >= 2) throw new Error('A day can contain at most two planned workouts.');
	const otherVisibleInWeek = context.workouts.filter(
		(record) =>
			record.id !== context.current.id && record.weekId === destinationWeek.id && !record.isRemoved
	).length;
	if (otherVisibleInWeek >= 14)
		throw new Error('A training week can contain at most fourteen workouts.');
	const proposed = editProposal(input, destinationWeek.id);
	return buildWorkoutEditPreview({
		current: context.current,
		recommended: context.recommended,
		proposed,
		workouts: context.workouts,
		weeks: context.weeks,
		today: context.futureStartDate,
		rebalance: input.rebalance,
		hasInjuryRisk: context.hasInjuryRisk
	});
}

function editProposal(
	input: FutureWorkoutAddInput | FutureWorkoutEditInput,
	weekId: string
): WorkoutEditProposal {
	const reason = input.userReason?.trim() || 'Runner-edited workout.';
	if (input.prescriptionKind === 'rest') {
		return {
			weekId,
			scheduledDate: input.scheduledDate,
			type: 'rest',
			prescriptionKind: 'rest',
			targetDistanceMeters: 0,
			targetDurationSeconds: null,
			intervalStructure: null,
			intensity: 'rest',
			purpose: input.purpose.trim() || 'Recovery day',
			reason,
			sourceRefs: [],
			isRemoved: false
		};
	}
	return {
		weekId,
		scheduledDate: input.scheduledDate,
		type: input.type === 'rest' ? 'easy' : input.type,
		prescriptionKind: input.prescriptionKind,
		targetDistanceMeters: input.prescriptionKind === 'distance' ? input.targetDistanceMeters : 0,
		targetDurationSeconds: input.prescriptionKind === 'timed' ? input.targetDurationSeconds : null,
		intervalStructure:
			input.prescriptionKind === 'timed' ? structuredClone(input.intervalStructure) : null,
		intensity: input.intensity.trim() || 'easy',
		purpose: input.purpose.trim(),
		reason,
		sourceRefs: [],
		isRemoved: false
	};
}
