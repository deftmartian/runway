import { and, asc, eq, gt, gte, isNull, lte, ne, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	activityDeletionTombstone,
	activityImport,
	androidDevice,
	androidImportRequest,
	androidPairingRequest,
	athleteProfile,
	auditEvent,
	importSource,
	importSourceItem,
	planAdjustment,
	trainingPlan,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { addDays, toIsoDateInTimeZone, todayIsoInTimeZone } from '$lib/training/date';
import { calculateConsequence } from '$lib/training/consequences';
import { buildActivityRouteTrace, buildHeartRateSeries } from '$lib/training/activity-trace';
import { selectAutoWorkoutMatch } from '$lib/training/activity-match';
import { summarizeHeartRateEffort } from '$lib/training/heart-rate';
import type { ConsequenceResult, ParsedGpxActivity } from '$lib/training/types';
import type { RunwayTransaction } from './transaction';
import { changedWorkoutState, workoutAdjustmentState } from './workout-state';
import { requireAthleteTimeZoneInTransaction } from './profiles';
import { effectiveWeekTargetDistance, planWeekIdForDate } from './schedule-queries';
import {
	recordPlanAdjustment,
	replayWorkoutLedgers,
	reverseLedgerAdjustmentsForTrigger
} from './adjustment-ledger';
import { lockActivityOwner } from './mutation-locks';
import { calculateUnplannedConsequence } from './extra-activity-mutations';

export async function linkActivityToWorkout(
	userId: string,
	input: { activityId: string; workoutId: string }
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const [targetPlanReference] = await tx
			.select({ planId: workout.planId })
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(
					eq(workout.planId, trainingPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.where(and(eq(workout.userId, userId), eq(workout.id, input.workoutId)))
			.limit(1);
		if (!targetPlanReference) throw new Error('Workout is not available for linking.');
		const [lockedPlan] = await tx
			.select({ id: trainingPlan.id })
			.from(trainingPlan)
			.where(
				and(
					eq(trainingPlan.id, targetPlanReference.planId),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.limit(1)
			.for('update');
		if (!lockedPlan) throw new Error('Workout is not available for linking.');
		const [targetActivity] = await tx
			.select({
				id: activity.id,
				source: activity.source,
				workoutId: activity.workoutId,
				activityDate: activity.activityDate,
				distanceMeters: activity.distanceMeters,
				durationSeconds: activity.durationSeconds,
				heartRateSummary: activity.heartRateSummary,
				heartRateSeries: activity.heartRateSeries,
				routeTrace: activity.routeTrace,
				feltHard: activity.feltHard,
				pain: activity.pain,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed
			})
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, input.activityId)))
			.limit(1)
			.for('update');
		if (!targetActivity) throw new Error('Activity not found.');
		if (targetActivity.workoutId) throw new Error('Activity is already linked.');

		const [targetWorkout] = await tx
			.select({
				id: workout.id,
				planId: workout.planId,
				planStartDate: trainingPlan.startDate,
				weekId: workout.weekId,
				scheduledDate: workout.scheduledDate,
				type: workout.type,
				status: workout.status,
				targetDistanceMeters: workout.targetDistanceMeters,
				targetDurationSeconds: workout.targetDurationSeconds,
				prescriptionKind: workout.prescriptionKind,
				intervalStructure: workout.intervalStructure,
				intensity: workout.intensity,
				purpose: workout.purpose,
				reason: workout.reason,
				sourceRefs: workout.sourceRefs
			})
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(
					eq(workout.planId, trainingPlan.id),
					eq(trainingPlan.id, lockedPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.leftJoin(
				workoutFeedback,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
			)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, input.workoutId),
					ne(workout.type, 'rest'),
					eq(workout.status, 'planned'),
					eq(workout.isRemoved, false),
					isNull(workoutFeedback.id)
				)
			)
			.limit(1)
			.for('update', { of: workout });
		if (!targetWorkout) throw new Error('Workout is not available for linking.');

		const [existingActivity] = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.workoutId, targetWorkout.id)))
			.limit(1);
		if (existingActivity) throw new Error('That workout already has an activity.');

		const activityDate = targetActivity.activityDate;
		if (
			targetWorkout.scheduledDate < addDays(activityDate, -3) ||
			targetWorkout.scheduledDate > addDays(activityDate, 3)
		) {
			throw new Error('Workout is outside the activity match window.');
		}
		const targetWeekId = await planWeekIdForDate(tx, userId, targetWorkout.planId, activityDate);
		if (!targetWeekId) {
			throw new Error('Activity date is outside the active plan weeks.');
		}
		const effectiveWeekTarget = await effectiveWeekTargetDistance(
			tx,
			userId,
			targetWorkout.planId,
			activityDate
		);
		const calculatedConsequence = calculateConsequence({
			status: 'done',
			choice: 'reduce_next',
			targetDistanceMeters: targetWorkout.targetDistanceMeters,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(targetWorkout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: targetWorkout.targetDurationSeconds }),
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			pain: targetActivity.pain,
			feltHard: targetActivity.feltHard,
			weekTargetDistanceMeters: Math.max(effectiveWeekTarget, targetWorkout.targetDistanceMeters)
		});
		const canAffectCurrentPlan =
			activityDate >= addDays(today, -7) && activityDate >= targetWorkout.planStartDate;
		const planConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
		const feedbackConsequence = planConsequence ?? historicalLinkConsequence(calculatedConsequence);
		const linkedState = changedWorkoutState(targetWorkout, {
			weekId: targetWeekId,
			scheduledDate: activityDate,
			status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done'
		});

		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['manual', 'import_extra', 'decision'],
			reason: 'Linking replaced the unplanned-run adjustment with the linked-workout result.'
		});

		const [linkedActivity] = await tx
			.update(activity)
			.set({
				workoutId: targetWorkout.id,
				reviewState: 'accepted',
				deviation: calculatedConsequence.deviation,
				consequence: planConsequence,
				consequencePlanId: planConsequence ? targetWorkout.planId : null
			})
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.id, targetActivity.id),
					isNull(activity.workoutId)
				)
			)
			.returning({ id: activity.id });
		if (!linkedActivity) throw new Error('Activity is no longer available for linking.');
		const [completedWorkout] = await tx
			.update(workout)
			.set({ ...linkedState, updatedAt: new Date() })
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, targetWorkout.id),
					eq(workout.status, 'planned'),
					eq(workout.isRemoved, false)
				)
			)
			.returning({ id: workout.id });
		if (!completedWorkout) throw new Error('Workout is no longer available for linking.');
		await recordPlanAdjustment(tx, {
			userId,
			planId: targetWorkout.planId,
			workoutId: targetWorkout.id,
			triggerType: 'link',
			triggerId: targetActivity.id,
			previousState: workoutAdjustmentState(targetWorkout),
			newState: linkedState,
			consequence: planConsequence,
			reason:
				targetWorkout.scheduledDate === activityDate
					? 'Activity completed this planned run.'
					: 'Activity completed and moved this planned run onto the day it occurred.'
		});
		await tx.insert(workoutFeedback).values({
			userId,
			workoutId: targetWorkout.id,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			feltHard: targetActivity.feltHard,
			pain: targetActivity.pain,
			choice: 'reduce_next',
			deviation: feedbackConsequence.deviation,
			consequence: feedbackConsequence
		});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.linked',
			detail: {
				activityId: targetActivity.id,
				workoutId: targetWorkout.id
			}
		});

		return feedbackConsequence;
	});
}

