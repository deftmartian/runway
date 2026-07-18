<script lang="ts">
	import { weekdays, type OnboardingFieldErrors, type OnboardingValues } from './onboarding-model';

	let {
		values = $bindable({} as OnboardingValues),
		errors,
		active,
		ontimezoneinput
	}: {
		values?: OnboardingValues;
		errors: OnboardingFieldErrors;
		active: boolean;
		ontimezoneinput: (zone: string) => void;
	} = $props();
	const errorFor = (field: keyof OnboardingValues) => errors[field];
</script>

<section
	class="setup-step"
	hidden={!active}
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
			<label class:selected={values.availability.includes(day.value)}>
				<input
					type="checkbox"
					name="availability"
					value={day.value}
					bind:group={values.availability}
					aria-invalid={Boolean(errorFor('availability'))}
				/>
				<span aria-hidden="true">{day.short}</span>
				<span class="sr-only">{day.label}</span>
			</label>
		{/each}
	</fieldset>
	{#if errorFor('availability')}
		<span id="availability-error" class="field-error block-error">{errorFor('availability')}</span>
	{/if}

	{#if values.startMode === 'established'}
		<label class="single-field">
			Preferred long-run day
			<select
				name="preferredLongRunDay"
				bind:value={values.preferredLongRunDay}
				aria-invalid={Boolean(errorFor('preferredLongRunDay'))}
				aria-describedby={errorFor('preferredLongRunDay') ? 'long-run-day-error' : undefined}
			>
				<option value="" disabled>Choose day</option>
				{#each weekdays.filter((day) => values.availability.includes(day.value)) as day (day.value)}
					<option value={String(day.value)}>{day.label}</option>
				{/each}
			</select>
			{#if errorFor('preferredLongRunDay')}
				<span id="long-run-day-error" class="field-error">{errorFor('preferredLongRunDay')}</span>
			{/if}
		</label>
	{:else}
		<input type="hidden" name="preferredLongRunDay" value="" />
	{/if}

	<label class="single-field">
		Training time zone
		<input
			name="timeZone"
			bind:value={values.timeZone}
			aria-invalid={Boolean(errorFor('timeZone'))}
			aria-describedby={errorFor('timeZone') ? 'time-zone-error' : undefined}
			oninput={(event) => {
				ontimezoneinput(event.currentTarget.value);
			}}
		/>
		<span class="field-help">Dates and missed-workout cutoffs use this zone.</span>
		{#if errorFor('timeZone')}
			<span id="time-zone-error" class="field-error">{errorFor('timeZone')}</span>
		{/if}
	</label>
</section>
