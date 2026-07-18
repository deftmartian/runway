import { and, asc, eq, gt, gte, isNull, lte, ne } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	auditEvent,
	trainingPlan,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { addDays, localDateAtNoon, todayIsoInTimeZone } from '$lib/training/date';
import { calculateExtraActivityConsequence } from '$lib/training/extra-activity';
import type { ConsequenceResult } from '$lib/training/types';
import { lockActivityOwner } from './mutation-locks';
import { requireAthleteTimeZoneInTransaction } from './profiles';
import { effectiveWeekTargetDistance, effectiveWeekTargetDuration } from './schedule-queries';
import type { RunwayTransaction } from './transaction';

type UnlinkedActivityPlanInput = {
	id: string;
	source: 'manual' | 'gpx';
	activityDate: string;
	distanceMeters: number;
	durationSeconds: number | null;
	feltHard: boolean;
	pain: boolean;
	extraPlanImpactConfirmed: boolean;
};

export async function recordManualRun(
	userId: string,
	input: {
		occurredDate: string;
		distanceMeters: number;
		durationSeconds?: number;
		feltHard: boolean;
		pain: boolean;
	}
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const timeZone = await requireAthleteTimeZoneInTransaction(
			tx,
			userId,
			'Set training time zone before recording a run.'
		);
		const today = todayIsoInTimeZone(timeZone);
		if (input.occurredDate > today) {
			throw new Error('Manual runs cannot be recorded in the future.');
		}
		const [createdActivity] = await tx
			.insert(activity)
			.values({
				userId,
				source: 'manual',
				reviewState: 'accepted',
				occurredAt: localDateAtNoon(input.occurredDate, timeZone),
				activityDate: input.occurredDate,
				distanceMeters: input.distanceMeters,
				feltHard: input.feltHard,
				pain: input.pain,
				extraPlanImpactConfirmed: true,
				...(input.durationSeconds === undefined ? {} : { durationSeconds: input.durationSeconds }),
				averagePaceSecondsPerKm:
					input.durationSeconds && input.distanceMeters > 0
						? input.durationSeconds / (input.distanceMeters / 1_000)
						: undefined,
				routeSummary: {
					pointCount: 0,
					startEndRedacted: true,
					hasElevation: false
				}
			})
			.returning();
		if (!createdActivity) throw new Error('Manual run could not be recorded.');

		const consequence = await calculateUnplannedConsequence(
			tx,
			userId,
			{
				id: createdActivity.id,
				source: 'manual',
				activityDate: input.occurredDate,
				distanceMeters: input.distanceMeters,
				durationSeconds: input.durationSeconds ?? null,
				feltHard: input.feltHard,
				pain: input.pain,
				extraPlanImpactConfirmed: true
			},
			today
		);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.manual',
			detail: { activityId: createdActivity.id, source: 'manual' }
		});
		return { activity: createdActivity, consequence };
	});
}

export async function confirmActivityAsExtra(userId: string, activityId: string) {
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
		if (targetActivity.workoutId) {
			throw new Error('Linked activities already count against the plan.');
		}
		if (targetActivity.extraPlanImpactConfirmed) {
			throw new Error('This activity has already been counted as extra.');
		}
		const [confirmedActivity] = await tx
			.update(activity)
			.set({ extraPlanImpactConfirmed: true, reviewState: 'accepted' })
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.id, targetActivity.id),
					eq(activity.extraPlanImpactConfirmed, false),
					isNull(activity.workoutId)
				)
			)
			.returning({ id: activity.id });
		if (!confirmedActivity) throw new Error('Activity is no longer available to count.');
		const consequence = await calculateUnplannedConsequence(
			tx,
			userId,
			{ ...targetActivity, extraPlanImpactConfirmed: true },
			today
		);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.confirmed_extra',
			detail: { activityId: targetActivity.id }
		});
		return consequence;
	});
}

export async function calculateUnplannedConsequence(
	tx: RunwayTransaction,
	userId: string,
	targetActivity: UnlinkedActivityPlanInput,
	today: string
): Promise<ConsequenceResult | null> {
	if (
		!targetActivity.extraPlanImpactConfirmed ||
		targetActivity.activityDate < addDays(today, -7) ||
		targetActivity.activityDate > today
	) {
		await clearActivityConsequence(tx, userId, targetActivity.id);
		return null;
	}

	const [nextRun] = await tx
		.select({
			id: workout.id,
			planId: workout.planId,
			targetDistanceMeters: workout.targetDistanceMeters,
			targetDurationSeconds: workout.targetDurationSeconds
		})
		.from(workout)
		.innerJoin(
			trainingPlan,
			and(
				eq(workout.planId, trainingPlan.id),
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.status, 'active'),
				lte(trainingPlan.startDate, targetActivity.activityDate)
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
				gt(workout.scheduledDate, targetActivity.activityDate),
				gte(workout.scheduledDate, today),
				isNull(workoutFeedback.id)
			)
		)
		.orderBy(asc(workout.scheduledDate))
		.limit(1);
	if (!nextRun) {
		await clearActivityConsequence(tx, userId, targetActivity.id);
		return null;
	}

	const consequence = calculateExtraActivityConsequence(targetActivity, {
		nextRunTargetDistanceMeters: nextRun.targetDistanceMeters,
		nextRunTargetDurationSeconds: nextRun.targetDurationSeconds,
		weekTargetDistanceMeters: Math.max(
			nextRun.targetDistanceMeters,
			await effectiveWeekTargetDistance(tx, userId, nextRun.planId, targetActivity.activityDate)
		),
		weekTargetDurationSeconds: Math.max(
			nextRun.targetDurationSeconds ?? 0,
			await effectiveWeekTargetDuration(tx, userId, nextRun.planId, targetActivity.activityDate)
		)
	});
	await tx
		.update(activity)
		.set({ consequence, consequencePlanId: nextRun.planId })
		.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
	return consequence;
}

async function clearActivityConsequence(
	tx: RunwayTransaction,
	userId: string,
	activityId: string
): Promise<void> {
	await tx
		.update(activity)
		.set({ consequence: null, consequencePlanId: null })
		.where(and(eq(activity.userId, userId), eq(activity.id, activityId)));
}
