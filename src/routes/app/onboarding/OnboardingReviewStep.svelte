<script lang="ts">
	import {
		distanceLabel,
		hasHealthCaution,
		healthBlocksScheduling,
		modeLabel,
		requiresConcentratedSchedule,
		selectedDayLabels,
		type OnboardingFieldErrors,
		type OnboardingValues
	} from './onboarding-model';

	let {
		values = $bindable({} as OnboardingValues),
		errors,
		active,
		hasActiveGoal
	}: {
		values?: OnboardingValues;
		errors: OnboardingFieldErrors;
		active: boolean;
		hasActiveGoal: boolean;
	} = $props();
	const errorFor = (field: keyof OnboardingValues) => errors[field];
	const healthBlocked = $derived(healthBlocksScheduling(values));
	const healthCaution = $derived(hasHealthCaution(values));
	const concentratedSchedule = $derived(requiresConcentratedSchedule(values));
	const selectedDays = $derived(selectedDayLabels(values.availability));
	const startingPoint = $derived(modeLabel(values));
</script>

<section
	class="setup-step review-step"
	hidden={!active}
	id="onboarding-step-4"
	tabindex="-1"
	aria-labelledby="review-step-heading"
>
	<header class="step-header">
		<p>Step 4 of 4</p>
		<h2 id="review-step-heading">Review</h2>
		<span>Recommendations are defaults. Future workouts remain editable.</span>
	</header>

	<div class="review-ledger">
		<div>
			<span>Goal</span>
			<strong>
				{values.goalKind === 'foundation'
					? 'Run 30 minutes continuously'
					: `${distanceLabel(values.raceDistance)} · ${values.targetDate || 'date needed'}`}
			</strong>
		</div>
		<div><span>Starting point</span><strong>{startingPoint}</strong></div>
		<div>
			<span>Available days</span><strong>{selectedDays.join(' · ') || 'Days needed'}</strong>
		</div>
		<div>
			<span>Priority</span>
			<strong>{values.priority === 'finish_healthy' ? 'Lower ramp' : 'Build consistency'}</strong>
		</div>
	</div>

	{#if healthBlocked}
		<div class="decision-note warning" role="status">
			<strong>Goal saved without workouts</strong>
			<span>
				Pain that is present now or a clinician-imposed running limit pauses workout scheduling.
				Update these answers when that changes.
			</span>
		</div>
	{:else if healthCaution}
		<div class="decision-note warning" role="status">
			<strong>Health caution included</strong>
			{#if values.startMode === 'established'}
				<span>
					Recovery or recurring pain lowers runway's distance-ramp limits. It does not determine
					whether running is appropriate or pause scheduling by itself.
				</span>
			{:else}
				<span>
					Recovery or recurring pain will remain visible with the plan. Timed prescriptions are not
					recalculated from this answer, and scheduling is not paused by it.
				</span>
			{/if}
		</div>
	{:else if values.startMode === 'foundation_to_goal' || values.startMode === 'foundation_only'}
		<div class="decision-note">
			<strong>Baseline confirmation required after week 9</strong>
			<span>
				Completed count, duration, distance, and longest activity will be shown for confirmation
				before any race phase is generated.
			</span>
		</div>
	{:else if values.startMode === 'calibration'}
		<div class="decision-note">
			<strong>Distance remains observational</strong>
			<span>
				Two weeks of identical timed sessions will be recorded before a baseline is offered for
				confirmation.
			</span>
		</div>
	{/if}

	{#if concentratedSchedule}
		<div class="decision-note warning" role="status">
			<strong>Two run days concentrate the weekly distance</strong>
			<span>
				runway's default for half-marathon and marathon goals is at least three run days. With two,
				each session carries more of the week.
			</span>
		</div>
		<label class="replace-confirmation">
			<input
				type="checkbox"
				name="confirmConcentratedSchedule"
				bind:checked={values.confirmConcentratedSchedule}
				aria-invalid={Boolean(errorFor('confirmConcentratedSchedule'))}
				aria-describedby={errorFor('confirmConcentratedSchedule')
					? 'confirm-concentration-help confirm-concentration-error'
					: 'confirm-concentration-help'}
			/>
			<span id="confirm-concentration-help">
				<strong>Use two run days anyway</strong> I have reviewed this concentration and want to create
				the editable plan.
			</span>
		</label>
		{#if errorFor('confirmConcentratedSchedule')}
			<span id="confirm-concentration-error" class="field-error block-error">
				{errorFor('confirmConcentratedSchedule')}
			</span>
		{/if}
	{/if}

	{#if hasActiveGoal}
		<label class="replace-confirmation">
			<input
				type="checkbox"
				name="confirmReplace"
				bind:checked={values.confirmReplace}
				aria-invalid={Boolean(errorFor('confirmReplace'))}
				aria-describedby={errorFor('confirmReplace') ? 'confirm-replace-error' : undefined}
			/>
			<span>
				<strong>Archive the current goal</strong> Its workouts and recorded activities remain in History.
			</span>
		</label>
		{#if errorFor('confirmReplace')}
			<span id="confirm-replace-error" class="field-error block-error">
				{errorFor('confirmReplace')}
			</span>
		{/if}
	{/if}
</section>
