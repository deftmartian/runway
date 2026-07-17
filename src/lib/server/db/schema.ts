import { relations, sql } from 'drizzle-orm';
import {
	boolean,
	check,
	date,
	foreignKey,
	index,
	integer,
	jsonb,
	pgEnum,
	pgTable,
	real,
	text,
	timestamp,
	uniqueIndex,
	uuid
} from 'drizzle-orm/pg-core';
import type {
	ActivityRouteTrace,
	ConsequenceResult,
	HeartRateActivitySummary,
	HeartRateSeries,
	HeartRateSettings,
	PlanSummary,
	TimedIntervalStructure,
	WorkoutStatus,
	WorkoutType
} from '../../training/types';
import { user } from './auth.schema';

export * from './auth.schema';

type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
type WorkoutAdjustmentState = {
	weekId: string;
	scheduledDate: string;
	type: WorkoutType;
	status: WorkoutStatus;
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	prescriptionKind: 'distance' | 'timed' | 'rest';
	intervalStructure: TimedIntervalStructure | null;
	intensity: string;
	purpose: string;
	reason: string;
	sourceRefs: string[];
	isRemoved: boolean;
};

export const raceDistance = pgEnum('race_distance', ['5k', '10k', 'half', 'marathon']);
export const goalKind = pgEnum('goal_kind', ['race', 'foundation']);
export const goalState = pgEnum('goal_state', ['pending', 'active', 'completed', 'archived']);
export const startMode = pgEnum('start_mode', [
	'established',
	'foundation_to_goal',
	'foundation_only',
	'calibration'
]);
export const goalPriority = pgEnum('goal_priority', ['finish_healthy', 'consistency']);
export const sexForEstimates = pgEnum('sex_for_estimates', ['female', 'male', 'not_specified']);
export const planStatus = pgEnum('plan_status', ['draft', 'active', 'archived']);
export const planPhase = pgEnum('plan_phase', ['distance', 'foundation', 'calibration']);
export const riskRating = pgEnum('risk_rating', [
	'conservative',
	'moderate',
	'aggressive',
	'unsafe'
]);
export const workoutType = pgEnum('workout_type', ['easy', 'long', 'recovery', 'rest', 'race']);
export const workoutStatus = pgEnum('workout_status', ['planned', 'done', 'skipped', 'shortened']);
export const workoutPrescriptionKind = pgEnum('workout_prescription_kind', [
	'distance',
	'timed',
	'rest'
]);
export const activitySource = pgEnum('activity_source', ['manual', 'gpx']);
export const activityReviewState = pgEnum('activity_review_state', ['review', 'accepted']);
export const deviationClassification = pgEnum('deviation_classification', [
	'near_plan',
	'short',
	'over',
	'skipped',
	'unplanned',
	'not_applicable'
]);
export const planDecision = pgEnum('plan_decision', [
	'keep_plan',
	'reduce_next',
	'next_rest',
	'repeat_prescription',
	'rebalance_week'
]);
export const consequenceChoice = pgEnum('consequence_choice', ['skip_continue', 'reduce_next']);
export const planAdjustmentTrigger = pgEnum('plan_adjustment_trigger', [
	'feedback',
	'manual',
	'import_match',
	'import_extra',
	'link',
	'decision',
	'manual_edit',
	'manual_add',
	'manual_remove',
	'rebalance'
]);

