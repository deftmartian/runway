<script lang="ts">
	import { enhance } from '$app/forms';
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
		auditRetention,
		form,
		privacyClientMessage,
		accountDeletionClientMessage,
		privacyAttention,
		settingsActionPending,
		enhanceSettingsAction
	}: {
		profile: SettingsProfile;
		user: SettingsUser;
		auditRetention: { enabled: boolean; retentionDays: number | null };
		form: SettingsFormState;
		privacyClientMessage: string;
		accountDeletionClientMessage: string;
		privacyAttention: 'routeMaps' | 'activityData' | null;
		settingsActionPending: string | null;
		enhanceSettingsAction: SettingsActionEnhancer;
	} = $props();

	let routeDataMode = $state<'discard' | 'private'>(untrack(() => profile.routeDataMode));
	let routeMapsOpen = $state(false);
	let deletionOpen = $state(false);
	let accountDeletionOpen = $state(false);

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
		if (accountDeletionClientMessage || (form.scope === 'accountDeletion' && form.message)) {
			accountDeletionOpen = true;
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
			<form method="post" action="/app/settings/export.json">
				<button class="button">Export training data</button>
			</form>
		</div>
		<details class="settings-control data-control">
			<summary
				><span
					><strong>Audit history</strong><small
						>{auditRetention.enabled
							? `${auditRetention.retentionDays} day retention`
							: 'No automatic expiry'}</small
					></span
				></summary
			>
			<div class="control-body">
				<p class="section-note">
					runway keeps a private record of security and training-data changes
					{auditRetention.enabled
						? ` for up to ${auditRetention.retentionDays} days.`
						: ' until you delete the related data or account.'}
					It can include event times, opaque record IDs, counts, and decisions, but not route coordinates,
					imported filenames, health-note text, passwords, tokens, or import credentials. Retained audit
					rows are included in your training-data export.
				</p>
			</div>
		</details>
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
		<details class="settings-control data-control danger-control" bind:open={accountDeletionOpen}>
			<summary><span><strong>Account deletion</strong><small>Permanent</small></span></summary>
			<div class="control-body">
				<p class="section-note">
					This permanently deletes the account, training plans, workouts, feedback, activities,
					imports, saved routes, health context, security credentials, and sessions. Browser folder
					access is cleared before the deletion request is sent. This cannot be undone.
				</p>
				{#if accountDeletionClientMessage}<p class="message bad-message" role="alert">
						{accountDeletionClientMessage}
					</p>{/if}
				{#if form.scope === 'accountDeletion' && form.message}<p
						class="message bad-message"
						role="alert"
					>
						{form.message}
					</p>{/if}
				<form
					method="post"
					action="?/deleteAccount"
					use:enhance={enhanceSettingsAction('deleteAccount')}
					aria-busy={settingsActionPending === 'deleteAccount'}
				>
					<input type="hidden" name="browserFolderDataCleared" value="" />
					<label
						>Type DELETE to confirm<input
							name="confirmation"
							type="text"
							autocomplete="off"
							spellcheck="false"
							pattern="DELETE"
							required
						/></label
					>
					<button class="danger" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'deleteAccount'
							? 'Deleting account…'
							: 'Delete account permanently'}</button
					>
				</form>
			</div>
		</details>
		<div class="mobile-account-actions">
			<AccountActions email={user.email} context="settings" showTheme={false} />
		</div>
	</div>
</section>
