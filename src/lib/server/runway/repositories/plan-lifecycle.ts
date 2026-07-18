import { and, asc, desc, eq, gte, inArray, lte, notInArray } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	athleteProfile,
	auditEvent,
	goal,
	trainingPlan,
	trainingWeek,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import { requireAthleteTimeZoneInTransaction } from './profiles';
import type { RunwayTransaction } from './transaction';
import { addDays, isValidTimeZone, todayIsoInTimeZone } from '$lib/training/date';
import { generatePlan } from '$lib/training/plan';
import {
	canUseDistancePlannerBaseline,
	derivePhaseBaseline,
	phaseTransitionOptions
} from '$lib/training/phase-transition';
import type {
	GeneratedPlan,
	PhaseBaseline,
	PhaseTransitionOption,
	PlanIntake,
	RaceDistance,
	RiskRating
} from '$lib/training/types';

export type PlanLifecycleReason = 'completed' | 'changed_goal' | 'abandoned';

export type PlanLifecycleResult = {
	planId: string;
	status: 'archived';
	lifecycleReason: PlanLifecycleReason;
	completedAt: Date | null;
	archivedAt: Date;
};

export type PhaseCompletionReview = {
	planId: string;
	phase: 'foundation' | 'calibration';
	goalKind: 'race' | 'foundation';
	goalTitle: string;
	baseline: PhaseBaseline;
	recommended: PhaseTransitionOption;
	options: PhaseTransitionOption[];
	racePlan: {
		risk: RiskRating;
		weeks: number;
		startDate: string;
		targetDate: string;
		warnings: string[];
	} | null;
};

export async function createGoalAndPlan(userId: string, intake: PlanIntake, timeZone: string) {
	if (!isValidTimeZone(timeZone)) throw new Error('Select a valid training time zone.');
	const phaseBlocked = intake.injuryFlags.currentPain || intake.injuryFlags.medicalRestriction;
	const generated = phaseBlocked ? null : generatePlan(intake);
	if (generated?.risk === 'unsafe') {
		throw new Error(
			"This goal is outside runway's plan-generation limits. Choose a later date or a shorter distance."
		);
	}
	if (generated && generated.weeks.length > 52) {
		throw new Error('Training plans cannot exceed 52 weeks.');
	}

	return db.transaction(async (tx) => {
		const established = intake.startMode === undefined || intake.startMode === 'established';
		const profileValues = {
			currentWeeklyDistanceMeters: established ? intake.currentWeeklyDistanceMeters : 0,
			currentRunsPerWeek: established ? intake.currentRunsPerWeek : 0,
			longestRecentRunMeters: established ? intake.longestRecentRunMeters : 0,
			preferredLongRunDay: established ? intake.preferredLongRunDay : null
		};
		await tx
			.insert(athleteProfile)
			.values({
				userId,
				units: intake.units,
				timeZone,
				...profileValues,
				experience: intake.experience,
				availability: intake.availability,
				injuryFlags: intake.injuryFlags
			})
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: {
					timeZone,
					...profileValues,
					experience: intake.experience,
					availability: intake.availability,
					injuryFlags: intake.injuryFlags,
					updatedAt: new Date()
				}
			});

		const archivedAt = new Date();
		await tx
			.update(trainingPlan)
			.set({
				status: 'archived',
				archivedAt,
				lifecycleReason: 'changed_goal',
				completedAt: null,
				updatedAt: archivedAt
			})
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')));
		await tx
			.update(goal)
			.set({ state: 'archived', updatedAt: archivedAt })
			.where(and(eq(goal.userId, userId), inArray(goal.state, ['pending', 'active'])));

		const goalKind = intake.goalKind ?? 'race';
		const raceDistance = goalKind === 'race' ? intake.raceDistance : null;
		const goalTargetDate =
			intake.targetDate ??
			generated?.targetDate ??
			addDays(intake.startDate ?? todayIsoInTimeZone(timeZone), 62);
		const [createdGoal] = await tx
			.insert(goal)
			.values({
				userId,
				title:
					goalKind === 'foundation'
						? 'Run continuously for 30 minutes'
						: raceDistance === null
							? 'Race plan'
							: `${labelRace(raceDistance)} plan`,
				kind: goalKind,
				state: phaseBlocked ? 'pending' : 'active',
				startMode: intake.startMode ?? 'established',
				distance: raceDistance,
				targetDate: goalTargetDate,
				priority: intake.priority
			})
			.returning();
		if (!createdGoal) throw new Error('Failed to create goal.');

		if (!generated) {
			await tx.insert(auditEvent).values({
				userId,
				eventType: 'goal.pending',
				detail: { goalId: createdGoal.id, reason: 'health_restriction' }
			});
			return { goal: createdGoal, plan: null, generated: null };
		}

		const createdPlan = await insertGeneratedPlan(tx, userId, createdGoal.id, generated);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'plan.created',
			detail: {
				goalKind,
				phase: generated.phase,
				weeks: generated.weeks.length,
				risk: generated.risk
			}
		});

		return { goal: createdGoal, plan: createdPlan, generated };
	});
}

