import { and, asc, desc, eq, inArray, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	goal,
	planAdjustment,
	trainingPlan,
	trainingWeek,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { getWorkoutRecommendationTraces } from './adjustment-ledger';
import { requireAthleteTimeZone } from './profiles';
import { addDays, toIsoDateInTimeZone, todayIsoInTimeZone } from '$lib/training/date';

export async function getActivePlan(userId: string) {
	const [record] = await db
		.select({ plan: trainingPlan, goal })
		.from(trainingPlan)
		.innerJoin(goal, eq(trainingPlan.goalId, goal.id))
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
		.orderBy(desc(trainingPlan.createdAt))
		.limit(1);
	return record;
}

export async function getCurrentGoal(userId: string) {
	const [record] = await db
		.select()
		.from(goal)
		.where(and(eq(goal.userId, userId), inArray(goal.state, ['pending', 'active'])))
		.orderBy(desc(goal.createdAt))
		.limit(1);
	return record ?? null;
}

export async function listPlanHistory(
	userId: string,
	options: { limit?: number; offset?: number } = {}
) {
	const limit = Math.min(50, Math.max(1, Math.trunc(options.limit ?? 20)));
	const offset = Math.max(0, Math.trunc(options.offset ?? 0));
	const timeZone = await requireAthleteTimeZone(userId);
	const today = todayIsoInTimeZone(timeZone);
	const rows = await db
		.select({ plan: trainingPlan, goal })
		.from(trainingPlan)
		.innerJoin(goal, and(eq(trainingPlan.goalId, goal.id), eq(goal.userId, userId)))
		.where(eq(trainingPlan.userId, userId))
		.orderBy(
			sql`case when ${trainingPlan.status} = 'active' then 0 else 1 end`,
			desc(trainingPlan.createdAt),
			desc(trainingPlan.id)
		)
		.limit(limit + 1)
		.offset(offset);
	const page = rows.slice(0, limit);
	const planIds = page.map((row) => row.plan.id);
	if (planIds.length === 0) {
		return { items: [], nextOffset: null, today };
	}

	const workoutRows = await db
		.select({
			id: workout.id,
			planId: workout.planId,
			scheduledDate: workout.scheduledDate,
			type: workout.type,
			status: workout.status,
			targetDistanceMeters: workout.targetDistanceMeters,
			isRemoved: workout.isRemoved
		})
		.from(workout)
		.where(and(eq(workout.userId, userId), inArray(workout.planId, planIds)));
	const workoutIds = workoutRows.filter((row) => !row.isRemoved).map((row) => row.id);
	const feedbackRows =
		workoutIds.length === 0
			? []
			: await db
					.select({
						workoutId: workoutFeedback.workoutId,
						completedDistanceMeters: workoutFeedback.completedDistanceMeters,
						pain: workoutFeedback.pain
					})
					.from(workoutFeedback)
					.where(
						and(eq(workoutFeedback.userId, userId), inArray(workoutFeedback.workoutId, workoutIds))
					);
	const activityRows = await db
		.select({
			workoutId: activity.workoutId,
			planId: workout.planId,
			distanceMeters: activity.distanceMeters,
			pain: activity.pain
		})
		.from(activity)
		.innerJoin(
			workout,
			and(
				eq(activity.workoutId, workout.id),
				eq(workout.userId, userId),
				inArray(workout.planId, planIds)
			)
		)
		.where(and(eq(activity.userId, userId), eq(activity.reviewState, 'accepted')));
	const feedbackByWorkout = new Map(feedbackRows.map((row) => [row.workoutId, row]));
	const activityByWorkout = new Map(
		activityRows.flatMap((row) => (row.workoutId ? [[row.workoutId, row] as const] : []))
	);

	const items = page.map(({ plan, goal: planGoal }) => {
		const cutoff = plan.archivedAt ? toIsoDateInTimeZone(plan.archivedAt, timeZone) : today;
		const planWorkouts = workoutRows.filter(
			(row) => row.planId === plan.id && row.type !== 'rest' && !row.isRemoved
		);
		let completedRuns = 0;
		let completedDistanceMeters = 0;
		let missedRuns = 0;
		let skippedRuns = 0;
		let painFlags = 0;
		for (const record of planWorkouts) {
			const feedback = feedbackByWorkout.get(record.id);
			const imported = activityByWorkout.get(record.id);
			const completedDistance = imported?.distanceMeters ?? feedback?.completedDistanceMeters ?? 0;
			if (completedDistance > 0 || record.status === 'done' || record.status === 'shortened') {
				completedRuns += 1;
				completedDistanceMeters += completedDistance;
			}
			if (record.status === 'skipped') skippedRuns += 1;
			if (record.status === 'planned' && record.scheduledDate < cutoff) missedRuns += 1;
			if (feedback?.pain || imported?.pain) painFlags += 1;
		}
		return {
			plan: {
				id: plan.id,
				status: plan.status,
				startDate: plan.startDate,
				targetDate: plan.targetDate,
				weeks: plan.weeks,
				risk: plan.risk,
				completedAt: plan.completedAt,
				archivedAt: plan.archivedAt,
				lifecycleReason: plan.lifecycleReason
			},
			goal: {
				id: planGoal.id,
				title: planGoal.title,
				distance: planGoal.distance,
				targetDate: planGoal.targetDate,
				priority: planGoal.priority
			},
			summary: {
				plannedRuns: planWorkouts.length,
				completedRuns,
				missedRuns,
				skippedRuns,
				painFlags,
				completedDistanceMeters
			}
		};
	});
	return {
		items,
		nextOffset: rows.length > limit ? offset + limit : null,
		today
	};
}

export async function getPlanDetail(userId: string, planId: string) {
	const timeZone = await requireAthleteTimeZone(userId);
	const today = todayIsoInTimeZone(timeZone);
	const [planRecord] = await db
		.select({ plan: trainingPlan, goal })
		.from(trainingPlan)
		.innerJoin(goal, eq(trainingPlan.goalId, goal.id))
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.id, planId)))
		.limit(1);

	if (!planRecord) return null;

	const [weeks, workouts, feedbackRows, activityRows, adjustments] = await Promise.all([
		db
			.select()
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, planId)))
			.orderBy(asc(trainingWeek.weekNumber))
			.limit(52),
		db
			.select()
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, planId)))
			.orderBy(asc(workout.scheduledDate))
			.limit(52 * 14),
		db
			.select({ feedback: workoutFeedback })
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workout.planId, planId)))
			.orderBy(desc(workoutFeedback.createdAt))
			.limit(52 * 14),
		db
			.select({ activity })
			.from(activity)
			.innerJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted'),
					eq(workout.planId, planId)
				)
			)
			.orderBy(asc(activity.activityDate), asc(activity.id))
			.limit(52 * 14),
		db
			.select()
			.from(planAdjustment)
			.where(and(eq(planAdjustment.userId, userId), eq(planAdjustment.planId, planId)))
			.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id))
			.limit(10_000)
	]);
	const feedback = feedbackRows.map((row) => row.feedback);
	const activities = activityRows.map((row) => row.activity);
	const cutoffDate = planRecord.plan.archivedAt
		? toIsoDateInTimeZone(planRecord.plan.archivedAt, timeZone)
		: today;
	return { ...planRecord, weeks, workouts, feedback, activities, adjustments, cutoffDate };
}

