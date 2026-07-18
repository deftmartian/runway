import {
	and,
	asc,
	desc,
	eq,
	gt,
	gte,
	inArray,
	isNotNull,
	isNull,
	lt,
	lte,
	ne,
	notInArray,
	or,
	sql
} from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	activityDeletionTombstone,
	activityImport,
	athleteProfile,
	auditEvent,
	goal,
	importSource,
	importSourceItem,
	planAdjustment,
	trainingPlan,
	trainingWeek,
	user as authUser,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import {
	addDays,
	isValidTimeZone,
	localDateAtNoon,
	parseIsoDate,
	toIsoDateInTimeZone,
	todayIsoInTimeZone
} from '$lib/training/date';
import { calculateConsequence, withAppliedDecision } from '$lib/training/consequences';
import { formatConsequenceAuditReason } from '$lib/training/consequence-presentation';
import { buildActivityRouteTrace, buildHeartRateSeries } from '$lib/training/activity-trace';
import { selectAutoWorkoutMatch } from '$lib/training/activity-match';
import { calculateExtraActivityConsequence } from '$lib/training/extra-activity';
import { buildHeartRateSettings, summarizeHeartRateEffort } from '$lib/training/heart-rate';
import { generatePlan } from '$lib/training/plan';
import {
	canUseDistancePlannerBaseline,
	derivePhaseBaseline,
	phaseTransitionOptions
} from '$lib/training/phase-transition';
import {
	previewWorkoutEdit as buildWorkoutEditPreview,
	proposalFromWorkout,
	rebalanceWorkoutStates,
	resizeTimedIntervalStructure,
	type EditableWorkoutState,
	type WorkoutEditPreview,
	type WorkoutEditProposal
} from '$lib/training/workout-edit';
import type {
	ConsequenceResult,
	GeneratedPlan,
	PhaseBaseline,
	PhaseTransitionOption,
	PlanIntake,
	PlanDecision,
	ParsedGpxActivity,
	RaceDistance,
	RiskRating,
	SexForEstimates,
	TimedIntervalStructure,
	TrainingIntake
} from '$lib/training/types';
import type {
	TrainingCalendarActivity,
	TrainingCalendarFeedback,
	TrainingCalendarPayload,
	TrainingPlanAdjustment,
	TrainingCalendarWeek,
	TrainingCalendarWorkout
} from '$lib/training/calendar-view';
import {
	replayWorkoutAdjustments,
	type ReplayableWorkoutState
} from '$lib/server/runway/adjustment-replay';

type RunwayTransaction = Parameters<Parameters<typeof db.transaction>[0]>[0];
type PlanAdjustmentTrigger = (typeof planAdjustment.$inferSelect)['triggerType'];
type WorkoutAdjustmentState = NonNullable<(typeof planAdjustment.$inferSelect)['previousState']>;
type WorkoutStateRecord = Omit<
	ReplayableWorkoutState,
	'prescriptionKind' | 'intervalStructure' | 'isRemoved'
> &
	Partial<Pick<ReplayableWorkoutState, 'prescriptionKind' | 'intervalStructure' | 'isRemoved'>>;
type PlanAdjustmentInput = {
	userId: string;
	planId: string;
	workoutId: string;
	triggerType: PlanAdjustmentTrigger;
	triggerId?: string | null;
	previousState: WorkoutAdjustmentState;
	newState: WorkoutAdjustmentState;
	consequence?: ConsequenceResult | null;
	reason: string;
};

export type PlanLifecycleReason = 'completed' | 'changed_goal' | 'abandoned';
export type PlanLifecycleResult = {
	planId: string;
	status: 'archived';
	lifecycleReason: PlanLifecycleReason;
	completedAt: Date | null;
	archivedAt: Date;
};

export async function createGoalAndPlan(userId: string, intake: PlanIntake, timeZone: string) {
	if (!isValidTimeZone(timeZone)) throw new Error('Select a valid training time zone.');
	const phaseBlocked = intake.injuryFlags.currentPain || intake.injuryFlags.medicalRestriction;
	const generated = phaseBlocked ? null : generatePlan(intake);
	if (generated?.risk === 'unsafe') throw new Error('Unsafe training plan rejected.');
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

export async function getAthleteProfile(userId: string) {
	const [profile] = await db
		.select()
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile ?? null;
}

export async function getActivityImportGeneration(userId: string): Promise<number> {
	const [profile] = await db
		.select({ generation: athleteProfile.activityImportGeneration })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile?.generation ?? 0;
}

export async function updateAthleteTimeZone(userId: string, timeZone: string) {
	if (!isValidTimeZone(timeZone)) throw new Error('Choose a valid IANA time zone.');
	return db.transaction(async (tx) => {
		await tx
			.insert(athleteProfile)
			.values({ userId, timeZone })
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: { timeZone, updatedAt: new Date() }
			});
		return { timeZone };
	});
}

export async function updateRouteDataMode(userId: string, routeDataMode: 'discard' | 'private') {
	return db.transaction(async (tx) => {
		await tx
			.insert(athleteProfile)
			.values({ userId, routeDataMode })
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: { routeDataMode, updatedAt: new Date() }
			});

		const cleared =
			routeDataMode === 'discard'
				? await tx
						.update(activity)
						.set({
							routeTrace: null,
							routeSummary: sql`jsonb_set(jsonb_set(${activity.routeSummary}, '{traceRetained}', 'false'::jsonb, true), '{startEndRedacted}', 'true'::jsonb, true)`
						})
						.where(and(eq(activity.userId, userId), isNotNull(activity.routeTrace)))
						.returning({ id: activity.id })
				: [];

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'profile.route_privacy_updated',
			detail: {
				routeDataMode,
				clearedRouteCount: cleared.length
			}
		});

		return { routeDataMode, clearedRouteCount: cleared.length };
	});
}

export async function updateTrainingProfile(
	userId: string,
	input: {
		sexForEstimates: SexForEstimates;
		ageYears?: number | undefined;
		heartRateSettingsSource: 'estimated' | 'custom';
		maxHeartRateBpm: number;
		zone2FloorBpm: number;
		zone3FloorBpm: number;
		zone4FloorBpm: number;
		zone5FloorBpm: number;
	}
) {
	const heartRateSettings = buildHeartRateSettings({
		maxHeartRateBpm: input.maxHeartRateBpm,
		source: input.heartRateSettingsSource,
		zone2FloorBpm: input.zone2FloorBpm,
		zone3FloorBpm: input.zone3FloorBpm,
		zone4FloorBpm: input.zone4FloorBpm,
		zone5FloorBpm: input.zone5FloorBpm
	});

	await db.transaction(async (tx) => {
		await tx
			.insert(athleteProfile)
			.values({
				userId,
				units: 'metric',
				sexForEstimates: input.sexForEstimates,
				...(input.ageYears === undefined ? {} : { ageYears: input.ageYears }),
				heartRateSettings
			})
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: {
					ageYears: input.ageYears ?? null,
					sexForEstimates: input.sexForEstimates,
					heartRateSettings,
					updatedAt: new Date()
				}
			});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'profile.heart_rate_updated',
			detail: {
				hasEstimateInputs: input.ageYears !== undefined,
				settingsSource: input.heartRateSettingsSource
			}
		});
	});

	return heartRateSettings;
}

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
			.set({
				targetDate,
				weeks: context.plan.weeks + 1,
				updatedAt: new Date()
			})
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

function normalizedBaselineRuns(baseline: PhaseBaseline): number {
	return Math.max(2, Math.min(5, Math.round(baseline.runsPerWeek)));
}