export async function completeActivePlan(userId: string): Promise<PlanLifecycleResult | null> {
	return db.transaction(async (tx) => {
		const timeZone = await requireAthleteTimeZoneInTransaction(
			tx,
			userId,
			'Set training time zone before completing a plan.'
		);
		const [active] = await tx
			.select({
				id: trainingPlan.id,
				targetDate: trainingPlan.targetDate,
				phase: trainingPlan.phase,
				goalKind: goal.kind
			})
			.from(trainingPlan)
			.innerJoin(goal, and(eq(trainingPlan.goalId, goal.id), eq(goal.userId, userId)))
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
			.limit(1);
		if (!active) return null;
		if (active.targetDate > todayIsoInTimeZone(timeZone)) {
			throw new Error('The active plan cannot be completed before its target date.');
		}
		if (active.phase !== 'distance' && active.goalKind === 'race') {
			throw new Error('Confirm the recorded baseline before starting the retained race goal.');
		}
		return endActivePlan(tx, userId, active.id, 'completed');
	});
}

export async function getPhaseCompletionReview(
	userId: string
): Promise<PhaseCompletionReview | null> {
	return db.transaction(async (tx) => {
		const context = await phaseCompletionContext(tx, userId);
		return context ? phaseReviewFromContext(context) : null;
	});
}