export async function unlinkActivityFromWorkout(userId: string, activityId: string) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const [targetActivity] = await tx
			.select({
				id: activity.id,
				source: activity.source,
				workoutId: activity.workoutId,
				activityDate: activity.activityDate,
				distanceMeters: activity.distanceMeters,
				durationSeconds: activity.durationSeconds,
				feltHard: activity.feltHard,
				pain: activity.pain,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed
			})
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, activityId)))
			.limit(1)
			.for('update');
		if (!targetActivity) throw new Error('Activity not found.');
		if (!targetActivity.workoutId) throw new Error('Activity is not linked.');
		const [lockedWorkout] = await tx
			.select({ id: workout.id })
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.id, targetActivity.workoutId)))
			.limit(1)
			.for('update');
		if (!lockedWorkout) throw new Error('Linked workout not found.');
		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['link', 'import_match', 'decision'],
			reason: 'Unlink restored every active workout change made by this link.'
		});

		await tx
			.delete(workoutFeedback)
			.where(
				and(
					eq(workoutFeedback.userId, userId),
					eq(workoutFeedback.workoutId, targetActivity.workoutId)
				)
			);
		const [unlinkedActivity] = await tx
			.update(activity)
			.set({
				workoutId: null,
				reviewState:
					targetActivity.source === 'gpx' && !targetActivity.extraPlanImpactConfirmed
						? 'review'
						: 'accepted',
				deviation: 'unplanned',
				appliedDecision: null,
				consequence: null,
				consequencePlanId: null
			})
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.id, targetActivity.id),
					eq(activity.workoutId, targetActivity.workoutId)
				)
			)
			.returning({ id: activity.id });
		if (!unlinkedActivity) throw new Error('Activity is no longer linked to this workout.');
		const consequence = await calculateUnplannedConsequence(tx, userId, targetActivity, today);

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.unlinked',
			detail: {
				activityId: targetActivity.id,
				workoutId: targetActivity.workoutId
			}
		});
		return consequence;
	});
}

