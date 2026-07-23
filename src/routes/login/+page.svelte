<script lang="ts">
	import { resolve } from '$app/paths';
	import { enhance } from '$app/forms';
	import { goto, replaceState } from '$app/navigation';
	import { authClient } from '$lib/auth-client';
	import { onMount } from 'svelte';
	import AuthSurface from './AuthSurface.svelte';
	import LocalSignInForm from './LocalSignInForm.svelte';
	import PasskeySignIn from './PasskeySignIn.svelte';
	import type { ActionData, PageData, SubmitFunction } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	const scopedForm = $derived((form ?? {}) as { message?: string; scope?: string });
	let passkeyMessage = $state('');
	let pendingAction = $state<string | null>(null);
	let passkeyPending = $state(false);
	let selectedAuthMode = $state<'sign-in' | 'create-account'>('sign-in');
	let dismissSignupResult = $state(false);
	const authMode = $derived(
		scopedForm.scope === 'signUpEmail' && !dismissSignupResult ? 'create-account' : selectedAuthMode
	);
	let signupUnavailable = $state(false);

	onMount(() => {
		const syncModeFromHash = () => {
			if (
				globalThis.location.hash === '#create-account' &&
				data.localAuthEnabled &&
				data.localSignupsEnabled
			) {
				selectedAuthMode = 'create-account';
				globalThis.document.title = 'Create account · runway';
				return;
			}
			if (globalThis.location.hash === '#create-account') {
				signupUnavailable = true;
			} else {
				globalThis.document.title = 'Sign in · runway';
			}
		};
		syncModeFromHash();
		globalThis.addEventListener('hashchange', syncModeFromHash);
		return () => {
			globalThis.removeEventListener('hashchange', syncModeFromHash);
		};
	});

	function showSignIn() {
		selectedAuthMode = 'sign-in';
		dismissSignupResult = true;
		signupUnavailable = false;
		globalThis.document.title = 'Sign in · runway';
		if (globalThis.location.hash === '#create-account') {
			let signInUrl = resolve('/login');
			signInUrl += globalThis.location.search;
			replaceState(signInUrl, {});
		}
	}

	function showCreateAccount() {
		if (!data.localAuthEnabled || !data.localSignupsEnabled) return;
		selectedAuthMode = 'create-account';
		dismissSignupResult = false;
		signupUnavailable = false;
		globalThis.document.title = 'Create account · runway';
		let createAccountUrl = resolve('/login');
		createAccountUrl += `${globalThis.location.search}#create-account`;
		replaceState(createAccountUrl, {});
	}

	function enhanceAuthAction(key: string): SubmitFunction {
		return ({ cancel }) => {
			if (pendingAction) {
				cancel();
				return;
			}
			pendingAction = key;
			return async ({ update }) => {
				try {
					await update();
				} finally {
					pendingAction = null;
				}
			};
		};
	}

	async function signInWithPasskey() {
		if (passkeyPending || pendingAction) return;
		if (!globalThis.isSecureContext) {
			passkeyMessage = 'Passkeys need a secure connection. Try another sign-in method.';
			return;
		}
		passkeyPending = true;
		passkeyMessage = 'Waiting for the passkey prompt…';
		try {
			const result = await authClient.signIn.passkey();
			if (result.error) {
				passkeyMessage = 'Passkey sign-in was cancelled or failed.';
				return;
			}
			passkeyMessage = '';
			await goto(resolve('/app'));
		} catch {
			passkeyMessage = 'Passkey sign-in did not finish. Try again.';
		} finally {
			passkeyPending = false;
		}
	}
</script>

