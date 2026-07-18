<script lang="ts">
	import { enhance } from '$app/forms';
	import { flushSync, onMount, tick } from 'svelte';
	import { SvelteDate } from 'svelte/reactivity';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	const initial = readInitialValues();
	const initialMinimumTargetDate = readMinimumTargetDate();
	const initialMinimumCalibrationTargetDate = readMinimumCalibrationTargetDate();
	const initialMinimumFoundationTargetDate = readMinimumFoundationTargetDate();
	const initialMaximumTargetDate = readMaximumTargetDate();
	const steps = ['Goal', 'Starting point', 'Schedule', 'Review'] as const;
	const weekdays = [
		{ value: 1, short: 'Mon', label: 'Monday' },
		{ value: 2, short: 'Tue', label: 'Tuesday' },
		{ value: 3, short: 'Wed', label: 'Wednesday' },
		{ value: 4, short: 'Thu', label: 'Thursday' },
		{ value: 5, short: 'Fri', label: 'Friday' },
		{ value: 6, short: 'Sat', label: 'Saturday' },
		{ value: 0, short: 'Sun', label: 'Sunday' }
	] as const;

	let step = $state(0);
	let goalKind = $state(initial.goalKind);
	let startMode = $state(initial.startMode);
	let raceDistance = $state(initial.raceDistance);
	let targetDate = $state(initial.targetDate);
	let priority = $state(initial.priority);
	let currentWeeklyDistanceKm = $state(initial.currentWeeklyDistanceKm);
	let currentRunsPerWeek = $state(initial.currentRunsPerWeek);
	let longestRecentRunKm = $state(initial.longestRecentRunKm);
	let experience = $state(initial.experience);
	let calibrationDurationMinutes = $state(initial.calibrationDurationMinutes);
	let availability = $state<number[]>(initial.availability);
	let preferredLongRunDay = $state(initial.preferredLongRunDay);
	let timeZone = $state(initial.timeZone);
	let recentInjury = $state(initial.recentInjury);
	let currentPain = $state(initial.currentPain);
	let recurringPain = $state(initial.recurringPain);
	let medicalRestriction = $state(initial.medicalRestriction);
	let injuryNotes = $state(initial.injuryNotes);
	let confirmReplace = $state(initial.confirmReplace);
	let minimumEstablishedTargetDate = $state(initialMinimumTargetDate);
	let minimumCalibrationTargetDate = $state(initialMinimumCalibrationTargetDate);
	let minimumFoundationTargetDate = $state(initialMinimumFoundationTargetDate);
	let maximumTargetDate = $state(initialMaximumTargetDate);
	let isSubmitting = $state(false);
	let hydrated = $state(false);
	let clientMessage = $state('');

	function readInitialValues() {
		return form?.values ?? data.initialValues;
	}

	function readMinimumTargetDate() {
		return data.minimumTargetDate;
	}

	function readMinimumCalibrationTargetDate() {
		return data.minimumCalibrationTargetDate;
	}

	function readMinimumFoundationTargetDate() {
		return data.minimumFoundationTargetDate;
	}

	function readMaximumTargetDate() {
		return data.maximumTargetDate;
	}

	const fieldErrors = $derived(form?.fieldErrors ?? {});
	const healthBlocked = $derived(currentPain || medicalRestriction);
	const healthCaution = $derived(recentInjury || recurringPain);
	const selectedDays = $derived(
		weekdays.filter((day) => availability.includes(day.value)).map((day) => day.short)
	);
	const minimumTargetDate = $derived(minimumForStartMode(startMode));
	const targetWindowHelp = $derived(
		startMode === 'foundation_to_goal'
			? `17–52 weeks ahead in ${timeZone || 'your time zone'}: nine foundation weeks, then at least eight race-plan weeks.`
			: startMode === 'calibration'
				? `10–52 weeks ahead in ${timeZone || 'your time zone'}: two calibration weeks, then at least eight race-plan weeks.`
				: `8–52 weeks ahead in ${timeZone || 'your time zone'}.`
	);
	const modeLabel = $derived.by(() => {
		switch (startMode) {
			case 'foundation_to_goal':
				return 'Nine-week foundation, then confirm a race baseline';
			case 'foundation_only':
				return 'Nine-week foundation to 30 continuous minutes';
			case 'calibration':
				return `Two-week ${calibrationDurationMinutes || '—'} minute calibration`;
			case 'established':
				return 'Distance plan from an established week';
			default:
				return 'Starting path needed';
		}
	});

	onMount(() => {
		hydrated = true;
		if (!timeZone) timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
		updateTargetWindow(timeZone);
	});

	function chooseGoal(kind: 'race' | 'foundation') {
		goalKind = kind;
		if (kind === 'foundation') {
			startMode = 'foundation_only';
			raceDistance = '';
			targetDate = '';
		} else if (startMode === 'foundation_only') {
			startMode = '';
		}
	}

	function chooseStartMode(mode: 'established' | 'foundation_to_goal' | 'calibration') {
		const nextMinimum = minimumForStartMode(mode);
		if (targetDate && (targetDate < nextMinimum || targetDate > maximumTargetDate)) {
			targetDate = '';
			clientMessage = `${startingPathName(mode)} needs a different target date. Choose the path first, then set the date.`;
		} else {
			clientMessage = '';
		}
		startMode = mode;
	}

	function minimumForStartMode(mode: typeof startMode) {
		return mode === 'foundation_to_goal'
			? minimumFoundationTargetDate
			: mode === 'calibration'
				? minimumCalibrationTargetDate
				: minimumEstablishedTargetDate;
	}

	function startingPathName(mode: 'established' | 'foundation_to_goal' | 'calibration') {
		return mode === 'foundation_to_goal'
			? 'Foundation first'
			: mode === 'calibration'
				? 'Short calibration'
				: 'Established week';
	}

	function nextStep() {
		goToStep(Math.min(steps.length - 1, step + 1));
	}

	function previousStep() {
		step = Math.max(0, step - 1);
		requestAnimationFrame(focusStep);
	}

	function goToStep(index: number) {
		if (index > step) {
			const targetStep = validationStep();
			if (targetStep !== null && targetStep < index) {
				clientMessage = validationMessage(targetStep);
				void focusProblem(targetStep);
				return;
			}
		}
		clientMessage = '';
		step = index;
		requestAnimationFrame(focusStep);
	}

	function focusStep() {
		document.querySelector<HTMLElement>(`#onboarding-step-${step + 1}`)?.focus();
	}

	function updateTargetWindow(zone: string) {
		try {
			const parts = new Intl.DateTimeFormat('en-US', {
				timeZone: zone,
				year: 'numeric',
				month: '2-digit',
				day: '2-digit'
			}).formatToParts(new Date());
			const year = parts.find((part) => part.type === 'year')?.value;
			const month = parts.find((part) => part.type === 'month')?.value;
			const day = parts.find((part) => part.type === 'day')?.value;
			if (!year || !month || !day) return;
			const today = `${year}-${month}-${day}`;
			minimumEstablishedTargetDate = shiftDate(today, 8 * 7);
			minimumCalibrationTargetDate = shiftDate(today, 10 * 7);
			minimumFoundationTargetDate = shiftDate(today, 17 * 7);
			maximumTargetDate = shiftDate(today, 52 * 7 - 1);
		} catch {
			// The server supplies the field-scoped time-zone error.
		}
	}

	function shiftDate(value: string, days: number) {
		const date = new SvelteDate(`${value}T00:00:00.000Z`);
		date.setUTCDate(date.getUTCDate() + days);
		return date.toISOString().slice(0, 10);
	}

	function errorFor(field: string): string | undefined {
		return fieldErrors[field as keyof typeof fieldErrors];
	}

	function validationStep(): number | null {
		if (goalKind === 'race' && !startMode) return 0;
		if (
			goalKind === 'race' &&
			(!raceDistance ||
				!targetDate ||
				targetDate < minimumTargetDate ||
				targetDate > maximumTargetDate)
		) {
			return 0;
		}
		if (!experience) return 1;
		if (
			startMode === 'established' &&
			!healthBlocked &&
			(!numberInRange(currentWeeklyDistanceKm, 3, 250) ||
				!integerInRange(currentRunsPerWeek, 2, 5) ||
				!numberInRange(longestRecentRunKm, Number.EPSILON, 80))
		) {
			return 1;
		}
		if (startMode === 'calibration' && !integerInRange(calibrationDurationMinutes, 10, 30)) {
			return 1;
		}
		const requiredDays =
			startMode === 'foundation_to_goal' || startMode === 'foundation_only' ? 3 : 2;
		if (new Set(availability).size < requiredDays || !timeZone) return 2;
		if (
			startMode === 'established' &&
			(!preferredLongRunDay ||
				!availability.includes(Number(preferredLongRunDay)) ||
				availability.length < Number(currentRunsPerWeek))
		) {
			return 2;
		}
		if (data.activeGoal && !confirmReplace) return 3;
		return null;
	}

	function numberInRange(value: string | number, minimum: number, maximum: number) {
		const rawValue = String(value).trim();
		if (!rawValue) return false;
		const parsed = Number(rawValue);
		return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum;
	}

	function integerInRange(value: string | number, minimum: number, maximum: number) {
		if (!numberInRange(value, minimum, maximum)) return false;
		return Number.isInteger(Number(value));
	}

	function validationMessage(targetStep: number) {
		if (
			targetStep === 0 &&
			goalKind === 'race' &&
			targetDate &&
			(targetDate < minimumTargetDate || targetDate > maximumTargetDate)
		) {
			return startMode === 'foundation_to_goal'
				? 'Foundation first needs a race date 17 to 52 weeks away.'
				: startMode === 'calibration'
					? 'Calibration needs a race date 10 to 52 weeks away.'
					: 'Choose a race date 8 to 52 weeks away.';
		}
		if (
			targetStep === 2 &&
			startMode === 'established' &&
			availability.length < Number(currentRunsPerWeek)
		) {
			return 'Choose at least as many available days as current weekly runs.';
		}
		return 'Complete this step before continuing.';
	}

	function errorStep(): number | null {
		if (errorFor('startMode') || errorFor('raceDistance') || errorFor('targetDate')) return 0;
		if (
			errorFor('currentWeeklyDistanceKm') ||
			errorFor('currentRunsPerWeek') ||
			errorFor('longestRecentRunKm') ||
			errorFor('experience') ||
			errorFor('calibrationDurationMinutes') ||
			errorFor('injuryNotes') ||
			errorFor('healthFlags')
		) {
			return 1;
		}
		if (errorFor('availability') || errorFor('preferredLongRunDay') || errorFor('timeZone')) {
			return 2;
		}
		if (errorFor('confirmReplace')) return 3;
		return null;
	}

	async function focusProblem(targetStep: number) {
		step = targetStep;
		await tick();
		const container = document.querySelector<HTMLElement>(`#onboarding-step-${targetStep + 1}`);
		(
			container?.querySelector<HTMLElement>('[aria-invalid="true"]') ??
			container?.querySelector<HTMLElement>('input:invalid, select:invalid, textarea:invalid') ??
			container?.querySelector<HTMLElement>('input:required, select:required') ??
			container
		)?.focus();
	}

	function validateBeforeSubmit(event: SubmitEvent) {
		const targetStep = validationStep();
		if (targetStep === null) {
			clientMessage = '';
			return;
		}
		event.preventDefault();
		clientMessage = validationMessage(targetStep);
		void focusProblem(targetStep);
	}

	$effect(() => {
		if (!form?.message) return;
		const targetStep = errorStep();
		if (targetStep !== null) void focusProblem(targetStep);
	});

	function distanceLabel(value: string) {
		return (
			({ '5k': '5K', '10k': '10K', half: 'Half marathon', marathon: 'Marathon' } as const)[
				value as '5k'
			] ?? 'Race goal'
		);
	}
