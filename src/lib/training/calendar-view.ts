import type {
	ActivityRouteTrace,
	ConsequenceResult,
	HeartRateActivitySummary,
	HeartRateSeries,
	RiskRating,
	TimedIntervalStructure,
	WorkoutStatus,
	WorkoutType
} from './types';

export type TrainingCalendarWeek = {
	id: string;
	weekNumber: number;
	startDate: string;
	targetDistanceMeters: number;
	targetDurationSeconds: number;
	eventDistanceMeters: number;
	totalScheduledDistanceMeters: number;
	longRunMeters: number;
	risk: RiskRating;
	isDownWeek: boolean;
	isTaper: boolean;
	completedDistanceMeters: number;
	completedDurationSeconds: number;
	eventCompletedDistanceMeters: number;
	completedRuns: number;
	plannedRuns: number;
	painFlags: number;
	hardFlags: number;
};

export type TrainingCalendarWorkout = {
	id: string;
	weekId: string;
	weekNumber: number;
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
	weekTargetDistanceMeters: number;
	adjustment: TrainingPlanAdjustment | null;
	recommended: {
		scheduledDate: string;
		type: WorkoutType;
		prescriptionKind: 'distance' | 'timed' | 'rest';
		targetDistanceMeters: number;
		targetDurationSeconds: number | null;
		intervalStructure: TimedIntervalStructure | null;
		purpose: string;
	} | null;
	isEdited: boolean;
};

export type TrainingPlanAdjustment = {
	id: string;
	planId: string;
	workoutId: string;
	triggerType:
		| 'feedback'
		| 'manual'
		| 'import_match'
		| 'import_extra'
		| 'link'
		| 'decision'
		| 'manual_edit'
		| 'manual_add'
		| 'manual_remove'
		| 'rebalance';
	triggerId: string | null;
	previousTargetDistanceMeters: number;
	newTargetDistanceMeters: number;
	previousScheduledDate: string | null;
	newScheduledDate: string | null;
	consequence: ConsequenceResult | null;
	reason: string;
	createdAt: Date;
};

export type TrainingCalendarActivity = {
	id: string;
	workoutId: string | null;
	source: 'manual' | 'gpx';
	reviewState: 'review' | 'accepted';
	occurredAt: Date;
	occurredDate: string;
	distanceMeters: number;
	durationSeconds: number | null;
	averagePaceSecondsPerKm: number | null;
	averageHeartRate: number | null;
	maxHeartRate: number | null;
	heartRateSummary: HeartRateActivitySummary | null;
	heartRateSeries: HeartRateSeries | null;
	routeTrace: ActivityRouteTrace | null;
	averageCadence: number | null;
	feltHard: boolean;
	pain: boolean;
	extraPlanImpactConfirmed: boolean;
	consequence: ConsequenceResult | null;
	routeSummary: {
		pointCount: number;
		startEndRedacted: boolean;
		hasElevation: boolean;
		traceRetained?: boolean;
	};
	matchedWorkoutPurpose: string | null;
	matchedWorkoutDate: string | null;
};

export type TrainingCalendarFeedback = {
	id: string;
	workoutId: string;
	completedDistanceMeters: number | null;
	completedDurationSeconds: number | null;
	feltHard: boolean;
	pain: boolean;
	consequence: ConsequenceResult;
	createdAt: Date;
	canDelete: boolean;
};

export type TrainingCalendarPayload = {
	today: string;
	month: string;
	previousMonth: string;
	nextMonth: string;
	currentMonth: string;
	rangeStart: string;
	rangeEnd: string;
	weeks: TrainingCalendarWeek[];
	workouts: TrainingCalendarWorkout[];
	activities: TrainingCalendarActivity[];
	feedback: TrainingCalendarFeedback[];
	planScale: { baselineMeters: number; peakMeters: number } | null;
};
