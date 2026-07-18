<script lang="ts">
	import { installPromptState, installRunway } from '$lib/pwa/install-prompt';
	import { serviceWorkerSetupMessage, serviceWorkerSetupState } from '$lib/pwa/lifecycle';

	let { compact = false }: { compact?: boolean } = $props();
	let installing = $state(false);

	async function install() {
		const prompt = $installPromptState.prompt;
		if (!prompt || installing) return;
		installing = true;
		try {
			await installRunway(prompt);
		} finally {
			installing = false;
		}
	}
</script>

{#if compact}
	{#if !$installPromptState.installed && $installPromptState.prompt && $serviceWorkerSetupState === 'ready'}
		<button
			type="button"
			class="install-shortcut"
			onclick={install}
			disabled={installing}
			aria-label="Install runway"
		>
			{installing ? 'Installing…' : 'Install'}
		</button>
	{/if}
{:else if !$installPromptState.installed}
	<section class="install-control" aria-label="Install runway">
		<div>
			<strong>Install runway</strong>
			{#if serviceWorkerSetupMessage($serviceWorkerSetupState)}
				<p class="setup-problem" role="status">
					{serviceWorkerSetupMessage($serviceWorkerSetupState)}
				</p>
			{:else if $serviceWorkerSetupState === 'development'}
				<p>Installation is available from a production build or preview.</p>
			{:else if $installPromptState.prompt}
				<p>Open runway from the home screen or app launcher.</p>
			{:else if $installPromptState.guidance === 'ios'}
				<p>In Safari, tap Share, then Add to Home Screen.</p>
			{:else}
				<p>Use the browser menu and choose Install app or Add to Home screen.</p>
			{/if}
		</div>
		{#if $installPromptState.prompt && $serviceWorkerSetupState === 'ready'}
			<button type="button" class="primary" onclick={install} disabled={installing}>
				{installing ? 'Installing…' : 'Install'}
			</button>
		{/if}
	</section>
{/if}

<style>
	.install-control {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 20px;
		padding: 4px 0;
	}

	.install-control div {
		display: grid;
		gap: 5px;
	}

	.install-control p {
		max-width: 68ch;
		margin: 0;
		color: var(--muted);
		font-size: 0.93rem;
		line-height: 1.5;
	}

	.install-control .setup-problem {
		color: var(--danger);
	}

	.install-shortcut {
		min-height: 38px;
		padding-inline: 11px;
		border-color: color-mix(in oklab, var(--accent), var(--line) 58%);
		background: transparent;
		color: var(--accent-strong);
		font-size: 0.86rem;
		font-weight: 760;
	}

	@media (max-width: 560px) {
		.install-control {
			align-items: stretch;
			flex-direction: column;
		}
	}
</style>
