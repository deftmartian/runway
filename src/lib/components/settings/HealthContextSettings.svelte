<script lang="ts">
	import { enhance } from '$app/forms';
	import type { SettingsActionEnhancer, SettingsFormState, SettingsProfile } from './types';

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

	let recentInjury = $state(false);
	let currentPain = $state(false);
	let recurringPain = $state(false);
	let medicalRestriction = $state(false);
	let injuryNotes = $state('');
	let healthContextOpen = $state(false);
	const savedContextCount = $derived(
		[
			profile.injuryFlags.recentInjury,
			profile.injuryFlags.currentPain,
			profile.injuryFlags.recurringPain,
			profile.injuryFlags.medicalRestriction,
			Boolean(profile.injuryFlags.notes)
		].filter(Boolean).length
	);

	$effect(() => {
		const saved = profile.injuryFlags;
		recentInjury = saved.recentInjury;
		currentPain = saved.currentPain;
		recurringPain = saved.recurringPain;
		medicalRestriction = saved.medicalRestriction;
		injuryNotes = saved.notes;
	});

	$effect(() => {
		if (form.scope === 'healthContext' && form.message) healthContextOpen = true;
	});
</script>

<details class="settings-group settings-control" bind:open={healthContextOpen}>
	<summary
		><span
			><strong>Health and running limits</strong><small
				>{savedContextCount === 0 ? 'Nothing saved' : 'Saved privately'}</small
			></span
		></summary
	>
	<div class="control-body">
		<div class="group-heading">
			<p>
				Update or remove the context used in future plan checks. Saving here does not rewrite the
				current plan or diagnose an injury.
			</p>
		</div>
		<form
			class="health-context-form"
			method="post"
			action="?/updateHealthContext"
			use:enhance={enhanceSettingsAction('healthContext')}
			aria-busy={settingsActionPending === 'healthContext'}
		>
			<fieldset class="health-flags">
				<legend class="visually-hidden">Health and running limits</legend>
				<label>
					<input type="checkbox" name="recentInjury" bind:checked={recentInjury} />
					<span
						><strong>Recovering from an injury</strong><small
							>Adds caution to future plan checks.</small
						></span
					>
				</label>
				<label>
					<input type="checkbox" name="currentPain" bind:checked={currentPain} />
					<span
						><strong>Pain is present now</strong><small
							>Prevents a new generated phase while selected.</small
						></span
					>
				</label>
				<label>
					<input type="checkbox" name="recurringPain" bind:checked={recurringPain} />
					<span
						><strong>Pain has returned during past runs</strong><small
							>Adds caution to future plan checks. Select current pain separately.</small
						></span
					>
				</label>
				<label>
					<input type="checkbox" name="medicalRestriction" bind:checked={medicalRestriction} />
					<span
						><strong>A clinician has limited or paused my running</strong><small
							>Prevents a new generated phase while selected.</small
						></span
					>
				</label>
			</fieldset>
			<label>
				Private profile context <span class="optional">Optional</span>
				<textarea name="injuryNotes" maxlength="240" rows="3" bind:value={injuryNotes}></textarea>
				<span class="field-note"
					>Stored privately with the training profile. Free text does not change plan calculations.</span
				>
			</label>
			<div class="form-actions">
				<button class="primary" disabled={settingsActionPending !== null}
					>{settingsActionPending === 'healthContext' ? 'Saving…' : 'Save health context'}</button
				>
				{#if form.scope === 'healthContext' && form.message}<p
						class="message compact-message"
						role="status"
						aria-live="polite"
					>
						{form.message}
					</p>{/if}
			</div>
		</form>

		{#if savedContextCount > 0}
			<form
				class="clear-health-context"
				method="post"
				action="?/updateHealthContext"
				use:enhance={enhanceSettingsAction(
					'healthContext',
					'Clear every saved health and running-limit selection and the private note? This does not rewrite the current plan.'
				)}
			>
				<button class="ghost" disabled={settingsActionPending !== null}>Clear health context</button
				>
			</form>
		{/if}
	</div>
</details>
