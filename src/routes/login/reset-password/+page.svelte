<script lang="ts">
	import { resolve } from '$app/paths';
	import { enhance } from '$app/forms';
	import AuthSurface from '../AuthSurface.svelte';
	import type { ActionData, PageData, SubmitFunction } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	let pending = $state(false);
	const enhancePasswordReset: SubmitFunction = ({ cancel }) => {
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

<AuthSurface title="Choose a new password">
	{#if data.hasToken && !form?.resetComplete}
		<p class="muted">Changing the password signs out existing sessions.</p>
	{/if}
	<form
		method="post"
		action="?/resetPassword"
		use:enhance={enhancePasswordReset}
		aria-busy={pending}
	>
		{#if data.message || form?.message}
			<p class="message" role="status" aria-live="polite">{data.message ?? form?.message}</p>
		{/if}
		{#if form?.resetComplete}
			<a class="button primary" href={resolve('/login')}>Sign in</a>
		{:else if !data.hasToken}
			<p class="message">Open this page from the reset link in your email.</p>
		{:else}
			<label>
				New password
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
			<label>
				Confirm password
				<input
					type="password"
					name="confirmPassword"
					autocomplete="new-password"
					minlength="12"
					maxlength="128"
					required
				/>
			</label>
			<button class="primary" disabled={pending}>
				{pending ? 'Changing password…' : 'Change password'}
			</button>
		{/if}
	</form>
	{#if !form?.resetComplete}
		<a class="inline-link" href={resolve('/login')}>Back to sign in</a>
	{/if}
</AuthSurface>
