import { and, asc, desc, eq, gte, inArray, isNotNull, isNull, lte, ne, or, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	planAdjustment,
	trainingPlan,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { addDays } from '$lib/training/date';
import { effectivePlanRisk, getPlanWeeks } from './plan-queries';
import { currentTrainingSignal } from './training-signal';
import { getTrainingReadContext, type TrainingReadContext } from './training-read-context';

export type PaceResult = {
	distanceMeters: number | null | undefined;
	durationSeconds: number | null | undefined;
};

export const maxHistoryPlanWorkouts = 52 * 14;

export function indexPlanHistoryEvidence<Feedback extends { workoutId: string }>(
	feedbackRows: Feedback[],
	linkedActivityRows: { workoutId: string | null }[]
) {
	const latestFeedbackByWorkout = new Map<string, Feedback>();
	for (const feedback of feedbackRows) {
		if (!latestFeedbackByWorkout.has(feedback.workoutId)) {
			latestFeedbackByWorkout.set(feedback.workoutId, feedback);
		}
	}

	const activityWorkoutIds = new Set(
		linkedActivityRows.flatMap((record) => (record.workoutId ? [record.workoutId] : []))
	);

	return { latestFeedbackByWorkout, activityWorkoutIds };
}

export function averagePaceFromPairedResults(results: PaceResult[]): number | null {
	let distanceMeters = 0;
	let durationSeconds = 0;
	for (const result of results) {
		if (!result.distanceMeters || result.distanceMeters <= 0) continue;
		if (!result.durationSeconds || result.durationSeconds <= 0) continue;
		distanceMeters += result.distanceMeters;
		durationSeconds += result.durationSeconds;
	}
	return distanceMeters > 0 ? durationSeconds / (distanceMeters / 1_000) : null;
}

export async function getHistory(userId: string, context?: TrainingReadContext) {
	const requestContext = context ?? (await getTrainingReadContext(userId));
	if (!requestContext.timeZone || !requestContext.today) {
		throw new Error('Set training time zone first.');
	}
	const today = requestContext.today;
	const activePlan = requestContext.activePlan;
	const [recordedSummary, heartRateSample, [acceptedActivity]] = await Promise.all([
		getRecordedHistorySummary(userId),
		getHeartRateSample(userId, today),
		db
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.reviewState, 'accepted')))
			.limit(1)
	]);
	if (!activePlan) {
		const recentFeedback = await getRecentFeedback(userId);
		return {
			hasAcceptedActivities: Boolean(acceptedActivity),
			recentFeedback,
			weeklySummaries: [],
			recordedSummary,
			heartRateSample,
			currentSignal: null,
			todayIso: today
		};
	}

	const activityWeekStart = sql<string>`to_char(date_trunc('week', ${activity.activityDate}::timestamp), 'YYYY-MM-DD')`;
	const activitySummaryWhere = and(
		eq(activity.userId, userId),
		eq(activity.reviewState, 'accepted'),
		gte(activity.activityDate, activePlan.plan.startDate),
		lte(activity.activityDate, today),
		or(eq(workout.planId, activePlan.plan.id), isNull(activity.workoutId))
	);
	const weeks = await getPlanWeeks(userId, activePlan.plan.id);
	const [
		planWorkouts,
		planFeedback,
		activityWeeklyRows,
		linkedActivityRows,
		manualAdjustmentRows,
		currentSignal
	] = await Promise.all([
		db
			.select()
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, activePlan.plan.id),
					eq(workout.isRemoved, false)
				)
			)
			.orderBy(asc(workout.scheduledDate))
			.limit(maxHistoryPlanWorkouts),
		db
			.select({
				id: workoutFeedback.id,
				workoutId: workoutFeedback.workoutId,
				completedDistanceMeters: workoutFeedback.completedDistanceMeters,
				completedDurationSeconds: workoutFeedback.completedDurationSeconds,
				feltHard: workoutFeedback.feltHard,
				pain: workoutFeedback.pain,
				consequence: workoutFeedback.consequence,
				createdAt: workoutFeedback.createdAt
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workout.planId, activePlan.plan.id)))
			.orderBy(desc(workoutFeedback.createdAt))
			.limit(maxHistoryPlanWorkouts),
		db
			.select({
				weekStart: activityWeekStart,
				completedRuns: sql<number>`count(*)::int`,
				completedDistanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}) filter (where ${workout.type} is distinct from 'race'), 0)::int`,
				eventCompletedDistanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}) filter (where ${workout.type} = 'race'), 0)::int`,
				completedDurationSeconds: sql<number>`coalesce(sum(${activity.durationSeconds}) filter (where ${workout.type} is distinct from 'race'), 0)::int`,
				paceDistanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}) filter (where ${workout.type} is distinct from 'race' and ${activity.distanceMeters} > 0 and ${activity.durationSeconds} > 0), 0)::int`,
				paceDurationSeconds: sql<number>`coalesce(sum(${activity.durationSeconds}) filter (where ${workout.type} is distinct from 'race' and ${activity.distanceMeters} > 0 and ${activity.durationSeconds} > 0), 0)::int`,
				longestRunMeters: sql<number>`coalesce(max(${activity.distanceMeters}), 0)::int`,
				painFlags: sql<number>`count(*) filter (where ${activity.pain})::int`,
				hardFlags: sql<number>`count(*) filter (where ${activity.feltHard})::int`,
				averageHeartRate: sql<number | null>`round(
					(sum((${activity.averageHeartRate}::bigint * ${activity.durationSeconds})) filter (where ${activity.averageHeartRate} is not null and ${activity.durationSeconds} is not null))::numeric
					/ nullif(sum(${activity.durationSeconds}) filter (where ${activity.averageHeartRate} is not null), 0)
				)::int`
			})
			.from(activity)
			.leftJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
			.where(activitySummaryWhere)
			.groupBy(activityWeekStart),
		db
			.select({ workoutId: activity.workoutId })
			.from(activity)
			.innerJoin(
				workout,
				and(
					eq(activity.workoutId, workout.id),
					eq(workout.userId, userId),
					eq(workout.planId, activePlan.plan.id)
				)
			)
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted'),
					gte(activity.activityDate, activePlan.plan.startDate),
					lte(activity.activityDate, today)
				)
			)
			.limit(maxHistoryPlanWorkouts),
		db
			.select({
				workoutId: planAdjustment.workoutId,
				previousScheduledDate: planAdjustment.previousScheduledDate,
				newScheduledDate: planAdjustment.newScheduledDate
			})
			.from(planAdjustment)
			.where(
				and(
					eq(planAdjustment.userId, userId),
					eq(planAdjustment.planId, activePlan.plan.id),
					inArray(planAdjustment.triggerType, [
						'manual_edit',
						'manual_add',
						'manual_remove',
						'rebalance',
						'decision'
					]),
					isNull(planAdjustment.reversedAt)
				)
			)
			.limit(10_000),
		currentTrainingSignal(
			userId,
			activePlan.plan,
			today,
			effectivePlanRisk(weeks, activePlan.plan.risk),
			weeks.some((week) => week.hasMixedLoad)
		)
	]);
	const activityWeeklyByStart = new Map(
		activityWeeklyRows.map((record) => [record.weekStart, record])
	);
	const { activityWorkoutIds, latestFeedbackByWorkout } = indexPlanHistoryEvidence(
		planFeedback,
		linkedActivityRows
	);

	const weeklySummaries = weeks
		.filter((week) => week.startDate <= today)
		.map((week) => {
			const weekEnd = addDays(week.startDate, 6);
			const runWorkouts = planWorkouts.filter(
				(record) =>
					!record.isRemoved &&
					record.type !== 'rest' &&
					record.scheduledDate >= week.startDate &&
					record.scheduledDate <= weekEnd &&
					record.scheduledDate <= today
			);
			const activitySummary = activityWeeklyByStart.get(week.startDate);
			let completedDistanceMeters = activitySummary?.completedDistanceMeters ?? 0;
			let eventCompletedDistanceMeters = activitySummary?.eventCompletedDistanceMeters ?? 0;
			let completedDurationSeconds = activitySummary?.completedDurationSeconds ?? 0;
			let completedRuns = activitySummary?.completedRuns ?? 0;
			let longestRunMeters = activitySummary?.longestRunMeters ?? 0;
			const changedRuns = new Set(
				manualAdjustmentRows
					.filter((adjustment) => {
						const date = adjustment.newScheduledDate ?? adjustment.previousScheduledDate;
						return date !== null && date >= week.startDate && date <= weekEnd;
					})
					.map((adjustment) => adjustment.workoutId)
			).size;
			let missedRuns = 0;
			let skippedRuns = 0;
			let painFlags = activitySummary?.painFlags ?? 0;
			let hardFlags = activitySummary?.hardFlags ?? 0;
			const paceResults: PaceResult[] = [
				{
					distanceMeters: activitySummary?.paceDistanceMeters,
					durationSeconds: activitySummary?.paceDurationSeconds
				}
			];

			for (const record of runWorkouts) {
				const feedback = latestFeedbackByWorkout.get(record.id);
				const hasActivity = activityWorkoutIds.has(record.id);
				const completedMeters = hasActivity
					? 0
					: (feedback?.completedDistanceMeters ??
						(record.status === 'done' ? record.targetDistanceMeters : 0));

				if (!hasActivity && (completedMeters > 0 || record.status === 'done')) {
					completedRuns += 1;
				}
				if (record.type === 'race') eventCompletedDistanceMeters += completedMeters;
				else completedDistanceMeters += completedMeters;
				longestRunMeters = Math.max(longestRunMeters, completedMeters);
				if (!hasActivity && record.type !== 'race') {
					completedDurationSeconds += feedback?.completedDurationSeconds ?? 0;
					paceResults.push({
						distanceMeters: feedback?.completedDistanceMeters,
						durationSeconds: feedback?.completedDurationSeconds
					});
				}
				if (record.status === 'skipped') skippedRuns += 1;
				if (record.status === 'planned' && record.scheduledDate < today) {
					missedRuns += 1;
				}
				if (!hasActivity && feedback?.pain) painFlags += 1;
				if (!hasActivity && feedback?.feltHard) hardFlags += 1;
			}

			return {
				weekNumber: week.weekNumber,
				startDate: week.startDate,
				targetDistanceMeters: runWorkouts.reduce(
					(sum, record) => sum + (record.type === 'race' ? 0 : record.targetDistanceMeters),
					0
				),
				fullTargetDistanceMeters: week.totalScheduledDistanceMeters,
				eventDistanceMeters: week.eventDistanceMeters,
				eventCompletedDistanceMeters,
				plannedRuns: runWorkouts.length,
				completedRuns,
				completedDistanceMeters,
				completedDurationSeconds,
				longestRunMeters,
				averagePaceSecondsPerKm: averagePaceFromPairedResults(paceResults),
				changedRuns,
				missedRuns,
				skippedRuns,
				painFlags,
				hardFlags,
				averageHeartRate: activitySummary?.averageHeartRate ?? null,
				highHeartRateRuns: 0
			};
		});

	return {
		hasAcceptedActivities: Boolean(acceptedActivity),
		recentFeedback: planFeedback.slice(0, 100),
		weeklySummaries,
		recordedSummary,
		heartRateSample,
		currentSignal,
		todayIso: today
	};
}

async function getHeartRateSample(userId: string, today: string) {
	const windowDays = 90;
	const windowStart = addDays(today, -(windowDays - 1));
	const where = and(
		eq(activity.userId, userId),
		eq(activity.reviewState, 'accepted'),
		gte(activity.activityDate, windowStart),
		lte(activity.activityDate, today),
		isNotNull(activity.averageHeartRate)
	);
	const [[summary], [latest], [oldest]] = await Promise.all([
		db
			.select({
				sampleCount: sql<number>`count(*)::int`,
				averageHeartRate: sql<number>`round(
					(sum((${activity.averageHeartRate}::bigint * ${activity.durationSeconds})) filter (where ${activity.durationSeconds} is not null))::numeric
					/ nullif(sum(${activity.durationSeconds}), 0)
				)::int`,
				highZoneSeconds: sql<number>`coalesce(sum(coalesce((${activity.heartRateSummary} ->> 'highSeconds')::int, 0)), 0)::int`
			})
			.from(activity)
			.where(where),
		db
			.select({
				activityDate: activity.activityDate,
				averageHeartRate: activity.averageHeartRate,
				maxHeartRate: activity.maxHeartRate
			})
			.from(activity)
			.where(where)
			.orderBy(desc(activity.activityDate), desc(activity.id))
			.limit(1),
		db
			.select({
				activityDate: activity.activityDate,
				averageHeartRate: activity.averageHeartRate
			})
			.from(activity)
			.where(where)
			.orderBy(asc(activity.activityDate), asc(activity.id))
			.limit(1)
	]);
	return {
		windowDays,
		windowStart,
		windowEnd: today,
		sampleCount: summary?.sampleCount ?? 0,
		averageHeartRate: summary?.averageHeartRate ?? null,
		highZoneSeconds: summary?.highZoneSeconds ?? 0,
		latest: latest ?? null,
		oldest: oldest ?? null
	};
}

function getRecentFeedback(userId: string) {
	return db
		.select({
			id: workoutFeedback.id,
			workoutId: workoutFeedback.workoutId,
			completedDistanceMeters: workoutFeedback.completedDistanceMeters,
			completedDurationSeconds: workoutFeedback.completedDurationSeconds,
			feltHard: workoutFeedback.feltHard,
			pain: workoutFeedback.pain,
			consequence: workoutFeedback.consequence,
			createdAt: workoutFeedback.createdAt
		})
		.from(workoutFeedback)
		.where(eq(workoutFeedback.userId, userId))
		.orderBy(desc(workoutFeedback.createdAt))
		.limit(100);
}

async function getRecordedHistorySummary(userId: string) {
	const completedDistance = sql<number>`coalesce(
		${activity.distanceMeters},
		${workoutFeedback.completedDistanceMeters},
		case when ${workout.status} = 'done' then ${workout.targetDistanceMeters} else 0 end
	)`;
	const completedDuration = sql<number>`coalesce(
		${activity.durationSeconds},
		${workoutFeedback.completedDurationSeconds},
		0
	)`;
	const completedRun = sql`(${completedDistance} > 0 or ${workout.status} = 'done')`;

	const [[linked], [unlinked]] = await Promise.all([
		db
			.select({
				runs: sql<number>`coalesce(count(*) filter (where ${completedRun}), 0)::int`,
				distanceMeters: sql<number>`coalesce(sum(${completedDistance}) filter (where ${completedRun}), 0)::int`,
				durationSeconds: sql<number>`coalesce(sum(${completedDuration}) filter (where ${completedRun}), 0)::int`,
				longestRunMeters: sql<number>`coalesce(max(${completedDistance}) filter (where ${completedRun}), 0)::int`,
				currentPlanRuns: sql<number>`coalesce(count(*) filter (where ${completedRun} and ${trainingPlan.status} = 'active'), 0)::int`,
				currentPlanDistanceMeters: sql<number>`coalesce(sum(${completedDistance}) filter (where ${completedRun} and ${trainingPlan.status} = 'active'), 0)::int`,
				archivedPlanRuns: sql<number>`coalesce(count(*) filter (where ${completedRun} and ${trainingPlan.status} = 'archived'), 0)::int`,
				archivedPlanDistanceMeters: sql<number>`coalesce(sum(${completedDistance}) filter (where ${completedRun} and ${trainingPlan.status} = 'archived'), 0)::int`
			})
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(eq(workout.planId, trainingPlan.id), eq(trainingPlan.userId, userId))
			)
			.leftJoin(
				activity,
				and(
					eq(activity.workoutId, workout.id),
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted')
				)
			)
			.leftJoin(
				workoutFeedback,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
			)
			.where(and(eq(workout.userId, userId), ne(workout.type, 'rest'))),
		db
			.select({
				runs: sql<number>`coalesce(count(*), 0)::int`,
				distanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}), 0)::int`,
				durationSeconds: sql<number>`coalesce(sum(${activity.durationSeconds}), 0)::int`,
				longestRunMeters: sql<number>`coalesce(max(${activity.distanceMeters}), 0)::int`
			})
			.from(activity)
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted'),
					isNull(activity.workoutId)
				)
			)
	]);

	const linkedSummary = linked ?? {
		runs: 0,
		distanceMeters: 0,
		durationSeconds: 0,
		longestRunMeters: 0,
		currentPlanRuns: 0,
		currentPlanDistanceMeters: 0,
		archivedPlanRuns: 0,
		archivedPlanDistanceMeters: 0
	};
	const unlinkedSummary = unlinked ?? {
		runs: 0,
		distanceMeters: 0,
		durationSeconds: 0,
		longestRunMeters: 0
	};

	return {
		totalRuns: linkedSummary.runs + unlinkedSummary.runs,
		totalDistanceMeters: linkedSummary.distanceMeters + unlinkedSummary.distanceMeters,
		totalDurationSeconds: linkedSummary.durationSeconds + unlinkedSummary.durationSeconds,
		longestRunMeters: Math.max(linkedSummary.longestRunMeters, unlinkedSummary.longestRunMeters),
		currentPlanRuns: linkedSummary.currentPlanRuns,
		currentPlanDistanceMeters: linkedSummary.currentPlanDistanceMeters,
		archivedPlanRuns: linkedSummary.archivedPlanRuns,
		archivedPlanDistanceMeters: linkedSummary.archivedPlanDistanceMeters,
		unlinkedRuns: unlinkedSummary.runs,
		unlinkedDistanceMeters: unlinkedSummary.distanceMeters
	};
}
