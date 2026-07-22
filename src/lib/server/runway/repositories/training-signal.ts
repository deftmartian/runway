import { and, desc, eq, gte, isNotNull, isNull, lte, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	athleteProfile,
	trainingPlan,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { formatConsequenceAuditReason } from '$lib/training/consequence-presentation';
import { addDays } from '$lib/training/date';
import type {
	ConsequenceResult,
	InjuryFlags,
	PlanDecision,
	RiskRating,
	TrainingHealthNotice
} from '$lib/training/types';

const riskRank: Record<RiskRating, number> = {
	conservative: 0,
	moderate: 1,
	aggressive: 2,
	unsafe: 3
};

export type RecordedTrainingEvidence = {
	consequence: ConsequenceResult;
	appliedDecision: PlanDecision | null;
	createdAt: Date;
	evidenceDate: string;
	source: 'feedback' | 'activity';
};

export function healthNoticeFor(flags: InjuryFlags): TrainingHealthNotice | null {
	if (flags.medicalRestriction) {
		return {
			level: 'paused',
			heading: 'Running limit recorded',
			message:
				'A clinician-imposed running limit is active. The schedule remains recorded, but runway does not treat it as clearance to continue.'
		};
	}
	if (flags.currentPain) {
		return {
			level: 'paused',
			heading: 'Pain is present now',
			message:
				'The schedule remains recorded, but runway does not treat it as clearance to continue. Seek qualified guidance if pain persists, worsens, or changes how you move.'
		};
	}
	if (flags.recentInjury || flags.recurringPain) {
		return {
			level: 'caution',
			heading: 'Health context noted',
			message:
				'Recovery or recurring pain is recorded. It changes the distance-ramp assessment, but it does not determine whether running is appropriate.'
		};
	}
	return null;
}

export function currentSignalReasonsFor(input: {
	planRisk: RiskRating;
	planWarnings: string[];
	selectedConsequence: ConsequenceResult | null;
	planHasMixedLoad?: boolean;
}) {
	const reasons: string[] = [];
	if (
		input.selectedConsequence &&
		riskRank[input.selectedConsequence.risk] >= riskRank[input.planRisk]
	) {
		reasons.push(formatConsequenceAuditReason(input.selectedConsequence));
	}
	reasons.push(
		...input.planWarnings.filter(
			(warning) =>
				!isStoredHealthContextWarning(warning) &&
				(!input.planHasMixedLoad || !isStoredNumericRampWarning(warning))
		)
	);
	if (reasons.length === 0 && input.planRisk !== 'conservative' && !input.planHasMixedLoad) {
		reasons.push("The saved plan is above runway's default ramp.");
	}
	return Array.from(new Set(reasons)).slice(0, 3);
}

function isStoredHealthContextWarning(warning: string): boolean {
	return (
		warning.startsWith('Injury recovery or recurring pain is included') ||
		warning.startsWith('Recent injury or recurring pain is noted')
	);
}

function isStoredNumericRampWarning(warning: string): boolean {
	return (
		warning.startsWith('The required weekly increase is above') ||
		warning.startsWith('Weekly distance growth above 10%')
	);
}

export function selectCurrentTrainingSignal(input: {
	planRisk: RiskRating;
	planWarnings: string[];
	recordedEvidence: RecordedTrainingEvidence[];
	planHasMixedLoad?: boolean;
	healthNotice?: TrainingHealthNotice | null;
}) {
	const latestEvidence = [...input.recordedEvidence].sort(
		(left, right) =>
			right.evidenceDate.localeCompare(left.evidenceDate) ||
			right.createdAt.getTime() - left.createdAt.getTime()
	)[0];
	const latestIsResolved =
		latestEvidence !== undefined &&
		(latestEvidence.appliedDecision !== null ||
			latestEvidence.consequence.appliedDecision !== null);
	const selected = latestEvidence && !latestIsResolved ? latestEvidence : undefined;
	const recordedEvidenceWins =
		selected !== undefined && riskRank[selected.consequence.risk] >= riskRank[input.planRisk];
	const selectedConsequence = recordedEvidenceWins ? selected.consequence : null;

	return {
		risk: recordedEvidenceWins ? selected.consequence.risk : input.planRisk,
		consequence: selectedConsequence,
		reasons: currentSignalReasonsFor({
			planRisk: input.planRisk,
			planWarnings: input.planWarnings,
			selectedConsequence,
			planHasMixedLoad: input.planHasMixedLoad ?? false
		}),
		source: recordedEvidenceWins ? selected.source : ('plan' as const),
		planComparisonStatus: input.planHasMixedLoad ? ('mixed' as const) : ('comparable' as const),
		healthNotice: input.healthNotice ?? null
	};
}

export async function currentTrainingSignal(
	userId: string,
	plan: typeof trainingPlan.$inferSelect,
	today: string,
	effectivePlanRisk: RiskRating = plan.risk,
	planHasMixedLoad = false
) {
	const recentStart = addDays(today, -28);
	const [feedbackRows, activityRows, profileRows] = await Promise.all([
		db
			.select({
				consequence: workoutFeedback.consequence,
				appliedDecision: workoutFeedback.appliedDecision,
				createdAt: workoutFeedback.createdAt,
				evidenceDate: workout.scheduledDate,
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
			.orderBy(desc(workout.scheduledDate), desc(workoutFeedback.createdAt))
			.limit(100),
		db
			.select({
				consequence: activity.consequence,
				appliedDecision: activity.appliedDecision,
				createdAt: activity.createdAt,
				evidenceDate: activity.activityDate,
				source: sql<'activity'>`'activity'`
			})
			.from(activity)
			.where(
				and(
					eq(activity.userId, userId),
					eq(activity.reviewState, 'accepted'),
					eq(activity.consequencePlanId, plan.id),
					gte(activity.activityDate, recentStart),
					lte(activity.activityDate, today),
					isNotNull(activity.consequence)
				)
			)
			.orderBy(desc(activity.activityDate), desc(activity.createdAt))
			.limit(100),
		db
			.select({ injuryFlags: athleteProfile.injuryFlags })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
	]);
	return selectCurrentTrainingSignal({
		planRisk: effectivePlanRisk,
		planWarnings: plan.summary.warnings,
		planHasMixedLoad,
		healthNotice: profileRows[0] ? healthNoticeFor(profileRows[0].injuryFlags) : null,
		recordedEvidence: [
			...feedbackRows,
			...activityRows.flatMap((record) =>
				record.consequence ? [{ ...record, consequence: record.consequence }] : []
			)
		]
	});
}