export async function confirmPhaseBaseline(userId: string) {
	return db.transaction(async (tx) => {
		const context = await phaseCompletionContext(tx, userId);
		if (!context) throw new Error('There is no completed beginner phase to review.');
		const review = phaseReviewFromContext(context);
		if (!review.options.includes('confirm_race_baseline') || !context.racePlan) {
			throw new Error('The recorded work does not support this race ramp yet.');
		}

		const completedAt = new Date();
		const [archived] = await tx
			.update(trainingPlan)
			.set({
				status: 'archived',
				completedAt,
				archivedAt: completedAt,
				lifecycleReason: 'completed',
				updatedAt: completedAt
			})
			.where(
				and(
					eq(trainingPlan.id, context.plan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.returning({ id: trainingPlan.id });
		if (!archived) throw new Error('The beginner phase changed before confirmation.');

		await tx
			.update(athleteProfile)
			.set({
				currentWeeklyDistanceMeters: context.baseline.weeklyDistanceMeters,
				currentRunsPerWeek: normalizedBaselineRuns(context.baseline),
				longestRecentRunMeters: context.baseline.longestActivityMeters,
				preferredLongRunDay: context.preferredLongRunDay,
				updatedAt: completedAt
			})
			.where(eq(athleteProfile.userId, userId));

		const createdPlan = await insertGeneratedPlan(tx, userId, context.goal.id, context.racePlan);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'plan.phase_transitioned',
			detail: {
				fromPlanId: context.plan.id,
				toPlanId: createdPlan.id,
				fromPhase: context.plan.phase,
				baseline: context.baseline
			}
		});
		return { plan: createdPlan, baseline: context.baseline };
	});
}

export async function continueBeginnerPhase(userId: string) {
	return db.transaction(async (tx) => {
		const context = await phaseCompletionContext(tx, userId);
		if (!context) throw new Error('There is no completed beginner phase to continue.');
		const [lastWeek] = await tx
			.select()
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, context.plan.id)))
			.orderBy(desc(trainingWeek.weekNumber))
			.limit(1);
		if (!lastWeek) throw new Error('The beginner phase has no week to repeat.');
		const lastWorkouts = await tx
			.select()
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, context.plan.id),
					eq(workout.weekId, lastWeek.id),
					eq(workout.isRemoved, false)
				)
			)
			.orderBy(asc(workout.scheduledDate));
		const newWeekStart = addDays(lastWeek.startDate, 7);
		const [createdWeek] = await tx
			.insert(trainingWeek)
			.values({
				userId,
				planId: context.plan.id,
				weekNumber: lastWeek.weekNumber + 1,
				startDate: newWeekStart,
				targetDistanceMeters: lastWeek.targetDistanceMeters,
				targetDurationSeconds: lastWeek.targetDurationSeconds,
				longRunMeters: lastWeek.longRunMeters,
				risk: lastWeek.risk,
				isDownWeek: lastWeek.isDownWeek,
				isTaper: false
			})
			.returning();
		if (!createdWeek) throw new Error('The continuation week could not be created.');
		if (lastWorkouts.length > 0) {
			await tx.insert(workout).values(
				lastWorkouts.map((record) => ({
					userId,
					planId: context.plan.id,
					weekId: createdWeek.id,
					scheduledDate: addDays(record.scheduledDate, 7),
					type: record.type,
					status: 'planned' as const,
					prescriptionKind: record.prescriptionKind,
					targetDistanceMeters: record.targetDistanceMeters,
					targetDurationSeconds: record.targetDurationSeconds,
					intervalStructure: record.intervalStructure,
					intensity: record.intensity,
					purpose: record.purpose,
					reason: `Repeated after the runner reviewed the completed ${context.plan.phase} phase.`,
					sourceRefs: record.sourceRefs
				}))
			);
		}
		const targetDate = addDays(context.plan.targetDate, 7);
		await tx
			.update(trainingPlan)
			.set({ targetDate, weeks: context.plan.weeks + 1, updatedAt: new Date() })
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.id, context.plan.id)));
		if (context.goal.kind === 'foundation') {
			await tx
				.update(goal)
				.set({ targetDate, updatedAt: new Date() })
				.where(and(eq(goal.userId, userId), eq(goal.id, context.goal.id)));
		}
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'plan.phase_continued',
			detail: { planId: context.plan.id, phase: context.plan.phase, targetDate }
		});
		return { planId: context.plan.id, targetDate };
	});
}

export async function archiveActivePlan(
	userId: string,
	reason: Exclude<PlanLifecycleReason, 'completed'>
): Promise<PlanLifecycleResult | null> {
	if (reason !== 'changed_goal' && reason !== 'abandoned') {
		throw new Error('Unknown plan lifecycle reason.');
	}
	return db.transaction(async (tx) => {
		const [active] = await tx
			.select({ id: trainingPlan.id })
			.from(trainingPlan)
			.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
			.limit(1);
		if (!active) return null;
		return endActivePlan(tx, userId, active.id, reason);
	});
}

