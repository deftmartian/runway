<script lang="ts">
	import { enhance } from '$app/forms';
	import type { ScopedEnhanceFactory } from './import-view-model';

	let {
		activeAction,
		importTimeZoneConfigured,
		scopedEnhance
	}: {
		activeAction: string | null;
		importTimeZoneConfigured: boolean;
		scopedEnhance: ScopedEnhanceFactory;
	} = $props();

	const actionPending = (key: string) => activeAction === key;
</script>

<section class="setup-section" aria-labelledby="nextcloud-heading">
	<h3 id="nextcloud-heading">Nextcloud</h3>
	<p>Connect a password-protected folder share and sync its GPX files.</p>
	<form
		method="post"
		action="?/saveNextcloudSource"
		use:enhance={scopedEnhance('connect-source', 'sources')}
		class="compact-form"
	>
		<label>
			Label
			<input name="label" placeholder="Gadgetbridge exports" />
		</label>
		<label>
			Share link
			<input name="shareUrl" inputmode="url" autocomplete="off" required />
		</label>
		<label>
			Share password
			<input type="password" name="sharePassword" autocomplete="off" required />
		</label>
		<button class="primary" disabled={activeAction !== null || !importTimeZoneConfigured}>
			{actionPending('connect-source') ? 'Connecting…' : 'Connect folder'}
		</button>
	</form>
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
</style>