export const athleteProfile = pgTable(
	'athlete_profile',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		units: text('units').notNull().default('metric'),
		timeZone: text('time_zone'),
		sexForEstimates: sexForEstimates('sex_for_estimates').notNull().default('not_specified'),
		ageYears: integer('age_years'),
		heartRateSettings: jsonb('heart_rate_settings').$type<HeartRateSettings>(),
		routeDataMode: text('route_data_mode')
			.$type<'discard' | 'private'>()
			.notNull()
			.default('discard'),
		currentWeeklyDistanceMeters: integer('current_weekly_distance_meters').notNull().default(0),
		currentRunsPerWeek: integer('current_runs_per_week').notNull().default(0),
		longestRecentRunMeters: integer('longest_recent_run_meters').notNull().default(0),
		experience: text('experience').notNull().default('unspecified'),
		preferredLongRunDay: integer('preferred_long_run_day'),
		availability: jsonb('availability').$type<number[]>().notNull().default([]),
		activityImportGeneration: integer('activity_import_generation').notNull().default(0),
		injuryFlags: jsonb('injury_flags')
			.$type<{
				recentInjury: boolean;
				currentPain: boolean;
				recurringPain: boolean;
				medicalRestriction: boolean;
				notes: string;
			}>()
			.notNull()
			.default({
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}),
		createdAt: timestamp('created_at').defaultNow().notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		uniqueIndex('athlete_profile_user_id_unique').on(table.userId),
		index('athlete_profile_user_id_idx').on(table.userId),
		index('athlete_profile_updated_at_idx').on(table.updatedAt),
		check(
			'athlete_profile_age_range',
			sql`${table.ageYears} is null or ${table.ageYears} between 18 and 100`
		),
		check(
			'athlete_profile_time_zone_nonempty',
			sql`${table.timeZone} is null or length(trim(${table.timeZone})) between 1 and 255`
		),
		check(
			'athlete_profile_import_generation_nonnegative',
			sql`${table.activityImportGeneration} >= 0`
		),
		check(
			'athlete_profile_route_data_mode_known',
			sql`${table.routeDataMode} in ('discard', 'private')`
		)
	]
);

export const goal = pgTable(
	'goal',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		title: text('title').notNull(),
		kind: goalKind('kind').notNull(),
		state: goalState('state').notNull(),
		startMode: startMode('start_mode').notNull(),
		distance: raceDistance('distance'),
		targetDate: date('target_date').notNull(),
		priority: goalPriority('priority').notNull().default('finish_healthy'),
		targetTimeSeconds: integer('target_time_seconds'),
		notes: text('notes'),
		createdAt: timestamp('created_at').defaultNow().notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		uniqueIndex('goal_id_user_unique').on(table.id, table.userId),
		index('goal_user_id_idx').on(table.userId),
		index('goal_user_target_date_idx').on(table.userId, table.targetDate),
		uniqueIndex('goal_current_user_unique')
			.on(table.userId)
			.where(sql`${table.state} in ('pending', 'active')`),
		check(
			'goal_kind_distance_consistent',
			sql`(${table.kind} = 'race' and ${table.distance} is not null) or (${table.kind} = 'foundation' and ${table.distance} is null)`
		),
		check(
			'goal_start_mode_consistent',
			sql`(${table.startMode} = 'foundation_only' and ${table.kind} = 'foundation') or (${table.startMode} <> 'foundation_only')`
		)
	]
);