async function insertGeneratedPlan(
	tx: RunwayTransaction,
	userId: string,
	goalId: string,
	generated: GeneratedPlan
) {
	const [createdPlan] = await tx
		.insert(trainingPlan)
		.values({
			userId,
			goalId,
			status: 'active',
			startDate: generated.startDate,
			targetDate: generated.targetDate,
			weeks: generated.weeks.length,
			risk: generated.risk,
			phase: generated.phase,
			summary: generated.summary,
			sourceRefs: generated.sourceRefs
		})
		.returning();
	if (!createdPlan) throw new Error('Failed to create training plan.');

	for (const generatedWeek of generated.weeks) {
		const [createdWeek] = await tx
			.insert(trainingWeek)
			.values({
				userId,
				planId: createdPlan.id,
				weekNumber: generatedWeek.weekNumber,
				startDate: generatedWeek.startDate,
				targetDistanceMeters: generatedWeek.trainingTargetDistanceMeters,
				targetDurationSeconds: generatedWeek.targetDurationSeconds,
				longRunMeters: generatedWeek.longRunMeters,
				risk: generatedWeek.risk,
				isDownWeek: generatedWeek.isDownWeek,
				isTaper: generatedWeek.isTaper
			})
			.returning();
		if (!createdWeek) throw new Error('Failed to create training week.');

		await tx.insert(workout).values(
			generatedWeek.workouts.map((generatedWorkout) => ({
				userId,
				planId: createdPlan.id,
				weekId: createdWeek.id,
				scheduledDate: generatedWorkout.scheduledDate,
				type: generatedWorkout.type,
				prescriptionKind: generatedWorkout.prescription.kind,
				targetDistanceMeters: generatedWorkout.targetDistanceMeters,
				...(generatedWorkout.targetDurationSeconds === undefined
					? {}
					: { targetDurationSeconds: generatedWorkout.targetDurationSeconds }),
				...(generatedWorkout.prescription.kind === 'timed'
					? {
							intervalStructure: {
								warmupSeconds: generatedWorkout.prescription.warmupSeconds,
								cooldownSeconds: generatedWorkout.prescription.cooldownSeconds,
								blocks: generatedWorkout.prescription.blocks
							}
						}
					: {}),
				intensity: generatedWorkout.intensity,
				purpose: generatedWorkout.purpose,
				reason: generatedWorkout.reason,
				sourceRefs: generatedWorkout.sourceRefs
			}))
		);
	}
	return createdPlan;
}

type PhaseCompletionContext = {
	plan: typeof trainingPlan.$inferSelect & { phase: 'foundation' | 'calibration' };
	goal: typeof goal.$inferSelect;
	baseline: PhaseBaseline;
	racePlan: GeneratedPlan | null;
	preferredLongRunDay: number;
};

async function phaseCompletionContext(
	tx: RunwayTransaction,
	userId: string
): Promise<PhaseCompletionContext | null> {
	const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
	const [record] = await tx
		.select({ plan: trainingPlan, goal })
		.from(trainingPlan)
		.innerJoin(goal, and(eq(trainingPlan.goalId, goal.id), eq(goal.userId, userId)))
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
		.limit(1);
	if (
		!record ||
		record.plan.phase === 'distance' ||
		record.plan.targetDate > todayIsoInTimeZone(timeZone)
	) {
		return null;
	}
	const plan = record.plan as typeof trainingPlan.$inferSelect & {
		phase: 'foundation' | 'calibration';
	};
	const [profile] = await tx
		.select()
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	if (!profile) throw new Error('Training profile not found.');
	const baselineWindowWeeks = Math.min(2, plan.weeks);
	const baselineWindowStart = addDays(plan.targetDate, -(baselineWindowWeeks * 7 - 1));
	const acceptedActivities = await tx
		.select({
			workoutId: activity.workoutId,
			distanceMeters: activity.distanceMeters,
			durationSeconds: activity.durationSeconds
		})
		.from(activity)
		.where(
			and(
				eq(activity.userId, userId),
				eq(activity.reviewState, 'accepted'),
				gte(activity.activityDate, baselineWindowStart),
				lte(activity.activityDate, plan.targetDate)
			)
		);
	const linkedWorkoutIds = acceptedActivities.flatMap((item) =>
		item.workoutId ? [item.workoutId] : []
	);
	const feedbackRows = await tx
		.select({
			workoutId: workoutFeedback.workoutId,
			distanceMeters: workoutFeedback.completedDistanceMeters,
			durationSeconds: workoutFeedback.completedDurationSeconds
		})
		.from(workoutFeedback)
		.innerJoin(workout, and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId)))
		.where(
			and(
				eq(workoutFeedback.userId, userId),
				eq(workout.planId, plan.id),
				gte(workout.scheduledDate, baselineWindowStart),
				lte(workout.scheduledDate, plan.targetDate),
				...(linkedWorkoutIds.length > 0
					? [notInArray(workoutFeedback.workoutId, linkedWorkoutIds)]
					: [])
			)
		);
	const baseline = derivePhaseBaseline(
		[
			...acceptedActivities.map((item) => ({
				distanceMeters: item.distanceMeters,
				durationSeconds: item.durationSeconds,
				completed: true
			})),
			...feedbackRows.map((item) => ({
				distanceMeters: item.distanceMeters,
				durationSeconds: item.durationSeconds,
				completed: true
			}))
		],
		baselineWindowWeeks
	);
	const preferredLongRunDay = preferredBaselineLongRunDay(
		profile.availability,
		normalizedBaselineRuns(baseline)
	);
	let racePlan: GeneratedPlan | null = null;
	if (
		record.goal.kind === 'race' &&
		record.goal.distance &&
		canUseDistancePlannerBaseline(baseline)
	) {
		try {
			const generated = generatePlan({
				startMode: 'established',
				goalKind: 'race',
				raceDistance: record.goal.distance,
				targetDate: record.goal.targetDate,
				priority: record.goal.priority,
				units: 'metric',
				experience:
					profile.experience === 'new' ||
					profile.experience === 'returning' ||
					profile.experience === 'comfortable'
						? profile.experience
						: 'returning',
				availability: profile.availability,
				injuryFlags: profile.injuryFlags,
				startDate: addDays(plan.targetDate, 1),
				currentWeeklyDistanceMeters: baseline.weeklyDistanceMeters,
				currentRunsPerWeek: normalizedBaselineRuns(baseline),
				longestRecentRunMeters: baseline.longestActivityMeters,
				preferredLongRunDay
			});
			if (generated.phase === 'distance' && generated.risk !== 'unsafe') racePlan = generated;
		} catch {
			racePlan = null;
		}
	}
	return { plan, goal: record.goal, baseline, racePlan, preferredLongRunDay };
}

