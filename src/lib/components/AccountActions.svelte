<script lang="ts">
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';
	import { clearAllDeviceFolderData } from '$lib/pwa/device-folder';

	let {
		email,
		context = 'header',
		showTheme = true,
		showSignOut = true
	}: {
		email: string;
		context?: 'header' | 'settings';
		showTheme?: boolean;
		showSignOut?: boolean;
	} = $props();
	let signOutPending = $state(false);
	let signOutError = $state('');

	async function signOut(event: SubmitEvent) {
		event.preventDefault();
		if (signOutPending || !(event.currentTarget instanceof HTMLFormElement)) return;
		const form = event.currentTarget;
		signOutPending = true;
		signOutError = '';
		try {
			await clearAllDeviceFolderData();
			HTMLFormElement.prototype.submit.call(form);
		} catch {
			signOutPending = false;
			signOutError = 'Folder access could not be cleared. Close other runway tabs and try again.';
		}
	}
</script>

<div class="account-actions" data-context={context}>
	{#if showTheme}<ThemeToggle />{/if}
	{#if showSignOut}
		<form method="post" action="/logout" onsubmit={signOut} aria-busy={signOutPending}>
			<button class="ghost" title={`Signed in as ${email}`} disabled={signOutPending}>
				{signOutPending ? 'Signing out…' : 'Sign out'}
			</button>
		</form>
	{/if}
</div>

{#if signOutError}
	<p class="account-action-error" role="alert">{signOutError}</p>
{/if}

<style>
	.account-actions {
		display: flex;
		gap: 8px;
		align-items: center;
	}

	.account-actions[data-context='settings'] {
		align-items: stretch;
		justify-content: flex-start;
		flex-wrap: wrap;
	}

	.account-actions[data-context='settings'] :global(button) {
		min-width: 132px;
	}

	.account-action-error {
		margin: 8px 0 0;
		color: var(--danger);
	}

	@media (max-width: 720px) {
		.account-actions[data-context='header'] {
			display: none;
		}
	}
</style>
