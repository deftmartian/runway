<script lang="ts">
	import { enhance } from '$app/forms';
	import { resolve } from '$app/paths';
	import type { SubmitFunction } from './$types';

	let {
		message,
		pendingAction,
		passkeyPending,
		primary = true,
		enhancer
	}: {
		message: string | undefined;
		pendingAction: string | null;
		passkeyPending: boolean;
		primary?: boolean;
		enhancer: SubmitFunction;
	} = $props();
</script>

<form
	method="post"
	action="?/signInEmail"
	use:enhance={enhancer}
	aria-busy={pendingAction === 'signInEmail'}
>
	<h2>Email and password</h2>
	{#if message}
		<p class="message" role="status" aria-live="polite">{message}</p>
	{/if}
	<label>
		Email
		<input type="email" name="email" autocomplete="email" maxlength="254" required />
	</label>
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
	<button class:primary disabled={pendingAction !== null || passkeyPending}>
		{pendingAction === 'signInEmail' ? 'Signing in…' : 'Sign in'}
	</button>
	<a class="inline-link" href={resolve('/login/forgot-password')}>Reset password</a>
</form>
