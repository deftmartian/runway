<script lang="ts">
	import { resolve } from '$app/paths';
	import { applyAction, enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import AccountActions from '$lib/components/AccountActions.svelte';
	import InstallAppControl from '$lib/components/InstallAppControl.svelte';
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import LedgerRow from '$lib/components/visual/LedgerRow.svelte';
	import { authClient } from '$lib/auth-client';
	import { disconnectDeviceFolder } from '$lib/pwa/device-folder';
	import { sourceCodeUrl } from '$lib/project';
	import { defaultHeartRateSettings, zoneFloors } from '$lib/training/heart-rate';
	import { sourceRefs, trainingSourceDetails } from '$lib/training/sources';
	import type { HeartRateSettings, SexForEstimates } from '$lib/training/types';
	import { onMount, untrack } from 'svelte';
	import type { ActionData, PageData, SubmitFunction } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	const initialProfile = untrack(() => data.profile);
	const heartRateSources = [
		trainingSourceDetails[sourceRefs.tanakaHeartRate],
		trainingSourceDetails[sourceRefs.gulatiHeartRate],
		trainingSourceDetails[sourceRefs.ahaTargetHeartRates]
	];
	type ScopedForm = { message?: string; scope?: string };
	type ProfileSource = HeartRateSettings['source'] | 'not_configured';
	let passkeyName = $state('Primary security key');
	let passkeyMessage = $state('');
	let passkeyPending = $state(false);
	let settingsActionPending = $state<string | null>(null);
	let timeZone = $state(initialProfile.timeZone ?? '');
	let routeDataMode = $state<'discard' | 'private'>(initialProfile.routeDataMode);
	let sexForEstimates = $state<SexForEstimates>(initialProfile.sexForEstimates);
	let ageYears = $state(initialProfile.ageYears?.toString() ?? '');
	let maxHeartRateBpm = $state<number | undefined>(initialProfile.maxHeartRateBpm ?? undefined);
	let zone2FloorBpm = $state<number | undefined>(initialProfile.zone2FloorBpm ?? undefined);
	let zone3FloorBpm = $state<number | undefined>(initialProfile.zone3FloorBpm ?? undefined);
	let zone4FloorBpm = $state<number | undefined>(initialProfile.zone4FloorBpm ?? undefined);
	let zone5FloorBpm = $state<number | undefined>(initialProfile.zone5FloorBpm ?? undefined);
	let heartRateSettingsSource = $state<ProfileSource>(
		initialProfile.heartRateSettingsSource as ProfileSource
	);
	let trainingProfilePending = $state(false);
	let trainingProfileMessage = $state('');
	let privacyClientMessage = $state('');
	let totpQrCode = $state(untrack(() => form?.totpQrCode ?? ''));
	let totpManualKey = $state(untrack(() => form?.totpManualKey ?? ''));
	const estimatedZones = $derived.by(() => {
		const parsedAge = Number(ageYears);
		if (!Number.isInteger(parsedAge) || parsedAge < 18 || parsedAge > 100) return null;
		return zoneFloors(defaultHeartRateSettings(parsedAge, sexForEstimates));
	});
	const scopedForm = $derived((form ?? {}) as ScopedForm);
	const trainingProfileFormMessage = $derived(
		trainingProfileMessage ||
			(scopedForm.scope === 'trainingProfile' ? (scopedForm.message ?? '') : '')
	);
	const totpSetupInProgress = $derived(Boolean(totpQrCode || form?.setupPending));
	const zonesConfigured = $derived(
		[maxHeartRateBpm, zone2FloorBpm, zone3FloorBpm, zone4FloorBpm, zone5FloorBpm].every(
			(value) => typeof value === 'number' && Number.isFinite(value)
		)
	);
	const heartRateSettingsSourceLabel = $derived(
		heartRateSettingsSource === 'not_configured' ? 'not configured' : heartRateSettingsSource
	);

	onMount(() => {
		if (!timeZone) timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
	});

	$effect(() => {
		if (form?.totpQrCode) {
			totpQrCode = form.totpQrCode;
			totpManualKey = form.totpManualKey ?? '';
		}
		if (form?.backupCodes || data.user.twoFactorEnabled) {
			totpQrCode = '';
			totpManualKey = '';
		}
	});

	const enhanceTrainingProfile: SubmitFunction = () => {
		trainingProfilePending = true;
		trainingProfileMessage = '';
		return async ({ result }) => {
			try {
				await applyAction(result);
				if (result.type === 'success') {
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
		if (announce) {
			trainingProfileMessage = 'Estimate applied. Save the profile to keep it.';
		}
	}

	function syncEstimatedZones() {
		if (heartRateSettingsSource !== 'estimated' && heartRateSettingsSource !== 'not_configured') {
			return;
		}
		if (estimatedZones) {
			applyEstimatedZones(false);
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

	async function addPasskey() {
		if (passkeyPending) return;
		if (!globalThis.isSecureContext) {
			passkeyMessage =
				'Passkeys are unavailable here. Open runway over HTTPS or use another sign-in method.';
			return;
		}
		const name = passkeyName.trim();
		if (!name || name.length > 80) {
			passkeyMessage = 'Use a passkey name between 1 and 80 characters.';
			return;
		}
		passkeyPending = true;
		passkeyMessage = 'Waiting for browser passkey prompt…';
		try {
			const result = await authClient.passkey.addPasskey({ name });
			if (result.error) {
				passkeyMessage =
					'code' in result.error && result.error.code === 'SESSION_NOT_FRESH'
						? 'Sign out and sign in again before adding a passkey.'
						: 'Passkey setup failed.';
				return;
			}
			passkeyMessage = 'Passkey added.';
			await invalidateAll();
		} catch {
			passkeyMessage = 'Passkey setup could not finish. Try again.';
		} finally {
			passkeyPending = false;
		}
	}

	function enhanceSettingsAction(key: string, confirmation?: string): SubmitFunction {
		return async ({ cancel }) => {
			if (settingsActionPending || (confirmation && !globalThis.confirm(confirmation))) {
				cancel();
				return;
			}
			settingsActionPending = key;
			if (key === 'deleteActivityData') {
				privacyClientMessage = '';
				try {
					// Stop and forget any foreground scan before the server removes
					// activity data, so an in-flight import cannot recreate a record.
					await disconnectDeviceFolder(data.user.id);
				} catch {
					privacyClientMessage =
						'Imported GPX activities were not deleted because runway could not clear this browser’s folder access. Close other runway tabs and try again.';
					settingsActionPending = null;
					cancel();
					return;
				}
			}
			return async ({ result, update }) => {
				try {
					if (result.type !== 'success' && key === 'deleteActivityData') {
						privacyClientMessage =
							'The folder was disconnected, but imported GPX activities were not deleted. Try again, or reconnect the folder from Import.';
					}
					await update({
						reset: result.type === 'success',
						invalidateAll: result.type === 'success'
					});
				} finally {
					settingsActionPending = null;
				}
			};
		};
	}

	function formResultMessage(data: unknown, fallback: string) {
		if (data && typeof data === 'object' && 'message' in data) {
			const message = (data as { message?: unknown }).message;
			if (typeof message === 'string') return message;
		}
		return fallback;
	}
</script>

<main class="page settings-page">
	<header class="page-heading">
		<h1>Settings</h1>
		{#if scopedForm.message && !scopedForm.scope}
			<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
		{/if}
	</header>

	<section class="settings-section" aria-labelledby="training-settings-heading">
		<header class="section-heading">
			<h2 id="training-settings-heading">Training</h2>
		</header>

		<div class="settings-group">
			<div class="group-heading">
				<h3>Heart-rate zones</h3>
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
						<label>
							Sex used for estimates
							<select
								name="sexForEstimates"
								bind:value={sexForEstimates}
								onchange={syncEstimatedZones}
							>
								<option value="not_specified">Prefer not to say</option>
								<option value="female">Female</option>
								<option value="male">Male</option>
							</select>
						</label>
						<label>
							Age
							<input
								name="ageYears"
								type="number"
								min="18"
								max="100"
								inputmode="numeric"
								placeholder="Optional"
								bind:value={ageYears}
								oninput={syncEstimatedZones}
							/>
						</label>
					</fieldset>

					<div class="estimate-panel" aria-live="polite">
						<div>
							<span class="field-note">Current source</span>
							<strong>{heartRateSettingsSourceLabel}</strong>
						</div>
						{#if estimatedZones}
							<div>
								<span class="field-note">Age-based estimate</span>
								<strong>{estimatedZones.maxHeartRateBpm} bpm max</strong>
								<span class="field-note">
									Z2 {estimatedZones.zone2FloorBpm} · Z3 {estimatedZones.zone3FloorBpm} · Z4
									{estimatedZones.zone4FloorBpm} · Z5 {estimatedZones.zone5FloorBpm}
								</span>
							</div>
							<button
								type="button"
								onclick={() => {
									applyEstimatedZones();
								}}>Use estimate</button
							>
						{:else}
							<div>
								<strong>Estimate unavailable</strong>
								<span class="field-note">Enter an age from 18 to 100 to calculate one.</span>
							</div>
						{/if}
					</div>

					<fieldset class="profile-fieldset zone-fieldset">
						<legend>Zones</legend>
						<div class="zone-input-grid">
							<label>
								Max heart rate
								<input
									name="maxHeartRateBpm"
									type="number"
									min="120"
									max="230"
									inputmode="numeric"
									bind:value={maxHeartRateBpm}
									oninput={markZonesCustom}
									required
								/>
							</label>
							<label>
								Zone 2 starts
								<input
									name="zone2FloorBpm"
									type="number"
									min="60"
									bind:value={zone2FloorBpm}
									oninput={markZonesCustom}
									required
								/>
							</label>
							<label>
								Zone 3 starts
								<input
									name="zone3FloorBpm"
									type="number"
									min="70"
									bind:value={zone3FloorBpm}
									oninput={markZonesCustom}
									required
								/>
							</label>
							<label>
								Zone 4 starts
								<input
									name="zone4FloorBpm"
									type="number"
									min="80"
									bind:value={zone4FloorBpm}
									oninput={markZonesCustom}
									required
								/>
							</label>
							<label>
								Zone 5 starts
								<input
									name="zone5FloorBpm"
									type="number"
									min="90"
									bind:value={zone5FloorBpm}
									oninput={markZonesCustom}
									required
								/>
							</label>
						</div>
					</fieldset>
				</div>
				<div class="form-actions">
					<button class="primary" disabled={trainingProfilePending || !zonesConfigured}>
						{trainingProfilePending ? 'Saving…' : 'Save training profile'}
					</button>
					{#if trainingProfileFormMessage}
						<p class="message compact-message" role="status" aria-live="polite">
							{trainingProfileFormMessage}
						</p>
					{/if}
				</div>
			</form>
		</div>

		<div class="settings-group">
			<div class="group-heading">
				<h3>Time zone</h3>
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
				<label>
					Training time zone
					<input
						name="timeZone"
						type="text"
						autocomplete="off"
						placeholder="America/Halifax"
						bind:value={timeZone}
						required
					/>
				</label>
				<div class="form-actions">
					<button class="primary" disabled={settingsActionPending !== null}>
						{settingsActionPending === 'timeZone' ? 'Saving time zone…' : 'Save time zone'}
					</button>
					{#if scopedForm.scope === 'timeZone' && scopedForm.message}
						<p class="message compact-message" role="status" aria-live="polite">
							{scopedForm.message}
						</p>
					{/if}
				</div>
			</form>
		</div>
	</section>

	<section class="settings-section" aria-labelledby="security-settings-heading">
		<header class="section-heading">
			<h2 id="security-settings-heading">Account security</h2>
		</header>

		<div class="settings-ledger"><LedgerRow label="Signed in as" value={data.user.email} /></div>

		<details
			class="security-control"
			open={totpSetupInProgress || data.authCapabilities.localPassword}
		>
			<summary>
				<span>
					<strong>Authenticator app</strong>
					<small>
						{data.user.twoFactorEnabled
							? 'Enabled'
							: totpSetupInProgress
								? 'Setup pending'
								: 'Not enabled'}
					</small>
				</span>
			</summary>
			<div class="security-body">
				{#if scopedForm.scope === 'twoFactor' && scopedForm.message}
					<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
				{/if}
				{#if !data.authCapabilities.localPassword}
					<p class="section-note">
						{data.authCapabilities.oidc
							? 'Manage two-factor authentication through your sign-in provider.'
							: 'An authenticator app requires a local password account.'}
					</p>
				{/if}
				{#if totpQrCode}
					<div class="setup-qr">
						<strong>Scan the QR code</strong>
						<p class="section-note">
							Add runway to an authenticator app, then enter the six-digit code.
						</p>
						<img src={totpQrCode} alt="QR code for authenticator setup" />
						{#if totpManualKey}
							<p class="section-note">Cannot scan it? Enter this setup key manually:</p>
							<code>{totpManualKey}</code>
						{/if}
					</div>
				{/if}
				{#if (totpQrCode || form?.setupPending) && !data.user.twoFactorEnabled}
					{#if form?.setupPending && !totpQrCode}
						<p class="section-note">
							Enter a new code from the authenticator entry you just added.
						</p>
					{/if}
					<form
						method="post"
						action="?/verifySetupTotp"
						use:enhance={enhanceSettingsAction('verifySetupTotp')}
						aria-busy={settingsActionPending === 'verifySetupTotp'}
					>
						<label>
							Authenticator code
							<input
								name="code"
								inputmode="numeric"
								autocomplete="one-time-code"
								pattern="[0-9][0-9][0-9][0-9][0-9][0-9]"
								maxlength="6"
								required
							/>
						</label>
						<button class="primary" disabled={settingsActionPending !== null}>
							{settingsActionPending === 'verifySetupTotp' ? 'Verifying…' : 'Verify code'}
						</button>
					</form>
				{/if}
				{#if form?.backupCodes}
					<details class="setup-codes" open>
						<summary>Recovery codes</summary>
						<p class="section-note">Save these outside runway now. Each code works once.</p>
						<pre>{form.backupCodes.join('\n')}</pre>
					</details>
				{/if}
				{#if data.authCapabilities.localPassword && data.user.twoFactorEnabled}
					<form
						method="post"
						action="?/disableTwoFactor"
						use:enhance={enhanceSettingsAction('disableTwoFactor')}
						aria-busy={settingsActionPending === 'disableTwoFactor'}
					>
						<label>
							Password
							<input
								type="password"
								name="password"
								autocomplete="current-password"
								maxlength="128"
								required
							/>
						</label>
						<button class="danger" disabled={settingsActionPending !== null}>
							{settingsActionPending === 'disableTwoFactor'
								? 'Disabling…'
								: 'Disable authenticator'}
						</button>
					</form>
				{:else if data.authCapabilities.localPassword && !totpSetupInProgress}
					<form
						method="post"
						action="?/enableTwoFactor"
						use:enhance={enhanceSettingsAction('enableTwoFactor')}
						aria-busy={settingsActionPending === 'enableTwoFactor'}
					>
						<label>
							Password
							<input
								type="password"
								name="password"
								autocomplete="current-password"
								maxlength="128"
								required
							/>
						</label>
						<button class="primary" disabled={settingsActionPending !== null}>
							{settingsActionPending === 'enableTwoFactor' ? 'Starting…' : 'Set up authenticator'}
						</button>
					</form>
				{/if}
			</div>
		</details>

		<details
			class="security-control"
			open={Boolean(passkeyMessage || (scopedForm.scope === 'passkeys' && scopedForm.message))}
		>
			<summary>
				<span>
					<strong>Passkeys</strong>
					<small
						>{data.passkeys.length === 1
							? '1 registered'
							: `${data.passkeys.length} registered`}</small
					>
				</span>
			</summary>
			<div class="security-body">
				{#if scopedForm.scope === 'passkeys' && scopedForm.message}
					<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
				{/if}
				<p class="section-note">
					Passkeys can be added when runway is opened over HTTPS or localhost.
				</p>
				<label>
					Passkey name
					<input bind:value={passkeyName} maxlength="80" disabled={passkeyPending} />
				</label>
				<button class="primary" type="button" onclick={addPasskey} disabled={passkeyPending}>
					{passkeyPending ? 'Adding passkey…' : 'Add passkey'}
				</button>
				{#if passkeyMessage}<p class="message" role="status" aria-live="polite">
						{passkeyMessage}
					</p>{/if}
				<div class="passkey-list">
					{#each data.passkeys as passkey (passkey.id)}
						<div class="passkey-row">
							<div>
								<strong>{passkey.name ?? 'Passkey'}</strong>
								<span class="field-note">
									{passkey.deviceType}{passkey.backedUp ? ' · backed up' : ''}
								</span>
							</div>
							<form
								method="post"
								action="?/deletePasskey"
								use:enhance={enhanceSettingsAction(
									`deletePasskey:${passkey.id}`,
									'Remove this passkey from the account?'
								)}
								aria-busy={settingsActionPending === `deletePasskey:${passkey.id}`}
							>
								<input type="hidden" name="id" value={passkey.id} />
								<button class="danger" disabled={settingsActionPending !== null}>
									{settingsActionPending === `deletePasskey:${passkey.id}` ? 'Removing…' : 'Remove'}
								</button>
							</form>
						</div>
					{:else}
						<p class="section-note">No passkeys registered.</p>
					{/each}
				</div>
			</div>
		</details>
	</section>

	<section class="settings-section" aria-labelledby="appearance-settings-heading">
		<header class="section-heading">
			<h2 id="appearance-settings-heading">Appearance</h2>
		</header>
		<div class="settings-group">
			<LedgerRow label="Theme" value="Light or dark interface">
				{#snippet action()}<ThemeToggle />{/snippet}
			</LedgerRow>
		</div>
		<div class="settings-group">
			<InstallAppControl />
		</div>
	</section>

	<section class="settings-section" aria-labelledby="data-settings-heading">
		<header class="section-heading">
			<h2 id="data-settings-heading">Data and privacy</h2>
		</header>
		<div class="settings-group">
			{#if privacyClientMessage}
				<p class="message bad-message" role="alert">{privacyClientMessage}</p>
			{/if}
			{#if scopedForm.scope === 'privacy' && scopedForm.message}
				<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
			{/if}
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
					<legend>Route maps</legend>
					<p class="section-note">
						Route traces stay in your runway database. Maps render locally without contacting an
						external tile service.
					</p>
					<label>
						<input type="radio" name="routeDataMode" value="private" bind:group={routeDataMode} />
						<span><strong>Keep the route trace</strong> for maps on future GPX imports.</span>
					</label>
					<label>
						<input type="radio" name="routeDataMode" value="discard" bind:group={routeDataMode} />
						<span><strong>Discard route points</strong> after calculating activity totals.</span>
					</label>
				</fieldset>
				<button disabled={settingsActionPending !== null}>
					{settingsActionPending === 'routeDataMode'
						? 'Saving route privacy…'
						: 'Save route privacy'}
				</button>
			</form>
			<div class="data-actions">
				<a class="button" href={resolve('/app/settings/export.json')}>Export data</a>
				<form
					method="post"
					action="?/deleteActivityData"
					use:enhance={enhanceSettingsAction(
						'deleteActivityData',
						'Delete imported GPX activities and import records, disconnect import folders, and clear this browser’s folder access? Manual runs remain. This cannot be undone.'
					)}
					aria-busy={settingsActionPending === 'deleteActivityData'}
				>
					<button class="danger" disabled={settingsActionPending !== null}>
						{settingsActionPending === 'deleteActivityData'
							? 'Deleting imported GPX activities…'
							: 'Delete imported GPX activities'}
					</button>
				</form>
			</div>
			<p class="section-note">
				Deleting imported GPX activities also disconnects import folders and clears this browser’s
				folder access. Manual runs remain in your history. Non-reversible file fingerprints remain
				as deletion markers so the same private files are not silently imported again. This cannot
				be undone.
			</p>
			<div class="mobile-account-actions">
				<AccountActions email={data.user.email} context="settings" showTheme={false} />
			</div>
		</div>
	</section>

	<section class="settings-section" aria-labelledby="about-settings-heading">
		<header class="section-heading">
			<h2 id="about-settings-heading">About</h2>
		</header>
		<div class="settings-group">
			<LedgerRow label="Source code" value="GNU AGPL v3.0 only">
				{#snippet action()}
					<!-- eslint-disable-next-line svelte/no-navigation-without-resolve -- external corresponding-source repository -->
					<a class="button" href={sourceCodeUrl} target="_blank" rel="noreferrer">View source</a>
				{/snippet}
			</LedgerRow>
		</div>
	</section>
</main>

<style>
	.settings-page {
		display: grid;
		gap: 0;
		max-width: 1040px;
	}

	.settings-page :global(*) {
		min-width: 0;
	}

	.page-heading {
		padding: 8px 0 24px;
	}

	.page-heading h1,
	.section-heading h2,
	.group-heading h3 {
		margin: 0;
		letter-spacing: 0;
	}

	.page-heading h1 {
		font-size: clamp(2rem, 5vw, 3rem);
	}

	.page-heading .message {
		margin: 14px 0 0;
	}

	.settings-section {
		display: grid;
		grid-template-columns: minmax(150px, 210px) minmax(0, 1fr);
		column-gap: clamp(24px, 5vw, 64px);
		padding: 30px 0;
		border-top: 1px solid var(--line);
	}

	.section-heading {
		grid-column: 1;
		grid-row: 1 / span 20;
	}

	.section-heading h2 {
		font-size: 1rem;
	}

	.settings-group,
	.settings-ledger,
	.security-control {
		grid-column: 2;
	}

	.settings-group {
		display: grid;
		gap: 16px;
		padding-bottom: 28px;
	}

	.settings-group + .settings-group {
		padding-top: 28px;
		border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.settings-group:last-child {
		padding-bottom: 0;
	}

	.group-heading {
		display: grid;
		gap: 5px;
	}

	.group-heading h3 {
		font-size: 1rem;
	}

	.group-heading p,
	.section-note {
		max-width: 68ch;
		margin: 0;
		color: var(--muted);
		font-size: 0.93rem;
		line-height: 1.5;
	}

	.field-note {
		color: var(--muted);
		font-size: 0.86rem;
		line-height: 1.4;
	}

	.estimate-sources {
		width: fit-content;
		max-width: 100%;
	}

	.estimate-sources summary,
	.setup-codes summary {
		font-weight: 680;
		cursor: pointer;
	}

	.estimate-sources[open] {
		width: 100%;
	}

	.estimate-sources p,
	.estimate-sources ul {
		margin-top: 12px;
	}

	.training-profile-form {
		gap: 20px;
	}

	.profile-form-grid {
		gap: 20px 24px;
	}

	.profile-fieldset,
	.estimate-panel {
		padding: 0;
		border: 0;
		border-radius: 0;
		background: transparent;
	}

	.profile-fieldset legend {
		margin-bottom: 10px;
		color: var(--text);
		font-size: 0.95rem;
	}

	.estimate-panel {
		padding-left: 24px;
		border-left: 2px solid color-mix(in oklab, var(--rail), transparent 45%);
	}

	.estimate-panel strong {
		font-size: 1rem;
	}

	.zone-fieldset {
		padding-top: 20px;
		border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.settings-ledger {
		padding-bottom: 18px;
	}

	.security-control {
		border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.security-control:last-child {
		border-bottom: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.security-control > summary {
		min-height: 62px;
		padding: 12px 2px;
		font-size: 1rem;
		cursor: pointer;
	}

	.security-control > summary > span {
		display: inline-flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 18px;
		width: calc(100% - 26px);
		margin-left: 8px;
		overflow-wrap: anywhere;
	}

	.security-control small {
		color: var(--muted);
		font-size: 0.86rem;
		font-weight: 500;
	}

	.security-body {
		display: grid;
		gap: 16px;
		padding: 4px 0 24px 26px;
	}

	.security-body form {
		max-width: 440px;
	}

	.setup-qr {
		display: grid;
		justify-items: start;
		gap: 10px;
		max-width: 460px;
		padding: 16px 0;
		border-block: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.setup-qr img {
		max-width: min(220px, 100%);
	}

	.setup-qr code {
		max-width: 100%;
		overflow-wrap: anywhere;
	}

	.setup-codes {
		max-width: 520px;
	}

	.setup-codes pre {
		max-width: 100%;
		overflow-x: auto;
	}

	.passkey-list {
		display: grid;
		gap: 0;
		max-width: 620px;
	}

	.passkey-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		padding: 14px 0;
		border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.passkey-row > div {
		display: grid;
		gap: 3px;
	}

	.passkey-row form {
		display: block;
	}

	.data-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}

	.route-privacy-form {
		display: grid;
		justify-items: start;
		gap: 14px;
		padding-bottom: 18px;
		border-bottom: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	.route-privacy-form fieldset {
		display: grid;
		gap: 12px;
		margin: 0;
		padding: 0;
		border: 0;
	}

	.route-privacy-form legend {
		margin-bottom: 8px;
		font-weight: 700;
	}

	.route-privacy-form label {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		max-width: 68ch;
		line-height: 1.45;
	}

	.route-privacy-form input {
		flex: 0 0 auto;
		margin-top: 0.2rem;
	}

	.data-actions form {
		display: inline-flex;
	}

	.mobile-account-actions {
		display: none;
		padding-top: 18px;
		border-top: 1px solid color-mix(in oklab, var(--line), transparent 35%);
	}

	@media (max-width: 820px) {
		.page-heading {
			padding-top: 2px;
		}

		.settings-section {
			grid-template-columns: 1fr;
			padding: 24px 0;
		}

		.section-heading,
		.settings-group,
		.settings-ledger,
		.security-control {
			grid-column: 1;
			grid-row: auto;
		}

		.section-heading {
			margin-bottom: 22px;
		}

		.profile-form-grid {
			grid-template-columns: 1fr;
		}

		.estimate-panel {
			padding: 16px 0 0;
			border-top: 2px solid color-mix(in oklab, var(--rail), transparent 45%);
			border-left: 0;
		}

		.zone-input-grid {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}

		.mobile-account-actions {
			display: block;
		}
	}

	@media (max-width: 520px) {
		.zone-input-grid {
			grid-template-columns: 1fr;
		}

		.security-body {
			padding-left: 0;
		}

		.security-control > summary > span {
			margin-left: 4px;
		}

		.passkey-row {
			align-items: stretch;
			flex-direction: column;
		}
	}
</style>
