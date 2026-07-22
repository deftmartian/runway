<script lang="ts">
	import { enhance } from '$app/forms';
	import WorkoutFeedbackForm from '$lib/components/WorkoutFeedbackForm.svelte';
	import { canRecordUnplannedRun } from './calendar-presentation';
	import type { CalendarEvent, CalendarFormState } from './calendar-types';
	import { formatDay, type EventActionEnhancer } from './event-detail-model';

	let {
		event,
		eventForm,
		today,
		pendingAction,
		enhanceAction
	}: {
		event: CalendarEvent;
		eventForm: CalendarFormState;
		today: string;
		pendingAction: string | null;
		enhanceAction: EventActionEnhancer;
	} = $props();

	const canEditPast = $derived(canRecordUnplannedRun(event, today));
	const defaultFeedbackStatus = $derived(
		event.workout && event.isRecordable && event.date < today && !event.activity
			? 'skipped'
			: 'done'
	);
</script>

{#if event.workout && event.isRecordable}
	<details
		class="feedback-details event-feedback primary-event-action"
		open={eventForm?.scope?.action === 'recordFeedback' && eventForm.message !== undefined}
	>
		<summary aria-label={`Record ${formatDay(event.date)} ${event.workout.purpose}`}
			>Record run</summary
		>
		<WorkoutFeedbackForm
			workoutId={event.workout.id}
			targetDistanceMeters={event.workout.targetDistanceMeters}
			targetDurationSeconds={event.workout.targetDurationSeconds}
			weekTargetDistanceMeters={event.workout.weekTargetDistanceMeters}
			workoutLabel={`${formatDay(event.date)} ${event.workout.purpose}`}
			defaultStatus={defaultFeedbackStatus}
		/>
	</details>
{/if}

{#if canEditPast}
	<details
		class="feedback-details event-feedback"
		class:primary-event-action={!(event.workout && event.isRecordable)}
		open={eventForm?.scope?.action === 'recordManualRun' && eventForm.message !== undefined}
	>
		<summary aria-label={`Record an unplanned run for ${formatDay(event.date)}`}
			>Record unplanned run</summary
		>
		<form
			method="post"
			action="?/recordManualRun"
			use:enhance={enhanceAction('record-manual')}
			aria-busy={pendingAction === 'record-manual'}
		>
			<input type="hidden" name="occurredDate" value={event.date} />
			<fieldset>
				<legend>Record {formatDay(event.date)}</legend>
				<label>
					Distance (km)
					<input name="distanceKm" type="number" min="0.1" max="100" step="0.1" required />
				</label>
				<label>
					Duration (min)
					<input name="durationMinutes" type="number" min="1" max="600" step="1" />
				</label>
				<div class="check-row">
					<label><input type="checkbox" name="feltHard" /> Effort was unusually hard</label>
					<label><input type="checkbox" name="pain" /> Pain changed or limited this run</label>
				</div>
				<p class="muted">
					This counts toward this week’s actual load. Saving shows its measured effect and choices
					to keep the plan, reduce or rest the next run, or spread a reduction. No future workout
					changes automatically. Mark hard effort or pain so the advice reflects it.
				</p>
				<button
					aria-label={`Save unplanned run for ${formatDay(event.date)}`}
					disabled={pendingAction !== null}
				>
					{pendingAction === 'record-manual' ? 'Saving…' : 'Save run'}
				</button>
			</fieldset>
		</form>
	</details>
{/if}