export async function updateActivityFeedback(
	userId: string,
	activityId: string,
	input: { feltHard: boolean; pain: boolean }
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const [targetActivity] = await tx
			.select({
				id: activity.id,
				source: activity.source,
				workoutId: activity.workoutId,
				activityDate: activity.activityDate,
				distanceMeters: activity.distanceMeters,
				durationSeconds: activity.durationSeconds,
				feltHard: activity.feltHard,
				pain: activity.pain,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed
			})
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, activityId)))
			.limit(1)
			.for('update');
		if (!targetActivity) throw new Error('Activity not found.');

		if (!targetActivity.workoutId) {
			await reverseLedgerAdjustmentsForTrigger(tx, {
				userId,
				triggerId: targetActivity.id,
				originalTriggerTypes: ['manual', 'import_extra', 'decision'],
				reason: 'Updating activity feedback replaced the earlier extra-run adjustment.'
			});
			await tx
				.update(activity)
				.set({
					feltHard: input.feltHard,
					pain: input.pain,
					appliedDecision: null,
					consequence: null,
					consequencePlanId: null
				})
				.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
			const consequence = await calculateUnplannedConsequence(
				tx,
				userId,
				{ ...targetActivity, ...input },
				today
			);
			await tx.insert(auditEvent).values({
				userId,
				eventType: 'activity.feedback_updated',
				detail: { activityId: targetActivity.id, linked: false }
			});
			return consequence;
		}

		const [linkedWorkout] = await tx
			.select({
				id: workout.id,
				planId: workout.planId,
				planStartDate: trainingPlan.startDate,
				planStatus: trainingPlan.status,
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
				sourceRefs: workout.sourceRefs
			})
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(eq(workout.planId, trainingPlan.id), eq(trainingPlan.userId, userId))
			)
			.where(and(eq(workout.userId, userId), eq(workout.id, targetActivity.workoutId)))
			.limit(1)
			.for('update', { of: workout });
		if (!linkedWorkout) throw new Error('Linked workout not found.');

		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['link', 'import_match', 'decision'],
			excludeWorkoutIds: [linkedWorkout.id],
			reason: 'Updating activity feedback replaced its earlier next-run adjustment.'
		});

		const effectiveWeekTarget = await effectiveWeekTargetDistance(
			tx,
			userId,
			linkedWorkout.planId,
			targetActivity.activityDate
		);
		const calculatedConsequence = calculateConsequence({
			status: 'done',
			choice: 'reduce_next',
			targetDistanceMeters: linkedWorkout.targetDistanceMeters,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(linkedWorkout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: linkedWorkout.targetDurationSeconds }),
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			pain: input.pain,
			feltHard: input.feltHard,
			weekTargetDistanceMeters: Math.max(effectiveWeekTarget, linkedWorkout.targetDistanceMeters)
		});
		const canAffectCurrentPlan =
			linkedWorkout.planStatus === 'active' &&
			targetActivity.activityDate >= addDays(today, -7) &&
			targetActivity.activityDate >= linkedWorkout.planStartDate;
		const planConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
		const feedbackConsequence = planConsequence ?? historicalLinkConsequence(calculatedConsequence);
		await tx
			.update(activity)
			.set({
				feltHard: input.feltHard,
				pain: input.pain,
				deviation: calculatedConsequence.deviation,
				appliedDecision: null,
				consequence: planConsequence,
				consequencePlanId: planConsequence ? linkedWorkout.planId : null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		await tx
			.update(workoutFeedback)
			.set({
				feltHard: input.feltHard,
				pain: input.pain,
				appliedDecision: null,
				deviation: feedbackConsequence.deviation,
				consequence: feedbackConsequence
			})
			.where(
				and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, linkedWorkout.id))
			);
		await tx
			.update(workout)
			.set({
				status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done',
				updatedAt: new Date()
			})
			.where(and(eq(workout.userId, userId), eq(workout.id, linkedWorkout.id)));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.feedback_updated',
			detail: { activityId: targetActivity.id, linked: true }
		});
		return feedbackConsequence;
	});
}

