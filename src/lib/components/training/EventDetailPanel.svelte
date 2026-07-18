<script lang="ts">
	import { enhance } from '$app/forms';
	import WorkoutFeedbackForm from '$lib/components/WorkoutFeedbackForm.svelte';
	import ActivityVisuals from './ActivityVisuals.svelte';
	import WorkoutEditor from './WorkoutEditor.svelte';
	import { decisionLabel, presentConsequence } from '$lib/training/consequence-presentation';
	import { formatPace } from '$lib/training/format';
	import { trainingSourceDetails, type TrainingSourceRef } from '$lib/training/sources';
	import { presentLoadChangeAssessment } from '$lib/training/training-assessment';
	import type { SubmitFunction } from '@sveltejs/kit';
	import { onMount, tick } from 'svelte';
	import type { CalendarEvent, CalendarFormState, WorkoutCandidate } from './calendar-types';
	import { canRecordUnplannedRun } from './calendar-presentation';
	import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
	import type { PlanDecision } from '$lib/training/types';

	let {
		event,
		candidates = [],
		form,
		today,
		targetDate,
		hasActivePlan,
		futureWorkouts = [],
		onclose
	}: {
		event: CalendarEvent;
		candidates?: WorkoutCandidate[];
		form: CalendarFormState;
		today: string;
		targetDate: string;
		hasActivePlan: boolean;
		futureWorkouts?: TrainingCalendarWorkout[];
		onclose: () => void;
	} = $props();

	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const day = (date: string) =>
		new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
			weekday: 'long',
			month: 'long',
			day: 'numeric'
		});
	const shortDay = (date: string) =>
		new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
			weekday: 'short',
			month: 'short',
			day: 'numeric'
		});
	const dateDistanceDays = (left: string, right: string) =>
		Math.abs(
			(Date.parse(`${left}T00:00:00.000Z`) - Date.parse(`${right}T00:00:00.000Z`)) /
				(24 * 60 * 60 * 1000)
		);
	const duration = (seconds: number | null | undefined) => {
		if (!seconds) return '—';
		const minutes = Math.round(seconds / 60);
		if (minutes < 60) return `${minutes} min`;
		const hours = Math.floor(minutes / 60);
		const remainder = minutes % 60;
		return `${hours}h ${String(remainder).padStart(2, '0')}m`;
	};
	const intervalDuration = (seconds: number) => {
		if (seconds % 60 === 0) return `${seconds / 60} min`;
		if (seconds < 60) return `${seconds} sec`;
		return `${Math.floor(seconds / 60)} min ${seconds % 60} sec`;
	};
	const timedSteps = $derived.by(() => {
		const structure = event.workout?.intervalStructure;
		if (!structure) return [];
		return [
			...(structure.warmupSeconds > 0
				? [`Warm up · walk ${intervalDuration(structure.warmupSeconds)}`]
				: []),
			...structure.blocks.map((block) => {
				const segments = block.segments
					.map((segment) => `${segment.kind} ${intervalDuration(segment.durationSeconds)}`)
					.join(' / ');
				return `${block.repetitions}× ${segments}`;
			}),
			...(structure.cooldownSeconds > 0
				? [`Cool down · walk ${intervalDuration(structure.cooldownSeconds)}`]
				: [])
		];
	});
	const bpm = (value: number | null | undefined) => (value ? `${value} bpm` : 'No HR');
	const kmChange = (meters: number) => {
		const rounded = Math.round((meters / 1000) * 10) / 10;
		return `${rounded > 0 ? '+' : ''}${rounded} km`;
	};
	const decisionPreview = (decision: PlanDecision) => {
		if (decision === 'keep_plan') return 'No future workout changes.';
		const originDate = event.date;
		const candidates = futureWorkouts
			.filter((workout) => workout.scheduledDate > originDate)
			.sort(
				(left, right) =>
					left.scheduledDate.localeCompare(right.scheduledDate) || left.id.localeCompare(right.id)
			);
		const next = candidates[0];
		if (!next) return 'No compatible future workout is available.';
		const nextLabel = `${shortDay(next.scheduledDate)} · ${next.purpose}`;
		if (decision === 'next_rest') return `${nextLabel} becomes rest.`;
		if (decision === 'repeat_prescription') {
			return `${nextLabel} changes from ${next.targetDurationSeconds ? duration(next.targetDurationSeconds) : km(next.targetDistanceMeters)} to the recorded ${plannedPrescription.toLowerCase()} prescription. Weekly load and run spacing are checked before it is applied.`;
		}
		if (decision === 'rebalance_week') {
			const originTimestamp = Date.parse(`${originDate}T00:00:00.000Z`);
			const weekday = new Date(originTimestamp).getUTCDay();
			const endDate = new Date(
				originTimestamp + (weekday === 0 ? 0 : 7 - weekday) * 24 * 60 * 60 * 1_000
			)
				.toISOString()
				.slice(0, 10);
			const count = candidates.filter((workout) => workout.scheduledDate <= endDate).length || 1;
			return `The reduction is shared across ${count} remaining compatible workout${count === 1 ? '' : 's'} this week, starting with ${nextLabel}.`;
		}
		if (next.targetDurationSeconds) {
			const after = Math.max(
				600,
				next.targetDurationSeconds - Math.max(300, Math.round(next.targetDurationSeconds * 0.15))
			);
			return `${nextLabel} changes from ${duration(next.targetDurationSeconds)} to ${duration(after)}.`;
		}
		const reduction = Math.abs(decisionRecord?.consequence.nextRunAdjustmentMeters ?? 500) || 500;
		return `${nextLabel} changes from ${km(next.targetDistanceMeters)} to ${km(Math.max(500, next.targetDistanceMeters - reduction))}.`;
	};
	const decisionUnavailable = (decision: PlanDecision) => {
		if (decision === 'keep_plan') return false;
		const candidates = futureWorkouts.filter((workout) => workout.scheduledDate > event.date);
		if (candidates.length === 0) return true;
		if (decision === 'repeat_prescription') return !event.workout;
		if (decision !== 'rebalance_week') return false;
		const originTimestamp = Date.parse(`${event.date}T00:00:00.000Z`);
		const weekday = new Date(originTimestamp).getUTCDay();
		const endDate = new Date(
			originTimestamp + (weekday === 0 ? 0 : 7 - weekday) * 24 * 60 * 60 * 1_000
		)
			.toISOString()
			.slice(0, 10);
		return !candidates.some((workout) => workout.scheduledDate <= endDate);
	};
	const adjustmentDistance = $derived(
		event.workout?.adjustment
			? `${km(event.workout.adjustment.previousTargetDistanceMeters)} to ${km(
					event.workout.adjustment.newTargetDistanceMeters
				)}`
			: ''
	);
	const adjustmentSchedule = $derived.by(() => {
		const adjustment = event.workout?.adjustment;
		if (!adjustment?.previousScheduledDate || !adjustment.newScheduledDate) return '';
		if (adjustment.previousScheduledDate === adjustment.newScheduledDate) return '';
		return `${shortDay(adjustment.previousScheduledDate)} to ${shortDay(adjustment.newScheduledDate)}`;
	});
	const plannedPrescription = $derived(
		event.workout && event.workout.targetDistanceMeters > 0
			? km(event.workout.targetDistanceMeters)
			: event.workout?.targetDurationSeconds
				? duration(event.workout.targetDurationSeconds)
				: event.workout
					? 'Rest'
					: '—'
	);
	const actualDistance = $derived.by(() => {
		if (event.activity) return km(event.activity.distanceMeters);
		if (!event.feedback) return '—';
		if (event.workout?.status === 'skipped') return 'Skipped';
		return event.feedback.completedDistanceMeters === null
			? 'Recorded'
			: km(event.feedback.completedDistanceMeters);
	});
	const actualDurationSeconds = $derived(
		event.activity?.durationSeconds ?? event.feedback?.completedDurationSeconds ?? null
	);
	const actualPace = $derived.by(() => {
		if (event.activity) {
			const pace = formatPace(event.activity.averagePaceSecondsPerKm);
			return event.activity.source === 'gpx' ? `${pace} elapsed` : `${pace} reported`;
		}
		if (event.feedback?.completedDistanceMeters && event.feedback.completedDurationSeconds) {
			return formatPace(
				event.feedback.completedDurationSeconds / (event.feedback.completedDistanceMeters / 1_000)
			);
		}
		return null;
	});
	const recordedStatus = $derived.by(() => {
		switch (event.workout?.status) {
			case 'done':
				return 'Completed';
			case 'shortened':
				return 'Shortened';
			case 'skipped':
				return 'Skipped';
			default:
				return null;
		}
	});
	const workoutSources = $derived.by(() =>
		(event.workout?.sourceRefs ?? []).flatMap((sourceRef) => {
			const source = trainingSourceDetails[sourceRef as TrainingSourceRef];
			return source ? [{ id: sourceRef, ...source }] : [];
		})
	);
	const canEditPast = $derived(canRecordUnplannedRun(event, today));
	const canEditFutureWorkout = $derived(
		event.workout?.status === 'planned' &&
			event.workout.type !== 'race' &&
			!event.workout.isRemoved &&
			event.workout.scheduledDate >= today
	);
	const canAddFutureWorkout = $derived(
		hasActivePlan && event.date >= today && event.date <= targetDate
	);
	const manualAdjustment = $derived(
		event.workout?.adjustment &&
			['manual_edit', 'manual_add', 'manual_remove', 'rebalance'].includes(
				event.workout.adjustment.triggerType
			)
			? event.workout.adjustment
			: null
	);
	const defaultFeedbackStatus = $derived(
		event.workout && event.isRecordable && event.date < today && !event.activity
			? 'skipped'
			: 'done'
	);
	const nearbyCandidates = $derived.by(() =>
		event.activity
			? candidates.filter((candidate) => dateDistanceDays(candidate.scheduledDate, event.date) <= 3)
			: candidates
	);
	const decisionRecord = $derived.by(() => {
		if (event.feedback) {
			return {
				source: 'feedback' as const,
				sourceId: event.feedback.id,
				consequence: event.feedback.consequence
			};
		}
		if (event.activity?.consequence) {
			return {
				source: 'activity' as const,
				sourceId: event.activity.id,
				consequence: event.activity.consequence
			};
		}
		return null;
	});
	const eventForm = $derived.by(() => {
		if (!form?.scope) return null;
		const scope = form.scope;
		switch (scope.action) {
			case 'recordFeedback':
			case 'deleteFeedback':
			case 'previewWorkoutEdit':
			case 'applyWorkoutEdit':
			case 'previewWorkoutRemoval':
			case 'removeWorkout':
			case 'resetWorkout':
				return scope.workoutId === event.workout?.id ? form : null;
			case 'recordManualRun':
			case 'previewWorkoutAdd':
			case 'applyWorkoutAdd':
				return scope.date === event.date ? form : null;
			case 'undoWorkoutAdjustment':
				return scope.adjustmentId === event.workout?.adjustment?.id ? form : null;
			case 'applyPlanDecision':
				return scope.sourceId === decisionRecord?.sourceId ? form : null;
			case 'linkActivity':
			case 'unlinkActivity':
			case 'deleteActivity':
			case 'confirmActivityExtra':
			case 'updateActivityFeedback':
				return scope.activityId === event.activity?.id ? form : null;
		}
	});
	const feedbackConsequence = $derived(
		event.feedback ? presentConsequence(event.feedback.consequence) : null
	);
	const feedbackAssessment = $derived(
		event.feedback ? presentLoadChangeAssessment(event.feedback.consequence.risk) : null
	);
	const formConsequence = $derived(
		eventForm?.consequence ? presentConsequence(eventForm.consequence) : null
	);
	const formAssessment = $derived(
		eventForm?.consequence ? presentLoadChangeAssessment(eventForm.consequence.risk) : null
	);

	let panel: HTMLDivElement | undefined;
	let desktopInspector = $state(false);
	let focusedEventId = '';
	let pendingAction = $state<string | null>(null);

	const enhanceEventAction =
		(key: string, confirmation?: string): SubmitFunction =>
		({ cancel }) => {
			if (pendingAction || (confirmation && !confirm(confirmation))) {
				cancel();
				return;
			}
			pendingAction = key;
			return async ({ update }) => {
				try {
					await update();
				} finally {
					pendingAction = null;
				}
			};
		};

	$effect(() => {
		if (event.id === focusedEventId) return;
		focusedEventId = event.id;
		void tick().then(() => panel?.focus());
	});

	onMount(() => {
		const mediaQuery = window.matchMedia('(min-width: 1180px)');
		const updateInspectorMode = () => {
			desktopInspector = mediaQuery.matches;
		};
		updateInspectorMode();
		mediaQuery.addEventListener('change', updateInspectorMode);
		return () => {
			mediaQuery.removeEventListener('change', updateInspectorMode);
		};
	});

	$effect(() => {
		if (desktopInspector) return;
		const previousBodyOverflow = document.body.style.overflow;
		const previousHtmlOverflow = document.documentElement.style.overflow;
		document.body.style.overflow = 'hidden';
		document.documentElement.style.overflow = 'hidden';
		return () => {
			document.body.style.overflow = previousBodyOverflow;
			document.documentElement.style.overflow = previousHtmlOverflow;
		};
	});

	function visibleFocusableElements(): HTMLElement[] {
		if (!panel) return [];
		return Array.from(
			panel.querySelectorAll<HTMLElement>(
				'a[href], button:not([disabled]), details summary, input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
			)
		).filter((element) => element.offsetParent !== null);
	}

	function handleModalFocus(focusEvent: FocusEvent) {
		if (desktopInspector || !panel || panel.contains(focusEvent.target as Node)) return;
		(visibleFocusableElements()[0] ?? panel).focus();
	}

	function handleModalKeydown(keyboardEvent: KeyboardEvent) {
		if (keyboardEvent.key === 'Escape') {
			onclose();
			return;
		}

		if (desktopInspector || keyboardEvent.key !== 'Tab' || !panel) return;
		const focusable = visibleFocusableElements();

		if (focusable.length === 0) {
			keyboardEvent.preventDefault();
			panel.focus();
			return;
		}

		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		if (!panel.contains(document.activeElement)) {
			keyboardEvent.preventDefault();
			first?.focus();
			return;
		}
		if (keyboardEvent.shiftKey && document.activeElement === first) {
			keyboardEvent.preventDefault();
			last?.focus();
		} else if (!keyboardEvent.shiftKey && document.activeElement === last) {
			keyboardEvent.preventDefault();
			first?.focus();
		}
	}
