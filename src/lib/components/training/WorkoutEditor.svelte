<script lang="ts">
	import { enhance } from '$app/forms';
	import { notifyEnhancedFormSaved } from '$lib/pwa/lifecycle';
	import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
	import {
		formatLoadChangeEvidence,
		presentLoadChangeAssessment,
		presentRampAssessment
	} from '$lib/training/training-assessment';
	import type { WorkoutEditProposal, WorkoutEditWorkoutChange } from '$lib/training/workout-edit';
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
	const dirtyScope = $derived(`workout-editor:${workout?.id ?? date}`);
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
			intensity: workout?.type === 'rest' ? 'rest' : 'easy',
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
		if (proposal.isRemoved) return `${proposal.scheduledDate} · Removed from the current plan`;
		if (proposal.prescriptionKind === 'rest') return `${proposal.scheduledDate} · Rest`;
		if (proposal.prescriptionKind === 'timed') {
			const structure = proposal.intervalStructure;
			const blocks = structure?.blocks
				.map(
					(block) =>
						`${block.repetitions} × ${block.segments.map((segment) => `${durationLabel(segment.durationSeconds)} ${segment.kind}`).join(' + ')}`
				)
				.join('; ');
			const intervals = [
				structure?.warmupSeconds ? `${durationLabel(structure.warmupSeconds)} warm-up` : '',
				blocks ?? '',
				structure?.cooldownSeconds ? `${durationLabel(structure.cooldownSeconds)} cool-down` : ''
			]
				.filter(Boolean)
				.join('; ');
			return `${proposal.scheduledDate} · ${durationLabel(proposal.targetDurationSeconds ?? 0)}${intervals ? ` · ${intervals}` : ''} · ${proposal.purpose} · ${proposal.intensity}`;
		}
		return `${proposal.scheduledDate} · ${Math.round((proposal.targetDistanceMeters / 1_000) * 10) / 10} km · ${proposal.purpose} · ${proposal.intensity}`;
	}

	function durationLabel(totalSeconds: number) {
		const minutes = Math.floor(totalSeconds / 60);
		const seconds = totalSeconds % 60;
		return seconds === 0 ? `${minutes} min` : `${minutes} min ${seconds} sec`;
	}

	function workoutChangeImpact(change: WorkoutEditWorkoutChange) {
		const assessment = presentLoadChangeAssessment(change.risk).label;
		if (change.changeShareOfWeekPercent === null) {
			return `Load cannot be compared · ${assessment}`;
		}
		if (change.relativeChangePercent === null) {
			return `${formatLoadChangeEvidence(change.changeShareOfWeekPercent, change.risk)} ${assessment}.`;
		}
		if (change.relativeChangePercent === 0 && change.changeShareOfWeekPercent === 0) {
			return `Load unchanged · ${assessment}`;
		}
		return `${change.relativeChangePercent}% change to this workout · ${formatLoadChangeEvidence(change.changeShareOfWeekPercent, change.risk)} ${assessment}.`;
	}

	const preserveEditorValues: SubmitFunction = () => {
		return async ({ update }) => {
			await update({ reset: false });
		};
	};

	const applyEditorValues: SubmitFunction = ({ formElement }) => {
		return async ({ result, update }) => {
			if (result.type === 'success') notifyEnhancedFormSaved(formElement);
			await update({ reset: false });
		};
	};
</script>

<form
	method="post"
	action={mode === 'edit' ? '?/previewWorkoutEdit' : '?/previewWorkoutAdd'}
	use:enhance={preserveEditorValues}
	class="workout-editor"
	data-pwa-dirty-scope={dirtyScope}
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
			Workout target
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

	<label>
		Purpose
		<input name="purpose" maxlength="120" value={initial.purpose} required />
	</label>
	<input type="hidden" name="intensity" value={prescriptionKind === 'rest' ? 'rest' : 'easy'} />
	<p class="muted">Run effort stays easy; runway does not model harder workout prescriptions.</p>
	<label>
		Reason for the change <span class="muted">(optional)</span>
		<input name="userReason" maxlength="500" />
	</label>
	<label class="check-row inline-check">
		<input type="checkbox" name="rebalance" />
		Spread the weekly change across the other compatible runs
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
				<dd>
					{preview.operation === 'add'
						? 'No workout scheduled.'
						: prescriptionLabel(preview.current)}
				</dd>
			</div>
			<div>
				<dt>Proposed</dt>
				<dd>{prescriptionLabel(preview.proposed)}</dd>
			</div>
		</dl>
		<div class="workout-change-list">
			<h4>Workouts changed</h4>
			{#each preview.workoutChanges as change (change.workoutId)}
				<article>
					<strong>{change.isSelected ? 'Selected workout' : 'Rebalanced workout'}</strong>
					<dl>
						<div>
							<dt>Before</dt>
							<dd>
								{preview.operation === 'add' && change.isSelected
									? 'No workout scheduled.'
									: prescriptionLabel(change.before)}
							</dd>
						</div>
						<div>
							<dt>After</dt>
							<dd>{prescriptionLabel(change.after)}</dd>
						</div>
					</dl>
					<p>{workoutChangeImpact(change)}</p>
				</article>
			{/each}
		</div>
		<ul class="preview-effects">
			{#each preview.weekChanges as week (week.weekId)}
				<li>
					Week {week.weekNumber}: {Math.round(week.distanceBeforeMeters / 100) / 10} →
					{Math.round(week.distanceAfterMeters / 100) / 10} km; {Math.round(
						week.durationBeforeSeconds / 60
					)} → {Math.round(week.durationAfterSeconds / 60)} min
				</li>
			{/each}
			<li>
				Edit assessment: {presentLoadChangeAssessment(preview.risk).label} · {formatLoadChangeEvidence(
					preview.weeklyLoadChangePercent,
					preview.risk
				)}
			</li>
			<li>
				Projected plan ramp: {preview.projectedRampPercent}% · {presentRampAssessment(
					preview.projectedRampRisk
				).label}
			</li>
			{#each preview.guardrails as guardrail (guardrail.kind)}
				<li><strong>{guardrail.label}.</strong> {guardrail.description}</li>
			{/each}
			{#if preview.spacingConflicts.length > 0}
				<li>
					Recovery spacing conflict with {preview.spacingConflicts
						.map((conflict) => `${conflict.purpose} on ${conflict.scheduledDate}`)
						.join(', ')}.
				</li>
			{/if}
		</ul>
		{#if editValues}
			<form
				method="post"
				action={mode === 'edit' ? '?/applyWorkoutEdit' : '?/applyWorkoutAdd'}
				use:enhance={applyEditorValues}
				data-pwa-dirty-scope={dirtyScope}
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

	.workout-change-list {
		display: grid;
		gap: 8px;
	}

	.workout-change-list h4,
	.workout-change-list dl,
	.workout-change-list dd,
	.workout-change-list p {
		margin: 0;
	}

	.workout-change-list article {
		display: grid;
		gap: 8px;
		padding: 10px;
		border: 1px solid var(--line);
		background: var(--surface);
	}

	.workout-change-list dl {
		display: grid;
		gap: 4px;
	}

	.workout-change-list dl > div {
		display: grid;
		grid-template-columns: 58px 1fr;
		gap: 8px;
	}

	.workout-change-list dt,
	.workout-change-list p {
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
