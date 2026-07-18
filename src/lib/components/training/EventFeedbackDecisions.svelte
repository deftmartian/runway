<script lang="ts">
	import { enhance } from '$app/forms';
	import {
		decisionLabel,
		presentConsequence,
		presentConsequenceFacts
	} from '$lib/training/consequence-presentation';
	import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
	import { presentConsequenceAssessment } from '$lib/training/training-assessment';
	import type { CalendarEvent, CalendarFormState } from './calendar-types';
	import {
		decisionRecordForEvent,
		planDecisionUnavailable,
		previewPlanDecision,
		recordedStatus,
		type EventActionEnhancer
	} from './event-detail-model';

	let {
		event,
		eventForm,
		futureWorkouts,
		today,
		pendingAction,
		enhanceAction
	}: {
		event: CalendarEvent;
		eventForm: CalendarFormState;
		futureWorkouts: TrainingCalendarWorkout[];
		today: string;
		pendingAction: string | null;
		enhanceAction: EventActionEnhancer;
	} = $props();

	const decisionRecord = $derived(decisionRecordForEvent(event));
	const status = $derived(recordedStatus(event));
	const feedbackConsequence = $derived(
		event.feedback ? presentConsequence(event.feedback.consequence) : null
	);
	const feedbackConsequenceFacts = $derived(
		event.feedback ? presentConsequenceFacts(event.feedback.consequence) : null
	);
	const feedbackAssessment = $derived(
		event.feedback ? presentConsequenceAssessment(event.feedback.consequence) : null
	);
	const formConsequence = $derived(
		eventForm?.consequence ? presentConsequence(eventForm.consequence) : null
	);
	const formConsequenceFacts = $derived(
		eventForm?.consequence ? presentConsequenceFacts(eventForm.consequence) : null
	);
	const formAssessment = $derived(
		eventForm?.consequence ? presentConsequenceAssessment(eventForm.consequence) : null
	);
</script>

{#if event.feedback}
	<section class="event-actions saved-feedback-actions" aria-label="Saved workout result">
		<div>
			<h3>Saved result</h3>
			<p class="muted">
				<strong>{status ?? 'Recorded'}</strong>
				· {event.feedback.feltHard ? 'Hard effort' : 'Effort not marked hard'}
				· {event.feedback.pain ? 'Pain reported' : 'No pain reported'}
			</p>
		</div>
		{#if event.feedback.canDelete}
			<form
				method="post"
				action="?/deleteFeedback"
				use:enhance={enhanceAction(
					'delete-feedback',
					'Undo this saved result and restore the plan changes it caused?'
				)}
			>
				<input type="hidden" name="workoutId" value={event.feedback.workoutId} />
				<button class="danger" disabled={pendingAction !== null}>
					{pendingAction === 'delete-feedback' ? 'Undoing…' : 'Undo saved result'}
				</button>
			</form>
		{:else}
			<p class="muted">Unlink or delete the activity to correct this imported result.</p>
		{/if}
	</section>

	{#if feedbackConsequence}
		<div
			class="message compact-message"
			class:bad-message={event.feedback.consequence.risk === 'unsafe'}
		>
			<strong>{feedbackConsequence.outcome}</strong>
			<span>{feedbackConsequence.planChange}</span>
			{#if feedbackConsequence.safety}<span>{feedbackConsequence.safety}</span>{/if}
			<span class="consequence-facts">
				{#if feedbackConsequenceFacts}
					<strong>{feedbackConsequenceFacts.weekImpact}</strong>
					<strong>{feedbackConsequenceFacts.nextRunImpact}</strong>
				{/if}
				{#if feedbackAssessment}<strong>{feedbackAssessment.label}</strong>{/if}
			</span>
		</div>
	{/if}
{/if}

{#if decisionRecord && !decisionRecord.consequence.appliedDecision}
	<section class="event-actions consequence-options" aria-labelledby="plan-decision-heading">
		<div>
			<h3 id="plan-decision-heading">Choose what changes next</h3>
			<p class="muted">Actual load is already counted. Nothing below happens until selected.</p>
		</div>
		<div class="decision-actions">
			{#each decisionRecord.consequence.options as decision (decision)}
				{@const unavailable = planDecisionUnavailable({
					decision,
					event,
					futureWorkouts,
					decisionRecord
				})}
				<form
					class="decision-option"
					method="post"
					action="?/applyPlanDecision"
					use:enhance={enhanceAction(
						`decision-${decision}`,
						decisionRecord.consequence.risk === 'unsafe' ||
							decisionRecord.consequence.risk === 'aggressive'
							? `Apply this ${decisionLabel(decision)} option despite the ${presentConsequenceAssessment(decisionRecord.consequence).label.toLowerCase()} assessment?`
							: undefined
					)}
				>
					<input type="hidden" name="source" value={decisionRecord.source} />
					<input type="hidden" name="sourceId" value={decisionRecord.sourceId} />
					<input type="hidden" name="decision" value={decision} />
					<p>{previewPlanDecision({ decision, event, futureWorkouts, decisionRecord })}</p>
					{#if decision === 'repeat_prescription' && !unavailable}
						<label class="decision-confirmation">
							<input type="checkbox" name="confirmRisk" required />
							I reviewed the replacement prescription and its possible load and spacing change.
						</label>
					{/if}
					<button
						class:primary={decision === decisionRecord.consequence.recommendedDecision}
						class:secondary={decision !== decisionRecord.consequence.recommendedDecision}
						disabled={pendingAction !== null || unavailable}
					>
						{decisionLabel(decision)}{decision === decisionRecord.consequence.recommendedDecision
							? ' · Recommended'
							: ''}
					</button>
				</form>
			{/each}
		</div>
	</section>
{/if}

{#if eventForm?.message}
	<p class="message compact-message" role="status" aria-live="polite">{eventForm.message}</p>
{/if}
{#if eventForm?.consequence && formConsequence}
	<div
		class="message compact-message"
		class:bad-message={eventForm.consequence.risk === 'unsafe'}
		role="status"
		aria-live="polite"
	>
		<strong>{formConsequence.outcome}</strong>
		<span>{formConsequence.planChange}</span>
		{#if formConsequence.safety}<span>{formConsequence.safety}</span>{/if}
		<span class="consequence-facts">
			{#if formConsequenceFacts}
				<strong>{formConsequenceFacts.weekImpact}</strong>
				<strong>{formConsequenceFacts.nextRunImpact}</strong>
			{/if}
			{#if formAssessment}<strong>{formAssessment.label}</strong>{/if}
		</span>
	</div>
{/if}

{#if event.workout && event.isRecordable && event.date < today && !event.activity && !event.feedback}
	<p class="message compact-message">
		No result is recorded for this past workout. Choose the result before saving.
	</p>
{/if}
