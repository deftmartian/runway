import { and, asc, desc, eq, isNull, lte, ne, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { activity, workout, workoutFeedback } from '$lib/server/db/schema';
import { getTrainingReadContext, type TrainingReadContext } from './training-read-context';

export async function getImportWorkoutCandidates(userId: string, context?: TrainingReadContext) {
	const requestContext = context ?? (await getTrainingReadContext(userId));
	const activePlan = requestContext.activePlan;
	if (!activePlan) return [];
	if (!requestContext.timeZone || !requestContext.today) {
		throw new Error('Set training time zone first.');
	}
	const today = requestContext.today;
	return db
		.select({
			id: workout.id,
			scheduledDate: workout.scheduledDate,
			type: workout.type,
			status: workout.status,
			purpose: workout.purpose,
			targetDistanceMeters: workout.targetDistanceMeters
		})
		.from(workout)
		.leftJoin(
			workoutFeedback,
			and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
		)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, activePlan.plan.id),
				ne(workout.type, 'rest'),
				eq(workout.status, 'planned'),
				eq(workout.isRemoved, false),
				isNull(workoutFeedback.id),
				lte(workout.scheduledDate, today)
			)
		)
		.orderBy(asc(workout.scheduledDate))
		.limit(300);
}

export async function getActivityRecords(
	userId: string,
	options: { limit?: number; offset?: number } = {}
) {
	const limit = Math.min(200, Math.max(1, Math.trunc(options.limit ?? 100)));
	const offset = Math.max(0, Math.trunc(options.offset ?? 0));
	const [rows, [count]] = await Promise.all([
		db
			.select({
				id: activity.id,
				workoutId: activity.workoutId,
				source: activity.source,
				reviewState: activity.reviewState,
				occurredAt: activity.occurredAt,
				activityDate: activity.activityDate,
				distanceMeters: activity.distanceMeters,
				durationSeconds: activity.durationSeconds,
				averagePaceSecondsPerKm: activity.averagePaceSecondsPerKm,
				averageHeartRate: activity.averageHeartRate,
				maxHeartRate: activity.maxHeartRate,
				heartRateSummary: activity.heartRateSummary,
				feltHard: activity.feltHard,
				pain: activity.pain,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed,
				consequence: activity.consequence,
				routeSummary: activity.routeSummary,
				createdAt: activity.createdAt,
				matchedWorkoutPurpose: workout.purpose,
				matchedWorkoutDate: workout.scheduledDate
			})
			.from(activity)
			.leftJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
			.where(eq(activity.userId, userId))
			.orderBy(
				sql`case when ${activity.workoutId} is null then 0 else 1 end`,
				desc(activity.occurredAt)
			)
			.limit(limit + 1)
			.offset(offset),
		db
			.select({ total: sql<number>`count(*)::int` })
			.from(activity)
			.where(eq(activity.userId, userId))
	]);
	return {
		items: rows.slice(0, limit),
		total: count?.total ?? 0,
		nextOffset: rows.length > limit ? offset + limit : null
	};
}

export async function getActivityTraceDetail(userId: string, activityId: string) {
	const [record] = await db
		.select({
			id: activity.id,
			routeTrace: activity.routeTrace,
			heartRateSeries: activity.heartRateSeries
		})
		.from(activity)
		.where(and(eq(activity.userId, userId), eq(activity.id, activityId)))
		.limit(1);
	return record ?? null;
}