export const trainingPlan = pgTable(
	'training_plan',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		goalId: uuid('goal_id').notNull(),
		status: planStatus('status').notNull().default('active'),
		phase: planPhase('phase').notNull(),
		startDate: date('start_date').notNull(),
		targetDate: date('target_date').notNull(),
		weeks: integer('weeks').notNull(),
		risk: riskRating('risk').notNull(),
		summary: jsonb('plan_summary').$type<PlanSummary>().notNull(),
		sourceRefs: jsonb('source_refs').$type<string[]>().notNull().default([]),
		completedAt: timestamp('completed_at', { withTimezone: true }),
		archivedAt: timestamp('archived_at', { withTimezone: true }),
		lifecycleReason: text('lifecycle_reason').$type<
			'completed' | 'changed_goal' | 'abandoned' | null
		>(),
		createdAt: timestamp('created_at').defaultNow().notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		uniqueIndex('training_plan_id_user_unique').on(table.id, table.userId),
		index('training_plan_user_status_idx').on(table.userId, table.status),
		index('training_plan_goal_id_idx').on(table.goalId),
		uniqueIndex('training_plan_active_user_unique')
			.on(table.userId)
			.where(sql`${table.status} = 'active'`),
		check('training_plan_weeks_range', sql`${table.weeks} between 1 and 52`),
		check(
			'training_plan_lifecycle_reason_known',
			sql`${table.lifecycleReason} is null or ${table.lifecycleReason} in ('completed', 'changed_goal', 'abandoned')`
		),
		check(
			'training_plan_lifecycle_status_consistent',
			sql`(
				(${table.status} in ('active', 'draft') and ${table.archivedAt} is null and ${table.completedAt} is null and ${table.lifecycleReason} is null)
				or
				(${table.status} = 'archived' and ${table.archivedAt} is not null and ${table.lifecycleReason} is not null)
			)`
		),
		check(
			'training_plan_completed_reason_consistent',
			sql`(
				(${table.completedAt} is null and ${table.lifecycleReason} is distinct from 'completed')
				or
				(${table.completedAt} is not null and ${table.lifecycleReason} = 'completed')
			)`
		),
		check(
			'training_plan_lifecycle_chronology',
			sql`${table.completedAt} is null or ${table.archivedAt} is null or ${table.completedAt} <= ${table.archivedAt}`
		),
		foreignKey({
			name: 'training_plan_goal_user_fk',
			columns: [table.goalId, table.userId],
			foreignColumns: [goal.id, goal.userId]
		}).onDelete('cascade')
	]
);

export const trainingWeek = pgTable(
	'training_week',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		planId: uuid('plan_id').notNull(),
		weekNumber: integer('week_number').notNull(),
		startDate: date('start_date').notNull(),
		targetDistanceMeters: integer('target_distance_meters').notNull(),
		targetDurationSeconds: integer('target_duration_seconds').notNull().default(0),
		longRunMeters: integer('long_run_meters').notNull(),
		risk: riskRating('risk').notNull(),
		isDownWeek: boolean('is_down_week').notNull().default(false),
		isTaper: boolean('is_taper').notNull().default(false),
		createdAt: timestamp('created_at').defaultNow().notNull()
	},
	(table) => [
		uniqueIndex('training_week_id_user_plan_unique').on(table.id, table.userId, table.planId),
		uniqueIndex('training_week_plan_week_unique').on(table.planId, table.weekNumber),
		index('training_week_user_start_idx').on(table.userId, table.startDate),
		check('training_week_distance_nonnegative', sql`${table.targetDistanceMeters} >= 0`),
		check('training_week_duration_nonnegative', sql`${table.targetDurationSeconds} >= 0`),
		check('training_week_long_run_nonnegative', sql`${table.longRunMeters} >= 0`),
		foreignKey({
			name: 'training_week_plan_user_fk',
			columns: [table.planId, table.userId],
			foreignColumns: [trainingPlan.id, trainingPlan.userId]
		}).onDelete('cascade')
	]
);

