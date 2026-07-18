<script lang="ts">
	import { enhance } from '$app/forms';
	import type {
		ImportWorkoutCandidate,
		ScopedEnhanceFactory,
		ScopedImportResult
	} from './import-view-model';

	let {
		candidates,
		activeAction,
		importTimeZoneConfigured,
		scopedResult,
		scopedEnhance
	}: {
		candidates: ImportWorkoutCandidate[];
		activeAction: string | null;
		importTimeZoneConfigured: boolean;
		scopedResult: ScopedImportResult | null;
		scopedEnhance: ScopedEnhanceFactory;
	} = $props();

	let matchMode = $state<'unlinked' | 'auto' | 'workout'>('unlinked');
	let workoutId = $state('');
	const result = $derived(scopedResult?.section === 'gpx' ? scopedResult : null);
	const actionPending = (key: string) => activeAction === key;
	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const isoDay = (date: Date | string) =>
		date instanceof Date ? date.toISOString().slice(0, 10) : date.slice(0, 10);
	const day = (date: Date | string) =>
		new Date(`${isoDay(date)}T00:00:00`).toLocaleDateString(undefined, {
			weekday: 'short',
			month: 'short',
			day: 'numeric'
		});
</script>

<section class="setup-section" aria-labelledby="manual-gpx-heading">
	<h3 id="manual-gpx-heading">Upload GPX</h3>
	<p>Choose a GPX file. Nothing affects your plan until you review or match the run.</p>
	{#if result?.failed}
		<p
			class="message compact-message"
			class:bad-message={result.failed}
			role="status"
			aria-live="polite"
		>
			{result.message}
		</p>
	{/if}
	<form
		method="post"
		action="?/importGpx"
		enctype="multipart/form-data"
		use:enhance={scopedEnhance('import-gpx', 'gpx')}
	>
		<label>
			GPX file
			<input
				type="file"
				name="file"
				accept=".gpx,application/gpx+xml,application/xml,text/xml"
				required
			/>
		</label>
		<fieldset class="gpx-match-options">
			<legend>Plan matching</legend>
			<label class="gpx-match-option">
				<input type="radio" name="matchMode" value="unlinked" bind:group={matchMode} />
				<span>
					<strong>Leave in inbox for review</strong>
					<small>No workout is completed until you review it.</small>
				</span>
			</label>
			<label class="gpx-match-option">
				<input type="radio" name="matchMode" value="auto" bind:group={matchMode} />
				<span>
					<strong>Auto-match by date and distance</strong>
					<small>A close available workout will be completed.</small>
				</span>
			</label>
			{#if candidates.length > 0}
				<label class="gpx-match-option">
					<input type="radio" name="matchMode" value="workout" bind:group={matchMode} />
					<span>
						<strong>Choose a planned workout</strong>
						<small>Completes the selected workout.</small>
					</span>
				</label>
				<label class="gpx-workout-select">
					Planned workout
					<select
						name="workoutId"
						bind:value={workoutId}
						required={matchMode === 'workout'}
						onchange={() => {
							if (workoutId) matchMode = 'workout';
						}}
					>
						<option value="">Choose a workout</option>
						{#each candidates as workout (workout.id)}
							<option value={workout.id}>
								{day(workout.scheduledDate)} · {workout.purpose} · {km(
									workout.targetDistanceMeters
								)}
							</option>
						{/each}
					</select>
				</label>
			{/if}
		</fieldset>
		<button class="primary" disabled={activeAction !== null || !importTimeZoneConfigured}>
			{actionPending('import-gpx') ? 'Importing…' : 'Import'}
		</button>
	</form>
</section>

<style>
	.setup-section {
		display: grid;
		align-content: start;
		gap: 12px;
		width: min(100%, 700px);
		min-width: 0;
		padding: 20px;
		border-block: 1px solid var(--line);
	}

	.setup-section h3,
	.setup-section p {
		margin: 0;
	}

	.setup-section h3 {
		font-size: 1rem;
	}

	.setup-section > p:not(.message) {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.setup-section form {
		display: grid;
		gap: 10px;
	}

	.gpx-match-options {
		padding: 10px;
	}
</style>
