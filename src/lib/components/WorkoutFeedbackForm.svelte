<script lang="ts">
	import { enhance } from '$app/forms';
	import {
		presentConsequence,
		presentConsequenceFacts
	} from '$lib/training/consequence-presentation';
	import { calculateConsequence } from '$lib/training/consequences';
	import { presentConsequenceAssessment } from '$lib/training/training-assessment';
	import type { SubmitFunction } from '@sveltejs/kit';

	type Status = 'done' | 'skipped';

	let {
		workoutId,
		targetDistanceMeters,
		targetDurationSeconds = null,
		weekTargetDistanceMeters,
		workoutLabel,
		defaultStatus = 'done'
	}: {
		workoutId: string;
		targetDistanceMeters: number;
		targetDurationSeconds?: number | null;
		weekTargetDistanceMeters: number;
		workoutLabel: string;
		defaultStatus?: Status;
	} = $props();

	let status = $state<Status>('done');
	let completedDistanceKm = $state('');
	let completedDurationMinutes = $state('');
	let activeWorkoutKey = $state('');
	let feltHard = $state(false);
	let pain = $state(false);
	let saving = $state(false);

	$effect(() => {
		const nextWorkoutKey = `${workoutId}:${defaultStatus}:${targetDistanceMeters}:${targetDurationSeconds ?? 0}`;
		if (activeWorkoutKey !== nextWorkoutKey) {
			status = defaultStatus === 'skipped' ? 'skipped' : 'done';
			completedDistanceKm =
				defaultStatus === 'skipped' || targetDistanceMeters === 0
					? ''
					: `${Math.round((targetDistanceMeters / 1000) * 10) / 10}`;
			completedDurationMinutes =
				defaultStatus === 'skipped' || !targetDurationSeconds
					? ''
					: String(Math.round(targetDurationSeconds / 60));
			feltHard = false;
			pain = false;
			activeWorkoutKey = nextWorkoutKey;
			return;
		}
		if (status === 'skipped') {
			completedDistanceKm = '';
			completedDurationMinutes = '';
			return;
		}
		if (status === 'done' && completedDistanceKm === '') {
			if (targetDistanceMeters > 0) {
				completedDistanceKm = `${Math.round((targetDistanceMeters / 1000) * 10) / 10}`;
			}
			if (targetDurationSeconds && completedDurationMinutes === '') {
				completedDurationMinutes = String(Math.round(targetDurationSeconds / 60));
			}
		}
	});

	const completedDistanceMeters = $derived(
		status === 'skipped'
			? 0
			: completedDistanceKm === ''
				? undefined
				: Math.round(Number(completedDistanceKm) * 1_000)
	);
	const measurementError = $derived.by(() => {
		if (status === 'skipped') return null;
		if (targetDistanceMeters > 0 && completedDistanceMeters === undefined) {
			return 'Enter the distance completed.';
		}
		if (completedDistanceMeters !== undefined && !Number.isFinite(completedDistanceMeters)) {
			return 'Enter a valid completed distance.';
		}
		if (completedDistanceMeters !== undefined && completedDistanceMeters <= 0) {
			return targetDistanceMeters > 0
				? 'A completed run needs a positive distance.'
				: 'Observed distance must be positive or left blank.';
		}
		if (targetDurationSeconds) {
			const minutes = Number(completedDurationMinutes);
			if (!Number.isFinite(minutes) || minutes <= 0) return 'Enter the duration completed.';
		}
		return null;
	});
	const consequence = $derived.by(() => {
		if (measurementError) return null;
		const completedDurationSeconds =
			completedDurationMinutes === ''
				? undefined
				: Math.round(Number(completedDurationMinutes) * 60);
		return calculateConsequence({
			status,
			choice: 'skip_continue',
			targetDistanceMeters,
			...(targetDurationSeconds ? { targetDurationSeconds } : {}),
			weekTargetDistanceMeters,
			feltHard,
			pain,
			...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
			...(completedDurationSeconds === undefined ? {} : { completedDurationSeconds })
		});
	});
	const consequencePresentation = $derived(consequence ? presentConsequence(consequence) : null);
	const consequenceFacts = $derived(consequence ? presentConsequenceFacts(consequence) : null);
	const consequenceAssessment = $derived(
		consequence ? presentConsequenceAssessment(consequence) : null
	);
	const feedbackEnhance: SubmitFunction = ({ cancel }) => {
		if (saving || measurementError) {
			cancel();
			return;
		}
		saving = true;
		return async ({ update }) => {
			try {
				await update();
			} finally {
				saving = false;
			}
		};
	};
