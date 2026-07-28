<script lang="ts">
	import ThemeToggle from '$lib/components/ThemeToggle.svelte';

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
</script>

<div class="account-actions" data-context={context}>
	{#if showTheme}<ThemeToggle />{/if}
	{#if showSignOut}
		<form method="post" action="/logout" aria-busy={signOutPending}>
			<button class="ghost" title={`Signed in as ${email}`} disabled={signOutPending}>
				{signOutPending ? 'Signing out…' : 'Sign out'}
			</button>
		</form>
	{/if}
</div>

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

	@media (max-width: 720px) {
		.account-actions[data-context='header'] {
			display: none;
		}
	}
</style>