function phaseReviewFromContext(context: PhaseCompletionContext): PhaseCompletionReview {
	const transition = phaseTransitionOptions(
		context.plan.phase,
		context.goal.kind,
		context.baseline,
		Boolean(context.racePlan)
	);
	return {
		planId: context.plan.id,
		phase: context.plan.phase,
		goalKind: context.goal.kind,
		goalTitle: context.goal.title,
		baseline: context.baseline,
		recommended: transition.recommended,
		options: transition.options,
		racePlan:
			context.racePlan?.phase === 'distance'
				? {
						risk: context.racePlan.risk,
						weeks: context.racePlan.weeks.length,
						startDate: context.racePlan.startDate,
						targetDate: context.racePlan.targetDate,
						warnings: context.racePlan.summary.warnings
					}
				: null
	};
}

async function endActivePlan(
	tx: RunwayTransaction,
	userId: string,
	planId: string,
	reason: PlanLifecycleReason
): Promise<PlanLifecycleResult | null> {
	const archivedAt = new Date();
	const completedAt = reason === 'completed' ? archivedAt : null;
	const [ended] = await tx
		.update(trainingPlan)
		.set({
			status: 'archived',
			archivedAt,
			completedAt,
			lifecycleReason: reason,
			updatedAt: archivedAt
		})
		.where(
			and(
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.id, planId),
				eq(trainingPlan.status, 'active')
			)
		)
		.returning({ id: trainingPlan.id, goalId: trainingPlan.goalId });
	if (!ended) return null;
	await tx
		.update(goal)
		.set({ state: reason === 'completed' ? 'completed' : 'archived', updatedAt: archivedAt })
		.where(and(eq(goal.userId, userId), eq(goal.state, 'active'), eq(goal.id, ended.goalId)));
	await tx.insert(auditEvent).values({
		userId,
		eventType: 'plan.archived',
		detail: { planId, reason }
	});
	return { planId, status: 'archived', lifecycleReason: reason, completedAt, archivedAt };
}

function normalizedBaselineRuns(baseline: PhaseBaseline): number {
	return Math.max(2, Math.min(5, Math.round(baseline.runsPerWeek)));
}

function preferredBaselineLongRunDay(availability: number[], runCount: number): number {
	for (const day of availability) {
		const recoveryDay = (day + 1) % 7;
		if (availability.filter((candidate) => candidate !== recoveryDay).length >= runCount) {
			return day;
		}
	}
	return availability[0] ?? 6;
}

function labelRace(distance: RaceDistance): string {
	const labels: Record<RaceDistance, string> = {
		'5k': '5K',
		'10k': '10K',
		half: 'Half marathon',
		marathon: 'Marathon'
	};
	return labels[distance];
}