export async function deleteActivityRecord(userId: string, activityId: string) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const [targetActivity] = await tx
			.select({
				id: activity.id,
				workoutId: activity.workoutId,
				source: activity.source,
				fileHash: activityImport.fileHash
			})
			.from(activity)
			.leftJoin(
				activityImport,
				and(eq(activityImport.activityId, activity.id), eq(activityImport.userId, userId))
			)
			.where(and(eq(activity.userId, userId), eq(activity.id, activityId)))
			.limit(1)
			.for('update', { of: activity });
		if (!targetActivity) throw new Error('Activity not found.');
		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['manual', 'import_match', 'import_extra', 'link', 'decision'],
			reason: 'Deleting the activity restored every active workout change derived from it.'
		});
		if (targetActivity.workoutId) {
			await tx
				.delete(workoutFeedback)
				.where(
					and(
						eq(workoutFeedback.userId, userId),
						eq(workoutFeedback.workoutId, targetActivity.workoutId)
					)
				);
		}
		if (targetActivity.fileHash) {
			await tx
				.insert(activityDeletionTombstone)
				.values({ userId, fileHash: targetActivity.fileHash })
				.onConflictDoNothing();
		}
		await tx
			.delete(auditEvent)
			.where(
				and(
					eq(auditEvent.userId, userId),
					sql`${auditEvent.detail} ->> 'activityId' = ${targetActivity.id}`
				)
			);
		await tx
			.update(planAdjustment)
			.set({ triggerId: null, consequence: null, reason: 'Deleted activity adjustment.' })
			.where(
				and(eq(planAdjustment.userId, userId), eq(planAdjustment.triggerId, targetActivity.id))
			);

		// Keep the keyed remote-item marker so a connected source does not fetch the
		// same file again. The marker contains no reversible path, and clearing the
		// activity reference lets the private activity itself be deleted.
		await tx
			.update(importSourceItem)
			.set({ activityId: null })
			.where(
				and(eq(importSourceItem.userId, userId), eq(importSourceItem.activityId, targetActivity.id))
			);
		await tx
			.delete(activityImport)
			.where(
				and(eq(activityImport.userId, userId), eq(activityImport.activityId, targetActivity.id))
			);
		await tx
			.delete(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.deleted',
			detail: {
				source: targetActivity.source
			}
		});
	});
}

