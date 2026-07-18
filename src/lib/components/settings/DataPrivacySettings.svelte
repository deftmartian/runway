<script lang="ts">
	import { enhance } from '$app/forms';
	import { resolve } from '$app/paths';
	import AccountActions from '$lib/components/AccountActions.svelte';
	import { untrack } from 'svelte';
	import type {
		SettingsActionEnhancer,
		SettingsFormState,
		SettingsProfile,
		SettingsUser
	} from './types';

	let {
		profile,
		user,
		form,
		privacyClientMessage,
		privacyAttention,
		settingsActionPending,
		enhanceSettingsAction
	}: {
		profile: SettingsProfile;
		user: SettingsUser;
		form: SettingsFormState;
		privacyClientMessage: string;
		privacyAttention: 'routeMaps' | 'activityData' | null;
		settingsActionPending: string | null;
		enhanceSettingsAction: SettingsActionEnhancer;
	} = $props();

	let routeDataMode = $state<'discard' | 'private'>(untrack(() => profile.routeDataMode));
	let routeMapsOpen = $state(false);
	let deletionOpen = $state(false);

	$effect(() => {
		if (privacyAttention === 'routeMaps' && form.scope === 'privacy' && form.message) {
			routeMapsOpen = true;
		}
		if (
			privacyClientMessage ||
			(privacyAttention === 'activityData' && form.scope === 'privacy' && form.message)
		) {
			deletionOpen = true;
		}
	});
</script>

<section class="settings-section" aria-labelledby="data-settings-heading">
	<header class="section-heading"><h2 id="data-settings-heading">Data and privacy</h2></header>
	<div class="settings-group">
		{#if privacyClientMessage}<p class="message bad-message" role="alert">
				{privacyClientMessage}
			</p>{/if}
		{#if form.scope === 'privacy' && form.message}<p
				class="message"
				role="status"
				aria-live="polite"
			>
				{form.message}
			</p>{/if}
		<details class="settings-control data-control" bind:open={routeMapsOpen}>
			<summary
				><span
					><strong>Route maps</strong><small
						>{profile.routeDataMode === 'private'
							? 'Route traces kept'
							: 'Route points discarded'}</small
					></span
				></summary
			>
			<div class="control-body">
				<form
					class="route-privacy-form"
					method="post"
					action="?/updateRouteDataMode"
					use:enhance={enhanceSettingsAction(
						'routeDataMode',
						routeDataMode === 'discard'
							? 'Stop retaining route points and remove every saved route map? Activity totals and heart-rate data remain.'
							: undefined
					)}
					aria-busy={settingsActionPending === 'routeDataMode'}
				>
					<fieldset>
						<legend class="visually-hidden">Route maps</legend>
						<p class="section-note">
							Route traces stay in your runway database. Maps render locally without contacting an
							external tile service.
						</p>
						<label
							><input
								type="radio"
								name="routeDataMode"
								value="private"
								bind:group={routeDataMode}
							/><span><strong>Keep the route trace</strong> for maps on future GPX imports.</span
							></label
						>
						<label
							><input
								type="radio"
								name="routeDataMode"
								value="discard"
								bind:group={routeDataMode}
							/><span><strong>Discard route points</strong> after calculating activity totals.</span
							></label
						>
					</fieldset>
					<button disabled={settingsActionPending !== null}
						>{settingsActionPending === 'routeDataMode'
							? 'Saving route privacy…'
							: 'Save route privacy'}</button
					>
				</form>
			</div>
		</details>
		<div class="data-actions">
			<a class="button" href={resolve('/app/settings/export.json')}>Export data</a>
		</div>
		<details class="settings-control data-control danger-control" bind:open={deletionOpen}>
			<summary
				><span><strong>Imported activity data</strong><small>Deletion options</small></span
				></summary
			>
			<div class="control-body">
				<p class="section-note">
					Deleting imported GPX activities also disconnects import folders and clears this browser’s
					folder access. Manual runs remain. File fingerprints stay behind as deletion markers, so
					the same private files are not silently imported again. This cannot be undone.
				</p>
				<form
					method="post"
					action="?/deleteActivityData"
					use:enhance={enhanceSettingsAction(
						'deleteActivityData',
						'Delete imported GPX activities and import records, disconnect import folders, and clear this browser’s folder access? Manual runs remain. This cannot be undone.'
					)}
					aria-busy={settingsActionPending === 'deleteActivityData'}
				>
					<button class="danger" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'deleteActivityData'
							? 'Deleting imported GPX activities…'
							: 'Delete imported GPX activities'}</button
					>
				</form>
			</div>
		</details>
		<div class="mobile-account-actions">
			<AccountActions email={user.email} context="settings" showTheme={false} />
		</div>
	</div>
</section>
