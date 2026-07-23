export type RaceDistance = '5k' | '10k' | 'half' | 'marathon';
export type GoalKind = 'race' | 'foundation';
export type GoalState = 'pending' | 'active' | 'completed' | 'archived';
export type PlanPhase = 'distance' | 'foundation' | 'calibration';
export type StartMode = 'established' | 'foundation_to_goal' | 'foundation_only' | 'calibration';
export type GoalPriority = 'finish_healthy' | 'consistency';
export type SexForEstimates = 'female' | 'male' | 'not_specified';
export type RiskRating = 'conservative' | 'moderate' | 'aggressive' | 'unsafe';
export type WorkoutType = 'easy' | 'long' | 'recovery' | 'rest' | 'race';
export type WorkoutStatus = 'planned' | 'done' | 'skipped' | 'shortened';
export type ConsequenceChoice = 'skip_continue' | 'reduce_next';
export type DeviationClassification =
	| 'near_plan'
	| 'short'
	| 'over'
	| 'skipped'
	| 'unplanned'
	| 'not_applicable';
export type PlanDecision =
	| 'keep_plan'
	| 'reduce_next'
	| 'next_rest'
	| 'repeat_prescription'
	| 'rebalance_week';
export type ConsequenceKind =
	| 'completed_as_planned'
	| 'pain_reported'
	| 'load_spike'
	| 'shortfall'
	| 'repeated_shortfall'
	| 'hard_effort'
	| 'repeated_skip'
	/** Legacy value retained for existing recorded consequences. */
	| 'repeated_miss'
	| 'skip_reduce'
	| 'skip_continue'
	| 'historical_link'
	| 'extra_activity';

export type InjuryFlags = {
	recentInjury: boolean;
	currentPain: boolean;
	recurringPain: boolean;
	medicalRestriction: boolean;
	notes: string;
};

export type TrainingHealthNotice = {
	level: 'caution' | 'paused';
	heading: string;
	message: string;
};

export type PrescriptionSegment = {
	kind: 'run' | 'walk';
	durationSeconds: number;
};

export type RunWalkBlock = {
	repetitions: number;
	segments: PrescriptionSegment[];
};

export type TimedIntervalStructure = {
	warmupSeconds: number;
	cooldownSeconds: number;
	blocks: RunWalkBlock[];
};

export type DistancePrescription = {
	kind: 'distance';
	distanceMeters: number;
};

export type TimedPrescription = {
	kind: 'timed';
	totalDurationSeconds: number;
} & TimedIntervalStructure;

export type RestPrescription = {
	kind: 'rest';
};

export type WorkoutPrescription = DistancePrescription | TimedPrescription | RestPrescription;

export type DistanceSummary = {
	kind: 'distance';
	baselineMeters: number;
	peakMeters: number;
	requiredWeeklyIncreasePercent: number;
	defaultWeeklyIncreasePercent: number;
	longRunPeakMeters: number;
	warnings: string[];
};

export type FoundationSummary = {
	kind: 'foundation';
	programWeeks: 9;
	sessionsPerWeek: 3;
	continuousRunTargetSeconds: 1_800;
	warnings: string[];
};

export type CalibrationSummary = {
	kind: 'calibration';
	programWeeks: 2;
	sessionsPerWeek: 2;
	sessionDurationSeconds: number;
	warnings: string[];
};

export type PlanSummary = DistanceSummary | FoundationSummary | CalibrationSummary;

export type PhaseBaseline = {
	activityCount: number;
	totalDurationSeconds: number;
	totalDistanceMeters: number;
	longestActivityMeters: number;
	weeklyDistanceMeters: number;
	runsPerWeek: number;
};

export type PhaseTransitionOption =
	| 'confirm_race_baseline'
	| 'another_foundation_week'
	| 'continue_calibration'
	| 'later_date'
	| 'shorter_goal';

export type HeartRateZoneKey = 'z1' | 'z2' | 'z3' | 'z4' | 'z5';

export type HeartRateZone = {
	key: HeartRateZoneKey;
	label: string;
	floorBpm: number;
	ceilingBpm?: number;
};

export type HeartRateSettings = {
	maxHeartRateBpm: number;
	source: 'estimated' | 'custom';
	zones: HeartRateZone[];
};

export type HeartRateActivitySummary = {
	/** Zone data is descriptive and must not be promoted into an automatic load decision. */
	effort: 'unknown';
	highSeconds: number;
	highShare: number;
	secondsByZone: Record<HeartRateZoneKey, number>;
	settingsSource: HeartRateSettings['source'];
};

export type HeartRateSeriesPoint = {
	elapsedSeconds: number;
	bpm: number;
};

export type HeartRateSeries = {
	version: 1;
	sourceSampleCount: number;
	points: HeartRateSeriesPoint[];
};

export type ActivityRoutePoint = {
	latitudeE6: number;
	longitudeE6: number;
	elapsedSeconds: number;
	segmentIndex: number;
	speedMetersPerSecond: number | null;
};

export type ActivityRouteTrace = {
	version: 1;
	sourcePointCount: number;
	points: ActivityRoutePoint[];
};

type CommonTrainingIntake = {
	priority: GoalPriority;
	units: 'metric';
	experience: 'new' | 'returning' | 'comfortable';
	availability: number[];
	injuryFlags: InjuryFlags;
	startDate?: string;
};

