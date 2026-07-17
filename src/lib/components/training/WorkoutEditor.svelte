<script lang="ts">
	import { enhance } from '$app/forms';
	import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
	import type { WorkoutEditProposal } from '$lib/training/workout-edit';
	import type { SubmitFunction } from '@sveltejs/kit';
	import type { CalendarFormState, WorkoutEditFormValues } from './calendar-types';

	let {
		workout = null,
		date,
		form,
		today,
		targetDate
	}: {
		workout?: TrainingCalendarWorkout | null;
		date: string;
		form: CalendarFormState;
		today: string;
		targetDate: string;
	} = $props();

	const initial = readInitial();
	let prescriptionKind = $state(initial.prescriptionKind);
	let workoutType = $state(initial.type);
	let replaceIntervals = $state(initial.replaceIntervals);
	const mode = readMode();
	const matchingForm = $derived.by(() => {
		if (!form?.scope) return null;
		if (
			workout &&
			'workoutId' in form.scope &&
			form.scope.workoutId === workout.id &&
			(form.scope.action === 'previewWorkoutEdit' || form.scope.action === 'applyWorkoutEdit')
		) {
			return form;
		}
		if (
			!workout &&
			'date' in form.scope &&
			form.scope.date === date &&
			(form.scope.action === 'previewWorkoutAdd' || form.scope.action === 'applyWorkoutAdd')
		) {
			return form;
		}
		return null;
	});
	const preview = $derived(matchingForm?.preview ?? null);
	const editValues = $derived(matchingForm?.editValues ?? null);

	function readInitial(): WorkoutEditFormValues {
		const firstBlock = workout?.intervalStructure?.blocks[0];
		const runSegment = firstBlock?.segments.find((segment) => segment.kind === 'run');
		const walkSegment = firstBlock?.segments.find((segment) => segment.kind === 'walk');
		return {
			...(workout ? { workoutId: workout.id } : {}),
			scheduledDate: date,
			type: workout?.type === 'race' ? 'easy' : (workout?.type ?? 'easy'),
			prescriptionKind: workout?.prescriptionKind ?? 'distance',
			distanceKm: workout?.targetDistanceMeters
				? String(Math.round((workout.targetDistanceMeters / 1_000) * 10) / 10)
				: '3',
			durationMinutes: workout?.targetDurationSeconds
				? String(Math.round(workout.targetDurationSeconds / 60))
				: '20',
			intervalStructureJson: workout?.intervalStructure
				? JSON.stringify(workout.intervalStructure)
				: '',
			replaceIntervals: !workout,
			runMinutes: runSegment ? String(runSegment.durationSeconds / 60) : '2',
			walkMinutes: walkSegment ? String(walkSegment.durationSeconds / 60) : '1',
			repetitions: String(firstBlock?.repetitions ?? 6),
			intensity: workout?.intensity ?? 'easy',
			purpose: workout?.purpose ?? 'Easy run',
			userReason: '',
			rebalance: false,
			confirmRisk: false
		};
	}

	function readMode() {
		return workout ? 'edit' : 'add';
	}

	function choosePrescription(kind: 'distance' | 'timed' | 'rest') {
		prescriptionKind = kind;
		if (kind === 'rest') workoutType = 'rest';
		else if (workoutType === 'rest') workoutType = 'easy';
	}

	function prescriptionLabel(proposal: WorkoutEditProposal | null) {
		if (!proposal) return 'No generated recommendation; this is a runner-added workout.';
		if (proposal.isRemoved) return 'Removed from the current plan';
		if (proposal.prescriptionKind === 'rest') return `${proposal.scheduledDate} · Rest`;
		if (proposal.prescriptionKind === 'timed') {
			return `${proposal.scheduledDate} · ${Math.round((proposal.targetDurationSeconds ?? 0) / 60)} min · ${proposal.purpose}`;
		}
		return `${proposal.scheduledDate} · ${Math.round((proposal.targetDistanceMeters / 1_000) * 10) / 10} km · ${proposal.purpose}`;
	}

	const preserveEditorValues: SubmitFunction = () => {
		return async ({ update }) => {
			await update({ reset: false });
		};
	};
