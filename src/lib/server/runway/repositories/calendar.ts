import { and, asc, desc, eq, gte, lte, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { activity, trainingWeek, workout, workoutFeedback } from '$lib/server/db/schema';
import { addDays, parseIsoDate } from '$lib/training/date';
import type {
	TrainingCalendarActivity,
	TrainingCalendarFeedback,
	TrainingCalendarPayload,
	TrainingCalendarWeek,
	TrainingCalendarWorkout
} from '$lib/training/calendar-view';
import { getLatestWorkoutAdjustments, getWorkoutRecommendationTraces } from './adjustment-ledger';
import { effectivePlanRisk, getPlanWeeks } from './plan-queries';
import { currentTrainingSignal } from './training-signal';
import { getTrainingReadContext, type TrainingReadContext } from './training-read-context';

export const CALENDAR_ACTIVITY_LIMIT = 500;

export function boundCalendarActivities<T>(rowsNewestFirst: T[]) {
	return {
		activities: rowsNewestFirst.slice(0, CALENDAR_ACTIVITY_LIMIT).reverse(),
		activityOverflow: {
			limit: CALENDAR_ACTIVITY_LIMIT,
			truncated: rowsNewestFirst.length > CALENDAR_ACTIVITY_LIMIT
		}
	};
}

async function readBoundedCalendarActivities(userId: string, rangeStart: string, rangeEnd: string) {
	const rows = await db
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
			hasHeartRateSeries: sql<boolean>`${activity.heartRateSeries} is not null`,
			hasRouteTrace: sql<boolean>`${activity.routeTrace} is not null`,
			averageCadence: activity.averageCadence,
			feltHard: activity.feltHard,
			pain: activity.pain,
			extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed,
			consequence: activity.consequence,
			routeSummary: activity.routeSummary,
			matchedWorkoutPurpose: workout.purpose,
			matchedWorkoutDate: workout.scheduledDate
		})
		.from(activity)
		.leftJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
		.where(
			and(
				eq(activity.userId, userId),
				eq(activity.reviewState, 'accepted'),
				gte(activity.activityDate, rangeStart),
				lte(activity.activityDate, rangeEnd)
			)
		)
		.orderBy(desc(activity.occurredAt))
		.limit(CALENDAR_ACTIVITY_LIMIT + 1);
	const bounded = boundCalendarActivities(rows);
	return {
		activities: bounded.activities.map((record) => ({
			...record,
			occurredDate: record.activityDate
		})),
		activityOverflow: bounded.activityOverflow
	};
}

async function readCurrentCalendarWeek(userId: string, planId: string, today: string) {
	const [currentWeek] = await db
		.select()
		.from(trainingWeek)
		.where(
			and(
				eq(trainingWeek.userId, userId),
				eq(trainingWeek.planId, planId),
				lte(trainingWeek.startDate, today)
			)
		)
		.orderBy(desc(trainingWeek.startDate))
		.limit(1);
	return currentWeek ?? null;
}

function readCalendarWorkouts(
	userId: string,
	planId: string,
	rangeStart: string,
	rangeEnd: string
) {
	return db
		.select({
			id: workout.id,
			weekId: workout.weekId,
			weekNumber: trainingWeek.weekNumber,
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
			sourceRefs: workout.sourceRefs,
			isRemoved: workout.isRemoved,
			weekTargetDistanceMeters: trainingWeek.targetDistanceMeters
		})
		.from(workout)
		.innerJoin(trainingWeek, eq(workout.weekId, trainingWeek.id))
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, planId),
				eq(trainingWeek.userId, userId),
				gte(workout.scheduledDate, rangeStart),
				lte(workout.scheduledDate, rangeEnd)
			)
		)
		.orderBy(asc(workout.scheduledDate));
}