function preferredBaselineLongRunDay(availability: number[], runCount: number): number {
	for (const day of availability) {
		const recoveryDay = (day + 1) % 7;
		if (availability.filter((candidate) => candidate !== recoveryDay).length >= runCount)
			return day;
	}
	return availability[0] ?? 6;
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
			if (record.status === 'planned' && record.scheduledDate < cutoff) {
				missedRuns += 1;
			}
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

export async function getTrainingCalendar(userId: string, options?: { month?: string | null }) {
	const [activePlan, timeZone] = await Promise.all([
		getActivePlan(userId),
		requireAthleteTimeZone(userId)
	]);
	const today = todayIsoInTimeZone(timeZone);
	const month = parseCalendarMonth(options?.month, today);
	const previousMonth = shiftCalendarMonth(month, -1);
	const nextMonth = shiftCalendarMonth(month, 1);
	const currentMonth = today.slice(0, 7);
	const { rangeStart, rangeEnd } = calendarMonthRange(month);

	if (!activePlan) {
		const activities = await db
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
				heartRateSeries: activity.heartRateSeries,
				routeTrace: activity.routeTrace,
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
			.orderBy(asc(activity.occurredAt));

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
				activities: activities.map((record) => ({
					...record,
					occurredDate: record.activityDate
				})),
				feedback: []
			})
		};
	}

	const [currentWeek] = await db
		.select()
		.from(trainingWeek)
		.where(
			and(
				eq(trainingWeek.userId, userId),
				eq(trainingWeek.planId, activePlan.plan.id),
				lte(trainingWeek.startDate, today)
			)
		)
		.orderBy(desc(trainingWeek.startDate))
		.limit(1);

	const allEffectiveWeeks = await getPlanWeeks(userId, activePlan.plan.id);
	const weeks = allEffectiveWeeks.filter(
		(week) => week.startDate >= rangeStart && week.startDate <= rangeEnd
	);

	const workouts = await db
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
				eq(workout.planId, activePlan.plan.id),
				eq(trainingWeek.userId, userId),
				gte(workout.scheduledDate, rangeStart),
				lte(workout.scheduledDate, rangeEnd)
			)
		)
		.orderBy(asc(workout.scheduledDate));

	const activities = await db
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
			heartRateSeries: activity.heartRateSeries,
			routeTrace: activity.routeTrace,
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
		.orderBy(asc(activity.occurredAt));

	const feedback = await db
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
				eq(workout.planId, activePlan.plan.id),
				gte(workout.scheduledDate, rangeStart),
				lte(workout.scheduledDate, rangeEnd)
			)
		)
		.orderBy(desc(workoutFeedback.createdAt));

	const [stats] = await db
		.select({
			plannedMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}), 0)`,
			doneCount: sql<number>`count(*) filter (where ${workout.status} = 'done')`,
			runCount: sql<number>`count(*) filter (where ${workout.type} <> 'rest')`
		})
		.from(workout)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, activePlan.plan.id),
				eq(workout.isRemoved, false)
			)
		);

	const currentSignal = await currentTrainingSignal(userId, activePlan.plan, today);
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
		activities: activities.map((record) => ({
			...record,
			occurredDate: record.activityDate
		})),
		feedback,
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

function parseCalendarMonth(value: string | null | undefined, today: string): string {
	if (value && /^\d{4}-\d{2}$/.test(value)) {
		const timestamp = Date.parse(`${value}-01T00:00:00.000Z`);
		if (!Number.isNaN(timestamp)) return value;
	}
	return today.slice(0, 7);
}

function shiftCalendarMonth(month: string, offset: number): string {
	const [yearText, monthText] = month.split('-');
	const year = Number(yearText);
	const monthIndex = Number(monthText) - 1;
	const date = new Date(Date.UTC(year, monthIndex + offset, 1));
	return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

function calendarMonthRange(month: string): { rangeStart: string; rangeEnd: string } {
	const monthStart = `${month}-01`;
	const nextMonthStart = `${shiftCalendarMonth(month, 1)}-01`;
	const monthEnd = addDays(nextMonthStart, -1);
	return {
		rangeStart: weekStartForIsoDate(monthStart),
		rangeEnd: addDays(weekStartForIsoDate(monthEnd), 6)
	};
}

function weekStartForIsoDate(date: string): string {
	const day = parseIsoDate(date).getUTCDay();
	return addDays(date, day === 0 ? -6 : 1 - day);
}

function buildTrainingCalendarPayload(input: {
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
		planScale: input.planScale ?? null
	};
}

async function getLatestWorkoutAdjustments(userId: string, workoutIds: string[]) {
	if (workoutIds.length === 0) return new Map<string, TrainingPlanAdjustment>();

	const rows = await db
		.select()
		.from(planAdjustment)
		.where(
			and(
				eq(planAdjustment.userId, userId),
				inArray(planAdjustment.workoutId, workoutIds),
				isNull(planAdjustment.reversedAt)
			)
		)
		.orderBy(desc(planAdjustment.createdAt));
	const latest = new Map<string, TrainingPlanAdjustment>();
	for (const row of rows) {
		if (!latest.has(row.workoutId)) latest.set(row.workoutId, row);
	}
	return latest;
}

async function getWorkoutRecommendationTraces(userId: string, workoutIds: string[]) {
	const traces = new Map<
		string,
		{
			recommended: TrainingCalendarWorkout['recommended'];
			isEdited: boolean;
		}
	>();
	if (workoutIds.length === 0) return traces;
	const rows = await db
		.select({
			workoutId: planAdjustment.workoutId,
			triggerType: planAdjustment.triggerType,
			previousState: planAdjustment.previousState,
			reversedAt: planAdjustment.reversedAt
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, userId), inArray(planAdjustment.workoutId, workoutIds)))
		.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id));
	const manualTriggers: PlanAdjustmentTrigger[] = [
		'manual_edit',
		'manual_add',
		'manual_remove',
		'rebalance'
	];
	for (const row of rows) {
		const current = traces.get(row.workoutId);
		const firstRecommendation = current
			? current.recommended
			: row.triggerType === 'manual_add'
				? null
				: {
						scheduledDate: row.previousState.scheduledDate,
						type: row.previousState.type,
						prescriptionKind: row.previousState.prescriptionKind,
						targetDistanceMeters: row.previousState.targetDistanceMeters,
						targetDurationSeconds: row.previousState.targetDurationSeconds,
						intervalStructure: row.previousState.intervalStructure,
						purpose: row.previousState.purpose
					};
		traces.set(row.workoutId, {
			recommended: firstRecommendation,
			isEdited:
				(current?.isEdited ?? false) ||
				(row.reversedAt === null && manualTriggers.includes(row.triggerType))
		});
	}
	return traces;
}

function currentSignalReasonsFor(input: {
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
		reasons.push('The saved plan exceeds the conservative ramp limits.');
	}
	return Array.from(new Set(reasons)).slice(0, 3);
}

async function currentTrainingSignal(
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

async function recordPlanAdjustment(tx: RunwayTransaction, input: PlanAdjustmentInput) {
	const [counts] = await tx
		.select({
			planCount: sql<number>`count(*)::int`,
			workoutCount: sql<number>`count(*) filter (where ${planAdjustment.workoutId} = ${input.workoutId})::int`
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, input.userId), eq(planAdjustment.planId, input.planId)));
	if ((counts?.planCount ?? 0) >= 10_000 || (counts?.workoutCount ?? 0) >= 100) {
		throw new Error('This plan has reached its adjustment ledger limit.');
	}
	await tx.insert(planAdjustment).values({
		userId: input.userId,
		planId: input.planId,
		workoutId: input.workoutId,
		triggerType: input.triggerType,
		triggerId: input.triggerId ?? null,
		previousTargetDistanceMeters: input.previousState.targetDistanceMeters,
		newTargetDistanceMeters: input.newState.targetDistanceMeters,
		previousScheduledDate: input.previousState.scheduledDate,
		newScheduledDate: input.newState.scheduledDate,
		previousState: input.previousState,
		newState: input.newState,
		consequence: input.consequence ?? null,
		reason: input.reason
	});
}

function workoutAdjustmentState(record: WorkoutStateRecord): WorkoutAdjustmentState {
	return {
		weekId: record.weekId,
		scheduledDate: record.scheduledDate,
		type: record.type,
		status: record.status,
		targetDistanceMeters: record.targetDistanceMeters,
		targetDurationSeconds: record.targetDurationSeconds,
		prescriptionKind:
			record.prescriptionKind ??
			(record.type === 'rest' ? 'rest' : record.targetDurationSeconds ? 'timed' : 'distance'),
		intervalStructure: record.intervalStructure ?? null,
		intensity: record.intensity,
		purpose: record.purpose,
		reason: record.reason,
		sourceRefs: record.sourceRefs,
		isRemoved: record.isRemoved ?? false
	};
}

function changedWorkoutState(
	record: WorkoutStateRecord,
	changes: Partial<WorkoutAdjustmentState>
): WorkoutAdjustmentState {
	return {
		...workoutAdjustmentState(record),
		...changes
	};
}

async function reverseLedgerAdjustmentsForTrigger(
	tx: RunwayTransaction,
	input: {
		userId: string;
		triggerId: string;
		originalTriggerTypes: PlanAdjustmentTrigger[];
		reason: string;
		excludeWorkoutIds?: string[];
	}
) {
	if (input.originalTriggerTypes.length === 0) return;
	const reversed = await tx
		.update(planAdjustment)
		.set({ reversedAt: new Date(), reversalReason: input.reason })
		.where(
			and(
				eq(planAdjustment.userId, input.userId),
				eq(planAdjustment.triggerId, input.triggerId),
				inArray(planAdjustment.triggerType, input.originalTriggerTypes),
				isNull(planAdjustment.reversedAt),
				...(input.excludeWorkoutIds && input.excludeWorkoutIds.length > 0
					? [notInArray(planAdjustment.workoutId, input.excludeWorkoutIds)]
					: [])
			)
		)
		.returning({ workoutId: planAdjustment.workoutId });

	await replayWorkoutLedgers(
		tx,
		input.userId,
		Array.from(new Set(reversed.map((row) => row.workoutId)))
	);
}

async function replayWorkoutLedgers(tx: RunwayTransaction, userId: string, workoutIds: string[]) {
	if (workoutIds.length === 0) return;
	const adjustments = await tx
		.select({
			workoutId: planAdjustment.workoutId,
			previousState: planAdjustment.previousState,
			newState: planAdjustment.newState,
			reversedAt: planAdjustment.reversedAt
		})
		.from(planAdjustment)
		.where(and(eq(planAdjustment.userId, userId), inArray(planAdjustment.workoutId, workoutIds)))
		.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id));
	for (const workoutId of workoutIds) {
		const state = replayWorkoutAdjustments(
			adjustments.filter((row) => row.workoutId === workoutId)
		);
		if (!state) continue;
		await tx
			.update(workout)
			.set({ ...state, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)));
	}
}

export async function getAthleteTimeZone(userId: string): Promise<string | null> {
	const [profile] = await db
		.select({ timeZone: athleteProfile.timeZone })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile?.timeZone ?? null;
}

async function requireAthleteTimeZone(userId: string, message = 'Set training time zone first.') {
	const timeZone = await getAthleteTimeZone(userId);
	if (!timeZone) throw new Error(message);
	return timeZone;
}

async function requireAthleteTimeZoneInTransaction(
	tx: RunwayTransaction,
	userId: string,
	message = 'Set training time zone first.'
) {
	const [profile] = await tx
		.select({ timeZone: athleteProfile.timeZone })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	if (!profile?.timeZone) throw new Error(message);
	return profile.timeZone;
}

function isoWeekStart(date: string) {
	const parsed = parseIsoDate(date);
	const day = parsed.getUTCDay();
	return addDays(date, day === 0 ? -6 : 1 - day);
}

async function effectiveWeekTargetDistance(
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

async function effectiveWeekTargetDuration(
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

async function planWeekIdForDate(
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

export async function getDashboard(userId: string) {
	const activePlan = await getActivePlan(userId);
	if (!activePlan) {
		return {
			activePlan: null,
			currentWeek: null,
			upcomingWorkouts: [],
			stats: null,
			currentSignal: null
		};
	}

	const timeZone = await requireAthleteTimeZone(userId);
	const today = todayIsoInTimeZone(timeZone);
	const currentWeekStart = isoWeekStart(today);
	const [currentWeek] = await db
		.select()
		.from(trainingWeek)
		.where(
			and(
				eq(trainingWeek.userId, userId),
				eq(trainingWeek.planId, activePlan.plan.id),
				lte(trainingWeek.startDate, today)
			)
		)
		.orderBy(desc(trainingWeek.startDate))
		.limit(1);

	const upcomingWorkouts = await db
		.select({
			id: workout.id,
			scheduledDate: workout.scheduledDate,
			type: workout.type,
			status: workout.status,
			targetDistanceMeters: workout.targetDistanceMeters,
			purpose: workout.purpose,
			reason: workout.reason,
			weekTargetDistanceMeters: trainingWeek.targetDistanceMeters
		})
		.from(workout)
		.innerJoin(trainingWeek, eq(workout.weekId, trainingWeek.id))
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, activePlan.plan.id),
				eq(trainingWeek.userId, userId),
				eq(workout.isRemoved, false),
				gte(workout.scheduledDate, currentWeekStart),
				lte(workout.scheduledDate, addDays(currentWeekStart, 13))
			)
		)
		.orderBy(asc(workout.scheduledDate));

	const [stats] = await db
		.select({
			plannedMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}), 0)`,
			doneCount: sql<number>`count(*) filter (where ${workout.status} = 'done')`,
			runCount: sql<number>`count(*) filter (where ${workout.type} <> 'rest')`
		})
		.from(workout)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, activePlan.plan.id),
				eq(workout.isRemoved, false)
			)
		);

	const [currentSignal, effectiveWeeks] = await Promise.all([
		currentTrainingSignal(userId, activePlan.plan, today),
		getPlanWeeks(userId, activePlan.plan.id)
	]);

	return {
		activePlan,
		currentWeek: effectiveWeeks.find((week) => week.id === currentWeek?.id) ?? currentWeek ?? null,
		upcomingWorkouts: upcomingWorkouts.map((record) => ({
			...record,
			weekTargetDistanceMeters:
				effectiveWeeks.find(
					(week) =>
						record.scheduledDate >= week.startDate &&
						record.scheduledDate <= addDays(week.startDate, 6)
				)?.targetDistanceMeters ?? record.weekTargetDistanceMeters
		})),
		stats: stats ?? null,
		currentSignal
	};
}

export async function recordWorkoutFeedback(
	userId: string,
	input: {
		workoutId: string;
		status: 'done' | 'skipped' | 'shortened';
		completedDistanceMeters?: number;
		completedDurationSeconds?: number;
		feltHard: boolean;
		pain: boolean;
		choice: 'skip_continue' | 'reduce_next';
	}
) {
	return db.transaction(async (tx) => {
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const [targetWorkout] = await tx
			.select({ workout, week: trainingWeek })
			.from(workout)
			.innerJoin(trainingWeek, eq(workout.weekId, trainingWeek.id))
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, input.workoutId),
					eq(workout.isRemoved, false)
				)
			)
			.limit(1);

		if (!targetWorkout) throw new Error('Workout not found.');
		if (targetWorkout.workout.scheduledDate > today) {
			throw new Error('Workout is scheduled for the future.');
		}
		if (targetWorkout.workout.status !== 'planned') {
			throw new Error('Feedback has already been recorded for this workout.');
		}

		const [existingFeedback] = await tx
			.select({ id: workoutFeedback.id })
			.from(workoutFeedback)
			.where(
				and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, input.workoutId))
			)
			.limit(1);
		if (existingFeedback) {
			throw new Error('Feedback has already been recorded for this workout.');
		}

		const completedDistanceMeters = input.completedDistanceMeters;

		const [recentMisses] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, targetWorkout.workout.planId),
					eq(workout.isRemoved, false),
					ne(workout.type, 'rest'),
					inArray(workout.status, ['skipped', 'shortened']),
					lt(workout.scheduledDate, targetWorkout.workout.scheduledDate),
					gte(workout.scheduledDate, addDays(targetWorkout.workout.scheduledDate, -28))
				)
			);
		const weekStartDate = isoWeekStart(targetWorkout.workout.scheduledDate);
		const [effectiveWeek] = await tx
			.select({
				targetDistanceMeters: sql<number>`coalesce(sum(${workout.targetDistanceMeters}) filter (where ${workout.type} <> 'rest'), 0)::int`
			})
			.from(workout)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.planId, targetWorkout.workout.planId),
					eq(workout.isRemoved, false),
					gte(workout.scheduledDate, weekStartDate),
					lte(workout.scheduledDate, addDays(weekStartDate, 6))
				)
			);

		const consequence = calculateConsequence({
			status: input.status,
			choice: input.choice,
			targetDistanceMeters: targetWorkout.workout.targetDistanceMeters,
			...(targetWorkout.workout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: targetWorkout.workout.targetDurationSeconds }),
			pain: input.pain,
			feltHard: input.feltHard,
			weekTargetDistanceMeters:
				effectiveWeek?.targetDistanceMeters ?? targetWorkout.week.targetDistanceMeters,
			recentMissedWorkouts: recentMisses?.count ?? 0,
			...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
			...(input.completedDurationSeconds === undefined
				? {}
				: { completedDurationSeconds: input.completedDurationSeconds })
		});
		const inferredStatus =
			input.status === 'skipped'
				? 'skipped'
				: consequence.deviation === 'short'
					? 'shortened'
					: 'done';

		await tx
			.update(workout)
			.set({ status: inferredStatus, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, input.workoutId)));

		await tx.insert(workoutFeedback).values({
			userId,
			workoutId: input.workoutId,
			feltHard: input.feltHard,
			pain: input.pain,
			choice: input.choice,
			deviation: consequence.deviation,
			consequence,
			...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
			...(input.completedDurationSeconds === undefined
				? {}
				: { completedDurationSeconds: input.completedDurationSeconds })
		});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.feedback',
			detail: {
				workoutId: input.workoutId,
				status: input.status,
				pain: input.pain,
				feltHard: input.feltHard
			}
		});

		return consequence;
	});
}