export async function getPlanSchedule(userId: string, planId: string) {
	const [planRecord] = await db
		.select({ plan: trainingPlan, goal })
		.from(trainingPlan)
		.innerJoin(goal, eq(trainingPlan.goalId, goal.id))
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.id, planId)))
		.limit(1);

	if (!planRecord) return null;

	const weeks = await getPlanWeeks(userId, planId);
	const workouts = await db
		.select()
		.from(workout)
		.where(and(eq(workout.userId, userId), eq(workout.planId, planId)))
		.orderBy(asc(workout.scheduledDate));

	return { ...planRecord, weeks, workouts };
}

export async function getPlanWeeks(userId: string, planId: string) {
	const [weeks, workouts] = await Promise.all([
		db
			.select()
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, planId)))
			.orderBy(asc(trainingWeek.weekNumber)),
		db
			.select({
				scheduledDate: workout.scheduledDate,
				type: workout.type,
				targetDistanceMeters: workout.targetDistanceMeters,
				targetDurationSeconds: workout.targetDurationSeconds,
				isRemoved: workout.isRemoved
			})
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, planId)))
	]);
	return weeks.map((week) => {
		const weekWorkouts = workouts.filter(
			(record) =>
				!record.isRemoved &&
				record.scheduledDate >= week.startDate &&
				record.scheduledDate <= addDays(week.startDate, 6)
		);
		return {
			...week,
			targetDistanceMeters: weekWorkouts.reduce(
				(sum, record) =>
					sum +
					(record.type === 'rest' || record.type === 'race' ? 0 : record.targetDistanceMeters),
				0
			),
			eventDistanceMeters: weekWorkouts.reduce(
				(sum, record) => sum + (record.type === 'race' ? record.targetDistanceMeters : 0),
				0
			),
			totalScheduledDistanceMeters: weekWorkouts.reduce(
				(sum, record) => sum + (record.type === 'rest' ? 0 : record.targetDistanceMeters),
				0
			),
			longRunMeters: weekWorkouts.reduce(
				(longest, record) =>
					record.type === 'long' ? Math.max(longest, record.targetDistanceMeters) : longest,
				0
			),
			targetDurationSeconds: weekWorkouts.reduce(
				(sum, record) => sum + (record.type === 'rest' ? 0 : (record.targetDurationSeconds ?? 0)),
				0
			)
		};
	});
}