export const workout = pgTable(
	'workout',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		planId: uuid('plan_id').notNull(),
		weekId: uuid('week_id').notNull(),
		scheduledDate: date('scheduled_date').notNull(),
		type: workoutType('type').notNull(),
		status: workoutStatus('status').notNull().default('planned'),
		prescriptionKind: workoutPrescriptionKind('prescription_kind').notNull(),
		targetDistanceMeters: integer('target_distance_meters').notNull().default(0),
		targetDurationSeconds: integer('target_duration_seconds'),
		intervalStructure: jsonb('interval_structure').$type<TimedIntervalStructure>(),
		intensity: text('intensity').notNull().default('easy'),
		purpose: text('purpose').notNull(),
		reason: text('reason').notNull(),
		sourceRefs: jsonb('source_refs').$type<string[]>().notNull().default([]),
		isRemoved: boolean('is_removed').notNull().default(false),
		createdAt: timestamp('created_at').defaultNow().notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		uniqueIndex('workout_id_user_unique').on(table.id, table.userId),
		uniqueIndex('workout_id_user_plan_unique').on(table.id, table.userId, table.planId),
		index('workout_user_date_idx').on(table.userId, table.scheduledDate),
		index('workout_user_plan_date_idx').on(table.userId, table.planId, table.scheduledDate),
		index('workout_plan_week_idx').on(table.planId, table.weekId),
		index('workout_status_idx').on(table.status),
		check('workout_target_distance_nonnegative', sql`${table.targetDistanceMeters} >= 0`),
		check(
			'workout_prescription_valid',
			sql`(
				(${table.prescriptionKind} = 'distance' and ${table.targetDistanceMeters} > 0 and ${table.targetDurationSeconds} is null and ${table.intervalStructure} is null and ${table.type} <> 'rest')
				or
				(${table.prescriptionKind} = 'timed' and ${table.targetDistanceMeters} = 0 and ${table.targetDurationSeconds} > 0 and ${table.intervalStructure} is not null and ${table.type} not in ('rest', 'race'))
				or
				(${table.prescriptionKind} = 'rest' and ${table.targetDistanceMeters} = 0 and ${table.targetDurationSeconds} is null and ${table.intervalStructure} is null and ${table.type} = 'rest')
			)`
		),
		foreignKey({
			name: 'workout_plan_user_fk',
			columns: [table.planId, table.userId],
			foreignColumns: [trainingPlan.id, trainingPlan.userId]
		}).onDelete('cascade'),
		foreignKey({
			name: 'workout_week_user_plan_fk',
			columns: [table.weekId, table.userId, table.planId],
			foreignColumns: [trainingWeek.id, trainingWeek.userId, trainingWeek.planId]
		}).onDelete('cascade')
	]
);

export const workoutFeedback = pgTable(
	'workout_feedback',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		workoutId: uuid('workout_id').notNull(),
		completedDistanceMeters: integer('completed_distance_meters'),
		completedDurationSeconds: integer('completed_duration_seconds'),
		feltHard: boolean('felt_hard').notNull().default(false),
		pain: boolean('pain').notNull().default(false),
		choice: consequenceChoice('choice'),
		deviation: deviationClassification('deviation').notNull(),
		appliedDecision: planDecision('applied_decision'),
		consequence: jsonb('consequence').$type<ConsequenceResult>().notNull(),
		createdAt: timestamp('created_at').defaultNow().notNull()
	},
	(table) => [
		index('workout_feedback_user_created_idx').on(table.userId, table.createdAt),
		uniqueIndex('workout_feedback_workout_unique').on(table.workoutId),
		check(
			'workout_feedback_completed_distance_nonnegative',
			sql`${table.completedDistanceMeters} is null or ${table.completedDistanceMeters} >= 0`
		),
		check(
			'workout_feedback_completed_duration_nonnegative',
			sql`${table.completedDurationSeconds} is null or ${table.completedDurationSeconds} >= 0`
		),
		foreignKey({
			name: 'workout_feedback_workout_user_fk',
			columns: [table.workoutId, table.userId],
			foreignColumns: [workout.id, workout.userId]
		}).onDelete('cascade')
	]
);