export async function deleteWorkoutFeedback(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		const [target] = await tx
			.select({
				feedbackId: workoutFeedback.id,
				workoutId: workout.id
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, workoutId)))
			.limit(1);
		if (!target) throw new Error('Workout feedback not found.');
		const [linkedActivity] = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.workoutId, workoutId)))
			.limit(1);
		if (linkedActivity) {
			throw new Error('Unlink or delete the recorded activity instead of deleting its feedback.');
		}

		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: target.feedbackId,
			originalTriggerTypes: ['feedback', 'decision'],
			reason: 'Deleting feedback restored only the workout changes derived from that feedback.'
		});
		await tx
			.delete(workoutFeedback)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, target.feedbackId)));
		await tx
			.update(workout)
			.set({ status: 'planned', updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)));
		await tx
			.delete(auditEvent)
			.where(
				and(
					eq(auditEvent.userId, userId),
					eq(auditEvent.eventType, 'workout.feedback'),
					sql`${auditEvent.detail} ->> 'workoutId' = ${workoutId}`
				)
			);
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.feedback_deleted',
			detail: { workoutId }
		});
		return { workoutId };
	});
}

export async function applyConsequenceDecision(
	userId: string,
	input: {
		source: 'feedback' | 'activity';
		sourceId: string;
		decision: PlanDecision;
		confirmRisk: boolean;
	}
) {
	return db.transaction(async (tx) => {
		const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
		const today = todayIsoInTimeZone(timeZone);
		const sourceLocked = await lockConsequenceDecisionSource(tx, userId, input);
		if (!sourceLocked) throw new Error('Plan-change proposal not found.');
		const source = await consequenceDecisionSource(tx, userId, input);
		if (!source) throw new Error('Plan-change proposal not found.');
		if (source.consequence.appliedDecision) {
			throw new Error('A decision has already been applied to this result.');
		}
		const consequence = withAppliedDecision(source.consequence, input.decision);
		const [lockedPlan] = await tx
			.select({ id: trainingPlan.id })
			.from(trainingPlan)
			.where(
				and(
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.id, source.planId),
					eq(trainingPlan.status, 'active')
				)
			)
			.limit(1)
			.for('update');
		if (!lockedPlan) throw new Error('This plan-change proposal is no longer current.');

		if (input.decision !== 'keep_plan') {
			const candidates = await tx
				.select({
					id: workout.id,
					planId: workout.planId,
					weekId: workout.weekId,
					scheduledDate: workout.scheduledDate,
					status: workout.status,
					targetDistanceMeters: workout.targetDistanceMeters,
					targetDurationSeconds: workout.targetDurationSeconds,
					prescriptionKind: workout.prescriptionKind,
					intervalStructure: workout.intervalStructure,
					type: workout.type,
					intensity: workout.intensity,
					purpose: workout.purpose,
					reason: workout.reason,
					sourceRefs: workout.sourceRefs,
					isRemoved: workout.isRemoved
				})
				.from(workout)
				.where(
					and(
						eq(workout.userId, userId),
						eq(workout.planId, source.planId),
						eq(workout.status, 'planned'),
						eq(workout.isRemoved, false),
						ne(workout.type, 'race'),
						ne(workout.type, 'rest'),
						gt(workout.scheduledDate, source.originDate),
						gte(workout.scheduledDate, today)
					)
				)
				.orderBy(asc(workout.scheduledDate), asc(workout.id))
				.limit(52);
			const firstCandidate = candidates[0];
			if (!firstCandidate) throw new Error('No future workout is available to change.');

			const affected =
				input.decision === 'rebalance_week'
					? candidates.filter(
							(candidate) => candidate.scheduledDate <= addDays(isoWeekStart(source.originDate), 6)
						)
					: [firstCandidate];
			if (input.decision === 'rebalance_week' && affected.length === 0) {
				throw new Error('No compatible workouts remain in this week. Choose another option.');
			}
			const workoutsToChange = affected.length > 0 ? affected : [firstCandidate];

			if (input.decision === 'repeat_prescription') {
				const [allWorkouts, weeks] = await Promise.all([
					tx
						.select()
						.from(workout)
						.where(and(eq(workout.userId, userId), eq(workout.planId, source.planId))),
					tx
						.select({
							id: trainingWeek.id,
							weekNumber: trainingWeek.weekNumber,
							startDate: trainingWeek.startDate
						})
						.from(trainingWeek)
						.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, source.planId)))
						.orderBy(asc(trainingWeek.weekNumber))
				]);
				const repeatedState = decisionWorkoutState({
					candidate: firstCandidate,
					originWorkout: source.originWorkout,
					decision: input.decision,
					consequence,
					shareCount: 1,
					index: 0
				});
				const preview = buildWorkoutEditPreview({
					current: firstCandidate,
					recommended: null,
					proposed: proposalFromWorkout({ ...firstCandidate, ...repeatedState }),
					workouts: allWorkouts.map(editableWorkout),
					weeks,
					today,
					rebalance: false
				});
				if (preview.requiresConfirmation && !input.confirmRisk) {
					throw new Error(
						'Review and confirm the elevated repeated prescription before applying it.'
					);
				}
			}

			for (const [index, candidate] of workoutsToChange.entries()) {
				const newState = decisionWorkoutState({
					candidate,
					originWorkout: source.originWorkout,
					decision: input.decision,
					consequence,
					shareCount: workoutsToChange.length,
					index
				});
				await tx
					.update(workout)
					.set({ ...newState, updatedAt: new Date() })
					.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
				await recordPlanAdjustment(tx, {
					userId,
					planId: source.planId,
					workoutId: candidate.id,
					triggerType: 'decision',
					triggerId: input.sourceId,
					previousState: workoutAdjustmentState(candidate),
					newState,
					consequence,
					reason: `Applied decision: ${input.decision}.`
				});
			}
		}

		if (input.source === 'feedback') {
			await tx
				.update(workoutFeedback)
				.set({ appliedDecision: input.decision, consequence })
				.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)));
		} else {
			await tx
				.update(activity)
				.set({ appliedDecision: input.decision, consequence })
				.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)));
		}

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'plan.decision_applied',
			detail: {
				source: input.source,
				sourceId: input.sourceId,
				decision: input.decision
			}
		});
		return consequence;
	});
}

async function lockConsequenceDecisionSource(
	tx: RunwayTransaction,
	userId: string,
	input: { source: 'feedback' | 'activity'; sourceId: string }
) {
	if (input.source === 'feedback') {
		const [record] = await tx
			.select({ id: workoutFeedback.id })
			.from(workoutFeedback)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)))
			.limit(1)
			.for('update');
		return Boolean(record);
	}
	const [record] = await tx
		.select({ id: activity.id })
		.from(activity)
		.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)))
		.limit(1)
		.for('update');
	return Boolean(record);
}

async function consequenceDecisionSource(
	tx: RunwayTransaction,
	userId: string,
	input: { source: 'feedback' | 'activity'; sourceId: string }
) {
	if (input.source === 'feedback') {
		const [record] = await tx
			.select({
				consequence: workoutFeedback.consequence,
				planId: workout.planId,
				originDate: workout.scheduledDate,
				originWorkout: workout
			})
			.from(workoutFeedback)
			.innerJoin(
				workout,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId))
			)
			.innerJoin(
				trainingPlan,
				and(
					eq(workout.planId, trainingPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.where(and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.id, input.sourceId)))
			.limit(1);
		return record ?? null;
	}

	const [record] = await tx
		.select({
			consequence: activity.consequence,
			consequencePlanId: activity.consequencePlanId,
			originDate: activity.activityDate,
			linkedWorkout: workout
		})
		.from(activity)
		.leftJoin(workout, and(eq(activity.workoutId, workout.id), eq(workout.userId, userId)))
		.where(and(eq(activity.userId, userId), eq(activity.id, input.sourceId)))
		.limit(1);
	if (!record?.consequence || !record.consequencePlanId) return null;
	const [active] = await tx
		.select({ id: trainingPlan.id })
		.from(trainingPlan)
		.where(
			and(
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.id, record.consequencePlanId),
				eq(trainingPlan.status, 'active')
			)
		)
		.limit(1);
	if (!active) return null;
	if (record.linkedWorkout) {
		if (record.linkedWorkout.planId !== active.id) return null;
		return {
			consequence: record.consequence,
			planId: active.id,
			originDate: record.originDate,
			originWorkout: record.linkedWorkout
		};
	}
	return {
		consequence: record.consequence,
		planId: active.id,
		originDate: record.originDate,
		originWorkout: null
	};
}

function decisionWorkoutState(input: {
	candidate: WorkoutStateRecord;
	originWorkout: WorkoutStateRecord | null;
	decision: Exclude<PlanDecision, 'keep_plan'>;
	consequence: ConsequenceResult;
	shareCount: number;
	index: number;
}): WorkoutAdjustmentState {
	const { candidate, originWorkout, decision, consequence, shareCount } = input;
	if (decision === 'next_rest') {
		return changedWorkoutState(candidate, {
			type: 'rest',
			prescriptionKind: 'rest',
			targetDistanceMeters: 0,
			targetDurationSeconds: null,
			intervalStructure: null,
			intensity: 'rest',
			purpose: 'Recovery day',
			reason: 'The runner explicitly chose rest after reviewing the recorded result.'
		});
	}
	if (decision === 'repeat_prescription') {
		if (!originWorkout || originWorkout.type === 'race' || originWorkout.type === 'rest') {
			throw new Error('This result has no prescription that can be repeated.');
		}
		return changedWorkoutState(candidate, {
			type: originWorkout.type,
			prescriptionKind:
				originWorkout.prescriptionKind ??
				(originWorkout.targetDurationSeconds ? 'timed' : 'distance'),
			targetDistanceMeters: originWorkout.targetDistanceMeters,
			targetDurationSeconds: originWorkout.targetDurationSeconds,
			intervalStructure: originWorkout.intervalStructure ?? null,
			intensity: originWorkout.intensity,
			purpose: originWorkout.purpose,
			reason: 'The runner explicitly chose to repeat the earlier prescription.',
			sourceRefs: originWorkout.sourceRefs
		});
	}

	if (candidate.targetDurationSeconds) {
		const totalReduction = Math.max(300, Math.round(candidate.targetDurationSeconds * 0.15));
		const reduction =
			decision === 'rebalance_week' ? Math.ceil(totalReduction / shareCount) : totalReduction;
		const targetDurationSeconds = Math.max(600, candidate.targetDurationSeconds - reduction);
		return changedWorkoutState(candidate, {
			targetDurationSeconds,
			intervalStructure: resizeTimedIntervalStructure(
				candidate.intervalStructure ?? null,
				targetDurationSeconds
			),
			reason: 'The runner explicitly reduced this timed workout after reviewing a result.'
		});
	}

	const proposedReduction = Math.abs(consequence.nextRunAdjustmentMeters) || 500;
	const reduction =
		decision === 'rebalance_week' ? Math.ceil(proposedReduction / shareCount) : proposedReduction;
	return changedWorkoutState(candidate, {
		targetDistanceMeters: Math.max(500, candidate.targetDistanceMeters - reduction),
		reason:
			decision === 'rebalance_week'
				? 'The runner explicitly rebalanced the remaining week.'
				: 'The runner explicitly reduced this workout after reviewing a result.'
	});
}