</script>

<main class="page onboarding-page">
	<header class="onboarding-header">
		<div>
			<p class="eyebrow">Plan setup</p>
			<h1>{data.activeGoal ? 'Change goal' : 'Build a plan'}</h1>
		</div>
		{#if data.activeGoal?.state === 'pending'}
			<p class="pending-note" role="status">
				Goal saved. No workouts will be created while pain is present now or a clinician has limited
				running.
			</p>
		{/if}
	</header>

	<nav class="step-rail" aria-label="Plan setup progress">
		{#each steps as label, index (label)}
			<button
				type="button"
				class:current={step === index}
				aria-current={step === index ? 'step' : undefined}
				onclick={() => {
					goToStep(index);
				}}
			>
				<span>{index + 1}</span>{label}
			</button>
		{/each}
	</nav>

	<form
		method="post"
		action="?/createPlan"
		novalidate
		aria-busy={isSubmitting}
		onsubmit={validateBeforeSubmit}
		use:enhance={() => {
			flushSync(() => (isSubmitting = true));
			return async ({ update }) => {
				await update();
				isSubmitting = false;
			};
		}}
	>
		{#if form?.message || clientMessage}
			<p class="message form-message" role="alert">{form?.message ?? clientMessage}</p>
		{/if}

		<fieldset disabled={!hydrated || isSubmitting}>
			<legend class="sr-only">Plan setup</legend>

			<section
				class="setup-step"
				hidden={step !== 0}
				id="onboarding-step-1"
				tabindex="-1"
				aria-labelledby="goal-step-heading"
			>
				<header class="step-header">
					<p>Step 1 of 4</p>
					<h2 id="goal-step-heading">Goal</h2>
					<span>Choose the outcome. The plan remains editable later.</span>
				</header>

				<div class="choice-track two-up">
					<label class:selected={goalKind === 'race'}>
						<input
							type="radio"
							name="goalKind"
							value="race"
							checked={goalKind === 'race'}
							onchange={() => {
								chooseGoal('race');
							}}
						/>
						<strong>Race goal</strong>
						<span>Build toward a selected distance and date.</span>
					</label>
					<label class:selected={goalKind === 'foundation'}>
						<input
							type="radio"
							name="goalKind"
							value="foundation"
							checked={goalKind === 'foundation'}
							onchange={() => {
								chooseGoal('foundation');
							}}
						/>
						<strong>30-minute foundation</strong>
						<span>Build to 30 minutes of continuous easy running.</span>
					</label>
				</div>

				{#if goalKind === 'race'}
					<fieldset
						class="starting-path-fieldset"
						aria-describedby={errorFor('startMode')
							? 'starting-path-help starting-path-error'
							: 'starting-path-help'}
					>
						<legend>Starting path</legend>
						<p id="starting-path-help">
							Choose this before the race date. The path determines how much preparation time is
							required.
						</p>
						<div class="choice-track mode-track">
							<label class:selected={startMode === 'established'}>
								<input
									type="radio"
									name="startMode"
									value="established"
									checked={startMode === 'established'}
									required={step === 0}
									onchange={() => {
										chooseStartMode('established');
									}}
								/>
								<strong>Established week</strong>
								<span>At least 3 km, two runs, and a recent longest run.</span>
							</label>
							<label class:selected={startMode === 'foundation_to_goal'}>
								<input
									type="radio"
									name="startMode"
									value="foundation_to_goal"
									checked={startMode === 'foundation_to_goal'}
									required={step === 0}
									onchange={() => {
										chooseStartMode('foundation_to_goal');
									}}
								/>
								<strong>Foundation first</strong>
								<span
									>Complete the NHS nine-week run/walk phase, then keep at least eight weeks for the
									race plan.</span
								>
							</label>
							<label class:selected={startMode === 'calibration'}>
								<input
									type="radio"
									name="startMode"
									value="calibration"
									checked={startMode === 'calibration'}
									required={step === 0}
									onchange={() => {
										chooseStartMode('calibration');
									}}
								/>
								<strong>Short calibration</strong>
								<span
									>Repeat a comfortable timed session for two weeks, then keep at least eight weeks
									for the race plan.</span
								>
							</label>
						</div>
						{#if errorFor('startMode')}<span id="starting-path-error" class="field-error"
								>{errorFor('startMode')}</span
							>{/if}
					</fieldset>

					{#if startMode && startMode !== 'foundation_only'}
						<div class="field-grid">
							<label>
								Race distance
								<select
									name="raceDistance"
									bind:value={raceDistance}
									required={step === 0}
									aria-invalid={Boolean(errorFor('raceDistance'))}
									aria-describedby={errorFor('raceDistance') ? 'race-distance-error' : undefined}
								>
									<option value="" disabled>Choose distance</option>
									<option value="5k">5K</option>
									<option value="10k">10K</option>
									<option value="half">Half marathon</option>
									<option value="marathon">Marathon</option>
								</select>
								{#if errorFor('raceDistance')}<span id="race-distance-error" class="field-error"
										>{errorFor('raceDistance')}</span
									>{/if}
							</label>
							<label>
								Target date
								<input
									type="date"
									name="targetDate"
									min={minimumTargetDate}
									max={maximumTargetDate}
									bind:value={targetDate}
									required={step === 0}
									aria-invalid={Boolean(errorFor('targetDate'))}
									aria-describedby={errorFor('targetDate')
										? 'target-window-help target-date-error'
										: 'target-window-help'}
								/>
								<span id="target-window-help" class="field-help">{targetWindowHelp}</span>
								{#if errorFor('targetDate')}<span id="target-date-error" class="field-error"
										>{errorFor('targetDate')}</span
									>{/if}
							</label>
						</div>
					{/if}
				{/if}

				<label class="single-field">
					Priority
					<select name="priority" bind:value={priority}>
						<option value="finish_healthy">Finish healthy</option>
						<option value="consistency">Build consistency</option>
					</select>
				</label>
			</section>

			<section
				class="setup-step"
				hidden={step !== 1}
				id="onboarding-step-2"
				tabindex="-1"
				aria-labelledby="starting-step-heading"
			>
				<header class="step-header">
					<p>Step 2 of 4</p>
					<h2 id="starting-step-heading">Starting point</h2>
					<span
						>Use work you could comfortably repeat now. Distance is never inferred from time.</span
					>
				</header>

				{#if startMode === 'foundation_to_goal' || startMode === 'foundation_only'}
					{#if startMode === 'foundation_only'}
						<input type="hidden" name="startMode" value="foundation_only" />
					{/if}
					<div class="foundation-summary">
						<strong>NHS Couch to 5K foundation</strong>
						<span
							>Nine weeks · three sessions per week · walk/run intervals · no assumed distance</span
						>
					</div>
				{/if}

				{#if startMode === 'established'}
					<div class="field-grid three-up">
						<label>
							Weekly distance (km)
							<input
								name="currentWeeklyDistanceKm"
								type="number"
								min="0"
								max="250"
								step="0.5"
								bind:value={currentWeeklyDistanceKm}
								aria-invalid={Boolean(errorFor('currentWeeklyDistanceKm'))}
								aria-describedby={errorFor('currentWeeklyDistanceKm')
									? 'weekly-distance-error'
									: undefined}
							/>
							{#if errorFor('currentWeeklyDistanceKm')}<span
									id="weekly-distance-error"
									class="field-error">{errorFor('currentWeeklyDistanceKm')}</span
								>{/if}
						</label>
						<label>
							Runs per week
							<input
								name="currentRunsPerWeek"
								type="number"
								min="0"
								max="5"
								step="1"
								bind:value={currentRunsPerWeek}
								aria-invalid={Boolean(errorFor('currentRunsPerWeek'))}
								aria-describedby={errorFor('currentRunsPerWeek')
									? 'runs-per-week-error'
									: undefined}
							/>
							{#if errorFor('currentRunsPerWeek')}<span id="runs-per-week-error" class="field-error"
									>{errorFor('currentRunsPerWeek')}</span
								>{/if}
						</label>
						<label>
							Longest recent run (km)
							<input
								name="longestRecentRunKm"
								type="number"
								min="0"
								max="80"
								step="0.5"
								bind:value={longestRecentRunKm}
								aria-invalid={Boolean(errorFor('longestRecentRunKm'))}
								aria-describedby={errorFor('longestRecentRunKm') ? 'longest-run-error' : undefined}
							/>
							{#if errorFor('longestRecentRunKm')}<span id="longest-run-error" class="field-error"
									>{errorFor('longestRecentRunKm')}</span
								>{/if}
						</label>
					</div>
				{:else if startMode === 'calibration'}
					<label class="single-field">
						Comfortable total duration
						<select name="calibrationDurationMinutes" bind:value={calibrationDurationMinutes}>
							{#each [10, 15, 20, 25, 30] as minutes (minutes)}
								<option value={String(minutes)}>{minutes} minutes</option>
							{/each}
						</select>
						<span class="field-help"
							>Distance will be observed from completed work, never assumed.</span
						>
						{#if errorFor('calibrationDurationMinutes')}<span class="field-error"
								>{errorFor('calibrationDurationMinutes')}</span
							>{/if}
					</label>
				{/if}

				<div class="field-grid health-grid">
					<label>
						Running experience
						<select
							name="experience"
							bind:value={experience}
							required={step === 1}
							aria-invalid={Boolean(errorFor('experience'))}
							aria-describedby={errorFor('experience') ? 'experience-error' : undefined}
						>
							<option value="" disabled>Choose experience</option>
							<option value="new">New runner</option>
							<option value="returning">Returning runner</option>
							<option value="comfortable">Comfortable with regular running</option>
						</select>
						{#if errorFor('experience')}<span id="experience-error" class="field-error"
								>{errorFor('experience')}</span
							>{/if}
					</label>
					<section class="health-context-panel" aria-labelledby="health-context-heading">
						<div class="health-context-heading">
							<h3 id="health-context-heading">Health and running limits</h3>
							<p>
								Select what is true now. These answers change plan risk or whether workouts can be
								scheduled. Effort that merely feels hard is recorded after a run, not here.
							</p>
						</div>
						<div class="health-flags">
							<label>
								<input type="checkbox" name="recentInjury" bind:checked={recentInjury} />
								<span>
									<strong>Recovering from an injury</strong>
									<small>Adds caution to plan risk; workouts can still be scheduled.</small>
								</span>
							</label>
							<label>
								<input type="checkbox" name="currentPain" bind:checked={currentPain} />
								<span>
									<strong>Pain is present now</strong>
									<small>Saves the goal without workouts until this is cleared.</small>
								</span>
							</label>
							<label>
								<input type="checkbox" name="recurringPain" bind:checked={recurringPain} />
								<span>
									<strong>Pain returns when I run</strong>
									<small>Adds caution to plan risk; workouts can still be scheduled.</small>
								</span>
							</label>
							<label>
								<input
									type="checkbox"
									name="medicalRestriction"
									bind:checked={medicalRestriction}
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
					Anything the plan should account for <span class="optional">Optional</span>
					<textarea name="injuryNotes" maxlength="240" rows="3" bind:value={injuryNotes}></textarea>
					<span class="field-help">Stored privately with the training profile.</span>
					{#if errorFor('injuryNotes')}<span class="field-error">{errorFor('injuryNotes')}</span
						>{/if}
				</label>
			</section>

			<section
				class="setup-step"
				hidden={step !== 2}
				id="onboarding-step-3"
				tabindex="-1"
				aria-labelledby="schedule-step-heading"
			>
				<header class="step-header">
					<p>Step 3 of 4</p>
					<h2 id="schedule-step-heading">Schedule</h2>
					<span>Choose days that are usually available. Workouts can be moved later.</span>
				</header>

				<fieldset
					class="day-choices"
					aria-describedby={errorFor('availability') ? 'availability-error' : undefined}
				>
					<legend>Available days</legend>
					{#each weekdays as day (day.value)}
						<label class:selected={availability.includes(day.value)}>
							<input
								type="checkbox"
								name="availability"
								value={day.value}
								bind:group={availability}
								aria-invalid={Boolean(errorFor('availability'))}
							/>
							<span aria-hidden="true">{day.short}</span>
							<span class="sr-only">{day.label}</span>
						</label>
					{/each}
				</fieldset>
				{#if errorFor('availability')}<span id="availability-error" class="field-error block-error"
						>{errorFor('availability')}</span
					>{/if}

				{#if startMode === 'established'}
					<label class="single-field">
						Preferred long-run day
						<select
							name="preferredLongRunDay"
							bind:value={preferredLongRunDay}
							aria-invalid={Boolean(errorFor('preferredLongRunDay'))}
							aria-describedby={errorFor('preferredLongRunDay') ? 'long-run-day-error' : undefined}
						>
							<option value="" disabled>Choose day</option>
							{#each weekdays.filter((day) => availability.includes(day.value)) as day (day.value)}
								<option value={String(day.value)}>{day.label}</option>
							{/each}
						</select>
						{#if errorFor('preferredLongRunDay')}<span id="long-run-day-error" class="field-error"
								>{errorFor('preferredLongRunDay')}</span
							>{/if}
					</label>
				{:else}
					<input type="hidden" name="preferredLongRunDay" value="" />
				{/if}

				<label class="single-field">
					Training time zone
					<input
						name="timeZone"
						bind:value={timeZone}
						aria-invalid={Boolean(errorFor('timeZone'))}
						aria-describedby={errorFor('timeZone') ? 'time-zone-error' : undefined}
						oninput={(event) => {
							updateTargetWindow(event.currentTarget.value);
						}}
					/>
					<span class="field-help">Dates and missed-workout cutoffs use this zone.</span>
					{#if errorFor('timeZone')}<span id="time-zone-error" class="field-error"
							>{errorFor('timeZone')}</span
						>{/if}
				</label>
			</section>

			<section
				class="setup-step review-step"
				hidden={step !== 3}
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
						<span>Goal</span><strong
							>{goalKind === 'foundation'
								? 'Run 30 minutes continuously'
								: `${distanceLabel(raceDistance)} · ${targetDate || 'date needed'}`}</strong
						>
					</div>
					<div><span>Starting point</span><strong>{modeLabel}</strong></div>
					<div>
						<span>Available days</span><strong>{selectedDays.join(' · ') || 'Days needed'}</strong>
					</div>
					<div>
						<span>Priority</span><strong
							>{priority === 'finish_healthy' ? 'Finish healthy' : 'Build consistency'}</strong
						>
					</div>
				</div>

				{#if healthBlocked}
					<div class="decision-note warning" role="status">
						<strong>Goal saved without workouts</strong>
						<span>
							Pain that is present now or a clinician-imposed running limit pauses workout
							scheduling. Update these answers when that changes.
						</span>
					</div>
				{:else if healthCaution}
					<div class="decision-note warning" role="status">
						<strong>Health caution included</strong>
						<span>
							Recovery or recurring pain will be carried into plan warnings and risk checks. It does
							not pause workout scheduling by itself.
						</span>
					</div>
				{:else if startMode === 'foundation_to_goal' || startMode === 'foundation_only'}
					<div class="decision-note">
						<strong>Baseline confirmation required after week 9</strong>
						<span
							>Completed count, duration, distance, and longest activity will be shown for
							confirmation before any race phase is generated.</span
						>
					</div>
				{:else if startMode === 'calibration'}
					<div class="decision-note">
						<strong>Distance remains observational</strong>
						<span
							>Two weeks of identical timed sessions will be recorded before a baseline is offered
							for confirmation.</span
						>
					</div>
				{/if}

				{#if data.activeGoal}
					<label class="replace-confirmation">
						<input
							type="checkbox"
							name="confirmReplace"
							bind:checked={confirmReplace}
							aria-invalid={Boolean(errorFor('confirmReplace'))}
							aria-describedby={errorFor('confirmReplace') ? 'confirm-replace-error' : undefined}
						/>
						<span
							><strong>Archive the current goal</strong> Its workouts and recorded activities remain in
							History.</span
						>
					</label>
					{#if errorFor('confirmReplace')}<span
							id="confirm-replace-error"
							class="field-error block-error">{errorFor('confirmReplace')}</span
						>{/if}
				{/if}
			</section>

			<div class="step-actions">
				{#if step > 0}
					<button class="button secondary" type="button" onclick={previousStep}>Back</button>
				{/if}
				{#if step < steps.length - 1}
					<button class="button primary" type="button" onclick={nextStep}>Continue</button>
				{:else}
					<button class="button primary" type="submit" disabled={isSubmitting}>
						{isSubmitting
							? 'Saving…'
							: healthBlocked
								? 'Save pending goal'
								: data.activeGoal
									? 'Replace active plan'
									: 'Create plan'}
					</button>
				{/if}
			</div>
		</fieldset>
	</form>
</main>

<style>
	.onboarding-page {
		max-width: 1080px;
	}

	.onboarding-header {
		display: flex;
		align-items: end;
		justify-content: space-between;
		gap: 24px;
		padding: 10px 0 24px;
		border-bottom: 1px solid var(--line);
	}

	.onboarding-header h1,
	.onboarding-header p,
	.step-header h2,
	.step-header p,
	.step-header span {
		margin: 0;
	}

	.onboarding-header h1 {
		font-size: clamp(2rem, 5vw, 3.4rem);
		line-height: 1;
	}

	.eyebrow,
	.step-header p {
		color: var(--accent);
		font-size: 0.73rem;
		font-weight: 780;
		letter-spacing: 0.13em;
		text-transform: uppercase;
	}

	.pending-note {
		max-width: 48ch;
		padding-left: 14px;
		border-left: 3px solid var(--review);
		color: var(--muted);
	}

	.step-rail {
		display: grid;
		grid-template-columns: repeat(4, 1fr);
		margin: 24px 0 42px;
		border-bottom: 1px solid var(--line);
	}

	.step-rail button {
		justify-content: flex-start;
		gap: 8px;
		min-height: 48px;
		padding: 8px 4px;
		border: 0;
		border-bottom: 3px solid transparent;
		border-radius: 0;
		background: transparent;
		color: var(--muted);
		font-weight: 680;
	}

	.step-rail button.current {
		border-bottom-color: var(--accent);
		color: var(--text);
	}

	.step-rail span {
		font-family: ui-monospace, monospace;
	}

	form,
	form > fieldset {
		margin: 0;
		padding: 0;
		border: 0;
	}

	.setup-step {
		display: grid;
		gap: 28px;
		min-width: 0;
		min-height: 470px;
	}

	.setup-step[hidden] {
		display: none;
	}

	.setup-step:focus {
		outline: 0;
	}

	.step-header {
		display: grid;
		gap: 7px;
		max-width: 720px;
	}

	.step-header h2 {
		font-size: clamp(1.65rem, 4vw, 2.4rem);
	}

	.step-header > span {
		color: var(--muted);
	}

	.choice-track {
		display: grid;
		gap: 1px;
		border: 1px solid var(--line);
		background: var(--line);
	}

	.choice-track.two-up {
		grid-template-columns: repeat(2, 1fr);
	}

	.choice-track.mode-track {
		grid-template-columns: repeat(3, 1fr);
	}

	.starting-path-fieldset {
		display: grid;
		gap: 12px;
		margin: 0;
		padding: 0;
		border: 0;
	}

	.starting-path-fieldset legend {
		padding: 0;
		font-weight: 690;
	}

	.starting-path-fieldset > p {
		margin: -4px 0 0;
		color: var(--muted);
		font-size: 0.88rem;
	}

	.choice-track label {
		display: flex;
		flex-direction: column;
		align-items: stretch;
		gap: 8px;
		min-width: 0;
		min-height: 126px;
		padding: 18px;
		background: var(--surface);
		cursor: pointer;
	}

	.choice-track label.selected {
		box-shadow: inset 0 3px var(--accent);
		background: var(--surface-strong);
	}

	.choice-track input {
		align-self: flex-start;
		width: 18px;
		height: 18px;
		margin: 0 0 8px;
	}

	.choice-track span,
	.foundation-summary span {
		color: var(--muted);
		font-size: 0.9rem;
		font-weight: 450;
	}

	.choice-track strong,
	.choice-track span,
	.review-ledger strong,
	.decision-note strong,
	.decision-note span {
		min-width: 0;
		overflow-wrap: anywhere;
	}

	.choice-track strong,
	.choice-track span {
		width: 100%;
		text-align: left;
	}

	.field-grid {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 20px;
	}

	.field-grid.three-up {
		grid-template-columns: repeat(3, minmax(0, 1fr));
	}

	.single-field {
		width: min(100%, 520px);
	}

	.field-grid > label,
	.single-field {
		display: grid;
		gap: 8px;
		align-content: start;
		font-weight: 690;
	}

	.field-help,
	.optional {
		color: var(--muted);
		font-size: 0.8rem;
		font-weight: 450;
	}

	.field-error {
		color: var(--danger);
		font-size: 0.82rem;
		font-weight: 650;
	}

	.block-error {
		display: block;
		margin-top: -18px;
	}

	.foundation-summary,
	.decision-note {
		display: grid;
		gap: 6px;
		padding: 18px 0 18px 18px;
		border-left: 3px solid var(--accent);
	}

	.decision-note.warning {
		border-left-color: var(--review);
	}

	.decision-note span {
		max-width: 68ch;
		color: var(--muted);
	}

	.health-grid {
		align-items: start;
	}

	.health-context-panel {
		grid-column: 1 / -1;
		display: grid;
		gap: 14px;
		padding-top: 4px;
	}

	.health-context-heading {
		display: grid;
		gap: 5px;
	}

	.health-context-heading h3,
	.health-context-heading p {
		margin: 0;
	}

	.health-context-heading p {
		max-width: 72ch;
		color: var(--muted);
		font-size: 0.88rem;
	}

	.health-flags {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 1px;
		border: 1px solid var(--line);
		background: var(--line);
	}

	.health-flags label {
		display: flex;
		align-items: flex-start;
		gap: 12px;
		min-height: 88px;
		padding: 15px;
		background: var(--surface);
		font-size: 0.9rem;
		font-weight: 500;
		cursor: pointer;
	}

	.health-flags input {
		flex: 0 0 auto;
		margin-top: 3px;
	}

	.health-flags span {
		display: grid;
		gap: 5px;
	}

	.health-flags strong {
		font-weight: 680;
	}

	.health-flags small {
		color: var(--muted);
		font-size: 0.8rem;
		line-height: 1.4;
	}

	.day-choices {
		display: grid;
		grid-template-columns: repeat(7, minmax(52px, 1fr));
		gap: 6px;
		margin: 0;
		padding: 0;
		border: 0;
	}

	.day-choices legend {
		grid-column: 1 / -1;
		margin-bottom: 8px;
		font-weight: 690;
	}

	.day-choices label {
		display: grid;
		place-items: center;
		min-height: 54px;
		border: 1px solid var(--line);
		border-radius: var(--radius-small);
		background: var(--surface);
		cursor: pointer;
	}

	.day-choices label.selected {
		border-color: var(--accent);
		background: color-mix(in oklab, var(--accent), transparent 88%);
	}

	.day-choices label:focus-within {
		outline: 3px solid var(--accent);
		outline-offset: 2px;
	}

	.day-choices input {
		position: absolute;
		opacity: 0;
		pointer-events: none;
	}

	.review-ledger {
		border-top: 1px solid var(--line);
	}

	.review-ledger > div {
		display: grid;
		grid-template-columns: minmax(130px, 0.35fr) 1fr;
		gap: 20px;
		min-width: 0;
		padding: 16px 0;
		border-bottom: 1px solid var(--line);
	}

	.review-ledger span {
		color: var(--muted);
	}

	.replace-confirmation {
		display: flex;
		gap: 12px;
		align-items: start;
		padding: 16px;
		border: 1px solid var(--review);
		border-radius: var(--radius-small);
	}

	.replace-confirmation input {
		width: 18px;
		height: 18px;
	}

	.replace-confirmation span {
		display: grid;
		gap: 4px;
	}

	.step-actions {
		display: flex;
		justify-content: flex-end;
		gap: 10px;
		margin-top: 32px;
		padding-top: 18px;
		border-top: 1px solid var(--line);
	}

	.form-message {
		margin-bottom: 20px;
	}

	@media (max-width: 760px) {
		.onboarding-header {
			display: grid;
		}

		.step-rail {
			margin-bottom: 28px;
		}

		.step-rail button {
			justify-content: center;
			font-size: 0;
		}

		.step-rail span {
			font-size: 0.78rem;
		}

		.choice-track.two-up,
		.choice-track.mode-track,
		.field-grid,
		.field-grid.three-up {
			grid-template-columns: 1fr;
		}

		.health-flags {
			grid-template-columns: 1fr;
		}

		.choice-track label {
			min-height: 0;
		}

		.setup-step {
			min-height: 0;
		}

		.day-choices {
			grid-template-columns: repeat(4, 1fr);
		}

		.review-ledger > div {
			grid-template-columns: 1fr;
			gap: 5px;
		}

		.step-actions .button {
			flex: 1;
		}
	}
</style>
