<script lang="ts">
	import { enhance } from '$app/forms';
	import type { CalendarEvent, WorkoutCandidate } from './calendar-types';
	import {
		dateDistanceDays,
		formatDistance,
		formatShortDay,
		type EventActionEnhancer
	} from './event-detail-model';

	let {
		event,
		candidates,
		pendingAction,
		enhanceAction
	}: {
		event: CalendarEvent;
		candidates: WorkoutCandidate[];
		pendingAction: string | null;
		enhanceAction: EventActionEnhancer;
	} = $props();

	const nearbyCandidates = $derived.by(() =>
		event.activity
			? candidates.filter((candidate) => dateDistanceDays(candidate.scheduledDate, event.date) <= 3)
			: candidates
	);
</script>

{#if event.activity}
	<div class="event-detail-list">
		<div>
			<span class="muted">Source</span>
			<strong>{event.activity.source === 'gpx' ? 'GPX import' : 'Recorded manually'}</strong>
		</div>
		{#if event.activity.source === 'gpx'}
			<div>
				<span class="muted">Route points</span>
				<strong>{event.activity.routeSummary.pointCount}</strong>
			</div>
		{/if}
		<div>
			<span class="muted">Match</span>
			<strong>{event.activity.matchedWorkoutPurpose ?? 'Needs review'}</strong>
		</div>
	</div>

	<section class="event-actions" aria-label="Activity actions">
		<div>
			<h3>Activity record</h3>
			<p class="muted">
				{event.activity.workoutId
					? 'Linked to a planned workout.'
					: event.activity.extraPlanImpactConfirmed
						? 'Counted as extra training.'
						: 'Needs a plan decision.'}
			</p>
		</div>
		<form
			method="post"
			action="?/updateActivityFeedback"
			use:enhance={enhanceAction('update-activity-feedback')}
			class="inline-action-form"
		>
			<input type="hidden" name="activityId" value={event.activity.id} />
			<fieldset>
				<legend>How it felt</legend>
				<label>
					<input type="checkbox" name="feltHard" checked={event.activity.feltHard} /> Effort was unusually
					hard
				</label>
				<label
					><input type="checkbox" name="pain" checked={event.activity.pain} /> Pain changed or limited
					this run</label
				>
			</fieldset>
			<button disabled={pendingAction !== null}>
				{pendingAction === 'update-activity-feedback' ? 'Saving…' : 'Save how it felt'}
			</button>
		</form>
		{#if event.activity.workoutId}
			<form method="post" action="?/unlinkActivity" use:enhance={enhanceAction('unlink-activity')}>
				<input type="hidden" name="activityId" value={event.activity.id} />
				<button disabled={pendingAction !== null}>
					{pendingAction === 'unlink-activity' ? 'Unlinking…' : 'Unlink activity'}
				</button>
			</form>
		{:else if nearbyCandidates.length > 0}
			<form
				method="post"
				action="?/linkActivity"
				use:enhance={enhanceAction('link-activity')}
				class="inline-action-form"
			>
				<input type="hidden" name="activityId" value={event.activity.id} />
				<label>
					Planned workout
					<select name="workoutId" required>
						{#each nearbyCandidates as workout (workout.id)}
							<option value={workout.id}>
								{formatShortDay(workout.scheduledDate)} · {workout.purpose} · {formatDistance(
									workout.targetDistanceMeters
								)}
							</option>
						{/each}
					</select>
				</label>
				<button class="primary" disabled={pendingAction !== null}>
					{pendingAction === 'link-activity' ? 'Linking…' : 'Link activity'}
				</button>
			</form>
		{:else}
			<p class="message compact-message">
				No open planned workout is available for this imported run.
			</p>
		{/if}
		{#if !event.activity.workoutId && !event.activity.extraPlanImpactConfirmed}
			<form
				method="post"
				action="?/confirmActivityExtra"
				use:enhance={enhanceAction(
					'confirm-activity-extra',
					'Count this activity as extra training? The current plan will stay unchanged until you choose a plan decision.'
				)}
			>
				<input type="hidden" name="activityId" value={event.activity.id} />
				<button disabled={pendingAction !== null}>
					{pendingAction === 'confirm-activity-extra' ? 'Counting…' : 'Count as extra training'}
				</button>
			</form>
		{/if}
		<form
			method="post"
			action="?/deleteActivity"
			use:enhance={enhanceAction(
				'delete-activity',
				'Delete this activity record? This cannot be undone.'
			)}
		>
			<input type="hidden" name="activityId" value={event.activity.id} />
			<button class="danger" disabled={pendingAction !== null}>
				{pendingAction === 'delete-activity' ? 'Deleting…' : 'Delete activity'}
			</button>
		</form>
	</section>
{/if}