export type FutureWorkoutEditInput = {
	workoutId: string;
	scheduledDate: string;
	type: Exclude<WorkoutStateRecord['type'], 'race'>;
	prescriptionKind: 'distance' | 'timed' | 'rest';
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	intervalStructure: TimedIntervalStructure | null;
	intensity: string;
	purpose: string;
	userReason?: string;
	rebalance: boolean;
	confirmRisk: boolean;
};

export type FutureWorkoutAddInput = Omit<FutureWorkoutEditInput, 'workoutId'>;

export async function previewFutureWorkoutEdit(
	userId: string,
	input: FutureWorkoutEditInput
): Promise<WorkoutEditPreview> {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, input.workoutId);
		return buildEditPreview(context, input);
	});
}

export async function applyFutureWorkoutEdit(userId: string, input: FutureWorkoutEditInput) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, input.workoutId);
		const preview = buildEditPreview(context, input);
		if (preview.requiresConfirmation && !input.confirmRisk) {
			throw new Error('Review and confirm the elevated edit before applying it.');
		}
		const proposal = preview.proposed;
		if (sameWorkoutProposal(proposalFromWorkout(context.current), proposal)) {
			throw new Error('The proposed workout is unchanged.');
		}
		const editId = crypto.randomUUID();
		const selectedState = workoutAdjustmentState({ ...context.current, ...proposal });
		await tx
			.update(workout)
			.set({ ...selectedState, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, context.current.id)));
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: context.current.id,
			triggerType: input.rebalance ? 'rebalance' : 'manual_edit',
			triggerId: editId,
			previousState: workoutAdjustmentState(context.current),
			newState: selectedState,
			reason: input.userReason?.trim() || 'Runner edited this workout.'
		});

		const rebalanced = input.rebalance
			? rebalanceWorkoutStates({
					selectedId: context.current.id,
					current: context.current,
					proposed: proposal,
					workouts: context.workouts,
					today: context.today
				})
			: [];
		for (const change of rebalanced) {
			const candidate = context.workouts.find((record) => record.id === change.workoutId);
			if (!candidate) continue;
			const newState = workoutAdjustmentState({ ...candidate, ...change.proposed });
			await tx
				.update(workout)
				.set({ ...newState, updatedAt: new Date() })
				.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
			await recordPlanAdjustment(tx, {
				userId,
				planId: context.plan.id,
				workoutId: candidate.id,
				triggerType: 'rebalance',
				triggerId: editId,
				previousState: workoutAdjustmentState(candidate),
				newState,
				reason: 'Runner explicitly rebalanced the remaining week.'
			});
		}
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.edited',
			detail: {
				workoutId: context.current.id,
				editId,
				rebalancedWorkoutIds: rebalanced.map((change) => change.workoutId)
			}
		});
		return { preview, editId };
	});
}

export async function previewFutureWorkoutRemoval(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, workoutId);
		const proposed = { ...proposalFromWorkout(context.current), isRemoved: true };
		return buildWorkoutEditPreview({
			current: context.current,
			recommended: context.recommended,
			proposed,
			workouts: context.workouts,
			weeks: context.weeks,
			today: context.today,
			rebalance: false
		});
	});
}

export async function removeFutureWorkout(userId: string, workoutId: string) {
	return db.transaction(async (tx) => {
		const context = await workoutEditContext(tx, userId, workoutId);
		const editId = crypto.randomUUID();
		const previousState = workoutAdjustmentState(context.current);
		const newState = changedWorkoutState(context.current, { isRemoved: true });
		await tx
			.update(workout)
			.set({ isRemoved: true, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, context.current.id)));
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: context.current.id,
			triggerType: 'manual_remove',
			triggerId: editId,
			previousState,
			newState,
			reason: 'Runner removed this future workout.'
		});
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.removed',
			detail: { workoutId: context.current.id, editId }
		});
		return { workoutId: context.current.id, editId };
	});
}

export async function previewFutureWorkoutAdd(userId: string, input: FutureWorkoutAddInput) {
	return db.transaction(async (tx) => {
		const context = await newWorkoutContext(tx, userId, input.scheduledDate);
		const proposal = editProposal(input, context.weekId);
		const placeholder: EditableWorkoutState = {
			id: 'new-workout',
			status: 'planned',
			...proposal,
			isRemoved: true
		};
		return buildWorkoutEditPreview({
			current: placeholder,
			recommended: null,
			proposed: proposal,
			workouts: [...context.workouts, placeholder],
			weeks: context.weeks,
			today: context.today,
			rebalance: input.rebalance
		});
	});
}

export async function addFutureWorkout(userId: string, input: FutureWorkoutAddInput) {
	return db.transaction(async (tx) => {
		const context = await newWorkoutContext(tx, userId, input.scheduledDate);
		const proposal = editProposal(input, context.weekId);
		const placeholder: EditableWorkoutState = {
			id: 'new-workout',
			status: 'planned',
			...proposal,
			isRemoved: true
		};
		const preview = buildWorkoutEditPreview({
			current: placeholder,
			recommended: null,
			proposed: proposal,
			workouts: [...context.workouts, placeholder],
			weeks: context.weeks,
			today: context.today,
			rebalance: input.rebalance
		});
		if (preview.requiresConfirmation && !input.confirmRisk) {
			throw new Error('Review and confirm the elevated edit before applying it.');
		}
		const [created] = await tx
			.insert(workout)
			.values({
				userId,
				planId: context.plan.id,
				status: 'planned',
				...proposal
			})
			.returning();
		if (!created) throw new Error('Workout could not be added.');
		const editId = crypto.randomUUID();
		const previousState = workoutAdjustmentState({ ...created, isRemoved: true });
		const newState = workoutAdjustmentState(created);
		await recordPlanAdjustment(tx, {
			userId,
			planId: context.plan.id,
			workoutId: created.id,
			triggerType: 'manual_add',
			triggerId: editId,
			previousState,
			newState,
			reason: input.userReason?.trim() || 'Runner added this future workout.'
		});
		const rebalanced = input.rebalance
			? rebalanceWorkoutStates({
					selectedId: placeholder.id,
					current: placeholder,
					proposed: proposal,
					workouts: [...context.workouts, placeholder],
					today: context.today
				})
			: [];
		for (const change of rebalanced) {
			const candidate = context.workouts.find((record) => record.id === change.workoutId);
			if (!candidate) continue;
			const changedState = workoutAdjustmentState({ ...candidate, ...change.proposed });
			await tx
				.update(workout)
				.set({ ...changedState, updatedAt: new Date() })
				.where(and(eq(workout.userId, userId), eq(workout.id, candidate.id)));
			await recordPlanAdjustment(tx, {
				userId,
				planId: context.plan.id,
				workoutId: candidate.id,
				triggerType: 'rebalance',
				triggerId: editId,
				previousState: workoutAdjustmentState(candidate),
				newState: changedState,
				reason: 'Runner explicitly rebalanced the remaining week after adding a workout.'
			});
		}
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'workout.added',
			detail: {
				workoutId: created.id,
				editId,
				rebalancedWorkoutIds: rebalanced.map((change) => change.workoutId)
			}
		});
		return { workoutId: created.id, editId, preview };
	});
}

export async function resetFutureWorkout(userId: string, workoutId: string) {
	return reverseManualWorkoutAdjustments(userId, { workoutId });
}

export async function undoFutureWorkoutAdjustment(userId: string, adjustmentId: string) {
	return reverseManualWorkoutAdjustments(userId, { adjustmentId });
}

async function reverseManualWorkoutAdjustments(
	userId: string,
	input: { workoutId?: string; adjustmentId?: string }
) {
	return db.transaction(async (tx) => {
		const today = todayIsoInTimeZone(await requireAthleteTimeZoneInTransaction(tx, userId));
		const manualTriggers: PlanAdjustmentTrigger[] = [
			'manual_edit',
			'manual_add',
			'manual_remove',
			'rebalance'
		];
		const [selectedAdjustment] = input.adjustmentId
			? await tx
					.select({ triggerId: planAdjustment.triggerId })
					.from(planAdjustment)
					.where(
						and(
							eq(planAdjustment.userId, userId),
							eq(planAdjustment.id, input.adjustmentId),
							inArray(planAdjustment.triggerType, manualTriggers),
							isNull(planAdjustment.reversedAt)
						)
					)
					.limit(1)
			: [];
		if (input.adjustmentId && !selectedAdjustment) {
			throw new Error('No reversible workout change was found.');
		}
		const rows = await tx
			.select({ id: planAdjustment.id, workoutId: planAdjustment.workoutId })
			.from(planAdjustment)
			.innerJoin(
				trainingPlan,
				and(
					eq(planAdjustment.planId, trainingPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.innerJoin(
				workout,
				and(
					eq(planAdjustment.workoutId, workout.id),
					eq(workout.userId, userId),
					eq(workout.status, 'planned'),
					gte(workout.scheduledDate, today)
				)
			)
			.where(
				and(
					eq(planAdjustment.userId, userId),
					inArray(planAdjustment.triggerType, manualTriggers),
					isNull(planAdjustment.reversedAt),
					...(input.workoutId ? [eq(planAdjustment.workoutId, input.workoutId)] : []),
					...(input.adjustmentId && selectedAdjustment?.triggerId
						? [eq(planAdjustment.triggerId, selectedAdjustment.triggerId)]
						: input.adjustmentId
							? [eq(planAdjustment.id, input.adjustmentId)]
							: [])
				)
			)
			.orderBy(desc(planAdjustment.createdAt));
		if (rows.length === 0) throw new Error('No reversible workout change was found.');
		await tx
			.update(planAdjustment)
			.set({
				reversedAt: new Date(),
				reversalReason: 'Runner reversed this manual workout change.'
			})
			.where(
				and(
					eq(planAdjustment.userId, userId),
					inArray(
						planAdjustment.id,
						rows.map((row) => row.id)
					)
				)
			);
		const workoutIds = Array.from(new Set(rows.map((row) => row.workoutId)));
		await replayWorkoutLedgers(tx, userId, workoutIds);
		await tx.insert(auditEvent).values({
			userId,
			eventType: input.adjustmentId ? 'workout.adjustment_undone' : 'workout.reset',
			detail: { workoutIds }
		});
		return { workoutIds };
	});
}

type WorkoutEditContext = Awaited<ReturnType<typeof workoutEditContext>>;

async function workoutEditContext(tx: RunwayTransaction, userId: string, workoutId: string) {
	const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
	const today = todayIsoInTimeZone(timeZone);
	const [workoutReference] = await tx
		.select({ planId: workout.planId })
		.from(workout)
		.where(and(eq(workout.userId, userId), eq(workout.id, workoutId)))
		.limit(1);
	if (!workoutReference) throw new Error('Future workout not found.');
	const [lockedPlan] = await tx
		.select({ id: trainingPlan.id })
		.from(trainingPlan)
		.where(
			and(
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.id, workoutReference.planId),
				eq(trainingPlan.status, 'active')
			)
		)
		.limit(1)
		.for('update');
	if (!lockedPlan) throw new Error('Future workout not found.');
	const [record] = await tx
		.select({ current: workout, plan: trainingPlan })
		.from(workout)
		.innerJoin(
			trainingPlan,
			and(
				eq(workout.planId, trainingPlan.id),
				eq(trainingPlan.userId, userId),
				eq(trainingPlan.status, 'active')
			)
		)
		.leftJoin(
			workoutFeedback,
			and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
		)
		.leftJoin(activity, and(eq(activity.workoutId, workout.id), eq(activity.userId, userId)))
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.id, workoutId),
				isNull(workoutFeedback.id),
				isNull(activity.id)
			)
		)
		.limit(1);
	if (!record) throw new Error('Future workout not found.');
	if (record.current.type === 'race') {
		throw new Error('Race events are changed through the goal editor.');
	}
	if (record.current.status !== 'planned' || record.current.scheduledDate < today) {
		throw new Error('Completed and past prescriptions cannot be edited.');
	}
	if (record.current.isRemoved) throw new Error('Reset or undo this removed workout first.');
	const [workouts, weeks, [firstAdjustment]] = await Promise.all([
		tx
			.select()
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, record.plan.id))),
		tx
			.select({
				id: trainingWeek.id,
				weekNumber: trainingWeek.weekNumber,
				startDate: trainingWeek.startDate
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, record.plan.id)))
			.orderBy(asc(trainingWeek.weekNumber)),
		tx
			.select({
				triggerType: planAdjustment.triggerType,
				previousState: planAdjustment.previousState
			})
			.from(planAdjustment)
			.where(and(eq(planAdjustment.userId, userId), eq(planAdjustment.workoutId, workoutId)))
			.orderBy(asc(planAdjustment.createdAt), asc(planAdjustment.id))
			.limit(1)
	]);
	return {
		current: editableWorkout(record.current),
		plan: record.plan,
		workouts: workouts.map(editableWorkout),
		weeks,
		today,
		recommended:
			firstAdjustment?.triggerType === 'manual_add'
				? null
				: firstAdjustment?.previousState
					? proposalFromAdjustment(firstAdjustment.previousState)
					: proposalFromWorkout(editableWorkout(record.current))
	};
}

