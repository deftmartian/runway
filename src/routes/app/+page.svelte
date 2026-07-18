<script lang="ts">
	import { resolve } from '$app/paths';
	import TrainingCalendar from '$lib/components/training/TrainingCalendar.svelte';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
</script>

<main class="page stack training-page">
	{#if !data.calendar}
		<section class="first-plan-empty">
			<h1>Create your first plan</h1>
			<p>Enter your goal, current weekly distance, and available running days.</p>
			<a class="button primary" href={resolve('/app/onboarding')}>Create plan</a>
		</section>
	{:else}
		{#if data.calendar.activityOverflow.truncated}
			<p class="message calendar-overflow" role="status">
				This month has more than {data.calendar.activityOverflow.limit} recorded activities. The calendar
				shows the newest {data.calendar.activityOverflow.limit}; older activities in this view are
				omitted.
			</p>
		{/if}
		<TrainingCalendar
			calendar={data.calendar}
			activityCandidates={data.activityCandidates}
			currentSignal={data.currentSignal}
			{form}
			hasActivePlan={Boolean(data.activePlan)}
			targetDate={data.activePlan?.plan.targetDate ?? null}
			defaultWeeklyIncreasePercent={data.activePlan?.plan.summary.kind === 'distance'
				? data.activePlan.plan.summary.defaultWeeklyIncreasePercent
				: null}
		/>
	{/if}
</main>

<style>
	.first-plan-empty {
		display: grid;
		justify-items: start;
		gap: 16px;
		max-width: 620px;
		margin-top: clamp(18px, 5vw, 56px);
		padding: clamp(30px, 6vw, 54px);
		border: 1px solid var(--line);
		border-left: 4px solid var(--accent);
		border-radius: var(--radius);
		background: color-mix(in oklab, var(--surface), var(--surface-strong) 42%);
	}

	.first-plan-empty h1,
	.first-plan-empty p {
		margin: 0;
	}

	.first-plan-empty h1 {
		font-size: clamp(2rem, 6vw, 3.4rem);
		line-height: 1;
	}

	.first-plan-empty p {
		max-width: 48ch;
		color: var(--muted);
		font-size: 1.05rem;
	}

	.calendar-overflow {
		margin: 0;
	}
</style>