</script>

<form
	method="post"
	action={mode === 'edit' ? '?/previewWorkoutEdit' : '?/previewWorkoutAdd'}
	use:enhance={preserveEditorValues}
	class="workout-editor"
>
	{#if workout}<input type="hidden" name="workoutId" value={workout.id} />{/if}
	<input type="hidden" name="intervalStructureJson" value={initial.intervalStructureJson} />
	<div class="form-grid two">
		<label>
			Date
			<input
				name="scheduledDate"
				type="date"
				min={today}
				max={targetDate}
				value={initial.scheduledDate}
				required
			/>
		</label>
		<label>
			Prescription
			<select
				name="prescriptionKind"
				bind:value={prescriptionKind}
				onchange={() => {
					choosePrescription(prescriptionKind as 'distance' | 'timed' | 'rest');
				}}
			>
				<option value="distance">Distance</option>
				<option value="timed">Timed run/walk</option>
				<option value="rest">Rest</option>
			</select>
		</label>
	</div>

	{#if prescriptionKind !== 'rest'}
		<div class="form-grid two">
			<label>
				Workout type
				<select name="type" bind:value={workoutType}>
					<option value="easy">Easy</option>
					<option value="long">Long</option>
					<option value="recovery">Recovery</option>
				</select>
			</label>
			{#if prescriptionKind === 'distance'}
				<label>
					Distance (km)
					<input
						name="distanceKm"
						type="number"
						min="0.1"
						max="100"
						step="0.1"
						value={initial.distanceKm}
						required
					/>
				</label>
			{:else}
				<label>
					Total duration (min)
					<input
						name="durationMinutes"
						type="number"
						min="10"
						max="360"
						step="1"
						value={initial.durationMinutes}
						required
					/>
				</label>
			{/if}
		</div>
	{:else}
		<input type="hidden" name="type" value="rest" />
	{/if}

	{#if prescriptionKind === 'timed'}
		<label class="check-row inline-check">
			<input type="checkbox" name="replaceIntervals" bind:checked={replaceIntervals} />
			Change the run/walk block
		</label>
		{#if replaceIntervals}
			<div class="form-grid three">
				<label>
					Run (min)
					<input
						name="runMinutes"
						type="number"
						min="0.25"
						step="0.25"
						value={initial.runMinutes}
						required
					/>
				</label>
				<label>
					Walk (min)
					<input
						name="walkMinutes"
						type="number"
						min="0.25"
						step="0.25"
						value={initial.walkMinutes}
					/>
				</label>
				<label>
					Repeats
					<input
						name="repetitions"
						type="number"
						min="1"
						max="100"
						step="1"
						value={initial.repetitions}
						required
					/>
				</label>
			</div>
		{/if}
	{/if}

	<div class="form-grid two">
		<label>
			Purpose
			<input name="purpose" maxlength="120" value={initial.purpose} required />
		</label>
		<label>
			Effort
			<input name="intensity" maxlength="80" value={initial.intensity} required />
		</label>
	</div>
	<label>
		Reason for the change <span class="muted">(optional)</span>
		<input name="userReason" maxlength="500" />
	</label>
	<label class="check-row inline-check">
		<input type="checkbox" name="rebalance" />
		Rebalance compatible workouts in the destination week
	</label>
	<p class="muted">By default this changes only this workout.</p>
	<button class="secondary">Preview {mode === 'edit' ? 'change' : 'workout'}</button>
</form>

{#if matchingForm?.message}
	<p class="message compact-message" role="status">{matchingForm.message}</p>
{/if}

{#if preview}
	<section class="edit-preview" aria-labelledby={`edit-preview-${workout?.id ?? date}`}>
		<h3 id={`edit-preview-${workout?.id ?? date}`}>Before applying</h3>
		<dl class="ledger-comparison">
			<div>
				<dt>Generated</dt>
				<dd>{prescriptionLabel(preview.recommended)}</dd>
			</div>
			<div>
				<dt>Current</dt>
				<dd>{prescriptionLabel(preview.current)}</dd>
			</div>
			<div>
				<dt>Proposed</dt>
				<dd>{prescriptionLabel(preview.proposed)}</dd>
			</div>
		</dl>
		<ul class="preview-effects">
			{#each preview.weekChanges as week (week.weekId)}
				<li>
					Week {week.weekNumber}: {Math.round(week.distanceBeforeMeters / 100) / 10} →
					{Math.round(week.distanceAfterMeters / 100) / 10} km; {Math.round(
						week.durationBeforeSeconds / 60
					)} → {Math.round(week.durationAfterSeconds / 60)} min
				</li>
			{/each}
			<li>Projected ramp {preview.projectedRampPercent}% · {preview.risk} risk</li>
			{#if preview.spacingConflicts.length > 0}
				<li>
					Recovery spacing conflict with {preview.spacingConflicts
						.map((conflict) => `${conflict.purpose} on ${conflict.scheduledDate}`)
						.join(', ')}.
				</li>
			{/if}
			{#if preview.affectedFutureWorkoutIds.length > 0}
				<li>{preview.affectedFutureWorkoutIds.length} other workout(s) will be rebalanced.</li>
			{/if}
		</ul>
		{#if editValues}
			<form
				method="post"
				action={mode === 'edit' ? '?/applyWorkoutEdit' : '?/applyWorkoutAdd'}
				use:enhance={preserveEditorValues}
			>
				{#if editValues.workoutId}<input
						type="hidden"
						name="workoutId"
						value={editValues.workoutId}
					/>{/if}
				<input type="hidden" name="scheduledDate" value={editValues.scheduledDate} />
				<input type="hidden" name="type" value={editValues.type} />
				<input type="hidden" name="prescriptionKind" value={editValues.prescriptionKind} />
				<input type="hidden" name="distanceKm" value={editValues.distanceKm} />
				<input type="hidden" name="durationMinutes" value={editValues.durationMinutes} />
				<input
					type="hidden"
					name="intervalStructureJson"
					value={editValues.intervalStructureJson}
				/>
				{#if editValues.replaceIntervals}<input
						type="hidden"
						name="replaceIntervals"
						value="on"
					/>{/if}
				<input type="hidden" name="runMinutes" value={editValues.runMinutes} />
				<input type="hidden" name="walkMinutes" value={editValues.walkMinutes} />
				<input type="hidden" name="repetitions" value={editValues.repetitions} />
				<input type="hidden" name="intensity" value={editValues.intensity} />
				<input type="hidden" name="purpose" value={editValues.purpose} />
				<input type="hidden" name="userReason" value={editValues.userReason} />
				{#if editValues.rebalance}<input type="hidden" name="rebalance" value="on" />{/if}
				<input type="hidden" name="confirmRisk" value="on" />
				<button class:bad={preview.risk === 'unsafe'}>
					Apply {preview.requiresConfirmation ? 'with confirmation' : 'change'}
				</button>
			</form>
		{/if}
	</section>
{/if}

<style>
	.workout-editor,
	.edit-preview {
		display: grid;
		gap: 12px;
	}

	.form-grid {
		display: grid;
		gap: 10px;
	}

	.form-grid.two {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}

	.form-grid.three {
		grid-template-columns: repeat(3, minmax(0, 1fr));
	}

	.inline-check {
		display: flex;
		align-items: center;
		gap: 8px;
	}

	.ledger-comparison {
		display: grid;
		gap: 1px;
		margin: 0;
		background: var(--line);
	}

	.ledger-comparison > div {
		display: grid;
		grid-template-columns: 82px 1fr;
		gap: 12px;
		padding: 10px;
		background: var(--surface);
	}

	.ledger-comparison dt,
	.ledger-comparison dd,
	.edit-preview h3,
	.preview-effects {
		margin: 0;
	}

	.ledger-comparison dt {
		color: var(--muted);
	}

	.preview-effects {
		display: grid;
		gap: 6px;
		padding-left: 20px;
	}

	@media (max-width: 560px) {
		.form-grid.two,
		.form-grid.three {
			grid-template-columns: 1fr;
		}
	}
</style>