async function newWorkoutContext(tx: RunwayTransaction, userId: string, scheduledDate: string) {
	const timeZone = await requireAthleteTimeZoneInTransaction(tx, userId);
	const today = todayIsoInTimeZone(timeZone);
	const [plan] = await tx
		.select()
		.from(trainingPlan)
		.where(and(eq(trainingPlan.userId, userId), eq(trainingPlan.status, 'active')))
		.limit(1)
		.for('update');
	if (!plan) throw new Error('No active plan is available.');
	if (scheduledDate < today || scheduledDate > plan.targetDate) {
		throw new Error('Workout dates must be between today and the active goal date.');
	}
	const weekId = await planWeekIdForDate(tx, userId, plan.id, scheduledDate);
	if (!weekId) throw new Error('The selected date is outside the active plan weeks.');
	const [workouts, weeks] = await Promise.all([
		tx
			.select()
			.from(workout)
			.where(and(eq(workout.userId, userId), eq(workout.planId, plan.id))),
		tx
			.select({
				id: trainingWeek.id,
				weekNumber: trainingWeek.weekNumber,
				startDate: trainingWeek.startDate
			})
			.from(trainingWeek)
			.where(and(eq(trainingWeek.userId, userId), eq(trainingWeek.planId, plan.id)))
			.orderBy(asc(trainingWeek.weekNumber))
	]);
	if (workouts.length >= 52 * 14) {
		throw new Error('This plan has reached its workout record limit.');
	}
	const visibleOnDate = workouts.filter(
		(record) => record.scheduledDate === scheduledDate && !record.isRemoved
	).length;
	if (visibleOnDate >= 2) throw new Error('A day can contain at most two planned workouts.');
	const visibleInWeek = workouts.filter(
		(record) => record.weekId === weekId && !record.isRemoved
	).length;
	if (visibleInWeek >= 14)
		throw new Error('A training week can contain at most fourteen workouts.');
	return { plan, today, weekId, workouts: workouts.map(editableWorkout), weeks };
}

function buildEditPreview(context: WorkoutEditContext, input: FutureWorkoutEditInput) {
	if (input.scheduledDate < context.today || input.scheduledDate > context.plan.targetDate) {
		throw new Error('Workout dates must be between today and the active goal date.');
	}
	const destinationWeek = context.weeks.find(
		(week) =>
			input.scheduledDate >= week.startDate && input.scheduledDate <= addDays(week.startDate, 6)
	);
	if (!destinationWeek) throw new Error('The selected date is outside the active plan weeks.');
	const otherVisibleOnDate = context.workouts.filter(
		(record) =>
			record.id !== context.current.id &&
			record.scheduledDate === input.scheduledDate &&
			!record.isRemoved
	).length;
	if (otherVisibleOnDate >= 2) throw new Error('A day can contain at most two planned workouts.');
	const otherVisibleInWeek = context.workouts.filter(
		(record) =>
			record.id !== context.current.id && record.weekId === destinationWeek.id && !record.isRemoved
	).length;
	if (otherVisibleInWeek >= 14)
		throw new Error('A training week can contain at most fourteen workouts.');
	const proposed = editProposal(input, destinationWeek.id);
	return buildWorkoutEditPreview({
		current: context.current,
		recommended: context.recommended,
		proposed,
		workouts: context.workouts,
		weeks: context.weeks,
		today: context.today,
		rebalance: input.rebalance
	});
}

function editProposal(
	input: FutureWorkoutAddInput | FutureWorkoutEditInput,
	weekId: string
): WorkoutEditProposal {
	const reason = input.userReason?.trim() || 'Runner-edited workout.';
	if (input.prescriptionKind === 'rest') {
		return {
			weekId,
			scheduledDate: input.scheduledDate,
			type: 'rest',
			prescriptionKind: 'rest',
			targetDistanceMeters: 0,
			targetDurationSeconds: null,
			intervalStructure: null,
			intensity: 'rest',
			purpose: input.purpose.trim() || 'Recovery day',
			reason,
			sourceRefs: [],
			isRemoved: false
		};
	}
	return {
		weekId,
		scheduledDate: input.scheduledDate,
		type: input.type === 'rest' ? 'easy' : input.type,
		prescriptionKind: input.prescriptionKind,
		targetDistanceMeters: input.prescriptionKind === 'distance' ? input.targetDistanceMeters : 0,
		targetDurationSeconds: input.prescriptionKind === 'timed' ? input.targetDurationSeconds : null,
		intervalStructure:
			input.prescriptionKind === 'timed' ? structuredClone(input.intervalStructure) : null,
		intensity: input.intensity.trim() || 'easy',
		purpose: input.purpose.trim(),
		reason,
		sourceRefs: [],
		isRemoved: false
	};
}

function editableWorkout(record: typeof workout.$inferSelect): EditableWorkoutState {
	return {
		id: record.id,
		weekId: record.weekId,
		scheduledDate: record.scheduledDate,
		type: record.type,
		status: record.status,
		prescriptionKind: record.prescriptionKind,
		targetDistanceMeters: record.targetDistanceMeters,
		targetDurationSeconds: record.targetDurationSeconds,
		intervalStructure: structuredClone(record.intervalStructure),
		intensity: record.intensity,
		purpose: record.purpose,
		reason: record.reason,
		sourceRefs: [...record.sourceRefs],
		isRemoved: record.isRemoved
	};
}

function proposalFromAdjustment(state: WorkoutAdjustmentState): WorkoutEditProposal {
	return {
		weekId: state.weekId,
		scheduledDate: state.scheduledDate,
		type: state.type,
		prescriptionKind: state.prescriptionKind,
		targetDistanceMeters: state.targetDistanceMeters,
		targetDurationSeconds: state.targetDurationSeconds,
		intervalStructure: structuredClone(state.intervalStructure),
		intensity: state.intensity,
		purpose: state.purpose,
		reason: state.reason,
		sourceRefs: [...state.sourceRefs],
		isRemoved: state.isRemoved ?? false
	};
}

function sameWorkoutProposal(left: WorkoutEditProposal, right: WorkoutEditProposal) {
	return JSON.stringify(left) === JSON.stringify(right);
}

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

		const canAffectCurrentPlan = input.occurredDate >= addDays(today, -7);
		const [nextRun] = canAffectCurrentPlan
			? await tx
					.select({
						id: workout.id,
						planId: workout.planId,
						planStartDate: trainingPlan.startDate,
						weekId: workout.weekId,
						scheduledDate: workout.scheduledDate,
						status: workout.status,
						targetDistanceMeters: workout.targetDistanceMeters,
						targetDurationSeconds: workout.targetDurationSeconds,
						type: workout.type,
						intensity: workout.intensity,
						purpose: workout.purpose,
						reason: workout.reason,
						sourceRefs: workout.sourceRefs
					})
					.from(workout)
					.innerJoin(
						trainingPlan,
						and(
							eq(workout.planId, trainingPlan.id),
							eq(trainingPlan.userId, userId),
							eq(trainingPlan.status, 'active'),
							lte(trainingPlan.startDate, input.occurredDate)
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
							gt(workout.scheduledDate, input.occurredDate),
							gte(workout.scheduledDate, today),
							isNull(workoutFeedback.id)
						)
					)
					.orderBy(asc(workout.scheduledDate))
					.limit(1)
			: [];

		const consequence = nextRun
			? calculateExtraActivityConsequence(input, {
					nextRunTargetDistanceMeters: nextRun.targetDistanceMeters,
					nextRunTargetDurationSeconds: nextRun.targetDurationSeconds,
					weekTargetDistanceMeters: Math.max(
						nextRun.targetDistanceMeters,
						await effectiveWeekTargetDistance(tx, userId, nextRun.planId, input.occurredDate)
					),
					weekTargetDurationSeconds: Math.max(
						nextRun.targetDurationSeconds ?? 0,
						await effectiveWeekTargetDuration(tx, userId, nextRun.planId, input.occurredDate)
					)
				})
			: null;
		await tx
			.update(activity)
			.set({
				consequence,
				consequencePlanId: consequence && nextRun ? nextRun.planId : null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, createdActivity.id)));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.manual',
			detail: {
				activityId: createdActivity.id,
				source: 'manual'
			}
		});

		return { activity: createdActivity, consequence };
	});
}

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

async function applyConfirmedExtraActivity(
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
		await tx
			.update(activity)
			.set({ consequence: null, consequencePlanId: null })
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		return null;
	}

	const [nextRun] = await tx
		.select({
			id: workout.id,
			planId: workout.planId,
			weekId: workout.weekId,
			scheduledDate: workout.scheduledDate,
			status: workout.status,
			targetDistanceMeters: workout.targetDistanceMeters,
			targetDurationSeconds: workout.targetDurationSeconds,
			type: workout.type,
			intensity: workout.intensity,
			purpose: workout.purpose,
			reason: workout.reason,
			sourceRefs: workout.sourceRefs
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
		await tx
			.update(activity)
			.set({ consequence: null, consequencePlanId: null })
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		return null;
	}

	const weekTargetDistanceMeters = Math.max(
		nextRun.targetDistanceMeters,
		await effectiveWeekTargetDistance(tx, userId, nextRun.planId, targetActivity.activityDate)
	);
	const weekTargetDurationSeconds = Math.max(
		nextRun.targetDurationSeconds ?? 0,
		await effectiveWeekTargetDuration(tx, userId, nextRun.planId, targetActivity.activityDate)
	);
	const consequence = calculateExtraActivityConsequence(targetActivity, {
		nextRunTargetDistanceMeters: nextRun.targetDistanceMeters,
		nextRunTargetDurationSeconds: nextRun.targetDurationSeconds,
		weekTargetDistanceMeters,
		weekTargetDurationSeconds
	});
	await tx
		.update(activity)
		.set({ consequence, consequencePlanId: nextRun.planId })
		.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
	return consequence;
}