export async function deleteActivityData(userId: string) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const now = new Date();
		const [activityCountRow] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.source, 'gpx')));
		const [sourceCountRow] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(importSource)
			.where(eq(importSource.userId, userId));
		const [activeDeviceCountRow] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(androidDevice)
			.where(and(eq(androidDevice.userId, userId), isNull(androidDevice.revokedAt)));
		const deletedActivityCount = activityCountRow?.count ?? 0;
		const deletedSourceCount = sourceCountRow?.count ?? 0;
		const revokedAndroidDeviceCount = activeDeviceCountRow?.count ?? 0;

		await tx
			.update(athleteProfile)
			.set({
				activityImportGeneration: sql`${athleteProfile.activityImportGeneration} + 1`,
				updatedAt: now
			})
			.where(eq(athleteProfile.userId, userId));

		await tx.delete(importSource).where(eq(importSource.userId, userId));
		await tx.delete(androidPairingRequest).where(eq(androidPairingRequest.userId, userId));
		await tx
			.update(androidDevice)
			.set({ revokedAt: now, updatedAt: now })
			.where(and(eq(androidDevice.userId, userId), isNull(androidDevice.revokedAt)));
		await tx.delete(androidImportRequest).where(eq(androidImportRequest.userId, userId));

		await tx.execute(sql`
			insert into ${activityDeletionTombstone} (user_id, file_hash)
			select distinct ${userId}, imported.file_hash
			from ${activityImport} as imported
			inner join ${activity} as imported_activity
				on imported_activity.id = imported.activity_id
				and imported_activity.user_id = imported.user_id
			where imported.user_id = ${userId}
				and imported_activity.source = 'gpx'
			on conflict (user_id, file_hash) do nothing
		`);

		const importedActivityTriggerExists = sql`exists (
			select 1
			from ${activity} as imported_activity
			where imported_activity.user_id = ${userId}
				and imported_activity.source = 'gpx'
				and imported_activity.id = ${planAdjustment.triggerId}
		)`;
		await tx
			.update(planAdjustment)
			.set({ reversedAt: now, reversalReason: 'Imported activity data was deleted.' })
			.where(
				and(
					eq(planAdjustment.userId, userId),
					isNull(planAdjustment.reversedAt),
					importedActivityTriggerExists
				)
			);

		const replayBatchSize = 100;
		let lastWorkoutId: string | null = null;
		for (;;) {
			const affectedWorkouts = await tx
				.selectDistinct({ workoutId: planAdjustment.workoutId })
				.from(planAdjustment)
				.innerJoin(
					activity,
					and(
						eq(activity.id, planAdjustment.triggerId),
						eq(activity.userId, userId),
						eq(activity.source, 'gpx')
					)
				)
				.where(
					and(
						eq(planAdjustment.userId, userId),
						...(lastWorkoutId ? [gt(planAdjustment.workoutId, lastWorkoutId)] : [])
					)
				)
				.orderBy(asc(planAdjustment.workoutId))
				.limit(replayBatchSize);
			if (affectedWorkouts.length === 0) break;
			const workoutIds = affectedWorkouts.map(({ workoutId }) => workoutId);
			await replayWorkoutLedgers(tx, userId, workoutIds);
			lastWorkoutId = workoutIds.at(-1) ?? null;
			if (affectedWorkouts.length < replayBatchSize) break;
		}

		await tx.delete(auditEvent).where(
			and(
				eq(auditEvent.userId, userId),
				sql`exists (
						select 1
						from ${activity} as imported_activity
						where imported_activity.user_id = ${userId}
							and imported_activity.source = 'gpx'
							and imported_activity.id::text = ${auditEvent.detail} ->> 'activityId'
					)`
			)
		);
		await tx
			.update(planAdjustment)
			.set({ triggerId: null, consequence: null, reason: 'Deleted activity adjustment.' })
			.where(and(eq(planAdjustment.userId, userId), importedActivityTriggerExists));
		await tx.delete(workoutFeedback).where(
			and(
				eq(workoutFeedback.userId, userId),
				sql`exists (
						select 1
						from ${activity} as imported_activity
						where imported_activity.user_id = ${userId}
							and imported_activity.source = 'gpx'
							and imported_activity.workout_id = ${workoutFeedback.workoutId}
					)`
			)
		);

		await tx.delete(activityImport).where(eq(activityImport.userId, userId));
		await tx.delete(importSourceItem).where(eq(importSourceItem.userId, userId));
		await tx.delete(activity).where(and(eq(activity.userId, userId), eq(activity.source, 'gpx')));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.deleted',
			detail: {
				count: deletedActivityCount,
				disconnectedImportSources: deletedSourceCount,
				disconnectedAndroidDevices: revokedAndroidDeviceCount
			}
		});

		return {
			count: deletedActivityCount,
			disconnectedImportSources: deletedSourceCount,
			disconnectedAndroidDevices: revokedAndroidDeviceCount
		};
	});
}

export async function recordImportedActivity(
	userId: string,
	fileHash: string,
	parsed: ParsedGpxActivity,
	matching: { mode: 'unlinked' } | { mode: 'auto' } | { mode: 'workout'; workoutId: string },
	expectedImportGeneration: number
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		return recordImportedActivityInTransaction(
			tx,
			userId,
			fileHash,
			parsed,
			matching,
			expectedImportGeneration
		);
	});
}