<AuthSurface title={authMode === 'create-account' ? 'Create account' : 'Sign in'}>
	{#if data.shareSignInRequired}
		<p class="message" role="status">
			Sign in, then share the GPX file to runway again. The file was not retained.
		</p>
	{/if}

	{#if data.localAuthEnabled && data.localSignupsEnabled}
		<nav class="auth-mode-switch" aria-label="Account access">
			<button
				type="button"
				class:active={authMode === 'sign-in'}
				aria-pressed={authMode === 'sign-in'}
				onclick={showSignIn}>Sign in</button
			>
			<button
				type="button"
				class:active={authMode === 'create-account'}
				aria-pressed={authMode === 'create-account'}
				onclick={showCreateAccount}>Create account</button
			>
		</nav>
	{/if}

	{#if signupUnavailable}
		<p class="message" role="status">Local account creation is not available on this server.</p>
	{/if}

	{#if authMode === 'sign-in'}
		{#if data.oidcConfigured}
			<form
				method="post"
				action="?/signInOidc"
				use:enhance={enhanceAuthAction('signInOidc')}
				aria-busy={pendingAction === 'signInOidc'}
			>
				{#if scopedForm.scope === 'signInOidc' && scopedForm.message}
					<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
				{/if}
				<button class="primary" disabled={pendingAction !== null || passkeyPending}>
					{pendingAction === 'signInOidc' ? 'Opening Authentik…' : 'Continue with Authentik'}
				</button>
			</form>

			<details class="auth-disclosure" open={scopedForm.scope === 'signInEmail'}>
				<summary>Other sign-in options</summary>
				<div class="auth-options stack">
					{#if data.localAuthEnabled}
						<LocalSignInForm
							message={scopedForm.scope === 'signInEmail' ? scopedForm.message : undefined}
							{pendingAction}
							{passkeyPending}
							primary={false}
							enhancer={enhanceAuthAction('signInEmail')}
						/>
					{/if}
					<PasskeySignIn
						pending={passkeyPending}
						disabled={passkeyPending || pendingAction !== null}
						message={passkeyMessage}
						onSignIn={signInWithPasskey}
					/>
				</div>
			</details>
		{:else if data.localAuthEnabled}
			<div class="auth-section">
				<LocalSignInForm
					message={scopedForm.scope === 'signInEmail' ? scopedForm.message : undefined}
					{pendingAction}
					{passkeyPending}
					enhancer={enhanceAuthAction('signInEmail')}
				/>
			</div>
			<div class="auth-section">
				<h2>Passkey</h2>
				<PasskeySignIn
					pending={passkeyPending}
					disabled={passkeyPending || pendingAction !== null}
					message={passkeyMessage}
					onSignIn={signInWithPasskey}
				/>
			</div>
		{:else}
			<p class="muted">Use a passkey to sign in.</p>
			<PasskeySignIn
				pending={passkeyPending}
				disabled={passkeyPending || pendingAction !== null}
				message={passkeyMessage}
				onSignIn={signInWithPasskey}
			/>
		{/if}
	{:else if data.localAuthEnabled && data.localSignupsEnabled}
		<form
			id="create-account"
			method="post"
			action="?/signUpEmail"
			use:enhance={enhanceAuthAction('signUpEmail')}
			aria-busy={pendingAction === 'signUpEmail'}
		>
			{#if scopedForm.scope === 'signUpEmail' && scopedForm.message}
				<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
			{/if}
			<label>
				Name <span class="optional">Optional</span>
				<input name="name" autocomplete="name" maxlength="100" />
			</label>
			<label>
				Email
				<input type="email" name="email" autocomplete="email" maxlength="254" required />
			</label>
			<label>
				Password
				<input
					type="password"
					name="password"
					autocomplete="new-password"
					minlength="12"
					maxlength="128"
					required
				/>
				<span class="muted">Use 12 to 128 characters.</span>
			</label>
			<button class="primary" disabled={pendingAction !== null || passkeyPending}>
				{pendingAction === 'signUpEmail' ? 'Creating account…' : 'Create account'}
			</button>
		</form>
	{/if}
</AuthSurface>

<style>
	.auth-mode-switch {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		gap: 4px;
		padding: 4px;
		border: 1px solid var(--line);
		border-radius: var(--radius-small);
		background: var(--surface-strong);
	}

	.auth-mode-switch button {
		border-color: transparent;
		background: transparent;
	}

	.auth-mode-switch button.active {
		border-color: var(--line);
		background: var(--surface);
		box-shadow: 0 1px 2px color-mix(in oklab, var(--text), transparent 92%);
	}
</style>