export async function confirmActivityAsExtra(userId: string, activityId: string) {
	return db.transaction(async (tx) => {
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
			.limit(1);
		if (!targetActivity) throw new Error('Activity not found.');
		if (targetActivity.workoutId)
			throw new Error('Linked activities already count against the plan.');
		if (targetActivity.extraPlanImpactConfirmed) {
			throw new Error('This activity has already been counted as extra.');
		}
		await tx
			.update(activity)
			.set({ extraPlanImpactConfirmed: true, reviewState: 'accepted' })
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		const consequence = await applyConfirmedExtraActivity(
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

export async function linkActivityToWorkout(
	userId: string,
	input: { activityId: string; workoutId: string }
) {
	return db.transaction(async (tx) => {
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
				heartRateSummary: activity.heartRateSummary,
				heartRateSeries: activity.heartRateSeries,
				routeTrace: activity.routeTrace,
				feltHard: activity.feltHard,
				pain: activity.pain,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed
			})
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, input.activityId)))
			.limit(1);
		if (!targetActivity) throw new Error('Activity not found.');
		if (targetActivity.workoutId) throw new Error('Activity is already linked.');
		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['manual', 'import_extra', 'decision'],
			reason: 'Linking replaced the unplanned-run adjustment with the linked-workout result.'
		});

		const [targetWorkout] = await tx
			.select({
				id: workout.id,
				planId: workout.planId,
				planStartDate: trainingPlan.startDate,
				weekId: workout.weekId,
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
				sourceRefs: workout.sourceRefs
			})
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(
					eq(workout.planId, trainingPlan.id),
					eq(trainingPlan.userId, userId),
					eq(trainingPlan.status, 'active')
				)
			)
			.leftJoin(
				workoutFeedback,
				and(eq(workoutFeedback.workoutId, workout.id), eq(workoutFeedback.userId, userId))
			)
			.where(
				and(
					eq(workout.userId, userId),
					eq(workout.id, input.workoutId),
					ne(workout.type, 'rest'),
					eq(workout.status, 'planned'),
					eq(workout.isRemoved, false),
					isNull(workoutFeedback.id)
				)
			)
			.limit(1);
		if (!targetWorkout) throw new Error('Workout is not available for linking.');

		const [existingActivity] = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(and(eq(activity.userId, userId), eq(activity.workoutId, targetWorkout.id)))
			.limit(1);
		if (existingActivity) throw new Error('That workout already has an activity.');

		const activityDate = targetActivity.activityDate;
		if (
			targetWorkout.scheduledDate < addDays(activityDate, -3) ||
			targetWorkout.scheduledDate > addDays(activityDate, 3)
		) {
			throw new Error('Workout is outside the activity match window.');
		}
		const effectiveWeekTarget = await effectiveWeekTargetDistance(
			tx,
			userId,
			targetWorkout.planId,
			activityDate
		);
		const calculatedConsequence = calculateConsequence({
			status: 'done',
			choice: 'reduce_next',
			targetDistanceMeters: targetWorkout.targetDistanceMeters,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(targetWorkout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: targetWorkout.targetDurationSeconds }),
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			pain: targetActivity.pain,
			feltHard: targetActivity.feltHard,
			weekTargetDistanceMeters: Math.max(effectiveWeekTarget, targetWorkout.targetDistanceMeters)
		});
		const canAffectCurrentPlan =
			activityDate >= addDays(today, -7) && activityDate >= targetWorkout.planStartDate;
		const planConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
		const feedbackConsequence = planConsequence ?? historicalLinkConsequence(calculatedConsequence);
		const targetWeekId =
			(await planWeekIdForDate(tx, userId, targetWorkout.planId, activityDate)) ??
			targetWorkout.weekId;
		const linkedState = changedWorkoutState(targetWorkout, {
			weekId: targetWeekId,
			scheduledDate: activityDate,
			status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done'
		});

		await tx
			.update(activity)
			.set({
				workoutId: targetWorkout.id,
				reviewState: 'accepted',
				deviation: calculatedConsequence.deviation,
				consequence: planConsequence,
				consequencePlanId: planConsequence ? targetWorkout.planId : null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		await tx
			.update(workout)
			.set({ ...linkedState, updatedAt: new Date() })
			.where(and(eq(workout.userId, userId), eq(workout.id, targetWorkout.id)));
		await recordPlanAdjustment(tx, {
			userId,
			planId: targetWorkout.planId,
			workoutId: targetWorkout.id,
			triggerType: 'link',
			triggerId: targetActivity.id,
			previousState: workoutAdjustmentState(targetWorkout),
			newState: linkedState,
			consequence: planConsequence,
			reason:
				targetWorkout.scheduledDate === activityDate
					? 'Activity completed this planned run.'
					: 'Activity completed and moved this planned run onto the day it occurred.'
		});
		await tx.insert(workoutFeedback).values({
			userId,
			workoutId: targetWorkout.id,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			feltHard: targetActivity.feltHard,
			pain: targetActivity.pain,
			choice: 'reduce_next',
			deviation: feedbackConsequence.deviation,
			consequence: feedbackConsequence
		});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.linked',
			detail: {
				activityId: targetActivity.id,
				workoutId: targetWorkout.id
			}
		});

		return feedbackConsequence;
	});
}

export async function unlinkActivityFromWorkout(userId: string, activityId: string) {
	return db.transaction(async (tx) => {
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
			.limit(1);
		if (!targetActivity) throw new Error('Activity not found.');
		if (!targetActivity.workoutId) throw new Error('Activity is not linked.');
		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['link', 'import_match', 'decision'],
			reason: 'Unlink restored every active workout change made by this link.'
		});

		await tx
			.delete(workoutFeedback)
			.where(
				and(
					eq(workoutFeedback.userId, userId),
					eq(workoutFeedback.workoutId, targetActivity.workoutId)
				)
			);
		await tx
			.update(activity)
			.set({
				workoutId: null,
				deviation: 'unplanned',
				appliedDecision: null,
				consequence: null,
				consequencePlanId: null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		const consequence = await applyConfirmedExtraActivity(tx, userId, targetActivity, today);

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.unlinked',
			detail: {
				activityId: targetActivity.id,
				workoutId: targetActivity.workoutId
			}
		});
		return consequence;
	});
}

export async function updateActivityFeedback(
	userId: string,
	activityId: string,
	input: { feltHard: boolean; pain: boolean }
) {
	return db.transaction(async (tx) => {
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
			.limit(1);
		if (!targetActivity) throw new Error('Activity not found.');

		if (!targetActivity.workoutId) {
			await reverseLedgerAdjustmentsForTrigger(tx, {
				userId,
				triggerId: targetActivity.id,
				originalTriggerTypes: ['manual', 'import_extra', 'decision'],
				reason: 'Updating activity feedback replaced the earlier extra-run adjustment.'
			});
			await tx
				.update(activity)
				.set({
					feltHard: input.feltHard,
					pain: input.pain,
					appliedDecision: null,
					consequence: null,
					consequencePlanId: null
				})
				.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
			const consequence = await applyConfirmedExtraActivity(
				tx,
				userId,
				{ ...targetActivity, ...input },
				today
			);
			await tx.insert(auditEvent).values({
				userId,
				eventType: 'activity.feedback_updated',
				detail: { activityId: targetActivity.id, linked: false }
			});
			return consequence;
		}

		const [linkedWorkout] = await tx
			.select({
				id: workout.id,
				planId: workout.planId,
				planStartDate: trainingPlan.startDate,
				planStatus: trainingPlan.status,
				weekId: workout.weekId,
				scheduledDate: workout.scheduledDate,
				status: workout.status,
				targetDistanceMeters: workout.targetDistanceMeters,
				targetDurationSeconds: workout.targetDurationSeconds,
				prescriptionKind: workout.prescriptionKind,
				intervalStructure: workout.intervalStructure,
				type: workout.type,
				intensity: workout.intensity,
				purpose: workout.purpose,
				reason: workout.reason,
				sourceRefs: workout.sourceRefs
			})
			.from(workout)
			.innerJoin(
				trainingPlan,
				and(eq(workout.planId, trainingPlan.id), eq(trainingPlan.userId, userId))
			)
			.where(and(eq(workout.userId, userId), eq(workout.id, targetActivity.workoutId)))
			.limit(1);
		if (!linkedWorkout) throw new Error('Linked workout not found.');

		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['link', 'import_match', 'decision'],
			excludeWorkoutIds: [linkedWorkout.id],
			reason: 'Updating activity feedback replaced its earlier next-run adjustment.'
		});

		const effectiveWeekTarget = await effectiveWeekTargetDistance(
			tx,
			userId,
			linkedWorkout.planId,
			targetActivity.activityDate
		);
		const calculatedConsequence = calculateConsequence({
			status: 'done',
			choice: 'reduce_next',
			targetDistanceMeters: linkedWorkout.targetDistanceMeters,
			completedDistanceMeters: targetActivity.distanceMeters,
			...(linkedWorkout.targetDurationSeconds === null
				? {}
				: { targetDurationSeconds: linkedWorkout.targetDurationSeconds }),
			...(targetActivity.durationSeconds === null
				? {}
				: { completedDurationSeconds: targetActivity.durationSeconds }),
			pain: input.pain,
			feltHard: input.feltHard,
			weekTargetDistanceMeters: Math.max(effectiveWeekTarget, linkedWorkout.targetDistanceMeters)
		});
		const canAffectCurrentPlan =
			linkedWorkout.planStatus === 'active' &&
			targetActivity.activityDate >= addDays(today, -7) &&
			targetActivity.activityDate >= linkedWorkout.planStartDate;
		const planConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
		const feedbackConsequence = planConsequence ?? historicalLinkConsequence(calculatedConsequence);
		await tx
			.update(activity)
			.set({
				feltHard: input.feltHard,
				pain: input.pain,
				deviation: calculatedConsequence.deviation,
				appliedDecision: null,
				consequence: planConsequence,
				consequencePlanId: planConsequence ? linkedWorkout.planId : null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));
		await tx
			.update(workoutFeedback)
			.set({
				feltHard: input.feltHard,
				pain: input.pain,
				appliedDecision: null,
				deviation: feedbackConsequence.deviation,
				consequence: feedbackConsequence
			})
			.where(
				and(eq(workoutFeedback.userId, userId), eq(workoutFeedback.workoutId, linkedWorkout.id))
			);
		await tx
			.update(workout)
			.set({
				status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done',
				updatedAt: new Date()
			})
			.where(and(eq(workout.userId, userId), eq(workout.id, linkedWorkout.id)));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.feedback_updated',
			detail: { activityId: targetActivity.id, linked: true }
		});
		return feedbackConsequence;
	});
}

export async function deleteActivityRecord(userId: string, activityId: string) {
	return db.transaction(async (tx) => {
		const [targetActivity] = await tx
			.select({
				id: activity.id,
				workoutId: activity.workoutId,
				source: activity.source,
				fileHash: activityImport.fileHash
			})
			.from(activity)
			.leftJoin(
				activityImport,
				and(eq(activityImport.activityId, activity.id), eq(activityImport.userId, userId))
			)
			.where(and(eq(activity.userId, userId), eq(activity.id, activityId)))
			.limit(1);
		if (!targetActivity) throw new Error('Activity not found.');
		await reverseLedgerAdjustmentsForTrigger(tx, {
			userId,
			triggerId: targetActivity.id,
			originalTriggerTypes: ['manual', 'import_match', 'import_extra', 'link', 'decision'],
			reason: 'Deleting the activity restored every active workout change derived from it.'
		});
		if (targetActivity.workoutId) {
			await tx
				.delete(workoutFeedback)
				.where(
					and(
						eq(workoutFeedback.userId, userId),
						eq(workoutFeedback.workoutId, targetActivity.workoutId)
					)
				);
		}
		if (targetActivity.fileHash) {
			await tx
				.insert(activityDeletionTombstone)
				.values({ userId, fileHash: targetActivity.fileHash })
				.onConflictDoNothing();
		}
		await tx
			.delete(auditEvent)
			.where(
				and(
					eq(auditEvent.userId, userId),
					sql`${auditEvent.detail} ->> 'activityId' = ${targetActivity.id}`
				)
			);
		await tx
			.update(planAdjustment)
			.set({ triggerId: null, consequence: null, reason: 'Deleted activity adjustment.' })
			.where(
				and(eq(planAdjustment.userId, userId), eq(planAdjustment.triggerId, targetActivity.id))
			);

		// Keep the keyed remote-item marker so a connected source does not fetch the
		// same file again. The marker contains no reversible path, and clearing the
		// activity reference lets the private activity itself be deleted.
		await tx
			.update(importSourceItem)
			.set({ activityId: null })
			.where(
				and(eq(importSourceItem.userId, userId), eq(importSourceItem.activityId, targetActivity.id))
			);
		await tx
			.delete(activityImport)
			.where(
				and(eq(activityImport.userId, userId), eq(activityImport.activityId, targetActivity.id))
			);
		await tx
			.delete(activity)
			.where(and(eq(activity.userId, userId), eq(activity.id, targetActivity.id)));

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.deleted',
			detail: {
				source: targetActivity.source
			}
		});
	});
}

