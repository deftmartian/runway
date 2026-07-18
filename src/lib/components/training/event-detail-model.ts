import {
	calculateConsequenceDecisionEffect,
	isConsequenceDecisionTargetCompatible
} from '$lib/training/consequences';
import { formatPace } from '$lib/training/format';
import { trainingSourceDetails, type TrainingSourceRef } from '$lib/training/sources';
import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
import type {
	ActivityRouteTrace,
	ConsequenceResult,
	HeartRateSeries,
	PlanDecision
} from '$lib/training/types';
import type { WorkoutEditProposal } from '$lib/training/workout-edit';
import type { SubmitFunction } from '@sveltejs/kit';
import type { CalendarEvent, CalendarFormState } from './calendar-types';
import { formatDurationChange } from '$lib/training/consequence-presentation';

export type ActivityTraceDetail = {
	id: string;
	routeTrace: ActivityRouteTrace | null;
	heartRateSeries: HeartRateSeries | null;
};

export type DecisionRecord = {
	source: 'feedback' | 'activity';
	sourceId: string;
	consequence: ConsequenceResult;
};

export type EventActionEnhancer = (key: string, confirmation?: string) => SubmitFunction;

export const formatDistance = (meters: number) => `${Math.round((meters / 1_000) * 10) / 10} km`;

export const formatDay = (date: string) =>
	new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
		weekday: 'long',
		month: 'long',
		day: 'numeric'
	});

export const formatShortDay = (date: string) =>
	new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
		weekday: 'short',
		month: 'short',
		day: 'numeric'
	});

export const dateDistanceDays = (left: string, right: string) =>
	Math.abs(
		(Date.parse(`${left}T00:00:00.000Z`) - Date.parse(`${right}T00:00:00.000Z`)) /
			(24 * 60 * 60 * 1_000)
	);

export function formatDuration(seconds: number | null | undefined) {
	if (!seconds) return '—';
	const minutes = Math.round(seconds / 60);
	if (minutes < 60) return `${minutes} min`;
	const hours = Math.floor(minutes / 60);
	const remainder = minutes % 60;
	return `${hours}h ${String(remainder).padStart(2, '0')}m`;
}

export function formatIntervalDuration(seconds: number) {
	if (seconds % 60 === 0) return `${seconds / 60} min`;
	if (seconds < 60) return `${seconds} sec`;
	return `${Math.floor(seconds / 60)} min ${seconds % 60} sec`;
}

export function timedWorkoutSteps(workout: TrainingCalendarWorkout | null) {
	const structure = workout?.intervalStructure;
	if (!structure) return [];
	return [
		...(structure.warmupSeconds > 0
			? [`Warm up · walk ${formatIntervalDuration(structure.warmupSeconds)}`]
			: []),
		...structure.blocks.map((block) => {
			const segments = block.segments
				.map((segment) => `${segment.kind} ${formatIntervalDuration(segment.durationSeconds)}`)
				.join(' / ');
			return `${block.repetitions}× ${segments}`;
		}),
		...(structure.cooldownSeconds > 0
			? [`Cool down · walk ${formatIntervalDuration(structure.cooldownSeconds)}`]
			: [])
	];
}

export function plannedPrescription(workout: TrainingCalendarWorkout | null) {
	if (workout && workout.targetDistanceMeters > 0)
		return formatDistance(workout.targetDistanceMeters);
	if (workout?.targetDurationSeconds) return formatDuration(workout.targetDurationSeconds);
	return workout ? 'Rest' : '—';
}

export function proposalPrescription(proposal: WorkoutEditProposal) {
	return proposal.prescriptionKind === 'rest'
		? 'Rest'
		: proposal.prescriptionKind === 'timed'
			? formatDuration(proposal.targetDurationSeconds)
			: formatDistance(proposal.targetDistanceMeters);
}

export function actualDistance(event: CalendarEvent) {
	if (event.activity) return formatDistance(event.activity.distanceMeters);
	if (!event.feedback) return '—';
	if (event.workout?.status === 'skipped') return 'Skipped';
	return event.feedback.completedDistanceMeters === null
		? 'Recorded'
		: formatDistance(event.feedback.completedDistanceMeters);
}

export function actualPace(event: CalendarEvent) {
	if (event.activity) {
		const pace = formatPace(event.activity.averagePaceSecondsPerKm);
		return event.activity.source === 'gpx' ? `${pace} elapsed` : `${pace} reported`;
	}
	if (event.feedback?.completedDistanceMeters && event.feedback.completedDurationSeconds) {
		return formatPace(
			event.feedback.completedDurationSeconds / (event.feedback.completedDistanceMeters / 1_000)
		);
	}
	return null;
}

export function recordedStatus(event: CalendarEvent) {
	switch (event.workout?.status) {
		case 'done':
			return 'Completed';
		case 'shortened':
			return 'Shortened';
		case 'skipped':
			return 'Skipped';
		default:
			return null;
	}
}

export function workoutSources(workout: TrainingCalendarWorkout | null) {
	return (workout?.sourceRefs ?? []).flatMap((sourceRef) => {
		const source = trainingSourceDetails[sourceRef as TrainingSourceRef];
		return source ? [{ id: sourceRef, ...source }] : [];
	});
}