export async function recordImportedActivityInTransaction(
	tx: RunwayTransaction,
	userId: string,
	fileHash: string,
	parsed: ParsedGpxActivity,
	matching: { mode: 'unlinked' } | { mode: 'auto' } | { mode: 'workout'; workoutId: string },
	expectedImportGeneration: number
) {
	const requestedWorkoutId = matching.mode === 'workout' ? matching.workoutId : undefined;
	const timeZone = await requireAthleteTimeZoneInTransaction(
		tx,
		userId,
		'Set training time zone before importing.'
	);
	const today = todayIsoInTimeZone(timeZone);
	const [importProfile] = await tx
		.select({ generation: athleteProfile.activityImportGeneration })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1)
		.for('update');
	if (importProfile?.generation !== expectedImportGeneration) {
		throw new Error('Import was cancelled because activity data was deleted.');
	}
	const [[existingImport], [deletedImport]] = await Promise.all([
		tx
			.select({ id: activityImport.id })
			.from(activityImport)
			.where(and(eq(activityImport.userId, userId), eq(activityImport.fileHash, fileHash)))
			.limit(1),
		tx
			.select({ id: activityDeletionTombstone.id })
			.from(activityDeletionTombstone)
			.where(
				and(
					eq(activityDeletionTombstone.userId, userId),
					eq(activityDeletionTombstone.fileHash, fileHash)
				)
			)
			.limit(1)
	]);
	if (existingImport) throw new Error('This activity file has already been imported.');
	if (deletedImport) throw new Error('This deleted activity file cannot be imported again.');

	const activityDate = toIsoDateInTimeZone(parsed.startedAt, timeZone);
	if (activityDate > today) {
		throw new Error('Imported activities cannot be in the future.');
	}
	if (requestedWorkoutId) {
		const [existingActivity] = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.workoutId, requestedWorkoutId)))
			.limit(1);
		if (existingActivity) {
			throw new Error('This workout already has an imported activity.');
		}
	}
	const [lockedMatchingPlan] =
		matching.mode === 'unlinked'
			? []
			: await tx
					.select({ id: trainingPlan.id })
					.from(trainingPlan)
					.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
					.limit(1)
					.for('update');

	const candidateWorkouts =
		matching.mode === 'unlinked' || !lockedMatchingPlan
			? []
			: await tx
					.select({
						id: workout.id,
						planId: workout.planId,
						planStartDate: trainingPlan.startDate,
						weekId: workout.weekId,
						scheduledDate: workout.scheduledDate,
						type: workout.type,
						status: workout.status,
						targetDistanceMeters: workout.targetDistanceMeters,
						targetDurationSeconds: workout.targetDurationSeconds,
						prescriptionKind: workout.prescriptionKind,
						intervalStructure: workout.intervalStructure,
						intensity: workout.intensity,
						purpose: workout.purpose,
						reason: workout.reason,
						sourceRefs: workout.sourceRefs
					})
					.from(workout)
					.innerJoin(
						trainingPlan,
						and(
							eq(workout.planId, trainingPlan.id),
							eq(trainingPlan.id, lockedMatchingPlan.id),
							eq(trainingPlan.userId, userId),
							eq(trainingPlan.status, 'active')
						)
					)
					.leftJoin(
						workoutFeedback,
						and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
					)
					.where(
						and(
							eq(workout.userId, userId),
							ne(workout.type, 'rest'),
							eq(workout.status, 'planned'),
							eq(workout.isRemoved, false),
							gte(workout.scheduledDate, addDays(activityDate, -3)),
							lte(workout.scheduledDate, addDays(activityDate, 3)),
							isNull(workoutFeedback.id),
							...(requestedWorkoutId ? [eq(workout.id, requestedWorkoutId)] : [])
						)
					)
					.limit(requestedWorkoutId ? 1 : 20)
					.for('update', { of: workout });
	const matchedWorkoutId = requestedWorkoutId
		? candidateWorkouts[0]?.id
		: matching.mode === 'auto'
			? (selectAutoWorkoutMatch(
					{
						activityDate,
						distanceMeters: parsed.distanceMeters,
						durationSeconds: parsed.durationSeconds
					},
					candidateWorkouts
				) ?? undefined)
			: undefined;
	const matchedWorkout = matchedWorkoutId
		? candidateWorkouts.find((candidate) => candidate.id === matchedWorkoutId)
		: undefined;
	if (requestedWorkoutId && !matchedWorkoutId) {
		throw new Error('Selected workout is not available for this activity import.');
	}
	const matchedTargetWeekId = matchedWorkout
		? await planWeekIdForDate(tx, userId, matchedWorkout.planId, activityDate)
		: null;
	if (matchedWorkout && !matchedTargetWeekId) {
		throw new Error('Activity date is outside the active plan weeks.');
	}

	const [profile] = await tx
		.select({
			heartRateSettings: athleteProfile.heartRateSettings,
			routeDataMode: athleteProfile.routeDataMode
		})
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	const heartRateSummary = summarizeHeartRateEffort(parsed, profile?.heartRateSettings);
	const heartRateSeries = buildHeartRateSeries(parsed);
	const routeTrace = profile?.routeDataMode === 'private' ? buildActivityRouteTrace(parsed) : null;
	// Zone occupancy is descriptive; it cannot stand in for the athlete's
	// subjective report that a run felt hard.
	const feltHardFromHeartRate = false;

	let createdActivity;
	try {
		[createdActivity] = await tx
			.insert(activity)
			.values({
				userId,
				...(matchedWorkoutId ? { workoutId: matchedWorkoutId } : {}),
				source: 'gpx',
				reviewState: matchedWorkoutId ? 'accepted' : 'review',
				occurredAt: parsed.startedAt,
				activityDate,
				distanceMeters: parsed.distanceMeters,
				durationSeconds: parsed.durationSeconds,
				averagePaceSecondsPerKm:
					parsed.distanceMeters > 0
						? parsed.durationSeconds / (parsed.distanceMeters / 1_000)
						: undefined,
				averageHeartRate: parsed.averageHeartRate,
				maxHeartRate: parsed.maxHeartRate,
				heartRateSummary,
				heartRateSeries,
				routeTrace,
				feltHard: feltHardFromHeartRate,
				pain: false,
				averageCadence: parsed.averageCadence,
				routeSummary: {
					pointCount: parsed.pointCount,
					startEndRedacted: routeTrace === null,
					hasElevation: parsed.hasElevation,
					traceRetained: routeTrace !== null
				}
			})
			.returning();
	} catch (error) {
		if (isActivityWorkoutConstraint(error)) {
			throw new Error('This workout already has an imported activity.', { cause: error });
		}
		throw error;
	}
	if (!createdActivity) throw new Error('Failed to record imported activity.');

	let importConsequence: ConsequenceResult | null = null;
	let importDeviation: ConsequenceResult['deviation'] = 'unplanned';
	if (matchedWorkout) {
		if (!matchedTargetWeekId) {
			throw new Error('Activity date is outside the active plan weeks.');
		}
		const effectiveWeekTarget = await effectiveWeekTargetDistance(
			tx,
			userId,
			matchedWorkout.planId,
			activityDate
		);
		const calculatedConsequence = calculateConsequence({
			status: 'done',
			choice: 'reduce_next',
			targetDistanceMeters: matchedWorkout.targetDistanceMeters,
			completedDistanceMeters: parsed.distanceMeters,
			...(matchedWorkout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: matchedWorkout.targetDurationSeconds }),
			completedDurationSeconds: parsed.durationSeconds,
			pain: false,
			feltHard: feltHardFromHeartRate,
			weekTargetDistanceMeters: Math.max(effectiveWeekTarget, matchedWorkout.targetDistanceMeters)
		});
		importDeviation = calculatedConsequence.deviation;
		const canAffectCurrentPlan =
			activityDate >= addDays(today, -7) && activityDate >= matchedWorkout.planStartDate;
		importConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
		const feedbackConsequence =
			importConsequence ?? historicalLinkConsequence(calculatedConsequence);
		const completedState = changedWorkoutState(matchedWorkout, {
			weekId: matchedTargetWeekId,
			scheduledDate: activityDate,
			status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done'
		});
		const [completedWorkout] = await tx
			.update(workout)
			.set({ ...completedState, updatedAt: new Date() })
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, matchedWorkout.id),
					eq(workout.status, 'planned'),
					eq(workout.isRemoved, false)
				)
			)
			.returning({ id: workout.id });
		if (!completedWorkout) {
			throw new Error('Selected workout is no longer available for this activity import.');
		}
		await recordPlanAdjustment(tx, {
			userId,
			planId: matchedWorkout.planId,
			workoutId: matchedWorkout.id,
			triggerType: 'import_match',
			triggerId: createdActivity.id,
			previousState: workoutAdjustmentState(matchedWorkout),
			newState: completedState,
			consequence: importConsequence,
			reason:
				matchedWorkout.scheduledDate === activityDate
					? 'Imported activity completed this planned run.'
					: 'Imported activity completed and moved this run onto the day it occurred.'
		});

		await tx.insert(workoutFeedback).values({
			userId,
			workoutId: matchedWorkout.id,
			completedDistanceMeters: parsed.distanceMeters,
			completedDurationSeconds: parsed.durationSeconds,
			feltHard: feltHardFromHeartRate,
			pain: false,
			choice: 'reduce_next',
			deviation: feedbackConsequence.deviation,
			consequence: feedbackConsequence
		});
	}
	await tx
		.update(activity)
		.set({
			deviation: importDeviation,
			consequence: importConsequence,
			consequencePlanId: importConsequence && matchedWorkout ? matchedWorkout.planId : null
		})
		.where(and(eq(activity.userId, userId), eq(activity.id, createdActivity.id)));

	try {
		await tx.insert(activityImport).values({
			userId,
			activityId: createdActivity.id,
			fileHash,
			result: 'imported',
			metadata: {
				pointCount: parsed.pointCount,
				hasHeartRate: parsed.hasHeartRate,
				hasCadence: parsed.hasCadence,
				hasSpeed: parsed.hasSpeed
			}
		});
	} catch (error) {
		if (isDuplicateImportConstraint(error)) {
			throw new Error('This activity file has already been imported.', { cause: error });
		}
		throw error;
	}

	await tx.insert(auditEvent).values({
		userId,
		eventType: 'activity.imported',
		detail: {
			activityId: createdActivity.id,
			source: 'gpx',
			matchedWorkout: Boolean(matchedWorkoutId)
		}
	});

	return { ...createdActivity, importConsequence };
}

