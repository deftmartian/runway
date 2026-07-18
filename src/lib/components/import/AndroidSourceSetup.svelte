<script lang="ts">
	import { enhance } from '$app/forms';
	import type { AndroidPairingSummary, ScopedEnhanceFactory } from './import-view-model';

	let {
		androidPairing,
		androidApplicationId,
		activeAction,
		scopedEnhance
	}: {
		androidPairing: AndroidPairingSummary | null;
		androidApplicationId: string | null;
		activeAction: string | null;
		scopedEnhance: ScopedEnhanceFactory;
	} = $props();

	const actionPending = (key: string) => activeAction === key;
	const dateTime = (value: Date | string) =>
		new Date(value).toLocaleString(undefined, {
			month: 'short',
			day: 'numeric',
			hour: 'numeric',
			minute: '2-digit'
		});

	function openAndroidFolderSettings() {
		if (!androidApplicationId) return;
		window.location.href = `intent://folder#Intent;scheme=runway-native;package=${androidApplicationId};end`;
	}
</script>

<section class="setup-section" aria-labelledby="android-app-heading">
	<h3 id="android-app-heading">Android folder</h3>
	<p>
		Pair this account with the installed runway app, then choose the folder that receives GPX files.
	</p>
	{#if androidPairing}
		<div class="pairing-code" role="status" aria-live="polite">
			<span>Pairing code</span>
			<strong>{androidPairing.code}</strong>
			<small>Enter it in the Android Folder screen by {dateTime(androidPairing.expiresAt)}.</small>
		</div>
	{/if}
	<form
		method="post"
		action="?/createAndroidPairing"
		use:enhance={scopedEnhance('create-android-pairing', 'sources')}
	>
		<button class="primary" disabled={activeAction !== null}>
			{actionPending('create-android-pairing') ? 'Creating…' : 'Create pairing code'}
		</button>
	</form>
	{#if androidApplicationId}
		<button type="button" class="button-link" onclick={openAndroidFolderSettings}>
			Open Android Folder screen
		</button>
	{:else}
		<p class="privacy-note">
			Open the Folder shortcut from the runway app icon after creating the code.
		</p>
	{/if}
	<p class="privacy-note">The pairing code works once and expires after ten minutes.</p>
</section>

<style>
	.setup-section {
		display: grid;
		align-content: start;
		gap: 12px;
		width: min(100%, 700px);
		min-width: 0;
		padding: 20px;
		border-block: 1px solid var(--line);
	}

	.setup-section h3,
	.setup-section p {
		margin: 0;
	}

	.setup-section h3 {
		font-size: 1rem;
	}

	.setup-section > p {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.setup-section form {
		display: grid;
		gap: 10px;
	}

	.pairing-code {
		display: grid;
		gap: 3px;
		padding: 12px;
		border: 1px solid color-mix(in oklab, var(--accent), var(--line) 55%);
		border-radius: var(--radius-small);
		background: color-mix(in oklab, var(--accent-soft), var(--surface) 70%);
	}

	.pairing-code span,
	.pairing-code small {
		color: var(--muted);
	}

	.pairing-code strong {
		font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
		font-size: 1.05rem;
		letter-spacing: 0.05em;
	}

	.setup-section > .button-link {
		justify-self: start;
	}

	.privacy-note {
		padding-left: 10px;
		border-left: 2px solid var(--line);
	}
</style>