export async function getPlanTrace(userId: string, planId: string) {
	const [weeks, workoutRows] = await Promise.all([
		db
			.select({
				id: trainingWeek.id,
				weekNumber: trainingWeek.weekNumber,
				startDate: trainingWeek.startDate
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, planId)))
			.orderBy(asc(trainingWeek.weekNumber))
			.limit(52),
		db
			.select({
				id: workout.id,
				scheduledDate: workout.scheduledDate,
				type: workout.type,
				targetDistanceMeters: workout.targetDistanceMeters,
				targetDurationSeconds: workout.targetDurationSeconds,
				isRemoved: workout.isRemoved
			})
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, planId)))
			.orderBy(asc(workout.scheduledDate), asc(workout.id))
			.limit(52 * 14)
	]);
	const recommendations = await getWorkoutRecommendationTraces(
		userId,
		workoutRows.map((record) => record.id)
	);

	return weeks.map((week) => {
		const endDate = addDays(week.startDate, 6);
		let recommendedDistanceMeters = 0;
		let recommendedDurationSeconds = 0;
		let currentDistanceMeters = 0;
		let currentDurationSeconds = 0;

		for (const record of workoutRows) {
			const trace = recommendations.get(record.id);
			const recommended =
				trace?.recommended ??
				(trace
					? null
					: {
							scheduledDate: record.scheduledDate,
							type: record.type,
							targetDistanceMeters: record.targetDistanceMeters,
							targetDurationSeconds: record.targetDurationSeconds
						});
			if (
				recommended &&
				recommended.type !== 'rest' &&
				recommended.type !== 'race' &&
				recommended.scheduledDate >= week.startDate &&
				recommended.scheduledDate <= endDate
			) {
				recommendedDistanceMeters += recommended.targetDistanceMeters;
				recommendedDurationSeconds += recommended.targetDurationSeconds ?? 0;
			}
			if (
				!record.isRemoved &&
				record.type !== 'rest' &&
				record.type !== 'race' &&
				record.scheduledDate >= week.startDate &&
				record.scheduledDate <= endDate
			) {
				currentDistanceMeters += record.targetDistanceMeters;
				currentDurationSeconds += record.targetDurationSeconds ?? 0;
			}
		}

		return {
			...week,
			recommendedDistanceMeters,
			recommendedDurationSeconds,
			currentDistanceMeters,
			currentDurationSeconds
		};
	});
}