export type NextcloudImportClaim = {
	sourceId: string;
	itemId: string;
	contentHash: string;
	claimedAt: Date;
};

export async function recordNextcloudImportedActivity(
	userId: string,
	claim: NextcloudImportClaim,
	parsed: ParsedGpxActivity,
	expectedImportGeneration: number
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const [activeClaim] = await tx
			.select({ sourceId: importSource.id, itemId: importSourceItem.id })
			.from(importSource)
			.innerJoin(
				importSourceItem,
				and(eq(importSourceItem.sourceId, importSource.id), eq(importSourceItem.userId, userId))
			)
			.where(
				and(
					eq(importSource.id, claim.sourceId),
					eq(importSource.userId, userId),
					eq(importSource.enabled, true),
					eq(importSourceItem.id, claim.itemId),
					eq(importSourceItem.status, 'importing'),
					eq(importSourceItem.contentHash, claim.contentHash),
					eq(importSourceItem.lastCheckedAt, claim.claimedAt)
				)
			)
			.limit(1)
			.for('update');
		if (!activeClaim) {
			throw new Error('Import was cancelled because the source was disconnected.');
		}

		const imported = await recordImportedActivityInTransaction(
			tx,
			userId,
			claim.contentHash,
			parsed,
			{ mode: 'unlinked' },
			expectedImportGeneration
		);
		const importedAt = new Date();
		const [completedItem] = await tx
			.update(importSourceItem)
			.set({
				status: 'imported',
				activityId: imported.id,
				importedAt,
				lastCheckedAt: importedAt,
				errorSummary: null
			})
			.where(
				and(
					eq(importSourceItem.id, claim.itemId),
					eq(importSourceItem.userId, userId),
					eq(importSourceItem.sourceId, claim.sourceId),
					eq(importSourceItem.status, 'importing'),
					eq(importSourceItem.contentHash, claim.contentHash),
					eq(importSourceItem.lastCheckedAt, claim.claimedAt)
				)
			)
			.returning({ id: importSourceItem.id });
		if (!completedItem) throw new Error('Import source claim changed before completion.');
		const [completedSource] = await tx
			.update(importSource)
			.set({
				lastCheckedAt: importedAt,
				lastSuccessAt: importedAt,
				lastImportedAt: importedAt,
				lastError: null,
				updatedAt: importedAt
			})
			.where(
				and(
					eq(importSource.id, claim.sourceId),
					eq(importSource.userId, userId),
					eq(importSource.enabled, true)
				)
			)
			.returning({ id: importSource.id });
		if (!completedSource) {
			throw new Error('Import was cancelled because the source was disconnected.');
		}
		return imported;
	});
}

