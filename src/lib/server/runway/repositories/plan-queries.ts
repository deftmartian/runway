import { and, asc, desc, eq, inArray, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	athleteProfile,
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
import { classifyRamp, hasInjuryRiskFlags } from '$lib/training/plan';
import type { PlanSummary, RiskRating, WorkoutType } from '$lib/training/types';
import type { TrainingDateContext } from './training-read-context';

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

export async function hasPlanHistory(userId: string): Promise<boolean> {
	const [record] = await db
		.select({ id: trainingPlan.id })
		.from(trainingPlan)
		.where(eq(trainingPlan.userId, userId))
		.limit(1);
	return record !== undefined;
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
	options: { limit?: number; offset?: number; context?: TrainingDateContext } = {}
) {
	const limit = Math.min(50, Math.max(1, Math.trunc(options.limit ?? 20)));
	const offset = Math.max(0, Math.trunc(options.offset ?? 0));
	const timeZone = options.context
		? options.context.timeZone
		: await requireAthleteTimeZone(userId);
	if (!timeZone) throw new Error('Set training time zone first.');
	const today = options.context?.today ?? todayIsoInTimeZone(timeZone);
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

	const [workoutRows, weekRows, [profile]] = await Promise.all([
		db
			.select({
				id: workout.id,
				planId: workout.planId,
				scheduledDate: workout.scheduledDate,
				type: workout.type,
				status: workout.status,
				targetDistanceMeters: workout.targetDistanceMeters,
				targetDurationSeconds: workout.targetDurationSeconds,
				isRemoved: workout.isRemoved
			})
			.from(workout)
			.where(and(eq(workout.userId, userId), inArray(workout.planId, planIds))),
		db
			.select({
				planId: trainingWeek.planId,
				startDate: trainingWeek.startDate,
				weekNumber: trainingWeek.weekNumber,
				risk: trainingWeek.risk
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), inArray(trainingWeek.planId, planIds)))
			.orderBy(asc(trainingWeek.weekNumber)),
		db
			.select({ injuryFlags: athleteProfile.injuryFlags })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
	]);
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
				risk: effectiveRiskForPlanRows(
					plan,
					weekRows.filter((row) => row.planId === plan.id),
					workoutRows.filter((row) => row.planId === plan.id),
					profile ? hasInjuryRiskFlags(profile.injuryFlags) : false
				),
				summary: plan.summary,
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
		getPlanWeeks(userId, planId),
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
			.select({
				workoutId: activity.workoutId,
				source: activity.source,
				distanceMeters: activity.distanceMeters,
				durationSeconds: activity.durationSeconds,
				feltHard: activity.feltHard,
				pain: activity.pain,
				consequence: activity.consequence
			})
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
	const cutoffDate = planRecord.plan.archivedAt
		? toIsoDateInTimeZone(planRecord.plan.archivedAt, timeZone)
		: today;
	return {
		...planRecord,
		plan: { ...planRecord.plan, risk: effectivePlanRisk(weeks, planRecord.plan.risk) },
		weeks,
		workouts,
		feedback,
		activities: activityRows,
		adjustments,
		cutoffDate
	};
}

