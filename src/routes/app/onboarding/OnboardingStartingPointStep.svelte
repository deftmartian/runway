<script lang="ts">
	import type { OnboardingFieldErrors, OnboardingValues } from './onboarding-model';

	let {
		values = $bindable({} as OnboardingValues),
		errors,
		active
	}: { values?: OnboardingValues; errors: OnboardingFieldErrors; active: boolean } = $props();
	const errorFor = (field: keyof OnboardingValues | 'healthFlags') => errors[field];
</script>

<section
	class="setup-step"
	hidden={!active}
	id="onboarding-step-2"
	tabindex="-1"
	aria-labelledby="starting-step-heading"
>
	<header class="step-header">
		<p>Step 2 of 4</p>
		<h2 id="starting-step-heading">Starting point</h2>
		<span>Use work you could comfortably repeat now. Distance is never inferred from time.</span>
	</header>

	{#if values.startMode === 'foundation_to_goal' || values.startMode === 'foundation_only'}
		{#if values.startMode === 'foundation_only'}
			<input type="hidden" name="startMode" value="foundation_only" />
		{/if}
		<div class="foundation-summary">
			<strong>NHS Couch to 5K foundation</strong>
			<span>Nine weeks · three sessions per week · walk/run intervals · no assumed distance</span>
		</div>
	{/if}

	{#if values.startMode === 'established'}
		<div class="field-grid three-up">
			<label>
				Weekly distance (km)
				<input
					name="currentWeeklyDistanceKm"
					type="number"
					min="0"
					max="250"
					step="0.5"
					bind:value={values.currentWeeklyDistanceKm}
					aria-invalid={Boolean(errorFor('currentWeeklyDistanceKm'))}
					aria-describedby={errorFor('currentWeeklyDistanceKm')
						? 'weekly-distance-error'
						: undefined}
				/>
				{#if errorFor('currentWeeklyDistanceKm')}
					<span id="weekly-distance-error" class="field-error">
						{errorFor('currentWeeklyDistanceKm')}
					</span>
				{/if}
			</label>
			<label>
				Runs per week
				<input
					name="currentRunsPerWeek"
					type="number"
					min="0"
					max="5"
					step="1"
					bind:value={values.currentRunsPerWeek}
					aria-invalid={Boolean(errorFor('currentRunsPerWeek'))}
					aria-describedby={errorFor('currentRunsPerWeek') ? 'runs-per-week-error' : undefined}
				/>
				{#if errorFor('currentRunsPerWeek')}
					<span id="runs-per-week-error" class="field-error">{errorFor('currentRunsPerWeek')}</span>
				{/if}
			</label>
			<label>
				Longest recent run (km)
				<input
					name="longestRecentRunKm"
					type="number"
					min="0"
					max="80"
					step="0.5"
					bind:value={values.longestRecentRunKm}
					aria-invalid={Boolean(errorFor('longestRecentRunKm'))}
					aria-describedby={errorFor('longestRecentRunKm') ? 'longest-run-error' : undefined}
				/>
				{#if errorFor('longestRecentRunKm')}
					<span id="longest-run-error" class="field-error">{errorFor('longestRecentRunKm')}</span>
				{/if}
			</label>
		</div>
	{:else if values.startMode === 'calibration'}
		<label class="single-field">
			Comfortable total duration
			<select name="calibrationDurationMinutes" bind:value={values.calibrationDurationMinutes}>
				{#each [10, 15, 20, 25, 30] as minutes (minutes)}
					<option value={String(minutes)}>{minutes} minutes</option>
				{/each}
			</select>
			<span class="field-help">Distance will be observed from completed work, never assumed.</span>
			{#if errorFor('calibrationDurationMinutes')}
				<span class="field-error">{errorFor('calibrationDurationMinutes')}</span>
			{/if}
		</label>
	{/if}

	<div class="field-grid health-grid">
		<label>
			Running experience
			<select
				name="experience"
				bind:value={values.experience}
				required
				aria-invalid={Boolean(errorFor('experience'))}
				aria-describedby={errorFor('experience') ? 'experience-error' : undefined}
			>
				<option value="" disabled>Choose experience</option>
				<option value="new">New runner</option>
				<option value="returning">Returning runner</option>
				<option value="comfortable">Comfortable with regular running</option>
			</select>
			{#if errorFor('experience')}
				<span id="experience-error" class="field-error">{errorFor('experience')}</span>
			{/if}
		</label>
		<section class="health-context-panel" aria-labelledby="health-context-heading">
			<div class="health-context-heading">
				<h3 id="health-context-heading">Health and running limits</h3>
				<p>
					Select what is true now. These answers change the ramp assessment or whether workouts can
					be scheduled. Effort that merely feels hard is recorded after a run, not here.
				</p>
			</div>
			<div class="health-flags">
				<label>
					<input type="checkbox" name="recentInjury" bind:checked={values.recentInjury} />
					<span>
						<strong>Recovering from an injury</strong>
						<small>
							{values.startMode === 'established'
								? "Lowers runway's distance-ramp limits; workouts can still be scheduled."
								: 'Adds a visible plan caution; timed sessions are not recalculated from this answer.'}
						</small>
					</span>
				</label>
				<label>
					<input type="checkbox" name="currentPain" bind:checked={values.currentPain} />
					<span>
						<strong>Pain is present now</strong>
						<small>Saves the goal without workouts until this is cleared.</small>
					</span>
				</label>
				<label>
					<input type="checkbox" name="recurringPain" bind:checked={values.recurringPain} />
					<span>
						<strong>Pain returns when I run</strong>
						<small>
							{values.startMode === 'established'
								? "Lowers runway's distance-ramp limits; workouts can still be scheduled."
								: 'Adds a visible plan caution; timed sessions are not recalculated from this answer.'}
						</small>
					</span>
				</label>
				<label>
					<input
						type="checkbox"
						name="medicalRestriction"
						bind:checked={values.medicalRestriction}
					/>
					<span>
						<strong>A clinician has limited or paused my running</strong>
						<small>Saves the goal without workouts until the limit is removed.</small>
					</span>
				</label>
			</div>
		</section>
	</div>
	<label class="single-field">
		Private context for your record <span class="optional">Optional</span>
		<textarea name="injuryNotes" maxlength="240" rows="3" bind:value={values.injuryNotes}
		></textarea>
		<span class="field-help">
			Stored privately with the training profile. Free text does not change plan calculations; only
			the selections above do.
		</span>
		{#if errorFor('injuryNotes')}
			<span class="field-error">{errorFor('injuryNotes')}</span>
		{/if}
	</label>
</section>