function readCalendarFeedback(
	userId: string,
	planId: string,
	rangeStart: string,
	rangeEnd: string
) {
	return db
		.select({
			id: workoutFeedback.id,
			workoutId: workoutFeedback.workoutId,
			completedDistanceMeters: workoutFeedback.completedDistanceMeters,
			completedDurationSeconds: workoutFeedback.completedDurationSeconds,
			feltHard: workoutFeedback.feltHard,
			pain: workoutFeedback.pain,
			consequence: workoutFeedback.consequence,
			createdAt: workoutFeedback.createdAt,
			canDelete: sql<boolean>`${activity.id} is null`
		})
		.from(workoutFeedback)
		.innerJoin(workout, and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId)))
		.leftJoin(
			activity,
			and(
				eq(activity.workoutId, workout.id),
				eq(activity.userId, userId),
				eq(activity.reviewState, 'accepted')
			)
		)
		.where(
			and(
				eq(workoutFeedback.userId, userId),
				eq(workout.planId, planId),
				gte(workout.scheduledDate, rangeStart),
				lte(workout.scheduledDate, rangeEnd)
			)
		)
		.orderBy(desc(workoutFeedback.createdAt));
}

async function readCalendarPlanStats(userId: string, planId: string) {
	const [stats] = await db
		.select({
			plannedMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}), 0)`,
			doneCount: sql<number>`count(*) filter (where ${workout.status} = 'done')`,
			runCount: sql<number>`count(*) filter (where ${workout.type} <> 'rest')`
		})
		.from(workout)
		.where(
			and(eq(workout.userId, userId), eq(workout.planId, planId), eq(workout.isRemoved, false))
		);
	return stats ?? null;
}

export async function getTrainingCalendar(
	userId: string,
	options?: { month?: string | null; context?: TrainingReadContext }
) {
	const context = options?.context ?? (await getTrainingReadContext(userId));
	if (!context.timeZone || !context.today) throw new Error('Set training time zone first.');
	const activePlan = context.activePlan;
	const today = context.today;
	const month = parseCalendarMonth(options?.month, today);
	const previousMonth = shiftCalendarMonth(month, -1);
	const nextMonth = shiftCalendarMonth(month, 1);
	const currentMonth = today.slice(0, 7);
	const { rangeStart, rangeEnd } = calendarMonthRange(month);

	if (!activePlan) {
		const activityPage = await readBoundedCalendarActivities(userId, rangeStart, rangeEnd);

		return {
			activePlan: null,
			currentWeek: null,
			stats: null,
			currentSignal: null,
			calendar: buildTrainingCalendarPayload({
				today,
				month,
				previousMonth,
				nextMonth,
				currentMonth,
				rangeStart,
				rangeEnd,
				weeks: [],
				workouts: [],
				activities: activityPage.activities,
				feedback: [],
				activityOverflow: activityPage.activityOverflow
			})
		};
	}

	const planId = activePlan.plan.id;
	const allEffectiveWeeks = await getPlanWeeks(userId, planId);
	const [currentWeek, workouts, activityPage, feedback, stats, currentSignal] = await Promise.all([
		readCurrentCalendarWeek(userId, planId, today),
		readCalendarWorkouts(userId, planId, rangeStart, rangeEnd),
		readBoundedCalendarActivities(userId, rangeStart, rangeEnd),
		readCalendarFeedback(userId, planId, rangeStart, rangeEnd),
		readCalendarPlanStats(userId, planId),
		currentTrainingSignal(
			userId,
			activePlan.plan,
			today,
			effectivePlanRisk(allEffectiveWeeks, activePlan.plan.risk)
		)
	]);
	const weeks = allEffectiveWeeks.filter(
		(week) => week.startDate >= rangeStart && week.startDate <= rangeEnd
	);

	const workoutIds = workouts.map((record) => record.id);
	const [latestAdjustmentsByWorkout, recommendationTraces] = await Promise.all([
		getLatestWorkoutAdjustments(userId, workoutIds),
		getWorkoutRecommendationTraces(userId, workoutIds)
	]);
	const calendarWorkouts: TrainingCalendarWorkout[] = workouts.map((record) => {
		const trace = recommendationTraces.get(record.id);
		return {
			...record,
			weekTargetDistanceMeters:
				allEffectiveWeeks.find((week) => week.id === record.weekId)?.targetDistanceMeters ??
				record.weekTargetDistanceMeters,
			adjustment: latestAdjustmentsByWorkout.get(record.id) ?? null,
			recommended: trace
				? trace.recommended
				: {
						scheduledDate: record.scheduledDate,
						type: record.type,
						prescriptionKind: record.prescriptionKind,
						targetDistanceMeters: record.targetDistanceMeters,
						targetDurationSeconds: record.targetDurationSeconds,
						intervalStructure: record.intervalStructure,
						purpose: record.purpose
					},
			isEdited: trace?.isEdited ?? false
		};
	});

	const calendar = buildTrainingCalendarPayload({
		today,
		month,
		previousMonth,
		nextMonth,
		currentMonth,
		rangeStart,
		rangeEnd,
		weeks,
		workouts: calendarWorkouts,
		activities: activityPage.activities,
		feedback,
		activityOverflow: activityPage.activityOverflow,
		planScale: {
			baselineMeters:
				activePlan.plan.summary.kind === 'distance' ? activePlan.plan.summary.baselineMeters : 0,
			peakMeters: Math.max(
				activePlan.plan.summary.kind === 'distance' ? activePlan.plan.summary.peakMeters : 0,
				...allEffectiveWeeks.map((week) => week.targetDistanceMeters)
			)
		}
	});

	return {
		activePlan,
		currentWeek:
			allEffectiveWeeks.find((week) => week.id === currentWeek?.id) ?? currentWeek ?? null,
		stats: stats ?? null,
		currentSignal,
		calendar
	};
}

export function parseCalendarMonth(value: string | null | undefined, today: string): string {
	if (value && /^\d{4}-\d{2}$/.test(value)) {
		const timestamp = Date.parse(`${value}-01T00:00:00.000Z`);
		if (!Number.isNaN(timestamp)) return value;
	}
	return today.slice(0, 7);
}

export function shiftCalendarMonth(month: string, offset: number): string {
	const [yearText, monthText] = month.split('-');
	const year = Number(yearText);
	const monthIndex = Number(monthText) - 1;
	const date = new Date(Date.UTC(year, monthIndex + offset, 1));
	return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

export function calendarMonthRange(month: string): { rangeStart: string; rangeEnd: string } {
	const monthStart = `${month}-01`;
	const nextMonthStart = `${shiftCalendarMonth(month, 1)}-01`;
	const monthEnd = addDays(nextMonthStart, -1);
	return {
		rangeStart: weekStartForIsoDate(monthStart),
		rangeEnd: addDays(weekStartForIsoDate(monthEnd), 6)
	};
}

export function weekStartForIsoDate(date: string): string {
	const day = parseIsoDate(date).getUTCDay();
	return addDays(date, day === 0 ? -6 : 1 - day);
}

export function buildTrainingCalendarPayload(input: {
	today: string;
	month: string;
	previousMonth: string;
	nextMonth: string;
	currentMonth: string;
	rangeStart: string;
	rangeEnd: string;
	weeks: Omit<
		TrainingCalendarWeek,
		| 'completedDistanceMeters'
		| 'completedDurationSeconds'
		| 'eventCompletedDistanceMeters'
		| 'completedRuns'
		| 'plannedRuns'
		| 'painFlags'
		| 'hardFlags'
	>[];
	workouts: TrainingCalendarWorkout[];
	activities: TrainingCalendarActivity[];
	feedback: TrainingCalendarFeedback[];
	activityOverflow: TrainingCalendarPayload['activityOverflow'];
	planScale?: { baselineMeters: number; peakMeters: number } | null;
}): TrainingCalendarPayload {
	const feedbackByWorkout = new Map(input.feedback.map((record) => [record.workoutId, record]));
	const activitiesByWorkout = new Map<
		string,
		{ distanceMeters: number; durationSeconds: number; activities: TrainingCalendarActivity[] }
	>();
	for (const record of input.activities) {
		if (record.reviewState !== 'accepted') continue;
		if (!record.workoutId) continue;
		const current = activitiesByWorkout.get(record.workoutId) ?? {
			distanceMeters: 0,
			durationSeconds: 0,
			activities: []
		};
		current.distanceMeters += record.distanceMeters;
		current.durationSeconds += record.durationSeconds ?? 0;
		current.activities.push(record);
		activitiesByWorkout.set(record.workoutId, current);
	}

	const summaries = new Map(
		input.weeks.map((week) => [
			week.id,
			{
				completedDistanceMeters: 0,
				completedDurationSeconds: 0,
				eventCompletedDistanceMeters: 0,
				completedRuns: 0,
				plannedRuns: 0,
				painFlags: 0,
				hardFlags: 0
			}
		])
	);
	const countedActivities = new Set<string>();

	for (const record of input.workouts) {
		const summary = summaries.get(record.weekId);
		if (!summary || record.type === 'rest' || record.isRemoved) continue;
		summary.plannedRuns += 1;

		const imported = activitiesByWorkout.get(record.id);
		for (const importedActivity of imported?.activities ?? [])
			countedActivities.add(importedActivity.id);

		const feedback = feedbackByWorkout.get(record.id);
		const completedMeters =
			imported?.distanceMeters ??
			feedback?.completedDistanceMeters ??
			(record.status === 'done' ? record.targetDistanceMeters : 0);
		const completedSeconds =
			imported?.durationSeconds ??
			feedback?.completedDurationSeconds ??
			(record.status === 'done' ? (record.targetDurationSeconds ?? 0) : 0);

		if (completedMeters > 0 || completedSeconds > 0 || record.status === 'done')
			summary.completedRuns += 1;
		if (record.type === 'race') summary.eventCompletedDistanceMeters += completedMeters;
		else {
			summary.completedDistanceMeters += completedMeters;
			summary.completedDurationSeconds += completedSeconds;
		}
		if (feedback?.pain) summary.painFlags += 1;
		if (feedback?.feltHard) summary.hardFlags += 1;
	}

	for (const record of input.activities) {
		if (record.reviewState !== 'accepted') continue;
		if (countedActivities.has(record.id)) continue;
		const week = input.weeks.find(
			(candidate) =>
				record.occurredDate >= candidate.startDate &&
				record.occurredDate <= addDays(candidate.startDate, 6)
		);
		const summary = week ? summaries.get(week.id) : undefined;
		if (!summary) continue;
		summary.completedDistanceMeters += record.distanceMeters;
		summary.completedDurationSeconds += record.durationSeconds ?? 0;
		if (record.distanceMeters > 0 || (record.durationSeconds ?? 0) > 0) summary.completedRuns += 1;
		if (record.pain) summary.painFlags += 1;
		if (record.feltHard) summary.hardFlags += 1;
	}

	return {
		today: input.today,
		month: input.month,
		previousMonth: input.previousMonth,
		nextMonth: input.nextMonth,
		currentMonth: input.currentMonth,
		rangeStart: input.rangeStart,
		rangeEnd: input.rangeEnd,
		weeks: input.weeks.map((week) => ({
			...week,
			...(summaries.get(week.id) ?? {
				completedDistanceMeters: 0,
				completedDurationSeconds: 0,
				eventCompletedDistanceMeters: 0,
				completedRuns: 0,
				plannedRuns: 0,
				painFlags: 0,
				hardFlags: 0
			})
		})),
		workouts: input.workouts,
		activities: input.activities,
		feedback: input.feedback,
		planScale: input.planScale ?? null,
		activityOverflow: input.activityOverflow
	};
}
