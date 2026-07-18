import { and, asc, eq, gt, gte, inArray, lt, lte, ne, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	auditEvent,
	trainingPlan,
	trainingWeek,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { addDays, todayIsoInTimeZone } from '$lib/training/date';
import {
	calculateConsequence,
	calculateConsequenceDecisionEffect,
	isConsequenceDecisionTargetCompatible,
	withAppliedDecision
} from '$lib/training/consequences';
import {
	previewWorkoutEdit as buildWorkoutEditPreview,
	proposalFromWorkout,
	resizeTimedIntervalStructure
} from '$lib/training/workout-edit';
import type { ConsequenceResult, PlanDecision } from '$lib/training/types';
import type { RunwayTransaction } from '$lib/server/runway/repositories/transaction';
import {
	changedWorkoutState,
	workoutAdjustmentState,
	editableWorkout,
	type WorkoutAdjustmentState,
	type WorkoutStateRecord
} from '$lib/server/runway/repositories/workout-state';
import { requireAthleteTimeZoneInTransaction } from '$lib/server/runway/repositories/profiles';
import { isoWeekStart } from '$lib/server/runway/repositories/schedule-queries';
import {
	recordPlanAdjustment,
	reverseLedgerAdjustmentsForTrigger
} from '$lib/server/runway/repositories/adjustment-ledger';
import { lockActivityOwner } from '$lib/server/runway/repositories/mutation-locks';

export async function recordWorkoutFeedback(
	userId: string,
	input: {
		workoutId: string;
		status: 'done' | 'skipped' | 'shortened';
		completedDistanceMeters?: number;
		completedDurationSeconds?: number;
		feltHard: boolean;
		pain: boolean;
		choice: 'skip_continue' | 'reduce_next';
	}
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const [targetWorkout] = await tx
			.select({ workout, week: trainingWeek })
			.from(workout)
			.innerJoin(trainingWeek, eq(workout.weekId, trainingWeek.id))
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, input.workoutId),
					eq(workout.isRemoved, false)
				)
			)
			.limit(1)
			.for('update', { of: workout });

		if (!targetWorkout) throw new Error('Workout not found.');
		if (targetWorkout.workout.scheduledDate > today) {
			throw new Error('Workout is scheduled for the future.');
		}
		if (targetWorkout.workout.type === 'rest') {
			throw new Error('Rest days do not accept workout feedback. Record an unplanned run instead.');
		}
		if (targetWorkout.workout.status !== 'planned') {
			throw new Error('Feedback has already been recorded for this workout.');
		}

		const [existingFeedback] = await tx
			.select({ id: workoutFeedback.id })
			.from(workoutFeedback)
			.where(
				and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, input.workoutId))
			)
			.limit(1);
		if (existingFeedback) {
			throw new Error('Feedback has already been recorded for this workout.');
		}

		const completedDistanceMeters = input.completedDistanceMeters;

		const [recentMisses] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, targetWorkout.workout.planId),
					eq(workout.isRemoved, false),
					ne(workout.type, 'rest'),
					inArray(workout.status, ['skipped', 'shortened']),
					lt(workout.scheduledDate, targetWorkout.workout.scheduledDate),
					gte(workout.scheduledDate, addDays(targetWorkout.workout.scheduledDate, -28))
				)
			);
		const weekStartDate = isoWeekStart(targetWorkout.workout.scheduledDate);
		const [effectiveWeek] = await tx
			.select({
				targetDistanceMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}) filter (where ${workout.type} not in ('rest', 'race')), 0)::int`
			})
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, targetWorkout.workout.planId),
					eq(workout.isRemoved, false),
					gte(workout.scheduledDate, weekStartDate),
					lte(workout.scheduledDate, addDays(weekStartDate, 6))
				)
			);

		const consequence = calculateConsequence({
			status: input.status,
			choice: input.choice,
			targetDistanceMeters: targetWorkout.workout.targetDistanceMeters,
			...(targetWorkout.workout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: targetWorkout.workout.targetDurationSeconds }),
			pain: input.pain,
			feltHard: input.feltHard,
			weekTargetDistanceMeters:
				effectiveWeek?.targetDistanceMeters ?? targetWorkout.week.targetDistanceMeters,
			recentMissedWorkouts: recentMisses?.count ?? 0,
			...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
			...(input.completedDurationSeconds === undefined
				? {}
				: { completedDurationSeconds: input.completedDurationSeconds })
		});
		const inferredStatus =
			input.status === 'skipped'
				? 'skipped'
				: consequence.deviation === 'short'
					? 'shortened'
					: 'done';

		const [updatedWorkout] = await tx
			.update(workout)
			.set({ status: inferredStatus, updatedAt: new Date() })
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, input.workoutId),
					eq(workout.status, 'planned'),
					eq(workout.isRemoved, false)
				)
			)
			.returning({ id: workout.id });
		if (!updatedWorkout) throw new Error('Workout is no longer available for feedback.');

		await tx.insert(workoutFeedback).values({
			userId,
			workoutId: input.workoutId,
			feltHard: input.feltHard,
			pain: input.pain,
			choice: input.choice,
			deviation: consequence.deviation,
			consequence,
			...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
			...(input.completedDurationSeconds === undefined
				? {}
				: { completedDurationSeconds: input.completedDurationSeconds })
		});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.feedback',
			detail: {
				workoutId: input.workoutId,
				status: input.status
			}
		});

		return consequence;
	});
}

export async function deleteWorkoutFeedback(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const [target] = await tx
			.select({
				feedbackId: workoutFeedback.id,
				workoutId: workout.id
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, workoutId)))
			.limit(1)
			.for('update', { of: workoutFeedback });
		if (!target) throw new Error('Workout feedback not found.');
		const [linkedActivity] = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.workoutId, workoutId)))
			.limit(1);
		if (linkedActivity) {
			throw new Error('Unlink or delete the recorded activity instead of deleting its feedback.');
		}

		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: target.feedbackId,
			originalTriggerTypes: ['feedback', 'decision'],
			reason: 'Deleting feedback restored only the workout changes derived from that feedback.'
		});
		await tx
			.delete(workoutFeedback)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, target.feedbackId)));
		await tx
			.update(workout)
			.set({ status: 'planned', updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)));
		await tx
			.delete(auditEvent)
			.where(
				and(
					eq(auditEvent.userId, userId),
					eq(auditEvent.eventType, 'workout.feedback'),
					sql`${auditEvent.detail} ->> 'workoutId' = ${workoutId}`
				)
			);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.feedback_deleted',
			detail: { workoutId }
		});
		return { workoutId };
	});
}

export async function applyConsequenceDecision(
	userId: string,
	input: {
		source: 'feedback' | 'activity';
		sourceId: string;
		decision: PlanDecision;
		confirmRisk: boolean;
	}
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const sourceLocked = await lockConsequenceDecisionSource(tx, userId, input);
		if (!sourceLocked) throw new Error('Plan-change proposal not found.');
		const source = await consequenceDecisionSource(tx, userId, input);
		if (!source) throw new Error('Plan-change proposal not found.');
		if (source.consequence.appliedDecision) {
			throw new Error('A decision has already been applied to this result.');
		}
		let consequence = withAppliedDecision(source.consequence, input.decision);
		const [lockedPlan] = await tx
			.select({ id: trainingPlan.id })
			.from(trainingPlan)
			.where(
				and(
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.id, source.planId),
					eq(trainingPlan.status, 'active')
				)
			)
			.limit(1)
			.for('update');
		if (!lockedPlan) throw new Error('This plan-change proposal is no longer current.');

		if (input.decision !== 'keep_plan') {
			const candidates = await tx
				.select({
					id: workout.id,
					planId: workout.planId,
					weekId: workout.weekId,
					scheduledDate: workout.scheduledDate,
					status: workout.status,
					targetDistanceMeters: workout.targetDistanceMeters,
					targetDurationSeconds: workout.targetDurationSeconds,
					prescriptionKind: workout.prescriptionKind,
					intervalStructure: workout.intervalStructure,
					type: workout.type,
					intensity: workout.intensity,
					purpose: workout.purpose,
					reason: workout.reason,
					sourceRefs: workout.sourceRefs,
					isRemoved: workout.isRemoved
				})
				.from(workout)
				.where(
					and(
						eq(workout.userId, userId),
						eq(workout.planId, source.planId),
						eq(workout.status, 'planned'),
						eq(workout.isRemoved, false),
						ne(workout.type, 'race'),
						ne(workout.type, 'rest'),
						gt(workout.scheduledDate, source.originDate),
						gte(workout.scheduledDate, today)
					)
				)
				.orderBy(asc(workout.scheduledDate), asc(workout.id))
				.limit(52);
			const firstCandidate = candidates[0];
			if (!firstCandidate) throw new Error('No future workout is available to change.');

			const affected =
				input.decision === 'rebalance_week'
					? candidates.filter(
							(candidate) =>
								candidate.scheduledDate <= addDays(isoWeekStart(source.originDate), 6) &&
								isConsequenceDecisionTargetCompatible(consequence, candidate)
						)
					: [firstCandidate];
			if (input.decision === 'rebalance_week' && affected.length === 0) {
				throw new Error('No compatible workouts remain in this week. Choose another option.');
			}
			const workoutsToChange = affected.length > 0 ? affected : [firstCandidate];
			if (input.decision === 'reduce_next') {
				const appliedEffect = calculateConsequenceDecisionEffect({
					consequence,
					decision: input.decision,
					target: {
						targetDistanceMeters: firstCandidate.targetDistanceMeters,
						targetDurationSeconds: firstCandidate.targetDurationSeconds
					}
				});
				if (!appliedEffect) throw new Error('The next workout has no amount that can be reduced.');
				consequence = {
					...consequence,
					nextRunAdjustment: {
						metric: appliedEffect.metric,
						value: appliedEffect.adjustment
					},
					nextRunAdjustmentMeters:
						appliedEffect.metric === 'distance' ? appliedEffect.adjustment : 0
				};
			}

			if (input.decision === 'repeat_prescription') {
				const [allWorkouts, weeks] = await Promise.all([
					tx
						.select()
						.from(workout)
						.where(and(eq(workout.userId, userId), eq(workout.planId, source.planId))),
					tx
						.select({
							id: trainingWeek.id,
							weekNumber: trainingWeek.weekNumber,
							startDate: trainingWeek.startDate
						})
						.from(trainingWeek)
						.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, source.planId)))
						.orderBy(asc(trainingWeek.weekNumber))
				]);
				const repeatedState = decisionWorkoutState({
					candidate: firstCandidate,
					originWorkout: source.originWorkout,
					decision: input.decision,
					consequence,
					shareCount: 1,
					index: 0
				});
				const preview = buildWorkoutEditPreview({
					current: firstCandidate,
					recommended: null,
					proposed: proposalFromWorkout({ ...firstCandidate, ...repeatedState }),
					workouts: allWorkouts.map(editableWorkout),
					weeks,
					today,
					rebalance: false
				});
				if (preview.requiresConfirmation && !input.confirmRisk) {
					throw new Error(
						'Review and confirm the elevated repeated prescription before applying it.'
					);
				}
			}

			for (const [index, candidate] of workoutsToChange.entries()) {
				const newState = decisionWorkoutState({
					candidate,
					originWorkout: source.originWorkout,
					decision: input.decision,
					consequence,
					shareCount: workoutsToChange.length,
					index
				});
				await tx
					.update(workout)
					.set({ ...newState, updatedAt: new Date() })
					.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
				await recordPlanAdjustment(tx, {
					userId,
					planId: source.planId,
					workoutId: candidate.id,
					triggerType: 'decision',
					triggerId: input.sourceId,
					previousState: workoutAdjustmentState(candidate),
					newState,
					consequence,
					reason: `Applied decision: ${input.decision}.`
				});
			}
		}

		if (input.source === 'feedback') {
			await tx
				.update(workoutFeedback)
				.set({ appliedDecision: input.decision, consequence })
				.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)));
		} else {
			await tx
				.update(activity)
				.set({ appliedDecision: input.decision, consequence })
				.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)));
		}

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'plan.decision_applied',
			detail: {
				source: input.source,
				sourceId: input.sourceId,
				...(input.source === 'activity' ? { activityId: input.sourceId } : {}),
				decision: input.decision
			}
		});
		return consequence;
	});
}

async function lockConsequenceDecisionSource(
	tx: RunwayTransaction,
	userId: string,
	input: { source: 'feedback' | 'activity'; sourceId: string }
) {
	if (input.source === 'feedback') {
		const [record] = await tx
			.select({ id: workoutFeedback.id })
			.from(workoutFeedback)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)))
			.limit(1)
			.for('update');
		return Boolean(record);
	}
	const [record] = await tx
		.select({ id: activity.id })
		.from(activity)
		.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)))
		.limit(1)
		.for('update');
	return Boolean(record);
}

async function consequenceDecisionSource(
	tx: RunwayTransaction,
	userId: string,
	input: { source: 'feedback' | 'activity'; sourceId: string }
) {
	if (input.source === 'feedback') {
		const [record] = await tx
			.select({
				consequence: workoutFeedback.consequence,
				planId: workout.planId,
				originDate: workout.scheduledDate,
				originWorkout: workout
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.innerJoin(
				trainingPlan,
				and(
					eq(workout.planId, trainingPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)))
			.limit(1);
		return record ?? null;
	}

	const [record] = await tx
		.select({
			consequence: activity.consequence,
			consequencePlanId: activity.consequencePlanId,
			originDate: activity.activityDate,
			linkedWorkout: workout
		})
		.from(activity)
		.leftJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
		.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)))
		.limit(1);
	if (!record?.consequence || !record.consequencePlanId) return null;
	const [active] = await tx
		.select({ id: trainingPlan.id })
		.from(trainingPlan)
		.where(
			and(
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.id, record.consequencePlanId),
				eq(trainingPlan.status, 'active')
			)
		)
		.limit(1);
	if (!active) return null;
	if (record.linkedWorkout) {
		if (record.linkedWorkout.planId !== active.id) return null;
		return {
			consequence: record.consequence,
			planId: active.id,
			originDate: record.originDate,
			originWorkout: record.linkedWorkout
		};
	}
	return {
		consequence: record.consequence,
		planId: active.id,
		originDate: record.originDate,
		originWorkout: null
	};
}

function decisionWorkoutState(input: {
	candidate: WorkoutStateRecord;
	originWorkout: WorkoutStateRecord | null;
	decision: Exclude<PlanDecision, 'keep_plan'>;
	consequence: ConsequenceResult;
	shareCount: number;
	index: number;
}): WorkoutAdjustmentState {
	const { candidate, originWorkout, decision, consequence, shareCount } = input;
	if (decision === 'next_rest') {
		return changedWorkoutState(candidate, {
			type: 'rest',
			prescriptionKind: 'rest',
			targetDistanceMeters: 0,
			targetDurationSeconds: null,
			intervalStructure: null,
			intensity: 'rest',
			purpose: 'Recovery day',
			reason: 'The runner explicitly chose rest after reviewing the recorded result.'
		});
	}
	if (decision === 'repeat_prescription') {
		if (!originWorkout || originWorkout.type === 'race' || originWorkout.type === 'rest') {
			throw new Error('This result has no prescription that can be repeated.');
		}
		return changedWorkoutState(candidate, {
			type: originWorkout.type,
			prescriptionKind:
				originWorkout.prescriptionKind ??
				(originWorkout.targetDurationSeconds ? 'timed' : 'distance'),
			targetDistanceMeters: originWorkout.targetDistanceMeters,
			targetDurationSeconds: originWorkout.targetDurationSeconds,
			intervalStructure: originWorkout.intervalStructure ?? null,
			intensity: originWorkout.intensity,
			purpose: originWorkout.purpose,
			reason: 'The runner explicitly chose to repeat the earlier prescription.',
			sourceRefs: originWorkout.sourceRefs
		});
	}

	const effect = calculateConsequenceDecisionEffect({
		consequence,
		decision,
		target: {
			targetDistanceMeters: candidate.targetDistanceMeters,
			targetDurationSeconds: candidate.targetDurationSeconds
		},
		shareCount
	});
	if (!effect) throw new Error('This workout has no amount that can be reduced.');

	if (effect.metric === 'duration') {
		return changedWorkoutState(candidate, {
			targetDurationSeconds: effect.newTarget,
			intervalStructure: resizeTimedIntervalStructure(
				candidate.intervalStructure ?? null,
				effect.newTarget
			),
			reason: 'The runner explicitly reduced this timed workout after reviewing a result.'
		});
	}

	return changedWorkoutState(candidate, {
		targetDistanceMeters: effect.newTarget,
		reason:
			decision === 'rebalance_week'
				? 'The runner explicitly rebalanced the remaining week.'
				: 'The runner explicitly reduced this workout after reviewing a result.'
	});
}
