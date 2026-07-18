<script lang="ts">
	import { enhance } from '$app/forms';
	import WorkoutEditor from './WorkoutEditor.svelte';
	import type { CalendarEvent, CalendarFormState } from './calendar-types';
	import {
		formatDistance,
		formatDuration,
		proposalPrescription,
		type EventActionEnhancer
	} from './event-detail-model';
	import {
		formatLoadChangeEvidence,
		presentLoadChangeAssessment,
		presentRampAssessment
	} from '$lib/training/training-assessment';

	let {
		event,
		form,
		eventForm,
		today,
		targetDate,
		hasActivePlan,
		pendingAction,
		enhanceAction
	}: {
		event: CalendarEvent;
		form: CalendarFormState;
		eventForm: CalendarFormState;
		today: string;
		targetDate: string;
		hasActivePlan: boolean;
		pendingAction: string | null;
		enhanceAction: EventActionEnhancer;
	} = $props();

	const canEditFutureWorkout = $derived(
		event.workout?.status === 'planned' &&
			event.workout.type !== 'race' &&
			!event.workout.isRemoved &&
			event.workout.scheduledDate > today
	);
	const canAddFutureWorkout = $derived(
		hasActivePlan && event.date > today && event.date <= targetDate
	);
	const manualAdjustment = $derived(
		event.workout?.adjustment &&
			['manual_edit', 'manual_add', 'manual_remove', 'rebalance'].includes(
				event.workout.adjustment.triggerType
			)
			? event.workout.adjustment
			: null
	);
</script>

{#if canEditFutureWorkout}
	<details
		class="feedback-details workout-edit-details"
		open={eventForm?.scope?.action === 'previewWorkoutEdit' ||
			eventForm?.scope?.action === 'applyWorkoutEdit'}
	>
		<summary>Edit planned workout</summary>
		<WorkoutEditor workout={event.workout} date={event.date} {form} {today} {targetDate} />
	</details>
{/if}

{#if canAddFutureWorkout}
	<details
		class="feedback-details workout-edit-details"
		open={eventForm?.scope?.action === 'previewWorkoutAdd' ||
			eventForm?.scope?.action === 'applyWorkoutAdd'}
	>
		<summary>{event.workout ? 'Add another planned workout' : 'Add planned workout'}</summary>
		<WorkoutEditor workout={null} date={event.date} {form} {today} {targetDate} />
	</details>
{/if}

{#if canEditFutureWorkout}
	<section class="event-actions workout-ledger-actions" aria-label="Workout plan controls">
		<form
			method="post"
			action="?/previewWorkoutRemoval"
			use:enhance={enhanceAction('preview-removal')}
		>
			<input type="hidden" name="workoutId" value={event.workout?.id} />
			<button class="ghost" disabled={pendingAction !== null}>Preview removal</button>
		</form>
		{#if manualAdjustment}
			<form method="post" action="?/resetWorkout" use:enhance={enhanceAction('reset-workout')}>
				<input type="hidden" name="workoutId" value={event.workout?.id} />
				<button class="ghost" disabled={pendingAction !== null}>Reset to generated</button>
			</form>
			<form
				method="post"
				action="?/undoWorkoutAdjustment"
				use:enhance={enhanceAction('undo-workout')}
			>
				<input type="hidden" name="adjustmentId" value={manualAdjustment.id} />
				<button class="ghost" disabled={pendingAction !== null}>Undo last change</button>
			</form>
		{/if}
	</section>
{/if}

{#if event.workout?.isRemoved && manualAdjustment}
	<div class="message compact-message">
		<strong>Removed from the current plan.</strong>
		<span
			>Undoing the removal restores the prescription that was in place immediately before it.</span
		>
		<form
			method="post"
			action="?/undoWorkoutAdjustment"
			use:enhance={enhanceAction('undo-removal')}
		>
			<input type="hidden" name="adjustmentId" value={manualAdjustment.id} />
			<button disabled={pendingAction !== null}>Undo removal</button>
		</form>
	</div>
{/if}

{#if eventForm?.scope?.action === 'previewWorkoutRemoval' && eventForm.preview && event.workout}
	<section class="edit-preview removal-preview">
		<h3>Review removal</h3>
		<dl class="data-strip">
			<div>
				<dt>Generated</dt>
				<dd>
					{eventForm.preview.recommended
						? proposalPrescription(eventForm.preview.recommended)
						: 'Added by you'}
				</dd>
			</div>
			<div>
				<dt>Current</dt>
				<dd>{proposalPrescription(eventForm.preview.current)}</dd>
			</div>
			<div>
				<dt>Proposed</dt>
				<dd>Removed from the current plan</dd>
			</div>
		</dl>
		{#each eventForm.preview.weekChanges as week (week.weekId)}
			<p>
				Week {week.weekNumber}: {formatDistance(week.distanceBeforeMeters)} → {formatDistance(
					week.distanceAfterMeters
				)}; {formatDuration(week.durationBeforeSeconds)} → {formatDuration(
					week.durationAfterSeconds
				)}.
			</p>
		{/each}
		<ul class="preview-effects">
			<li>
				Removal assessment: {presentLoadChangeAssessment(eventForm.preview.risk).label} ·
				{formatLoadChangeEvidence(
					eventForm.preview.weeklyLoadChangePercent,
					eventForm.preview.risk
				)}
			</li>
			<li>
				Projected plan ramp: {eventForm.preview.projectedRampPercent}% ·
				{presentRampAssessment(eventForm.preview.projectedRampRisk).label}
			</li>
			<li>
				Affected workouts: {eventForm.preview.workoutChanges.length}. No other workout changes
				unless it is listed here.
			</li>
			{#each eventForm.preview.guardrails as guardrail (guardrail.kind)}
				<li><strong>{guardrail.label}.</strong> {guardrail.description}</li>
			{/each}
		</ul>
		<form method="post" action="?/removeWorkout" use:enhance={enhanceAction('remove-workout')}>
			<input type="hidden" name="workoutId" value={event.workout.id} />
			<label class="decision-confirmation">
				<input type="checkbox" required />
				I reviewed the removed prescription, weekly load, and projected ramp.
			</label>
			<button class="bad" disabled={pendingAction !== null}>Remove workout</button>
		</form>
	</section>
{/if}