export async function getHistory(userId: string) {
	const timeZone = await requireAthleteTimeZone(userId);
	const today = todayIsoInTimeZone(timeZone);
	const [activePlan, recordedSummary, heartRateSample, [acceptedActivity]] = await Promise.all([
		getActivePlan(userId),
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

	const weeks = await getPlanWeeks(userId, activePlan.plan.id);
	const planWorkouts = await db
		.select()
		.from(workout)
		.where(
			and(
				eq(workout.userId, userId),
				eq(workout.planId, activePlan.plan.id),
				eq(workout.isRemoved, false)
			)
		)
		.orderBy(asc(workout.scheduledDate));
	const planFeedback = await db
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
		.innerJoin(workout, and(eq(workoutFeedback.workoutId, workout.id), eq(workout.userId, userId)))
		.where(and(eq(workoutFeedback.userId, userId), eq(workout.planId, activePlan.plan.id)))
		.orderBy(desc(workoutFeedback.createdAt))
		.limit(300);
	const activityWeekStart = sql<string>`to_char(date_trunc('week', ${activity.activityDate}::timestamp), 'YYYY-MM-DD')`;
	const activitySummaryWhere = and(
		eq(activity.userId, userId),
		eq(activity.reviewState, 'accepted'),
		gte(activity.activityDate, activePlan.plan.startDate),
		lte(activity.activityDate, today),
		or(eq(workout.planId, activePlan.plan.id), isNull(activity.workoutId))
	);
	const [activityWeeklyRows, linkedActivityRows] = await Promise.all([
		db
			.select({
				weekStart: activityWeekStart,
				completedRuns: sql<number>`count(*)::int`,
				completedDistanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}) filter (where ${workout.type} is distinct from 'race'), 0)::int`,
				eventCompletedDistanceMeters: sql<number>`coalesce(sum(${activity.distanceMeters}) filter (where ${workout.type} = 'race'), 0)::int`,
				completedDurationSeconds: sql<number>`coalesce(sum(${activity.durationSeconds}) filter (where ${workout.type} is distinct from 'race'), 0)::int`,
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
			.limit(52 * 7)
	]);
	const activityWeeklyByStart = new Map(
		activityWeeklyRows.map((record) => [record.weekStart, record])
	);
	const activityWorkoutIds = new Set(
		linkedActivityRows.flatMap((record) => (record.workoutId ? [record.workoutId] : []))
	);

	const latestFeedbackByWorkout = new Map<string, (typeof planFeedback)[number]>();
	for (const feedback of planFeedback) {
		if (!latestFeedbackByWorkout.has(feedback.workoutId)) {
			latestFeedbackByWorkout.set(feedback.workoutId, feedback);
		}
	}

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
			let changedRuns = 0;
			let missedRuns = 0;
			let skippedRuns = 0;
			let painFlags = activitySummary?.painFlags ?? 0;
			let hardFlags = activitySummary?.hardFlags ?? 0;

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
				if (!hasActivity) completedDurationSeconds += feedback?.completedDurationSeconds ?? 0;
				if (['skipped', 'shortened', 'moved'].includes(record.status)) changedRuns += 1;
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
				averagePaceSecondsPerKm:
					completedDistanceMeters > 0 && completedDurationSeconds > 0
						? completedDurationSeconds / (completedDistanceMeters / 1_000)
						: null,
				changedRuns,
				missedRuns,
				skippedRuns,
				painFlags,
				hardFlags,
				averageHeartRate: activitySummary?.averageHeartRate ?? null,
				highHeartRateRuns: 0
			};
		});
	const currentSignal = await currentTrainingSignal(userId, activePlan.plan, today);

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

async function getRecentFeedback(userId: string) {
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

	const [linked] = await db
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
		.where(and(eq(workout.userId, userId), ne(workout.type, 'rest')));

	const [unlinked] = await db
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
		);

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

export async function getImportWorkoutCandidates(userId: string) {
	const activePlan = await getActivePlan(userId);
	if (!activePlan) return [];

	const today = todayIsoInTimeZone(await requireAthleteTimeZone(userId));
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

export async function exportUserData(userId: string) {
	const [
		[account],
		[profile],
		goals,
		plans,
		weeks,
		workouts,
		feedback,
		activities,
		imports,
		adjustments,
		importSources,
		importItems,
		deletionTombstones,
		auditEvents
	] = await Promise.all([
		db
			.select({
				id: authUser.id,
				name: authUser.name,
				email: authUser.email,
				createdAt: authUser.createdAt
			})
			.from(authUser)
			.where(eq(authUser.id, userId))
			.limit(1),
		db.select().from(athleteProfile).where(eq(athleteProfile.userId, userId)).limit(1),
		db.select().from(goal).where(eq(goal.userId, userId)).orderBy(desc(goal.createdAt)),
		db
			.select()
			.from(trainingPlan)
			.where(eq(trainingPlan.userId, userId))
			.orderBy(desc(trainingPlan.createdAt)),
		db
			.select()
			.from(trainingWeek)
			.where(eq(trainingWeek.userId, userId))
			.orderBy(asc(trainingWeek.startDate)),
		db.select().from(workout).where(eq(workout.userId, userId)).orderBy(asc(workout.scheduledDate)),
		db
			.select()
			.from(workoutFeedback)
			.where(eq(workoutFeedback.userId, userId))
			.orderBy(desc(workoutFeedback.createdAt)),
		db
			.select()
			.from(activity)
			.where(eq(activity.userId, userId))
			.orderBy(desc(activity.occurredAt)),
		db
			.select()
			.from(activityImport)
			.where(eq(activityImport.userId, userId))
			.orderBy(desc(activityImport.createdAt)),
		db
			.select()
			.from(planAdjustment)
			.where(eq(planAdjustment.userId, userId))
			.orderBy(desc(planAdjustment.createdAt)),
		db
			.select({
				id: importSource.id,
				type: importSource.type,
				label: importSource.label,
				shareHost: importSource.shareHost,
				enabled: importSource.enabled,
				syncIntervalMinutes: importSource.syncIntervalMinutes,
				lastCheckedAt: importSource.lastCheckedAt,
				lastSuccessAt: importSource.lastSuccessAt,
				lastImportedAt: importSource.lastImportedAt,
				lastError: importSource.lastError,
				createdAt: importSource.createdAt,
				updatedAt: importSource.updatedAt
			})
			.from(importSource)
			.where(eq(importSource.userId, userId)),
		db
			.select({
				id: importSourceItem.id,
				sourceId: importSourceItem.sourceId,
				status: importSourceItem.status,
				contentLength: importSourceItem.contentLength,
				firstSeenAt: importSourceItem.firstSeenAt,
				lastCheckedAt: importSourceItem.lastCheckedAt,
				importedAt: importSourceItem.importedAt,
				errorSummary: importSourceItem.errorSummary
			})
			.from(importSourceItem)
			.where(eq(importSourceItem.userId, userId)),
		db
			.select()
			.from(activityDeletionTombstone)
			.where(eq(activityDeletionTombstone.userId, userId))
			.orderBy(desc(activityDeletionTombstone.createdAt)),
		db
			.select()
			.from(auditEvent)
			.where(eq(auditEvent.userId, userId))
			.orderBy(desc(auditEvent.createdAt))
	]);

	return {
		version: 2,
		exportedAt: new Date().toISOString(),
		account: account ?? null,
		profile: profile ?? null,
		goals,
		plans,
		weeks,
		workouts,
		feedback,
		activities,
		imports,
		adjustments,
		importSources,
		importItems,
		deletionTombstones,
		redactions: [
			'import source share tokens and sealed passwords',
			'import item remote paths, etags, and content hashes'
		],
		auditEvents
	};
}

export async function deleteActivityData(userId: string) {
	return db.transaction(async (tx) => {
		await tx
			.update(athleteProfile)
			.set({
				activityImportGeneration: sql`${athleteProfile.activityImportGeneration} + 1`,
				updatedAt: new Date()
			})
			.where(eq(athleteProfile.userId, userId));
		const records = await tx
			.select({
				id: activity.id,
				workoutId: activity.workoutId,
				fileHash: activityImport.fileHash
			})
			.from(activity)
			.leftJoin(
				activityImport,
				and(eq(activityImport.activityId, activity.id), eq(activityImport.userId, userId))
			)
			.where(and(eq(activity.userId, userId), eq(activity.source, 'gpx')));
		const workoutIds = Array.from(
			new Set(records.map((record) => record.workoutId).filter((id) => id !== null))
		);
		const deletedSources = await tx
			.delete(importSource)
			.where(eq(importSource.userId, userId))
			.returning({ id: importSource.id });
		const activityIds = records.map((record) => record.id);
		if (activityIds.length > 0) {
			const reversed = await tx
				.update(planAdjustment)
				.set({
					reversedAt: new Date(),
					reversalReason: 'Imported activity data was deleted.'
				})
				.where(
					and(
						eq(planAdjustment.userId, userId),
						inArray(planAdjustment.triggerId, activityIds),
						isNull(planAdjustment.reversedAt)
					)
				)
				.returning({ workoutId: planAdjustment.workoutId });
			await replayWorkoutLedgers(
				tx,
				userId,
				Array.from(new Set(reversed.map((row) => row.workoutId)))
			);
			const hashes = Array.from(
				new Set(records.flatMap((record) => (record.fileHash ? [record.fileHash] : [])))
			);
			if (hashes.length > 0) {
				await tx
					.insert(activityDeletionTombstone)
					.values(hashes.map((fileHash) => ({ userId, fileHash })))
					.onConflictDoNothing();
			}
			await tx
				.delete(auditEvent)
				.where(
					and(
						eq(auditEvent.userId, userId),
						inArray(sql<string>`${auditEvent.detail} ->> 'activityId'`, activityIds)
					)
				);
			await tx
				.update(planAdjustment)
				.set({ triggerId: null, consequence: null, reason: 'Deleted activity adjustment.' })
				.where(
					and(eq(planAdjustment.userId, userId), inArray(planAdjustment.triggerId, activityIds))
				);
		}

		await tx.delete(activityImport).where(eq(activityImport.userId, userId));
		await tx.delete(importSourceItem).where(eq(importSourceItem.userId, userId));
		if (activityIds.length > 0) {
			await tx
				.delete(activity)
				.where(and(eq(activity.userId, userId), inArray(activity.id, activityIds)));
		}
		if (workoutIds.length > 0) {
			await tx
				.delete(workoutFeedback)
				.where(
					and(eq(workoutFeedback.userId, userId), inArray(workoutFeedback.workoutId, workoutIds))
				);
		}

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.deleted',
			detail: {
				count: records.length,
				disconnectedImportSources: deletedSources.length
			}
		});

		return { count: records.length, disconnectedImportSources: deletedSources.length };
	});
}

export async function recordImportedActivity(
	userId: string,
	fileHash: string,
	parsed: ParsedGpxActivity,
	matching: { mode: 'unlinked' } | { mode: 'auto' } | { mode: 'workout'; workoutId: string },
	expectedImportGeneration: number
) {
	return db.transaction(async (tx) => {
		const requestedWorkoutId = matching.mode === 'workout' ? matching.workoutId : undefined;
		const timeZone = await requireAthleteTimeZoneInTransaction(
			tx,
			userId,
			'Set training time zone before importing.'
		);
		const today = todayIsoInTimeZone(timeZone);
		const [importProfile] = await tx
			.select({ generation: athleteProfile.activityImportGeneration })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
			.for('update');
		if (importProfile?.generation !== expectedImportGeneration) {
			throw new Error('Import was cancelled because activity data was deleted.');
		}
		const [[existingImport], [deletedImport]] = await Promise.all([
			tx
				.select({ id: activityImport.id })
				.from(activityImport)
				.where(and(eq(activityImport.userId, userId), eq(activityImport.fileHash, fileHash)))
				.limit(1),
			tx
				.select({ id: activityDeletionTombstone.id })
				.from(activityDeletionTombstone)
				.where(
					and(
						eq(activityDeletionTombstone.userId, userId),
						eq(activityDeletionTombstone.fileHash, fileHash)
					)
				)
				.limit(1)
		]);
		if (existingImport) throw new Error('This activity file has already been imported.');
		if (deletedImport) throw new Error('This deleted activity file cannot be imported again.');

		const activityDate = toIsoDateInTimeZone(parsed.startedAt, timeZone);
		if (activityDate > today) {
			throw new Error('Imported activities cannot be in the future.');
		}
		if (requestedWorkoutId) {
			const [existingActivity] = await tx
				.select({ id: activity.id })
				.from(activity)
				.where(and(eq(activity.userId, userId), eq(activity.workoutId, requestedWorkoutId)))
				.limit(1);
			if (existingActivity) {
				throw new Error('This workout already has an imported activity.');
			}
		}

		const candidateWorkouts =
			matching.mode === 'unlinked'
				? []
				: await tx
						.select({
							id: workout.id,
							planId: workout.planId,
							planStartDate: trainingPlan.startDate,
							weekId: workout.weekId,
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
							sourceRefs: workout.sourceRefs
						})
						.from(workout)
						.innerJoin(
							trainingPlan,
							and(
								eq(workout.planId, trainingPlan.id),
								eq(trainingPlan.userId, userId),
								eq(trainingPlan.status, 'active')
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
								gte(workout.scheduledDate, addDays(activityDate, -3)),
								lte(workout.scheduledDate, addDays(activityDate, 3)),
								isNull(workoutFeedback.id),
								...(requestedWorkoutId ? [eq(workout.id, requestedWorkoutId)] : [])
							)
						)
						.limit(requestedWorkoutId ? 1 : 20);
		const matchedWorkoutId = requestedWorkoutId
			? candidateWorkouts[0]?.id
			: matching.mode === 'auto'
				? (selectAutoWorkoutMatch(
						{
							activityDate,
							distanceMeters: parsed.distanceMeters,
							durationSeconds: parsed.durationSeconds
						},
						candidateWorkouts
					) ?? undefined)
				: undefined;
		const matchedWorkout = matchedWorkoutId
			? candidateWorkouts.find((candidate) => candidate.id === matchedWorkoutId)
			: undefined;
		if (requestedWorkoutId && !matchedWorkoutId) {
			throw new Error('Selected workout is not available for this activity import.');
		}

		const [profile] = await tx
			.select({
				heartRateSettings: athleteProfile.heartRateSettings,
				routeDataMode: athleteProfile.routeDataMode
			})
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1);
		const heartRateSummary = summarizeHeartRateEffort(parsed, profile?.heartRateSettings);
		const heartRateSeries = buildHeartRateSeries(parsed);
		const routeTrace =
			profile?.routeDataMode === 'private' ? buildActivityRouteTrace(parsed) : null;
		// Zone occupancy is descriptive; it cannot stand in for the athlete's
		// subjective report that a run felt hard.
		const feltHardFromHeartRate = false;

		let createdActivity;
		try {
			[createdActivity] = await tx
				.insert(activity)
				.values({
					userId,
					...(matchedWorkoutId ? { workoutId: matchedWorkoutId } : {}),
					source: 'gpx',
					reviewState: matchedWorkoutId ? 'accepted' : 'review',
					occurredAt: parsed.startedAt,
					activityDate,
					distanceMeters: parsed.distanceMeters,
					durationSeconds: parsed.durationSeconds,
					averagePaceSecondsPerKm:
						parsed.distanceMeters > 0
							? parsed.durationSeconds / (parsed.distanceMeters / 1_000)
							: undefined,
					averageHeartRate: parsed.averageHeartRate,
					maxHeartRate: parsed.maxHeartRate,
					heartRateSummary,
					heartRateSeries,
					routeTrace,
					feltHard: feltHardFromHeartRate,
					pain: false,
					averageCadence: parsed.averageCadence,
					routeSummary: {
						pointCount: parsed.pointCount,
						startEndRedacted: routeTrace === null,
						hasElevation: parsed.hasElevation,
						traceRetained: routeTrace !== null
					}
				})
				.returning();
		} catch (error) {
			if (isActivityWorkoutConstraint(error)) {
				throw new Error('This workout already has an imported activity.', { cause: error });
			}
			throw error;
		}
		if (!createdActivity) throw new Error('Failed to record imported activity.');

		let importConsequence: ConsequenceResult | null = null;
		let importDeviation: ConsequenceResult['deviation'] = 'unplanned';
		if (matchedWorkout) {
			const effectiveWeekTarget = await effectiveWeekTargetDistance(
				tx,
				userId,
				matchedWorkout.planId,
				activityDate
			);
			const calculatedConsequence = calculateConsequence({
				status: 'done',
				choice: 'reduce_next',
				targetDistanceMeters: matchedWorkout.targetDistanceMeters,
				completedDistanceMeters: parsed.distanceMeters,
				...(matchedWorkout.targetDurationSeconds === null
					? {}
					: { targetDurationSeconds: matchedWorkout.targetDurationSeconds }),
				completedDurationSeconds: parsed.durationSeconds,
				pain: false,
				feltHard: feltHardFromHeartRate,
				weekTargetDistanceMeters: Math.max(effectiveWeekTarget, matchedWorkout.targetDistanceMeters)
			});
			importDeviation = calculatedConsequence.deviation;
			const canAffectCurrentPlan =
				activityDate >= addDays(today, -7) && activityDate >= matchedWorkout.planStartDate;
			importConsequence = canAffectCurrentPlan ? calculatedConsequence : null;
			const feedbackConsequence =
				importConsequence ?? historicalLinkConsequence(calculatedConsequence);
			const targetWeekId =
				(await planWeekIdForDate(tx, userId, matchedWorkout.planId, activityDate)) ??
				matchedWorkout.weekId;
			const completedState = changedWorkoutState(matchedWorkout, {
				weekId: targetWeekId,
				scheduledDate: activityDate,
				status: calculatedConsequence.deviation === 'short' ? 'shortened' : 'done'
			});
			await tx
				.update(workout)
				.set({ ...completedState, updatedAt: new Date() })
				.where(and(eq(workout.userId, userId), eq(workout.id, matchedWorkout.id)));
			await recordPlanAdjustment(tx, {
				userId,
				planId: matchedWorkout.planId,
				workoutId: matchedWorkout.id,
				triggerType: 'import_match',
				triggerId: createdActivity.id,
				previousState: workoutAdjustmentState(matchedWorkout),
				newState: completedState,
				consequence: importConsequence,
				reason:
					matchedWorkout.scheduledDate === activityDate
						? 'Imported activity completed this planned run.'
						: 'Imported activity completed and moved this run onto the day it occurred.'
			});

			await tx.insert(workoutFeedback).values({
				userId,
				workoutId: matchedWorkout.id,
				completedDistanceMeters: parsed.distanceMeters,
				completedDurationSeconds: parsed.durationSeconds,
				feltHard: feltHardFromHeartRate,
				pain: false,
				choice: 'reduce_next',
				deviation: feedbackConsequence.deviation,
				consequence: feedbackConsequence
			});
		}
		await tx
			.update(activity)
			.set({
				deviation: importDeviation,
				consequence: importConsequence,
				consequencePlanId: importConsequence && matchedWorkout ? matchedWorkout.planId : null
			})
			.where(and(eq(activity.userId, userId), eq(activity.id, createdActivity.id)));

		try {
			await tx.insert(activityImport).values({
				userId,
				activityId: createdActivity.id,
				fileHash,
				result: 'imported',
				metadata: {
					pointCount: parsed.pointCount,
					hasHeartRate: parsed.hasHeartRate,
					hasCadence: parsed.hasCadence,
					hasSpeed: parsed.hasSpeed
				}
			});
		} catch (error) {
			if (isDuplicateImportConstraint(error)) {
				throw new Error('This activity file has already been imported.', { cause: error });
			}
			throw error;
		}

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'activity.imported',
			detail: {
				activityId: createdActivity.id,
				source: 'gpx',
				matchedWorkout: Boolean(matchedWorkoutId)
			}
		});

		return { ...createdActivity, importConsequence };
	});
}

function historicalLinkConsequence(calculated: ConsequenceResult): ConsequenceResult {
	const historicalPain = calculated.kind === 'pain_reported';
	return {
		...calculated,
		kind: historicalPain ? 'pain_reported' : 'historical_link',
		weeklyDistanceDeltaMeters: 0,
		nextRunAdjustmentMeters: 0,
		risk: historicalPain ? 'unsafe' : 'conservative',
		recommendedDecision: 'keep_plan',
		options: ['keep_plan'],
		appliedDecision: null
	};
}

function isDuplicateImportConstraint(error: unknown): boolean {
	return isUniqueConstraint(error, ['activity_import_user_hash_unique']);
}

function isActivityWorkoutConstraint(error: unknown): boolean {
	return isUniqueConstraint(error, ['activity_workout_unique']);
}

function isUniqueConstraint(error: unknown, constraints: string[]): boolean {
	if (typeof error !== 'object' || error === null) return false;
	const maybePostgresError = error as {
		code?: unknown;
		constraint_name?: unknown;
		constraint?: unknown;
		message?: unknown;
	};
	const rawConstraint = maybePostgresError.constraint ?? maybePostgresError.constraint_name;
	const constraint = typeof rawConstraint === 'string' ? rawConstraint : '';
	const message = typeof maybePostgresError.message === 'string' ? maybePostgresError.message : '';
	return (
		maybePostgresError.code === '23505' &&
		constraints.some((name) => constraint === name || message.includes(name))
	);
}

export function generatedPlanToIntake(input: {
	raceDistance: TrainingIntake['raceDistance'];
	targetDate: string;
	priority: TrainingIntake['priority'];
	currentWeeklyDistanceKm: number;
	currentRunsPerWeek: number;
	longestRecentRunKm: number;
	experience: TrainingIntake['experience'];
	availability: number[];
	preferredLongRunDay: number;
	recentInjury: boolean;
	currentPain: boolean;
	recurringPain: boolean;
	medicalRestriction: boolean;
	injuryNotes: string;
}): TrainingIntake {
	return {
		raceDistance: input.raceDistance,
		targetDate: input.targetDate,
		priority: input.priority,
		units: 'metric',
		currentWeeklyDistanceMeters: Math.round(input.currentWeeklyDistanceKm * 1_000),
		currentRunsPerWeek: input.currentRunsPerWeek,
		longestRecentRunMeters: Math.round(input.longestRecentRunKm * 1_000),
		experience: input.experience,
		availability: input.availability,
		preferredLongRunDay: input.preferredLongRunDay,
		injuryFlags: {
			recentInjury: input.recentInjury,
			currentPain: input.currentPain,
			recurringPain: input.recurringPain,
			medicalRestriction: input.medicalRestriction,
			notes: input.injuryNotes
		}
	};
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

const riskRank: Record<RiskRating, number> = {
	conservative: 0,
	moderate: 1,
	aggressive: 2,
	unsafe: 3
};