export function decisionRecordForEvent(event: CalendarEvent): DecisionRecord | null {
	if (event.feedback) {
		return {
			source: 'feedback',
			sourceId: event.feedback.id,
			consequence: event.feedback.consequence
		};
	}
	if (event.activity?.consequence) {
		return {
			source: 'activity',
			sourceId: event.activity.id,
			consequence: event.activity.consequence
		};
	}
	return null;
}

export function formForEvent(
	form: CalendarFormState,
	event: CalendarEvent,
	decisionRecord: DecisionRecord | null
) {
	if (!form?.scope) return null;
	const scope = form.scope;
	switch (scope.action) {
		case 'recordFeedback':
		case 'deleteFeedback':
		case 'previewWorkoutEdit':
		case 'applyWorkoutEdit':
		case 'previewWorkoutRemoval':
		case 'removeWorkout':
		case 'resetWorkout':
			return scope.workoutId === event.workout?.id ? form : null;
		case 'recordManualRun':
		case 'previewWorkoutAdd':
		case 'applyWorkoutAdd':
			return scope.date === event.date ? form : null;
		case 'undoWorkoutAdjustment':
			return scope.adjustmentId === event.workout?.adjustment?.id ? form : null;
		case 'applyPlanDecision':
			return scope.sourceId === decisionRecord?.sourceId ? form : null;
		case 'linkActivity':
		case 'unlinkActivity':
		case 'deleteActivity':
		case 'confirmActivityExtra':
		case 'updateActivityFeedback':
			return scope.activityId === event.activity?.id ? form : null;
	}
}

export function previewPlanDecision(input: {
	decision: PlanDecision;
	event: CalendarEvent;
	futureWorkouts: TrainingCalendarWorkout[];
	decisionRecord: DecisionRecord;
}) {
	const { decision, event, futureWorkouts, decisionRecord } = input;
	if (decision === 'keep_plan') return 'No future workout changes.';
	const candidates = futureWorkouts
		.filter((workout) => workout.scheduledDate > event.date)
		.sort(
			(left, right) =>
				left.scheduledDate.localeCompare(right.scheduledDate) || left.id.localeCompare(right.id)
		);
	const compatibleCandidates =
		decision === 'rebalance_week'
			? candidates.filter(
					(workout) =>
						workout.scheduledDate <= endDateForWeek(event.date) &&
						isConsequenceDecisionTargetCompatible(decisionRecord.consequence, workout)
				)
			: candidates;
	const next = compatibleCandidates[0];
	if (!next) return 'No compatible future workout is available.';
	const nextLabel = `${formatShortDay(next.scheduledDate)} · ${next.purpose}`;
	if (decision === 'next_rest') return `${nextLabel} becomes rest.`;
	if (decision === 'repeat_prescription') {
		return `${nextLabel} changes from ${next.targetDurationSeconds ? formatDuration(next.targetDurationSeconds) : formatDistance(next.targetDistanceMeters)} to the recorded ${plannedPrescription(event.workout).toLowerCase()} prescription. Weekly load and run spacing are checked before it is applied.`;
	}
	const shareCount = decision === 'rebalance_week' ? compatibleCandidates.length : 1;
	const effect = calculateConsequenceDecisionEffect({
		consequence: decisionRecord.consequence,
		decision,
		target: {
			targetDistanceMeters: next.targetDistanceMeters,
			targetDurationSeconds: next.targetDurationSeconds
		},
		shareCount
	});
	if (!effect) return 'This choice does not change a workout amount.';
	const previous =
		effect.metric === 'duration'
			? formatDurationChange(effect.previousTarget)
			: formatDistance(effect.previousTarget);
	const after =
		effect.metric === 'duration'
			? formatDurationChange(effect.newTarget)
			: formatDistance(effect.newTarget);
	if (decision === 'rebalance_week') {
		return `The reduction is shared across ${shareCount} remaining compatible workout${shareCount === 1 ? '' : 's'} this week. ${nextLabel} changes from ${previous} to ${after}.`;
	}
	return `${nextLabel} changes from ${previous} to ${after}.`;
}

export function planDecisionUnavailable(input: {
	decision: PlanDecision;
	event: CalendarEvent;
	futureWorkouts: TrainingCalendarWorkout[];
	decisionRecord: DecisionRecord;
}) {
	const { decision, event, futureWorkouts, decisionRecord } = input;
	if (decision === 'keep_plan') return false;
	const candidates = futureWorkouts.filter((workout) => workout.scheduledDate > event.date);
	if (candidates.length === 0) return true;
	if (decision === 'repeat_prescription') return !event.workout;
	if (decision !== 'rebalance_week') return false;
	return !candidates.some(
		(workout) =>
			workout.scheduledDate <= endDateForWeek(event.date) &&
			isConsequenceDecisionTargetCompatible(decisionRecord.consequence, workout)
	);
}

function endDateForWeek(date: string) {
	const originTimestamp = Date.parse(`${date}T00:00:00.000Z`);
	const weekday = new Date(originTimestamp).getUTCDay();
	return new Date(originTimestamp + (weekday === 0 ? 0 : 7 - weekday) * 24 * 60 * 60 * 1_000)
		.toISOString()
		.slice(0, 10);
}