export type EstablishedTrainingIntake = CommonTrainingIntake & {
	startMode?: 'established';
	goalKind?: 'race';
	raceDistance: RaceDistance;
	targetDate: string;
	currentWeeklyDistanceMeters: number;
	currentRunsPerWeek: number;
	longestRecentRunMeters: number;
	preferredLongRunDay: number;
};

export type FoundationToGoalIntake = CommonTrainingIntake & {
	startMode: 'foundation_to_goal';
	goalKind: 'race';
	raceDistance: RaceDistance;
	targetDate: string;
};

export type FoundationOnlyIntake = CommonTrainingIntake & {
	startMode: 'foundation_only';
	goalKind: 'foundation';
	raceDistance: null;
	targetDate?: string;
};

export type CalibrationIntake = CommonTrainingIntake & {
	startMode: 'calibration';
	goalKind: GoalKind;
	raceDistance: RaceDistance | null;
	targetDate?: string;
	calibrationDurationSeconds: number;
};

/** Established distance-planner intake retained as a named type for existing callers. */
export type TrainingIntake = EstablishedTrainingIntake;
export type PlanIntake =
	| EstablishedTrainingIntake
	| FoundationToGoalIntake
	| FoundationOnlyIntake
	| CalibrationIntake;

export type GeneratedWorkout = {
	scheduledDate: string;
	type: WorkoutType;
	targetDistanceMeters: number;
	targetDurationSeconds?: number;
	prescription: WorkoutPrescription;
	intensity: string;
	purpose: string;
	reason: string;
	sourceRefs: string[];
};

export type GeneratedWeek = {
	weekNumber: number;
	startDate: string;
	/** Non-event running volume used for ramp and readiness calculations. */
	trainingTargetDistanceMeters: number;
	/** Goal-event distance scheduled in this week, otherwise zero. */
	eventDistanceMeters: number;
	/** Total of every scheduled workout, including the goal event. */
	targetDistanceMeters: number;
	targetDurationSeconds: number;
	longRunMeters: number;
	risk: RiskRating;
	isDownWeek: boolean;
	isTaper: boolean;
	workouts: GeneratedWorkout[];
};

type GeneratedPlanBase = {
	startDate: string;
	targetDate: string;
	weeks: GeneratedWeek[];
	risk: RiskRating;
	sourceRefs: string[];
};

export type GeneratedDistancePlan = GeneratedPlanBase & {
	phase: 'distance';
	startMode: 'established';
	summary: DistanceSummary;
};

export type GeneratedFoundationPlan = GeneratedPlanBase & {
	phase: 'foundation';
	startMode: 'foundation_to_goal' | 'foundation_only';
	summary: FoundationSummary;
};

export type GeneratedCalibrationPlan = GeneratedPlanBase & {
	phase: 'calibration';
	startMode: 'calibration';
	summary: CalibrationSummary;
};

export type GeneratedPlan =
	| GeneratedDistancePlan
	| GeneratedFoundationPlan
	| GeneratedCalibrationPlan;

export type WorkoutFeedbackInput = {
	status: Extract<WorkoutStatus, 'done' | 'skipped' | 'shortened'>;
	choice: ConsequenceChoice;
	targetDistanceMeters: number;
	targetDurationSeconds?: number;
	completedDistanceMeters?: number;
	completedDurationSeconds?: number;
	pain: boolean;
	feltHard: boolean;
	weekTargetDistanceMeters: number;
	/** Prior skipped runs in the caller's bounded recent window. */
	recentSkippedWorkouts?: number;
	/** Prior shortened runs in the caller's bounded recent window. */
	recentShortenedWorkouts?: number;
};

export type ConsequenceMetric = 'distance' | 'duration';

/** A signed load change expressed in the prescription's native unit. */
export type ConsequenceMetricDelta = {
	metric: ConsequenceMetric;
	value: number;
};

export type ConsequenceResult = {
	kind: ConsequenceKind;
	/** Omitted on legacy records; only set when native-unit load comparison is unavailable. */
	comparisonStatus?: 'not_comparable';
	deviation: DeviationClassification;
	metric: 'distance' | 'duration' | 'none';
	actualDifference: number;
	weeklyLoadDelta: ConsequenceMetricDelta | null;
	nextRunAdjustment: ConsequenceMetricDelta | null;
	/** Legacy distance projection; presentation and decisions use weeklyLoadDelta. */
	weeklyDistanceDeltaMeters: number;
	/** Legacy distance projection; presentation and decisions use nextRunAdjustment. */
	nextRunAdjustmentMeters: number;
	risk: RiskRating;
	/** False when activity is retained for review but is too old to change the active plan. */
	planChangeAvailable?: boolean;
	recommendedDecision: PlanDecision;
	options: PlanDecision[];
	appliedDecision: PlanDecision | null;
};

export type ParsedGpxActivity = {
	startedAt: Date;
	durationSeconds: number;
	distanceMeters: number;
	pointCount: number;
	averageHeartRate?: number | undefined;
	maxHeartRate?: number | undefined;
	averageCadence?: number | undefined;
	averageSpeedMetersPerSecond?: number | undefined;
	heartRateSamples?: { at: Date; bpm: number; segmentIndex: number }[] | undefined;
	routePoints: {
		latitude: number;
		longitude: number;
		at: Date;
		segmentIndex: number;
		speedMetersPerSecond: number | null;
	}[];
	hasElevation: boolean;
	hasHeartRate: boolean;
	hasCadence: boolean;
	hasSpeed: boolean;
};
