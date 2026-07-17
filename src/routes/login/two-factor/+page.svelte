<script lang="ts">
	import { enhance } from '$app/forms';
	import AuthSurface from '../AuthSurface.svelte';
	import type { ActionData, SubmitFunction } from './$types';

	let { form }: { form: ActionData } = $props();
	const scopedForm = $derived((form ?? {}) as { message?: string; scope?: string });
	let pendingAction = $state<string | null>(null);

	function enhanceVerification(key: string): SubmitFunction {
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
</script>

<AuthSurface title="Verify sign-in">
	<p class="muted">Enter the current code from your authenticator.</p>
	<div class="auth-section">
		<form
			method="post"
			action="?/verifyTotp"
			use:enhance={enhanceVerification('verifyTotp')}
			aria-busy={pendingAction === 'verifyTotp'}
		>
			{#if scopedForm.scope === 'verifyTotp' && scopedForm.message}
				<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
			{/if}
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
			<label class="check-row">
				<input type="checkbox" name="trustDevice" />
				Trust this device for 30 days
			</label>
			<button class="primary" disabled={pendingAction !== null}>
				{pendingAction === 'verifyTotp' ? 'Verifying…' : 'Verify code'}
			</button>
		</form>
	</div>
	<div class="auth-section">
		<form
			method="post"
			action="?/verifyBackupCode"
			use:enhance={enhanceVerification('verifyBackupCode')}
			aria-busy={pendingAction === 'verifyBackupCode'}
		>
			<h2>Use a backup code</h2>
			{#if scopedForm.scope === 'verifyBackupCode' && scopedForm.message}
				<p class="message" role="status" aria-live="polite">{scopedForm.message}</p>
			{/if}
			<label>
				Backup code
				<input
					name="code"
					inputmode="text"
					autocomplete="off"
					autocapitalize="none"
					spellcheck="false"
					pattern="[A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]-[A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]"
					maxlength="11"
					required
				/>
			</label>
			<label class="check-row">
				<input type="checkbox" name="trustDevice" />
				Trust this device for 30 days
			</label>
			<button disabled={pendingAction !== null}>
				{pendingAction === 'verifyBackupCode' ? 'Checking backup code…' : 'Use backup code'}
			</button>
		</form>
	</div>
</AuthSurface>
