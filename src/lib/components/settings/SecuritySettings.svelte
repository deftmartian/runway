<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { authClient } from '$lib/auth-client';
	import LedgerRow from '$lib/components/visual/LedgerRow.svelte';
	import { untrack } from 'svelte';
	import type {
		SettingsActionEnhancer,
		SettingsAuthCapabilities,
		SettingsFormState,
		SettingsPasskey,
		SettingsUser
	} from './types';

	let {
		user,
		passkeys,
		authCapabilities,
		form,
		settingsActionPending,
		enhanceSettingsAction
	}: {
		user: SettingsUser;
		passkeys: SettingsPasskey[];
		authCapabilities: SettingsAuthCapabilities;
		form: SettingsFormState;
		settingsActionPending: string | null;
		enhanceSettingsAction: SettingsActionEnhancer;
	} = $props();

	let passkeyName = $state('Primary security key');
	let passkeyMessage = $state('');
	let passkeyPending = $state(false);
	let totpQrCode = $state(untrack(() => form.totpQrCode ?? ''));
	let totpManualKey = $state(untrack(() => form.totpManualKey ?? ''));
	const totpSetupInProgress = $derived(Boolean(totpQrCode || form.setupPending));
	let authenticatorOpen = $state(false);
	let passkeysOpen = $state(false);

	$effect(() => {
		if (form.totpQrCode) {
			totpQrCode = form.totpQrCode;
			totpManualKey = form.totpManualKey ?? '';
		}
		if (form.backupCodes || user.twoFactorEnabled) {
			totpQrCode = '';
			totpManualKey = '';
		}
	});

	$effect(() => {
		if (totpSetupInProgress || (form.scope === 'twoFactor' && form.message)) {
			authenticatorOpen = true;
		}
		if (passkeyMessage || (form.scope === 'passkeys' && form.message)) passkeysOpen = true;
	});

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
</script>

<section class="settings-section" aria-labelledby="security-settings-heading">
	<header class="section-heading"><h2 id="security-settings-heading">Account security</h2></header>
	<div class="settings-ledger"><LedgerRow label="Signed in as" value={user.email} /></div>

	<details class="security-control settings-control" bind:open={authenticatorOpen}>
		<summary
			><span
				><strong>Authenticator app</strong><small
					>{user.twoFactorEnabled
						? 'Enabled'
						: totpSetupInProgress
							? 'Setup pending'
							: 'Not enabled'}</small
				></span
			></summary
		>
		<div class="security-body">
			{#if form.scope === 'twoFactor' && form.message}<p
					class="message"
					role="status"
					aria-live="polite"
				>
					{form.message}
				</p>{/if}
			{#if !authCapabilities.localPassword}<p class="section-note">
					{authCapabilities.oidc
						? 'Manage two-factor authentication through your sign-in provider.'
						: 'An authenticator app requires a local password account.'}
				</p>{/if}
			{#if totpQrCode}
				<div class="setup-qr">
					<strong>Scan the QR code</strong>
					<p class="section-note">
						Add runway to an authenticator app, then enter the six-digit code.
					</p>
					<img src={totpQrCode} alt="QR code for authenticator setup" />
					{#if totpManualKey}<p class="section-note">
							Cannot scan it? Enter this setup key manually:
						</p>
						<code>{totpManualKey}</code>{/if}
				</div>
			{/if}
			{#if (totpQrCode || form.setupPending) && !user.twoFactorEnabled}
				{#if form.setupPending && !totpQrCode}<p class="section-note">
						Enter a new code from the authenticator entry you just added.
					</p>{/if}
				<form
					method="post"
					action="?/verifySetupTotp"
					use:enhance={enhanceSettingsAction('verifySetupTotp')}
					aria-busy={settingsActionPending === 'verifySetupTotp'}
				>
					<label
						>Authenticator code<input
							name="code"
							inputmode="numeric"
							autocomplete="one-time-code"
							pattern="[0-9][0-9][0-9][0-9][0-9][0-9]"
							maxlength="6"
							required
						/></label
					>
					<button class="primary" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'verifySetupTotp' ? 'Verifying…' : 'Verify code'}</button
					>
				</form>
			{/if}
			{#if form.backupCodes}
				<details class="setup-codes" open>
					<summary>Recovery codes</summary>
					<p class="section-note">Save these outside runway now. Each code works once.</p>
					<pre>{form.backupCodes.join('\n')}</pre>
				</details>
			{/if}
			{#if authCapabilities.localPassword && user.twoFactorEnabled}
				<form
					method="post"
					action="?/disableTwoFactor"
					use:enhance={enhanceSettingsAction('disableTwoFactor')}
					aria-busy={settingsActionPending === 'disableTwoFactor'}
				>
					<label
						>Password<input
							type="password"
							name="password"
							autocomplete="current-password"
							maxlength="128"
							required
						/></label
					>
					<button class="danger" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'disableTwoFactor'
							? 'Disabling…'
							: 'Disable authenticator'}</button
					>
				</form>
			{:else if authCapabilities.localPassword && !totpSetupInProgress}
				<form
					method="post"
					action="?/enableTwoFactor"
					use:enhance={enhanceSettingsAction('enableTwoFactor')}
					aria-busy={settingsActionPending === 'enableTwoFactor'}
				>
					<label
						>Password<input
							type="password"
							name="password"
							autocomplete="current-password"
							maxlength="128"
							required
						/></label
					>
					<button class="primary" disabled={settingsActionPending !== null}
						>{settingsActionPending === 'enableTwoFactor'
							? 'Starting…'
							: 'Set up authenticator'}</button
					>
				</form>
			{/if}
		</div>
	</details>

	<details class="security-control settings-control" bind:open={passkeysOpen}>
		<summary
			><span
				><strong>Passkeys</strong><small
					>{passkeys.length === 1 ? '1 registered' : `${passkeys.length} registered`}</small
				></span
			></summary
		>
		<div class="security-body">
			{#if form.scope === 'passkeys' && form.message}<p
					class="message"
					role="status"
					aria-live="polite"
				>
					{form.message}
				</p>{/if}
			<p class="section-note">
				Passkeys can be added when runway is opened over HTTPS or localhost.
			</p>
			<label
				>Passkey name<input
					bind:value={passkeyName}
					maxlength="80"
					disabled={passkeyPending}
				/></label
			>
			<button class="primary" type="button" onclick={addPasskey} disabled={passkeyPending}
				>{passkeyPending ? 'Adding passkey…' : 'Add passkey'}</button
			>
			{#if passkeyMessage}<p class="message" role="status" aria-live="polite">
					{passkeyMessage}
				</p>{/if}
			<div class="passkey-list">
				{#each passkeys as passkey (passkey.id)}
					<div class="passkey-row">
						<div>
							<strong>{passkey.name ?? 'Passkey'}</strong><span class="field-note"
								>{passkey.deviceType}{passkey.backedUp ? ' · backed up' : ''}</span
							>
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
							<input type="hidden" name="id" value={passkey.id} /><button
								class="danger"
								disabled={settingsActionPending !== null}
								>{settingsActionPending === `deletePasskey:${passkey.id}`
									? 'Removing…'
									: 'Remove'}</button
							>
						</form>
					</div>
				{:else}<p class="section-note">No passkeys registered.</p>{/each}
			</div>
		</div>
	</details>
</section>
