<script lang="ts">
	import {
		minimumForStartMode,
		targetWindowHelp,
		type OnboardingFieldErrors,
		type OnboardingValues,
		type RaceStartMode,
		type TargetWindows
	} from './onboarding-model';

	let {
		values = $bindable({} as OnboardingValues),
		errors,
		windows,
		active,
		onchoosegoal,
		onchoosestartmode
	}: {
		values?: OnboardingValues;
		errors: OnboardingFieldErrors;
		windows: TargetWindows;
		active: boolean;
		onchoosegoal: (kind: OnboardingValues['goalKind']) => void;
		onchoosestartmode: (mode: RaceStartMode) => void;
	} = $props();

	const errorFor = (field: keyof OnboardingValues) => errors[field];
	const minimumTargetDate = $derived(minimumForStartMode(values.startMode, windows));
	const windowHelp = $derived(targetWindowHelp(values.startMode, values.timeZone));
</script>

<section
	class="setup-step"
	hidden={!active}
	id="onboarding-step-1"
	tabindex="-1"
	aria-labelledby="goal-step-heading"
>
	<header class="step-header">
		<p>Step 1 of 4</p>
		<h2 id="goal-step-heading">Goal</h2>
		<span>Choose the outcome. The plan remains editable later.</span>
	</header>

	<div
		class="choice-track two-up"
		aria-invalid={Boolean(errorFor('goalKind'))}
		aria-describedby={errorFor('goalKind') ? 'goal-kind-error' : undefined}
	>
		<label class:selected={values.goalKind === 'race'}>
			<input
				type="radio"
				name="goalKind"
				value="race"
				checked={values.goalKind === 'race'}
				onchange={() => {
					onchoosegoal('race');
				}}
			/>
			<strong>Race goal</strong>
			<span>Build toward a selected distance and date.</span>
		</label>
		<label class:selected={values.goalKind === 'foundation'}>
			<input
				type="radio"
				name="goalKind"
				value="foundation"
				checked={values.goalKind === 'foundation'}
				onchange={() => {
					onchoosegoal('foundation');
				}}
			/>
			<strong>30-minute foundation</strong>
			<span>Build to 30 minutes of continuous easy running.</span>
		</label>
	</div>
	{#if errorFor('goalKind')}
		<span id="goal-kind-error" class="field-error">{errorFor('goalKind')}</span>
	{/if}

	{#if values.goalKind === 'race'}
		<fieldset
			class="starting-path-fieldset"
			aria-describedby={errorFor('startMode')
				? 'starting-path-help starting-path-error'
				: 'starting-path-help'}
		>
			<legend>How you’re starting</legend>
			<p id="starting-path-help">
				Choose this before the race date. The path determines how much preparation time is required.
			</p>
			<div class="choice-track mode-track">
				<label class:selected={values.startMode === 'established'}>
					<input
						type="radio"
						name="startMode"
						value="established"
						checked={values.startMode === 'established'}
						required
						onchange={() => {
							onchoosestartmode('established');
						}}
					/>
					<strong>Established week</strong>
					<span>At least 3 km, two runs, and a recent longest run.</span>
				</label>
				<label class:selected={values.startMode === 'foundation_to_goal'}>
					<input
						type="radio"
						name="startMode"
						value="foundation_to_goal"
						checked={values.startMode === 'foundation_to_goal'}
						required
						onchange={() => {
							onchoosestartmode('foundation_to_goal');
						}}
					/>
					<strong>Foundation first</strong>
					<span>
						Complete the NHS nine-week run/walk phase, then keep at least eight weeks for the race
						plan.
					</span>
				</label>
				<label class:selected={values.startMode === 'calibration'}>
					<input
						type="radio"
						name="startMode"
						value="calibration"
						checked={values.startMode === 'calibration'}
						required
						onchange={() => {
							onchoosestartmode('calibration');
						}}
					/>
					<strong>Two-week baseline</strong>
					<span>
						Repeat a comfortable timed session for two weeks, then keep at least eight weeks for the
						race plan.
					</span>
				</label>
			</div>
			{#if errorFor('startMode')}
				<span id="starting-path-error" class="field-error">{errorFor('startMode')}</span>
			{/if}
		</fieldset>

		{#if values.startMode && values.startMode !== 'foundation_only'}
			<div class="field-grid">
				<label>
					Race distance
					<select
						name="raceDistance"
						bind:value={values.raceDistance}
						required
						aria-invalid={Boolean(errorFor('raceDistance'))}
						aria-describedby={errorFor('raceDistance') ? 'race-distance-error' : undefined}
					>
						<option value="" disabled>Choose distance</option>
						<option value="5k">5K</option>
						<option value="10k">10K</option>
						<option value="half">Half marathon</option>
						<option value="marathon">Marathon</option>
					</select>
					{#if errorFor('raceDistance')}
						<span id="race-distance-error" class="field-error">{errorFor('raceDistance')}</span>
					{/if}
				</label>
				<label>
					Target date
					<input
						type="date"
						name="targetDate"
						min={minimumTargetDate}
						max={windows.maximum}
						bind:value={values.targetDate}
						required
						aria-invalid={Boolean(errorFor('targetDate'))}
						aria-describedby={errorFor('targetDate')
							? 'target-window-help target-date-error'
							: 'target-window-help'}
					/>
					<span id="target-window-help" class="field-help">{windowHelp}</span>
					{#if errorFor('targetDate')}
						<span id="target-date-error" class="field-error">{errorFor('targetDate')}</span>
					{/if}
				</label>
			</div>
		{/if}
	{/if}

	{#if values.goalKind === 'foundation'}
		<input type="hidden" name="priority" value="finish_healthy" />
	{:else}
		<label class="single-field">
			Priority
			<select
				name="priority"
				bind:value={values.priority}
				aria-invalid={Boolean(errorFor('priority'))}
				aria-describedby={errorFor('priority') ? 'priority-error' : undefined}
			>
				<option value="finish_healthy">Lower ramp</option>
				<option value="consistency">Build consistency</option>
			</select>
			{#if errorFor('priority')}
				<span id="priority-error" class="field-error">{errorFor('priority')}</span>
			{/if}
		</label>
	{/if}
</section>
