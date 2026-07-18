<script lang="ts">
	import { applyAction, enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { defaultHeartRateSettings, zoneFloors } from '$lib/training/heart-rate';
	import { notifyEnhancedFormSaved } from '$lib/pwa/lifecycle';
	import { sourceRefs, trainingSourceDetails } from '$lib/training/sources';
	import type { HeartRateSettings, SexForEstimates } from '$lib/training/types';
	import { onMount, untrack } from 'svelte';
	import type { SubmitFunction } from '@sveltejs/kit';
	import type { SettingsActionEnhancer, SettingsFormState, SettingsProfile } from './types';
	import HealthContextSettings from './HealthContextSettings.svelte';

	let {
		profile,
		form,
		settingsActionPending,
		enhanceSettingsAction
	}: {
		profile: SettingsProfile;
		form: SettingsFormState;
		settingsActionPending: string | null;
		enhanceSettingsAction: SettingsActionEnhancer;
	} = $props();

	type ProfileSource = HeartRateSettings['source'] | 'not_configured';
	const initialProfile = untrack(() => profile);
	const heartRateSources = [
		trainingSourceDetails[sourceRefs.tanakaHeartRate],
		trainingSourceDetails[sourceRefs.gulatiHeartRate],
		trainingSourceDetails[sourceRefs.ahaTargetHeartRates]
	];
	let timeZone = $state(initialProfile.timeZone ?? '');
	let sexForEstimates = $state<SexForEstimates>(initialProfile.sexForEstimates);
	let ageYears = $state(initialProfile.ageYears?.toString() ?? '');
	let maxHeartRateBpm = $state<number | undefined>(initialProfile.maxHeartRateBpm ?? undefined);
	let zone2FloorBpm = $state<number | undefined>(initialProfile.zone2FloorBpm ?? undefined);
	let zone3FloorBpm = $state<number | undefined>(initialProfile.zone3FloorBpm ?? undefined);
	let zone4FloorBpm = $state<number | undefined>(initialProfile.zone4FloorBpm ?? undefined);
	let zone5FloorBpm = $state<number | undefined>(initialProfile.zone5FloorBpm ?? undefined);
	let heartRateSettingsSource = $state<ProfileSource>(
		isProfileSource(initialProfile.heartRateSettingsSource)
			? initialProfile.heartRateSettingsSource
			: 'not_configured'
	);
	let trainingProfilePending = $state(false);
	let trainingProfileMessage = $state('');
	let heartRateOpen = $state(false);
	let timeZoneOpen = $state(false);
	const estimatedZones = $derived(calculateEstimatedZones(ageYears, sexForEstimates));

	function calculateEstimatedZones(age: string, sex: SexForEstimates) {
		const parsedAge = Number(age);
		if (!Number.isInteger(parsedAge) || parsedAge < 18 || parsedAge > 100) return null;
		return zoneFloors(defaultHeartRateSettings(parsedAge, sex));
	}
	const trainingProfileFormMessage = $derived(
		trainingProfileMessage || (form.scope === 'trainingProfile' ? (form.message ?? '') : '')
	);
	const zonesConfigured = $derived(
		[maxHeartRateBpm, zone2FloorBpm, zone3FloorBpm, zone4FloorBpm, zone5FloorBpm].every(
			(value) => typeof value === 'number' && Number.isFinite(value)
		)
	);
	const savedZonesConfigured = $derived(
		[
			profile.maxHeartRateBpm,
			profile.zone2FloorBpm,
			profile.zone3FloorBpm,
			profile.zone4FloorBpm,
			profile.zone5FloorBpm
		].every((value) => typeof value === 'number' && Number.isFinite(value))
	);
	const profileSourceLabel = (source: ProfileSource) =>
		source === 'not_configured'
			? 'Not configured'
			: source === 'estimated'
				? 'Age estimate'
				: 'Custom';
	const heartRateSettingsSourceLabel = $derived(profileSourceLabel(heartRateSettingsSource));
	const savedHeartRateSettingsSourceLabel = $derived(
		profileSourceLabel(
			isProfileSource(profile.heartRateSettingsSource)
				? profile.heartRateSettingsSource
				: 'not_configured'
		)
	);

	$effect(() => {
		if (trainingProfileFormMessage) heartRateOpen = true;
		if (form.scope === 'timeZone' && form.message) timeZoneOpen = true;
	});

	onMount(() => {
		if (!timeZone) timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
	});

	const enhanceTrainingProfile: SubmitFunction = ({ formElement }) => {
		trainingProfilePending = true;
		trainingProfileMessage = '';
		return async ({ result }) => {
			try {
				await applyAction(result);
				if (result.type === 'success') {
					notifyEnhancedFormSaved(formElement);
					trainingProfileMessage = formResultMessage(result.data, 'Training profile saved.');
					await invalidateAll();
				} else if (result.type === 'failure') {
					trainingProfileMessage = formResultMessage(
						result.data,
						'Training profile could not be saved.'
					);
				}
			} finally {
				trainingProfilePending = false;
			}
		};
	};

	function applyEstimatedZones(announce = true) {
		if (!estimatedZones) return;
		maxHeartRateBpm = estimatedZones.maxHeartRateBpm;
		zone2FloorBpm = estimatedZones.zone2FloorBpm;
		zone3FloorBpm = estimatedZones.zone3FloorBpm;
		zone4FloorBpm = estimatedZones.zone4FloorBpm;
		zone5FloorBpm = estimatedZones.zone5FloorBpm;
		heartRateSettingsSource = 'estimated';
		if (announce) trainingProfileMessage = 'Estimate applied. Save the profile to keep it.';
	}

	function syncEstimatedZones(age = ageYears, sex = sexForEstimates) {
		if (heartRateSettingsSource !== 'estimated' && heartRateSettingsSource !== 'not_configured') {
			return;
		}
		const zones = calculateEstimatedZones(age, sex);
		if (zones) {
			maxHeartRateBpm = zones.maxHeartRateBpm;
			zone2FloorBpm = zones.zone2FloorBpm;
			zone3FloorBpm = zones.zone3FloorBpm;
			zone4FloorBpm = zones.zone4FloorBpm;
			zone5FloorBpm = zones.zone5FloorBpm;
			heartRateSettingsSource = 'estimated';
			return;
		}
		maxHeartRateBpm = undefined;
		zone2FloorBpm = undefined;
		zone3FloorBpm = undefined;
		zone4FloorBpm = undefined;
		zone5FloorBpm = undefined;
		heartRateSettingsSource = 'not_configured';
	}

	function markZonesCustom() {
		heartRateSettingsSource = 'custom';
	}

	function isProfileSource(value: string): value is ProfileSource {
		return value === 'not_configured' || value === 'estimated' || value === 'custom';
	}

	function formResultMessage(data: unknown, fallback: string) {
		if (data && typeof data === 'object' && 'message' in data) {
			const message = (data as { message?: unknown }).message;
			if (typeof message === 'string') return message;
		}
		return fallback;
	}
</script>

<section class="settings-section" aria-labelledby="training-settings-heading">
	<header class="section-heading"><h2 id="training-settings-heading">Training</h2></header>

	<details class="settings-group settings-control" bind:open={heartRateOpen}>
		<summary
			><span
				><strong>Heart-rate zones</strong><small
					>{savedZonesConfigured ? savedHeartRateSettingsSourceLabel : 'Not configured'}</small
				></span
			></summary
		>
		<div class="control-body">
			<div class="group-heading">
				<p>
					Use an age-based estimate or enter measured values. Heart rate does not change the plan.
				</p>
			</div>
			<details class="estimate-sources">
				<summary>How estimates are calculated</summary>
				<p class="section-note">
					These adult population estimates are editable defaults, not a measured maximum.
				</p>
				<ul>
					{#each heartRateSources as source (source.url)}
						<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external evidence source -->
						<li><a href={source.url} target="_blank" rel="noreferrer">{source.label}</a></li>
					{/each}
				</ul>
			</details>
			<form
				class="training-profile-form"
				method="post"
				action="?/updateTrainingProfile"
				use:enhance={enhanceTrainingProfile}
			>
				<input type="hidden" name="heartRateSettingsSource" value={heartRateSettingsSource} />
				<div class="profile-form-grid">
					<fieldset class="profile-fieldset">
						<legend>Inputs</legend>
						<label
							>Sex used for estimates
							<select
								name="sexForEstimates"
								bind:value={sexForEstimates}
								onchange={(event) => {
									syncEstimatedZones(ageYears, event.currentTarget.value as SexForEstimates);
								}}
							>
								<option value="not_specified">Prefer not to say</option><option value="female"
									>Female</option
								><option value="male">Male</option>
							</select>
						</label>
						<label
							>Age<input
								name="ageYears"
								type="number"
								min="18"
								max="100"
								inputmode="numeric"
								placeholder="Optional"
								bind:value={ageYears}
								oninput={(event) => {
									syncEstimatedZones(event.currentTarget.value, sexForEstimates);
								}}
							/></label
						>
					</fieldset>
					<div class="estimate-panel" aria-live="polite">
						<div>
							<span class="field-note">Current source</span><strong
								>{heartRateSettingsSourceLabel}</strong
							>
						</div>
						{#if estimatedZones}
							<div>
								<span class="field-note">Age-based estimate</span><strong
									>{estimatedZones.maxHeartRateBpm} bpm max</strong
								><span class="field-note"
									>Z2 {estimatedZones.zone2FloorBpm} · Z3 {estimatedZones.zone3FloorBpm} · Z4 {estimatedZones.zone4FloorBpm}
									· Z5 {estimatedZones.zone5FloorBpm}</span
								>
							</div>
							<button
								type="button"
								onclick={() => {
									applyEstimatedZones();
								}}>Use estimate</button
							>
						{:else}<div>
								<strong>Estimate unavailable</strong><span class="field-note"
									>Enter an age from 18 to 100 to calculate one.</span
								>
							</div>{/if}
					</div>
					<fieldset class="profile-fieldset zone-fieldset">
						<legend>Zones</legend>
						<div class="zone-input-grid">
							<label
								>Max heart rate<input
									name="maxHeartRateBpm"
									type="number"
									min="120"
									max="230"
									inputmode="numeric"
									bind:value={maxHeartRateBpm}
									oninput={markZonesCustom}
									required
								/></label
							>
							<label
								>Zone 2 starts<input
									name="zone2FloorBpm"
									type="number"
									min="60"
									bind:value={zone2FloorBpm}
									oninput={markZonesCustom}
									required
								/></label
							>
							<label
								>Zone 3 starts<input
									name="zone3FloorBpm"
									type="number"
									min="70"
									bind:value={zone3FloorBpm}
									oninput={markZonesCustom}
									required
								/></label
							>
							<label
								>Zone 4 starts<input
									name="zone4FloorBpm"
									type="number"
									min="80"
									bind:value={zone4FloorBpm}
									oninput={markZonesCustom}
									required
								/></label
							>
							<label
								>Zone 5 starts<input
									name="zone5FloorBpm"
									type="number"
									min="90"
									bind:value={zone5FloorBpm}
									oninput={markZonesCustom}
									required
								/></label
							>
						</div>
					</fieldset>
				</div>
				<div class="form-actions">
					<button class="primary" disabled={trainingProfilePending || !zonesConfigured}
						>{trainingProfilePending ? 'Saving…' : 'Save training profile'}</button
					>
					{#if trainingProfileFormMessage}<p
							class="message compact-message"
							role="status"
							aria-live="polite"
						>
							{trainingProfileFormMessage}
						</p>{/if}
				</div>
			</form>
		</div>
	</details>

	<HealthContextSettings {profile} {form} {settingsActionPending} {enhanceSettingsAction} />

	<details class="settings-group settings-control" bind:open={timeZoneOpen}>
		<summary
			><span><strong>Time zone</strong><small>{profile.timeZone || 'Not set'}</small></span
			></summary
		>
		<div class="control-body">
			<div class="group-heading">
				<p>
					This controls calendar “today” and the dates assigned to future imports and manual runs.
					Changing it does not move existing saved activity dates.
				</p>
			</div>
			<form
				method="post"
				action="?/updateTimeZone"
				use:enhance={enhanceSettingsAction('timeZone')}
				aria-busy={settingsActionPending === 'timeZone'}
			>
				<label
					>Training time zone<input
						name="timeZone"
						type="text"
						autocomplete="off"
						placeholder="America/Halifax"
						bind:value={timeZone}
						required
					/></label
				>
				<div class="form-actions">
					<button class="primary" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'timeZone' ? 'Saving time zone…' : 'Save time zone'}</button
					>
					{#if form.scope === 'timeZone' && form.message}<p
							class="message compact-message"
							role="status"
							aria-live="polite"
						>
							{form.message}
						</p>{/if}
				</div>
			</form>
		</div>
	</details>
</section>