function historicalLinkConsequence(calculated: ConsequenceResult): ConsequenceResult {
	const historicalPain = calculated.kind === 'pain_reported';
	const neutralDelta =
		calculated.metric === 'none' ? null : { metric: calculated.metric, value: 0 };
	return {
		...calculated,
		kind: historicalPain ? 'pain_reported' : 'historical_link',
		weeklyLoadDelta: neutralDelta,
		nextRunAdjustment: neutralDelta,
		weeklyDistanceDeltaMeters: 0,
		nextRunAdjustmentMeters: 0,
		risk: historicalPain ? 'unsafe' : 'conservative',
		recommendedDecision: 'keep_plan',
		options: ['keep_plan'],
		appliedDecision: null
	};
}

function isDuplicateImportConstraint(error: unknown): boolean {
	return isUniqueConstraint(error, ['activity_import_user_hash_unique']);
}

function isActivityWorkoutConstraint(error: unknown): boolean {
	return isUniqueConstraint(error, ['activity_workout_unique']);
}

function isUniqueConstraint(error: unknown, constraints: string[]): boolean {
	if (typeof error !== 'object' || error === null) return false;
	const maybePostgresError = error as {
		code?: unknown;
		constraint_name?: unknown;
		constraint?: unknown;
		message?: unknown;
	};
	const rawConstraint = maybePostgresError.constraint ?? maybePostgresError.constraint_name;
	const constraint = typeof rawConstraint === 'string' ? rawConstraint : '';
	const message = typeof maybePostgresError.message === 'string' ? maybePostgresError.message : '';
	return (
		maybePostgresError.code === '23505' &&
		constraints.some((name) => constraint === name || message.includes(name))
	);
}