export async function getPlanWeeks(userId: string, planId: string) {
	const [weeks, workouts, [planRecord], [profile]] = await Promise.all([
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
			.where(and(eq(workout.userId, userId), eq(workout.planId, planId))),
		db
			.select({ summary: trainingPlan.summary })
			.from(trainingPlan)
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.id, planId)))
			.limit(1),
		db
			.select({ injuryFlags: athleteProfile.injuryFlags })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
	]);
	const effectiveWeeks = weeks.map((week) => {
		const weekWorkouts = workouts.filter(
			(record) =>
				!record.isRemoved &&
				record.scheduledDate >= week.startDate &&
				record.scheduledDate <= addDays(week.startDate, 6)
		);
		return {
			...week,
			hasMixedLoad:
				weekWorkouts.some(
					(record) =>
						record.type !== 'rest' && record.type !== 'race' && record.targetDistanceMeters > 0
				) &&
				weekWorkouts.some(
					(record) =>
						record.type !== 'rest' &&
						record.type !== 'race' &&
						(record.targetDurationSeconds ?? 0) > 0
				),
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
	const hasInjuryRisk = profile ? hasInjuryRiskFlags(profile.injuryFlags) : false;
	return effectiveWeeks.map((week, index) => {
		const usesDuration = week.targetDurationSeconds > 0 && week.targetDistanceMeters === 0;
		const currentLoad = usesDuration ? week.targetDurationSeconds : week.targetDistanceMeters;
		const previousWeek = effectiveWeeks[index - 1];
		const previousLoad = previousWeek
			? usesDuration
				? previousWeek.targetDurationSeconds
				: previousWeek.targetDistanceMeters
			: !usesDuration && planRecord?.summary.kind === 'distance'
				? planRecord.summary.baselineMeters
				: 0;
		if (previousLoad <= 0) return week;
		const rampPercent = ((currentLoad - previousLoad) / previousLoad) * 100;
		return { ...week, risk: classifyRamp(rampPercent, hasInjuryRisk) };
	});
}

const riskRank: Record<RiskRating, number> = {
	conservative: 0,
	moderate: 1,
	aggressive: 2,
	unsafe: 3
};

export function effectivePlanRisk(weeks: { risk: RiskRating }[], fallback: RiskRating): RiskRating {
	return weeks.reduce(
		(highest, week) => (riskRank[week.risk] > riskRank[highest] ? week.risk : highest),
		fallback
	);
}

function effectiveRiskForPlanRows(
	plan: { risk: RiskRating; summary: PlanSummary },
	weeks: { startDate: string; risk: RiskRating }[],
	workouts: {
		scheduledDate: string;
		type: WorkoutType;
		targetDistanceMeters: number;
		targetDurationSeconds: number | null;
		isRemoved: boolean;
	}[],
	hasInjuryRisk: boolean
): RiskRating {
	const effectiveWeeks = weeks.map((week, index) => {
		const endDate = addDays(week.startDate, 6);
		const current = workouts.filter(
			(record) =>
				!record.isRemoved &&
				record.type !== 'rest' &&
				record.type !== 'race' &&
				record.scheduledDate >= week.startDate &&
				record.scheduledDate <= endDate
		);
		const targetDistanceMeters = current.reduce(
			(sum, record) => sum + record.targetDistanceMeters,
			0
		);
		const targetDurationSeconds = current.reduce(
			(sum, record) => sum + (record.targetDurationSeconds ?? 0),
			0
		);
		const usesDuration = targetDurationSeconds > 0 && targetDistanceMeters === 0;
		const previousWeek = weeks[index - 1];
		const previous = previousWeek
			? workouts.filter(
					(record) =>
						!record.isRemoved &&
						record.type !== 'rest' &&
						record.type !== 'race' &&
						record.scheduledDate >= previousWeek.startDate &&
						record.scheduledDate <= addDays(previousWeek.startDate, 6)
				)
			: [];
		const previousLoad = previousWeek
			? previous.reduce(
					(sum, record) =>
						sum +
						(usesDuration ? (record.targetDurationSeconds ?? 0) : record.targetDistanceMeters),
					0
				)
			: !usesDuration && plan.summary.kind === 'distance'
				? plan.summary.baselineMeters
				: 0;
		if (previousLoad <= 0) return { risk: week.risk };
		const currentLoad = usesDuration ? targetDurationSeconds : targetDistanceMeters;
		return {
			risk: classifyRamp(((currentLoad - previousLoad) / previousLoad) * 100, hasInjuryRisk)
		};
	});
	return effectivePlanRisk(effectiveWeeks, plan.risk);
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
