import { and, desc, eq, gte, isNotNull, isNull, lte, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { activity, trainingPlan, workout, workoutFeedback } from '$lib/server/db/schema';
import { formatConsequenceAuditReason } from '$lib/training/consequence-presentation';
import { addDays } from '$lib/training/date';
import type { ConsequenceResult, RiskRating } from '$lib/training/types';

const riskRank: Record<RiskRating, number> = {
	conservative: 0,
	moderate: 1,
	aggressive: 2,
	unsafe: 3
};

export function currentSignalReasonsFor(input: {
	planRisk: RiskRating;
	planWarnings: string[];
	selectedConsequence: ConsequenceResult | null;
}) {
	const reasons: string[] = [];
	if (
		input.selectedConsequence &&
		riskRank[input.selectedConsequence.risk] >= riskRank[input.planRisk]
	) {
		reasons.push(formatConsequenceAuditReason(input.selectedConsequence));
	}
	reasons.push(...input.planWarnings);
	if (reasons.length === 0 && input.planRisk !== 'conservative') {
		reasons.push("The saved plan is above runway's default ramp.");
	}
	return Array.from(new Set(reasons)).slice(0, 3);
}

export async function currentTrainingSignal(
	userId: string,
	plan: typeof trainingPlan.$inferSelect,
	today: string
) {
	const recentStart = addDays(today, -28);
	const [feedbackRows, activityRows] = await Promise.all([
		db
			.select({
				consequence: workoutFeedback.consequence,
				createdAt: workoutFeedback.createdAt,
				source: sql<'feedback'>`'feedback'`
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.leftJoin(activity, and(eq(activity.workoutId, workout.id), eq(activity.userId, userId)))
			.where(
				and(
					eq(workoutFeedback.userId, userId),
					eq(workout.planId, plan.id),
					isNull(activity.id),
					gte(workout.scheduledDate, recentStart),
					lte(workout.scheduledDate, today)
				)
			)
			.orderBy(desc(workoutFeedback.createdAt))
			.limit(100),
		db
			.select({
				consequence: activity.consequence,
				createdAt: activity.createdAt,
				source: sql<'activity'>`'activity'`
			})
			.from(activity)
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted'),
					gte(activity.activityDate, recentStart),
					lte(activity.activityDate, today),
					isNotNull(activity.consequence)
				)
			)
			.orderBy(desc(activity.createdAt))
			.limit(100)
	]);
	const selected = [
		...feedbackRows,
		...activityRows.flatMap((record) =>
			record.consequence ? [{ ...record, consequence: record.consequence }] : []
		)
	].sort(
		(left, right) =>
			riskRank[right.consequence.risk] - riskRank[left.consequence.risk] ||
			right.createdAt.getTime() - left.createdAt.getTime()
	)[0];
	const recordedSignalWins =
		selected !== undefined && riskRank[selected.consequence.risk] >= riskRank[plan.risk];
	const selectedConsequence = recordedSignalWins ? selected.consequence : null;
	return {
		risk: recordedSignalWins ? selected.consequence.risk : plan.risk,
		consequence: selectedConsequence,
		reasons: currentSignalReasonsFor({
			planRisk: plan.risk,
			planWarnings: plan.summary.warnings,
			selectedConsequence
		}),
		source: recordedSignalWins ? selected.source : ('plan' as const)
	};
}
