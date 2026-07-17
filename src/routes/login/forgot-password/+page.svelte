<script lang="ts">
	import { resolve } from '$app/paths';
	import { enhance } from '$app/forms';
	import AuthSurface from '../AuthSurface.svelte';
	import type { ActionData, SubmitFunction } from './$types';

	let { form }: { form: ActionData } = $props();
	let pending = $state(false);
	const enhanceResetRequest: SubmitFunction = ({ cancel }) => {
		if (pending) {
			cancel();
			return;
		}
		pending = true;
		return async ({ update }) => {
			try {
				await update();
			} finally {
				pending = false;
			}
		};
	};
</script>

<AuthSurface title="Reset password">
	<p class="muted">Enter the email address for your local account.</p>
	<form method="post" action="?/requestReset" use:enhance={enhanceResetRequest} aria-busy={pending}>
		{#if form?.message}
			<p class="message" role="status" aria-live="polite">{form.message}</p>
		{/if}
		<label>
			Email
			<input type="email" name="email" autocomplete="email" maxlength="254" required />
		</label>
		<button class="primary" disabled={pending}>
			{pending ? 'Sending reset link…' : 'Send reset link'}
		</button>
	</form>
	<a class="inline-link" href={resolve('/login')}>Back to sign in</a>
</AuthSurface>