export const activity = pgTable(
	'activity',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		workoutId: uuid('workout_id'),
		source: activitySource('source').notNull(),
		reviewState: activityReviewState('review_state').notNull(),
		occurredAt: timestamp('occurred_at', { withTimezone: true }).notNull(),
		activityDate: date('activity_date').notNull(),
		distanceMeters: integer('distance_meters').notNull(),
		durationSeconds: integer('duration_seconds'),
		averagePaceSecondsPerKm: real('average_pace_seconds_per_km'),
		averageHeartRate: integer('average_heart_rate'),
		maxHeartRate: integer('max_heart_rate'),
		heartRateSummary: jsonb('heart_rate_summary').$type<HeartRateActivitySummary>(),
		heartRateSeries: jsonb('heart_rate_series').$type<HeartRateSeries>(),
		routeTrace: jsonb('route_trace').$type<ActivityRouteTrace>(),
		averageCadence: integer('average_cadence'),
		feltHard: boolean('felt_hard').notNull().default(false),
		pain: boolean('pain').notNull().default(false),
		extraPlanImpactConfirmed: boolean('extra_plan_impact_confirmed').notNull().default(false),
		deviation: deviationClassification('deviation').notNull().default('unplanned'),
		appliedDecision: planDecision('applied_decision'),
		consequence: jsonb('consequence').$type<ConsequenceResult | null>(),
		routeSummary: jsonb('route_summary')
			.$type<{
				pointCount: number;
				startEndRedacted: boolean;
				hasElevation: boolean;
				traceRetained?: boolean;
			}>()
			.notNull(),
		createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull()
	},
	(table) => [
		uniqueIndex('activity_id_user_unique').on(table.id, table.userId),
		index('activity_user_occurred_idx').on(table.userId, table.occurredAt),
		index('activity_user_date_idx').on(table.userId, table.activityDate),
		index('activity_workout_idx').on(table.workoutId),
		uniqueIndex('activity_workout_unique')
			.on(table.workoutId)
			.where(sql`${table.workoutId} is not null`),
		check('activity_distance_nonnegative', sql`${table.distanceMeters} >= 0`),
		check(
			'activity_duration_positive',
			sql`${table.durationSeconds} is null or ${table.durationSeconds} > 0`
		),
		check(
			'activity_average_heart_rate_range',
			sql`${table.averageHeartRate} is null or ${table.averageHeartRate} between 30 and 240`
		),
		check(
			'activity_max_heart_rate_range',
			sql`${table.maxHeartRate} is null or ${table.maxHeartRate} between 30 and 260`
		),
		foreignKey({
			name: 'activity_workout_user_fk',
			columns: [table.workoutId, table.userId],
			foreignColumns: [workout.id, workout.userId]
		})
	]
);

export const activityImport = pgTable(
	'activity_import',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		activityId: uuid('activity_id'),
		fileHash: text('file_hash').notNull(),
		parser: text('parser').notNull().default('gpx'),
		result: text('result').notNull(),
		metadata: jsonb('metadata')
			.$type<{
				pointCount: number;
				hasHeartRate: boolean;
				hasCadence: boolean;
				hasSpeed: boolean;
			}>()
			.notNull(),
		createdAt: timestamp('created_at').defaultNow().notNull()
	},
	(table) => [
		index('activity_import_user_created_idx').on(table.userId, table.createdAt),
		index('activity_import_activity_id_idx').on(table.activityId),
		uniqueIndex('activity_import_user_hash_unique').on(table.userId, table.fileHash),
		check('activity_import_result_known', sql`${table.result} in ('imported')`),
		foreignKey({
			name: 'activity_import_activity_user_fk',
			columns: [table.activityId, table.userId],
			foreignColumns: [activity.id, activity.userId]
		})
	]
);

export const planAdjustment = pgTable(
	'plan_adjustment',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		planId: uuid('plan_id').notNull(),
		workoutId: uuid('workout_id').notNull(),
		triggerType: planAdjustmentTrigger('trigger_type').notNull(),
		triggerId: uuid('trigger_id'),
		previousTargetDistanceMeters: integer('previous_target_distance_meters').notNull(),
		newTargetDistanceMeters: integer('new_target_distance_meters').notNull(),
		previousScheduledDate: date('previous_scheduled_date'),
		newScheduledDate: date('new_scheduled_date'),
		previousState: jsonb('previous_state').$type<WorkoutAdjustmentState>().notNull(),
		newState: jsonb('new_state').$type<WorkoutAdjustmentState>().notNull(),
		consequence: jsonb('consequence').$type<ConsequenceResult | null>(),
		reason: text('reason').notNull(),
		reversedAt: timestamp('reversed_at', { withTimezone: true }),
		reversalReason: text('reversal_reason'),
		createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull()
	},
	(table) => [
		index('plan_adjustment_user_created_idx').on(table.userId, table.createdAt),
		index('plan_adjustment_plan_created_idx').on(table.planId, table.createdAt),
		index('plan_adjustment_workout_created_idx').on(table.workoutId, table.createdAt),
		check(
			'plan_adjustment_previous_target_nonnegative',
			sql`${table.previousTargetDistanceMeters} >= 0`
		),
		check('plan_adjustment_new_target_nonnegative', sql`${table.newTargetDistanceMeters} >= 0`),
		foreignKey({
			name: 'plan_adjustment_plan_user_fk',
			columns: [table.planId, table.userId],
			foreignColumns: [trainingPlan.id, trainingPlan.userId]
		}).onDelete('cascade'),
		foreignKey({
			name: 'plan_adjustment_workout_user_plan_fk',
			columns: [table.workoutId, table.userId, table.planId],
			foreignColumns: [workout.id, workout.userId, workout.planId]
		}).onDelete('cascade')
	]
);