</script>

<form
	method="post"
	action="?/recordFeedback"
	use:enhance={feedbackEnhance}
	aria-label={`Record ${workoutLabel}`}
	aria-busy={saving}
>
	<input type="hidden" name="workoutId" value={workoutId} />
	<fieldset>
		<legend>Record {workoutLabel}</legend>
		<label>
			Result
			<select name="status" bind:value={status}>
				<option value="done">Run recorded</option>
				<option value="skipped">Skipped</option>
			</select>
		</label>
		{#if targetDistanceMeters > 0}
			<label>
				Distance completed (km)
				<input
					name="completedDistanceKm"
					type="number"
					min="0.1"
					max="100"
					step="0.1"
					required={status !== 'skipped'}
					disabled={status === 'skipped'}
					aria-invalid={Boolean(measurementError)}
					aria-describedby={measurementError ? `measurement-error-${workoutId}` : undefined}
					bind:value={completedDistanceKm}
				/>
			</label>
		{:else if targetDurationSeconds}
			<label>
				Distance observed (km) <span class="optional">Optional</span>
				<input
					name="completedDistanceKm"
					type="number"
					min="0.1"
					max="100"
					step="0.1"
					disabled={status === 'skipped'}
					aria-invalid={Boolean(measurementError)}
					aria-describedby={measurementError
						? `observed-distance-help-${workoutId} measurement-error-${workoutId}`
						: `observed-distance-help-${workoutId}`}
					bind:value={completedDistanceKm}
				/>
				<span id={`observed-distance-help-${workoutId}`} class="field-help">
					Records what happened; it does not turn this timed session into a distance target.
				</span>
			</label>
		{/if}
		<label>
			Duration completed (min{targetDurationSeconds ? '' : ', optional'})
			<input
				name="completedDurationMinutes"
				type="number"
				min="1"
				max="600"
				step="1"
				required={status !== 'skipped' && Boolean(targetDurationSeconds)}
				disabled={status === 'skipped'}
				bind:value={completedDurationMinutes}
			/>
		</label>
		{#if measurementError}
			<p id={`measurement-error-${workoutId}`} class="field-error">{measurementError}</p>
		{/if}
		<div class="check-row">
			<label
				><input type="checkbox" name="feltHard" bind:checked={feltHard} /> Effort was unusually hard</label
			>
			<label
				><input type="checkbox" name="pain" bind:checked={pain} /> Pain changed or limited this run</label
			>
		</div>
		<p class="muted">
			Hard effort changes the next-run advice. Pain adds health guidance. Select both when both were
			true.
		</p>
		<input type="hidden" name="choice" value="skip_continue" />
		{#if consequence && consequencePresentation}
			<div class="message consequence-preview" aria-live="polite">
				<strong>{consequencePresentation.outcome}</strong>
				<span>{consequencePresentation.planChange}</span>
				{#if consequencePresentation.safety}
					<span>{consequencePresentation.safety}</span>
				{/if}
				<span class="consequence-facts">
					{#if consequenceFacts}
						<strong>{consequenceFacts.weekImpact}</strong>
						<strong>{consequenceFacts.nextRunImpact}</strong>
					{/if}
					{#if consequenceAssessment}<strong>{consequenceAssessment.label}</strong>{/if}
				</span>
			</div>
		{/if}
		<button
			aria-label={`Save feedback for ${workoutLabel}`}
			disabled={saving || Boolean(measurementError)}
		>
			{saving ? 'Saving…' : 'Save result'}
		</button>
	</fieldset>
</form>
