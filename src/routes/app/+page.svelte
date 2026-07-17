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
		<TrainingCalendar
			calendar={data.calendar}
			activityCandidates={data.activityCandidates}
			currentSignal={data.currentSignal}
			{form}
			hasActivePlan={Boolean(data.activePlan)}
			targetDate={data.activePlan?.plan.targetDate ?? null}
		/>
	{/if}
</main>

<style>
	.first-plan-empty {
		display: grid;
		justify-items: start;
		gap: 16px;
		max-width: 620px;
		padding: clamp(40px, 10vw, 100px) 0;
	}

	.first-plan-empty h1,
	.first-plan-empty p {
		margin: 0;
	}

	.first-plan-empty h1 {
		font-size: clamp(2.2rem, 7vw, 4rem);
		line-height: 0.98;
	}

	.first-plan-empty p {
		max-width: 48ch;
		color: var(--muted);
		font-size: 1.05rem;
	}
</style>