export const activityDeletionTombstone = pgTable(
	'activity_deletion_tombstone',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		fileHash: text('file_hash').notNull(),
		createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull()
	},
	(table) => [
		uniqueIndex('activity_deletion_tombstone_user_hash_unique').on(table.userId, table.fileHash),
		index('activity_deletion_tombstone_user_created_idx').on(table.userId, table.createdAt)
	]
);

export const importSource = pgTable(
	'import_source',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		type: text('type').notNull().default('nextcloud_share'),
		label: text('label').notNull(),
		shareHost: text('share_host').notNull(),
		shareTokenSecret: text('share_token_secret').notNull(),
		shareTokenKey: text('share_token_key').notNull(),
		sharePasswordSecret: text('share_password_secret').notNull(),
		enabled: boolean('enabled').notNull().default(true),
		syncIntervalMinutes: integer('sync_interval_minutes').notNull().default(5),
		lastCheckedAt: timestamp('last_checked_at'),
		lastSuccessAt: timestamp('last_success_at'),
		lastImportedAt: timestamp('last_imported_at'),
		lastError: text('last_error'),
		createdAt: timestamp('created_at').defaultNow().notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		uniqueIndex('import_source_id_user_unique').on(table.id, table.userId),
		index('import_source_user_enabled_idx').on(table.userId, table.enabled),
		index('import_source_enabled_checked_idx').on(table.enabled, table.lastCheckedAt),
		uniqueIndex('import_source_user_share_unique').on(
			table.userId,
			table.shareHost,
			table.shareTokenKey
		),
		check('import_source_type_known', sql`${table.type} in ('nextcloud_share')`),
		check('import_source_interval_positive', sql`${table.syncIntervalMinutes} > 0`)
	]
);

export const importSourceItem = pgTable(
	'import_source_item',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		sourceId: uuid('source_id').notNull(),
		remoteKey: text('remote_key').notNull(),
		etag: text('etag'),
		contentLength: integer('content_length'),
		lastModifiedAt: timestamp('last_modified_at'),
		contentHash: text('content_hash'),
		status: text('status').notNull(),
		activityId: uuid('activity_id'),
		firstSeenAt: timestamp('first_seen_at').defaultNow().notNull(),
		lastCheckedAt: timestamp('last_checked_at').defaultNow().notNull(),
		importedAt: timestamp('imported_at'),
		errorSummary: text('error_summary')
	},
	(table) => [
		index('import_source_item_user_checked_idx').on(table.userId, table.lastCheckedAt),
		index('import_source_item_source_status_idx').on(table.sourceId, table.status),
		uniqueIndex('import_source_item_source_key_unique').on(table.sourceId, table.remoteKey),
		index('import_source_item_source_hash_idx').on(table.sourceId, table.contentHash),
		check(
			'import_source_item_status_known',
			sql`${table.status} in ('importing', 'imported', 'failed')`
		),
		foreignKey({
			name: 'import_source_item_source_user_fk',
			columns: [table.sourceId, table.userId],
			foreignColumns: [importSource.id, importSource.userId]
		}).onDelete('cascade'),
		foreignKey({
			name: 'import_source_item_activity_user_fk',
			columns: [table.activityId, table.userId],
			foreignColumns: [activity.id, activity.userId]
		})
	]
);

