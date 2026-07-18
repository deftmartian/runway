<script lang="ts">
	import { resolve } from '$app/paths';
	import ActivityVisuals from './ActivityVisuals.svelte';
	import type { CalendarEvent } from './calendar-types';
	import {
		actualDistance,
		actualPace,
		formatDistance,
		formatDuration,
		formatShortDay,
		plannedPrescription,
		timedWorkoutSteps,
		workoutSources,
		type ActivityTraceDetail
	} from './event-detail-model';

	let { event }: { event: CalendarEvent } = $props();
	let activityTraceDetails = $state<
		Record<string, ActivityTraceDetail | 'loading' | 'failed' | undefined>
	>({});
	const timedSteps = $derived(timedWorkoutSteps(event.workout));
	const sources = $derived(workoutSources(event.workout));
	const prescription = $derived(plannedPrescription(event.workout));
	const completedDistance = $derived(actualDistance(event));
	const completedPace = $derived(actualPace(event));
	const actualDurationSeconds = $derived(
		event.activity?.durationSeconds ?? event.feedback?.completedDurationSeconds ?? null
	);
	const activityTraceDetail = $derived(
		event.activity ? activityTraceDetails[event.activity.id] : undefined
	);
	const adjustmentDistance = $derived(
		event.workout?.adjustment
			? `${formatDistance(event.workout.adjustment.previousTargetDistanceMeters)} to ${formatDistance(
					event.workout.adjustment.newTargetDistanceMeters
				)}`
			: ''
	);
	const adjustmentSchedule = $derived.by(() => {
		const adjustment = event.workout?.adjustment;
		if (!adjustment?.previousScheduledDate || !adjustment.newScheduledDate) return '';
		if (adjustment.previousScheduledDate === adjustment.newScheduledDate) return '';
		return `${formatShortDay(adjustment.previousScheduledDate)} to ${formatShortDay(adjustment.newScheduledDate)}`;
	});

	$effect(() => {
		const selectedActivity = event.activity;
		if (!selectedActivity) return;
		if (!selectedActivity.hasRouteTrace && !selectedActivity.hasHeartRateSeries) return;
		void loadActivityTrace(selectedActivity.id);
	});

	async function loadActivityTrace(activityId: string) {
		if (activityTraceDetails[activityId]) return;
		activityTraceDetails[activityId] = 'loading';
		try {
			const response = await fetch(resolve('/app/import/activity/[activityId]', { activityId }), {
				headers: { accept: 'application/json' }
			});
			if (!response.ok) throw new Error('Activity detail request failed.');
			activityTraceDetails[activityId] = (await response.json()) as ActivityTraceDetail;
		} catch {
			activityTraceDetails[activityId] = 'failed';
		}
	}

	const bpm = (value: number | null | undefined) => (value ? `${value} bpm` : 'No HR');
</script>

{#if timedSteps.length > 0}
	<section class="interval-prescription" aria-labelledby={`intervals-${event.id}`}>
		<h3 id={`intervals-${event.id}`}>Run/walk instructions</h3>
		<ol>
			{#each timedSteps as step (step)}
				<li>{step}</li>
			{/each}
		</ol>
	</section>
{/if}

{#if event.workout?.reason}
	<p class="message compact-message">{event.workout.reason}</p>
{:else if event.kind === 'rest'}
	<p class="message compact-message">Scheduled recovery.</p>
{/if}

{#if sources.length > 0}
	<details class="training-source-details">
		<summary>Why this workout?</summary>
		<ul>
			{#each sources as source (source.id)}
				<li>
					<span>{source.rule}</span>
					<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external evidence source -->
					<a href={source.url} target="_blank" rel="noreferrer">{source.label}</a>
				</li>
			{/each}
		</ul>
	</details>
{/if}

{#if event.workout?.adjustment}
	<div class="message compact-message adjustment-message">
		<strong>Plan change.</strong>
		<span>{event.workout.adjustment.reason}</span>
		<span class="consequence-facts">
			<strong>{adjustmentDistance}</strong>
			{#if adjustmentSchedule}<strong>{adjustmentSchedule}</strong>{/if}
		</span>
	</div>
{/if}

<dl class="event-detail-grid" class:planned-only={!event.activity && !event.feedback}>
	<div>
		<dt>Planned</dt>
		<dd>
			<strong>{prescription}</strong>
			{#if event.workout}<small>{event.workout.type}</small>{/if}
		</dd>
	</div>
	{#if event.activity || event.feedback}
		<div>
			<dt>Actual</dt>
			<dd>
				<strong>{completedDistance}</strong>
				{#if completedPace}<small>{completedPace}</small>{/if}
			</dd>
		</div>
		<div>
			<dt>Heart rate</dt>
			<dd>
				<strong>{event.activity ? bpm(event.activity.averageHeartRate) : '—'}</strong>
				{#if event.activity?.maxHeartRate}<small>max {event.activity.maxHeartRate}</small>{/if}
			</dd>
		</div>
		<div>
			<dt>{event.activity?.source === 'gpx' ? 'Elapsed time' : 'Duration'}</dt>
			<dd>
				<strong>{formatDuration(actualDurationSeconds)}</strong>
				{#if event.activity?.heartRateSummary}
					<small>{Math.round(event.activity.heartRateSummary.highShare * 100)}% in zones 4–5</small>
				{/if}
			</dd>
		</div>
	{/if}
</dl>

{#if event.activity}
	<ActivityVisuals
		id={`calendar-${event.activity.id}`}
		routeTrace={activityTraceDetail &&
		activityTraceDetail !== 'loading' &&
		activityTraceDetail !== 'failed'
			? activityTraceDetail.routeTrace
			: null}
		heartRateSeries={activityTraceDetail &&
		activityTraceDetail !== 'loading' &&
		activityTraceDetail !== 'failed'
			? activityTraceDetail.heartRateSeries
			: null}
		heartRateSummary={event.activity.heartRateSummary}
		averageHeartRate={event.activity.averageHeartRate}
		maxHeartRate={event.activity.maxHeartRate}
		durationSeconds={event.activity.durationSeconds}
	/>
	{#if activityTraceDetail === 'loading'}
		<p class="muted activity-trace-note" aria-live="polite">Loading private activity detail…</p>
	{:else if activityTraceDetail === 'failed'}
		<p class="message compact-message" role="status">Activity visuals could not be loaded.</p>
	{:else if event.activity.source === 'gpx' && !event.activity.hasRouteTrace}
		<p class="muted activity-trace-note">
			This import predates saved route traces. Future GPX imports can include the route map.
		</p>
	{/if}
{/if}
