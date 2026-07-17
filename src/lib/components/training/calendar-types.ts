import type {
	TrainingCalendarActivity,
	TrainingCalendarFeedback,
	TrainingCalendarWeek,
	TrainingCalendarWorkout
} from '$lib/training/calendar-view';
import type { ConsequenceResult } from '$lib/training/types';
import type { WorkoutEditPreview } from '$lib/training/workout-edit';

export type CalendarEventKind = 'planned' | 'actual' | 'review' | 'rest' | 'open';

export type CalendarEvent = {
	id: string;
	date: string;
	kind: CalendarEventKind;
	title: string;
	workout: TrainingCalendarWorkout | null;
	activity: TrainingCalendarActivity | null;
	feedback: TrainingCalendarFeedback | null;
	week: TrainingCalendarWeek | null;
	isRecordable: boolean;
	isToday: boolean;
};

export type WorkoutCandidate = {
	id: string;
	scheduledDate: string;
	purpose: string;
	targetDistanceMeters: number;
};

export type CalendarDay = {
	date: string;
	weekday: string;
	dayNumber: string;
	inSelectedMonth: boolean;
	isToday: boolean;
	events: CalendarEvent[];
};

export type CalendarFormScope =
	| { action: 'recordFeedback' | 'deleteFeedback'; workoutId: string }
	| {
			action:
				| 'previewWorkoutEdit'
				| 'applyWorkoutEdit'
				| 'previewWorkoutRemoval'
				| 'removeWorkout'
				| 'resetWorkout';
			workoutId: string;
	  }
	| { action: 'recordManualRun'; date: string }
	| { action: 'previewWorkoutAdd' | 'applyWorkoutAdd'; date: string }
	| { action: 'undoWorkoutAdjustment'; adjustmentId: string }
	| { action: 'applyPlanDecision'; sourceId: string }
	| {
			action:
				| 'linkActivity'
				| 'unlinkActivity'
				| 'deleteActivity'
				| 'confirmActivityExtra'
				| 'updateActivityFeedback';
			activityId: string;
	  };

export type CalendarFormState =
	| {
			message?: string;
			consequence?: ConsequenceResult | null;
			preview?: WorkoutEditPreview;
			editValues?: WorkoutEditFormValues;
			scope?: CalendarFormScope;
	  }
	| null
	| undefined;

export type WorkoutEditFormValues = {
	workoutId?: string;
	scheduledDate: string;
	type: string;
	prescriptionKind: string;
	distanceKm: string;
	durationMinutes: string;
	intervalStructureJson: string;
	replaceIntervals: boolean;
	runMinutes: string;
	walkMinutes: string;
	repetitions: string;
	intensity: string;
	purpose: string;
	userReason: string;
	rebalance: boolean;
	confirmRisk: boolean;
};