export const passwordResetToken = pgTable(
	'password_reset_token',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		tokenHash: text('token_hash').notNull(),
		expiresAt: timestamp('expires_at').notNull(),
		usedAt: timestamp('used_at'),
		requestedAt: timestamp('requested_at').defaultNow().notNull(),
		createdAt: timestamp('created_at').defaultNow().notNull()
	},
	(table) => [
		index('password_reset_token_user_requested_idx').on(table.userId, table.requestedAt),
		index('password_reset_token_expires_idx').on(table.expiresAt),
		uniqueIndex('password_reset_token_hash_unique').on(table.tokenHash)
	]
);

export const auditEvent = pgTable(
	'audit_event',
	{
		id: uuid('id').defaultRandom().primaryKey(),
		userId: text('user_id')
			.notNull()
			.references(() => user.id, { onDelete: 'cascade' }),
		eventType: text('event_type').notNull(),
		detail: jsonb('detail').$type<Record<string, JsonValue>>().notNull(),
		createdAt: timestamp('created_at').defaultNow().notNull()
	},
	(table) => [
		index('audit_event_user_created_idx').on(table.userId, table.createdAt),
		index('audit_event_created_idx').on(table.createdAt),
		index('audit_event_type_idx').on(table.eventType)
	]
);

export const passwordResetRateLimit = pgTable(
	'password_reset_rate_limit',
	{
		keyHash: text('key_hash').primaryKey(),
		count: integer('count').notNull().default(0),
		resetAt: timestamp('reset_at').notNull(),
		updatedAt: timestamp('updated_at')
			.defaultNow()
			.$onUpdate(() => new Date())
			.notNull()
	},
	(table) => [
		index('password_reset_rate_limit_reset_idx').on(table.resetAt),
		check('password_reset_rate_limit_count_nonnegative', sql`${table.count} >= 0`)
	]
);

export const goalRelations = relations(goal, ({ many }) => ({
	plans: many(trainingPlan)
}));

export const trainingPlanRelations = relations(trainingPlan, ({ many, one }) => ({
	goal: one(goal, { fields: [trainingPlan.goalId], references: [goal.id] }),
	weeks: many(trainingWeek),
	workouts: many(workout),
	adjustments: many(planAdjustment)
}));

export const trainingWeekRelations = relations(trainingWeek, ({ many, one }) => ({
	plan: one(trainingPlan, { fields: [trainingWeek.planId], references: [trainingPlan.id] }),
	workouts: many(workout)
}));

export const workoutRelations = relations(workout, ({ many, one }) => ({
	plan: one(trainingPlan, { fields: [workout.planId], references: [trainingPlan.id] }),
	week: one(trainingWeek, { fields: [workout.weekId], references: [trainingWeek.id] }),
	feedback: many(workoutFeedback),
	activities: many(activity),
	adjustments: many(planAdjustment)
}));

export const planAdjustmentRelations = relations(planAdjustment, ({ one }) => ({
	plan: one(trainingPlan, { fields: [planAdjustment.planId], references: [trainingPlan.id] }),
	workout: one(workout, { fields: [planAdjustment.workoutId], references: [workout.id] })
}));

export const passwordResetTokenRelations = relations(passwordResetToken, ({ one }) => ({
	user: one(user, { fields: [passwordResetToken.userId], references: [user.id] })
}));

export const importSourceRelations = relations(importSource, ({ many, one }) => ({
	user: one(user, { fields: [importSource.userId], references: [user.id] }),
	items: many(importSourceItem)
}));

export const importSourceItemRelations = relations(importSourceItem, ({ one }) => ({
	user: one(user, { fields: [importSourceItem.userId], references: [user.id] }),
	source: one(importSource, {
		fields: [importSourceItem.sourceId],
		references: [importSource.id]
	}),
	activity: one(activity, {
		fields: [importSourceItem.activityId],
		references: [activity.id]
	})
}));