</script>

<svelte:window onkeydown={handleModalKeydown} onfocusin={handleModalFocus} />

<div class="event-detail-backdrop" aria-hidden="true"></div>

<div
	id="event-detail-panel"
	class="event-detail-panel"
	role={desktopInspector ? 'region' : 'dialog'}
	aria-modal={desktopInspector ? undefined : 'true'}
	aria-labelledby="event-detail-heading"
	tabindex="-1"
	bind:this={panel}
>
	<div class="event-detail-header">
		<div>
			<div class="badge">{day(event.date)}</div>
			<h2 id="event-detail-heading" class="section-title">{event.title}</h2>
		</div>
		<button
			type="button"
			class="ghost close-button"
			aria-label="Close training detail"
			onclick={onclose}
		>
			Close
		</button>
	</div>

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

	{#if workoutSources.length > 0}
		<details class="training-source-details">
			<summary>Why this workout?</summary>
			<ul>
				{#each workoutSources as source (source.id)}
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
				<strong>{plannedPrescription}</strong>
				{#if event.workout}<small>{event.workout.type}</small>{/if}
			</dd>
		</div>
		{#if event.activity || event.feedback}
			<div>
				<dt>Actual</dt>
				<dd>
					<strong>{actualDistance}</strong>
					{#if actualPace}<small>{actualPace}</small>{/if}
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
					<strong>{duration(actualDurationSeconds)}</strong>
					{#if event.activity?.heartRateSummary}
						<small
							>{Math.round(event.activity.heartRateSummary.highShare * 100)}% in zones 4–5</small
						>
					{/if}
				</dd>
			</div>
		{/if}
	</dl>

	{#if event.activity}
		<ActivityVisuals
			id={`calendar-${event.activity.id}`}
			routeTrace={event.activity.routeTrace}
			heartRateSeries={event.activity.heartRateSeries}
			heartRateSummary={event.activity.heartRateSummary}
			averageHeartRate={event.activity.averageHeartRate}
			maxHeartRate={event.activity.maxHeartRate}
			durationSeconds={event.activity.durationSeconds}
		/>
		{#if event.activity.source === 'gpx' && !event.activity.routeTrace}
			<p class="muted activity-trace-note">
				This import predates saved route traces. Future GPX imports can include the route map.
			</p>
		{/if}
	{/if}

	{#if canEditFutureWorkout}
		<details
			class="feedback-details workout-edit-details"
			open={eventForm?.scope?.action === 'previewWorkoutEdit' ||
				eventForm?.scope?.action === 'applyWorkoutEdit'}
		>
			<summary>Edit planned workout</summary>
			<WorkoutEditor workout={event.workout} date={event.date} {form} {today} {targetDate} />
		</details>
	{/if}

	{#if canAddFutureWorkout}
		<details
			class="feedback-details workout-edit-details"
			open={eventForm?.scope?.action === 'previewWorkoutAdd' ||
				eventForm?.scope?.action === 'applyWorkoutAdd'}
		>
			<summary>{event.workout ? 'Add another planned workout' : 'Add planned workout'}</summary>
			<WorkoutEditor workout={null} date={event.date} {form} {today} {targetDate} />
		</details>
	{/if}

	{#if canEditFutureWorkout}
		<section class="event-actions workout-ledger-actions" aria-label="Workout plan controls">
			<form
				method="post"
				action="?/previewWorkoutRemoval"
				use:enhance={enhanceEventAction('preview-removal')}
			>
				<input type="hidden" name="workoutId" value={event.workout?.id} />
				<button class="ghost" disabled={pendingAction !== null}>Preview removal</button>
			</form>
			{#if manualAdjustment}
				<form
					method="post"
					action="?/resetWorkout"
					use:enhance={enhanceEventAction('reset-workout')}
				>
					<input type="hidden" name="workoutId" value={event.workout?.id} />
					<button class="ghost" disabled={pendingAction !== null}>Reset to generated</button>
				</form>
				<form
					method="post"
					action="?/undoWorkoutAdjustment"
					use:enhance={enhanceEventAction('undo-workout')}
				>
					<input type="hidden" name="adjustmentId" value={manualAdjustment.id} />
					<button class="ghost" disabled={pendingAction !== null}>Undo last change</button>
				</form>
			{/if}
		</section>
	{/if}

	{#if event.workout?.isRemoved && manualAdjustment}
		<div class="message compact-message">
			<strong>Removed from the current plan.</strong>
			<span
				>Undoing the removal restores the prescription that was in place immediately before it.</span
			>
			<form
				method="post"
				action="?/undoWorkoutAdjustment"
				use:enhance={enhanceEventAction('undo-removal')}
			>
				<input type="hidden" name="adjustmentId" value={manualAdjustment.id} />
				<button disabled={pendingAction !== null}>Undo removal</button>
			</form>
		</div>
	{/if}

	{#if eventForm?.scope?.action === 'previewWorkoutRemoval' && eventForm.preview && event.workout}
		<section class="edit-preview removal-preview">
			<h3>Before removing</h3>
			{#each eventForm.preview.weekChanges as week (week.weekId)}
				<p>
					Week {week.weekNumber}: {km(week.distanceBeforeMeters)} → {km(week.distanceAfterMeters)}; {duration(
						week.durationBeforeSeconds
					)} → {duration(week.durationAfterSeconds)}.
				</p>
			{/each}
			<form
				method="post"
				action="?/removeWorkout"
				use:enhance={enhanceEventAction('remove-workout')}
			>
				<input type="hidden" name="workoutId" value={event.workout.id} />
				<button class="bad" disabled={pendingAction !== null}>Remove workout</button>
			</form>
		</section>
	{/if}

	{#if event.workout && event.isRecordable}
		<details
			class="feedback-details event-feedback primary-event-action"
			open={eventForm?.scope?.action === 'recordFeedback' && eventForm.message !== undefined}
		>
			<summary aria-label={`Record ${day(event.date)} ${event.workout.purpose}`}>Record run</summary
			>
			<WorkoutFeedbackForm
				workoutId={event.workout.id}
				targetDistanceMeters={event.workout.targetDistanceMeters}
				targetDurationSeconds={event.workout.targetDurationSeconds}
				weekTargetDistanceMeters={event.workout.weekTargetDistanceMeters}
				workoutLabel={`${day(event.date)} ${event.workout.purpose}`}
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
			<summary aria-label={`Record an unplanned run for ${day(event.date)}`}
				>Record unplanned run</summary
			>
			<form
				method="post"
				action="?/recordManualRun"
				use:enhance={enhanceEventAction('record-manual')}
				aria-busy={pendingAction === 'record-manual'}
			>
				<input type="hidden" name="occurredDate" value={event.date} />
				<fieldset>
					<legend>Record {day(event.date)}</legend>
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
						Hard effort changes load advice; pain triggers the safety path. This run is outside the
						schedule and can reduce the next run.
					</p>
					<button
						aria-label={`Save unplanned run for ${day(event.date)}`}
						disabled={pendingAction !== null}
					>
						{pendingAction === 'record-manual' ? 'Saving…' : 'Save run'}
					</button>
				</fieldset>
			</form>
		</details>
	{/if}

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
				use:enhance={enhanceEventAction('update-activity-feedback')}
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
				<form
					method="post"
					action="?/unlinkActivity"
					use:enhance={enhanceEventAction('unlink-activity')}
				>
					<input type="hidden" name="activityId" value={event.activity.id} />
					<button disabled={pendingAction !== null}>
						{pendingAction === 'unlink-activity' ? 'Unlinking…' : 'Unlink activity'}
					</button>
				</form>
			{:else if nearbyCandidates.length > 0}
				<form
					method="post"
					action="?/linkActivity"
					use:enhance={enhanceEventAction('link-activity')}
					class="inline-action-form"
				>
					<input type="hidden" name="activityId" value={event.activity.id} />
					<label>
						Planned workout
						<select name="workoutId" required>
							{#each nearbyCandidates as workout (workout.id)}
								<option value={workout.id}>
									{shortDay(workout.scheduledDate)} · {workout.purpose} · {km(
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
					use:enhance={enhanceEventAction(
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
				use:enhance={enhanceEventAction(
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

	{#if event.feedback}
		<section class="event-actions saved-feedback-actions" aria-label="Saved workout result">
			<div>
				<h3>Saved result</h3>
				<p class="muted">
					<strong>{recordedStatus ?? 'Recorded'}</strong>
					· {event.feedback.feltHard ? 'Hard effort' : 'Effort not marked hard'}
					· {event.feedback.pain ? 'Pain reported' : 'No pain reported'}
				</p>
			</div>
			{#if event.feedback.canDelete}
				<form
					method="post"
					action="?/deleteFeedback"
					use:enhance={enhanceEventAction(
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
					<strong>Week {kmChange(event.feedback.consequence.weeklyDistanceDeltaMeters)}</strong>
					<strong>Next run {kmChange(event.feedback.consequence.nextRunAdjustmentMeters)}</strong>
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
					<form
						class="decision-option"
						method="post"
						action="?/applyPlanDecision"
						use:enhance={enhanceEventAction(
							`decision-${decision}`,
							decisionRecord.consequence.risk === 'unsafe' ||
								decisionRecord.consequence.risk === 'aggressive'
								? `Apply this ${decisionLabel(decision)} option despite the ${presentLoadChangeAssessment(decisionRecord.consequence.risk).label.toLowerCase()} assessment?`
								: undefined
						)}
					>
						<input type="hidden" name="source" value={decisionRecord.source} />
						<input type="hidden" name="sourceId" value={decisionRecord.sourceId} />
						<input type="hidden" name="decision" value={decision} />
						<p>{decisionPreview(decision)}</p>
						{#if decision === 'repeat_prescription' && !decisionUnavailable(decision)}
							<label class="decision-confirmation">
								<input type="checkbox" name="confirmRisk" required />
								I reviewed the replacement prescription and its possible load and spacing change.
							</label>
						{/if}
						<button
							class:primary={decision === decisionRecord.consequence.recommendedDecision}
							class:secondary={decision !== decisionRecord.consequence.recommendedDecision}
							disabled={pendingAction !== null || decisionUnavailable(decision)}
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
				<strong>Week {kmChange(eventForm.consequence.weeklyDistanceDeltaMeters)}</strong>
				<strong>Next run {kmChange(eventForm.consequence.nextRunAdjustmentMeters)}</strong>
				{#if formAssessment}<strong>{formAssessment.label}</strong>{/if}
			</span>
		</div>
	{/if}

	{#if event.workout && event.isRecordable && event.date < today && !event.activity && !event.feedback}
		<p class="message compact-message">
			No result is recorded for this past workout. Choose the result before saving.
		</p>
	{/if}
</div>
