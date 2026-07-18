import { and, desc, eq, gte, lte, sql } from 'drizzle-orm';
import { trainingWeek, workout } from '$lib/server/db/schema';
import { addDays, parseIsoDate } from '$lib/training/date';
import type { RunwayTransaction } from './transaction';

export function isoWeekStart(date: string) {
	const parsed = parseIsoDate(date);
	const day = parsed.getUTCDay();
	return addDays(date, day === 0 ? -6 : 1 - day);
}

export async function effectiveWeekTargetDistance(
	tx: RunwayTransaction,
	userId: string,
	planId: string,
	date: string
) {
	const start = isoWeekStart(date);
	const [result] = await tx
		.select({
			targetDistanceMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}) filter (where ${workout.type} not in ('rest', 'race')), 0)::int`
		})
		.from(workout)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, planId),
				eq(workout.isRemoved, false),
				gte(workout.scheduledDate, start),
				lte(workout.scheduledDate, addDays(start, 6))
			)
		);
	return result?.targetDistanceMeters ?? 0;
}

export async function effectiveWeekTargetDuration(
	tx: RunwayTransaction,
	userId: string,
	planId: string,
	date: string
) {
	const start = isoWeekStart(date);
	const [result] = await tx
		.select({
			targetDurationSeconds: sql<number>`coalesce(sum(${workout.targetDurationSeconds}) filter (where ${workout.type} not in ('rest', 'race')), 0)::int`
		})
		.from(workout)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, planId),
				eq(workout.isRemoved, false),
				gte(workout.scheduledDate, start),
				lte(workout.scheduledDate, addDays(start, 6))
			)
		);
	return result?.targetDurationSeconds ?? 0;
}

export async function planWeekIdForDate(
	tx: RunwayTransaction,
	userId: string,
	planId: string,
	date: string
) {
	const [candidate] = await tx
		.select({ id: trainingWeek.id, startDate: trainingWeek.startDate })
		.from(trainingWeek)
		.where(
			and(
				eq(trainingWeek.userId, userId),
				eq(trainingWeek.planId, planId),
				lte(trainingWeek.startDate, date)
			)
		)
		.orderBy(desc(trainingWeek.startDate))
		.limit(1);
	return candidate && date <= addDays(candidate.startDate, 6) ? candidate.id : null;
}
