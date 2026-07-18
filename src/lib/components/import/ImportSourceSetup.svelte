<script lang="ts">
	import { enhance } from '$app/forms';
	import AndroidSourceSetup from './AndroidSourceSetup.svelte';
	import BrowserFolderSource from './BrowserFolderSource.svelte';
	import GpxUploadSource from './GpxUploadSource.svelte';
	import ImportPrivacy from './ImportPrivacy.svelte';
	import NextcloudSourceSetup from './NextcloudSourceSetup.svelte';
	import type {
		AndroidDeviceSummary,
		AndroidPairingSummary,
		ImportSection,
		ImportSourceSummary,
		ImportWorkoutCandidate,
		ScopedEnhanceFactory,
		ScopedImportResult
	} from './import-view-model';

	let {
		userId,
		candidates,
		sources,
		androidDevices,
		androidApplicationId,
		importTimeZoneConfigured,
		routeDataMode,
		androidPairing,
		activeAction,
		activeSection,
		scopedResult,
		scopedEnhance
	}: {
		userId: string;
		candidates: ImportWorkoutCandidate[];
		sources: ImportSourceSummary[];
		androidDevices: AndroidDeviceSummary[];
		androidApplicationId: string | null;
		importTimeZoneConfigured: boolean;
		routeDataMode: 'private' | 'discard';
		androidPairing: AndroidPairingSummary | null;
		activeAction: string | null;
		activeSection: ImportSection | null;
		scopedResult: ScopedImportResult | null;
		scopedEnhance: ScopedEnhanceFactory;
	} = $props();

	type ImportSourceKind = 'android' | 'browser' | 'nextcloud' | 'upload';

	let setupOpen = $state(false);
	let chosenImportSource = $state<ImportSourceKind | null>(null);
	let browserFolderConnected = $state(false);

	const selectedImportSource = $derived<ImportSourceKind | null>(
		chosenImportSource ?? (androidPairing ? 'android' : null)
	);
	const sourceResult = $derived(scopedResult?.section === 'sources' ? scopedResult : null);
	const actionPending = (key: string) => activeAction === key;
	const dateTime = (value: Date | string | null) =>
		value
			? new Date(value).toLocaleString(undefined, {
					month: 'short',
					day: 'numeric',
					hour: 'numeric',
					minute: '2-digit'
				})
			: 'Not yet';

	function chooseSource(source: ImportSourceKind) {
		chosenImportSource = source;
	}

	function setBrowserFolderConnection(connected: boolean) {
		browserFolderConnected = connected;
	}

	$effect(() => {
		if (scopedResult?.section === 'gpx' && !scopedResult.failed) setupOpen = false;
	});
</script>

<section class="import-sources" aria-labelledby="import-sources-title">
	<header class="sources-heading">
		<h2 id="import-sources-title">Import sources</h2>
	</header>

	{#if sourceResult}
		<p
			class="message compact-message"
			class:bad-message={sourceResult.failed}
			role="status"
			aria-live="polite"
		>
			{sourceResult.message}
		</p>
	{/if}

	<details class="source-setup" bind:open={setupOpen}>
		<summary>Add import source</summary>
		<div id="source-setup-body" class="source-setup-body">
			<div class="source-choices" role="group" aria-label="Choose an import source">
				<button
					type="button"
					class:selected={selectedImportSource === 'android'}
					aria-pressed={selectedImportSource === 'android'}
					onclick={() => {
						chooseSource('android');
					}}
				>
					<strong>Android folder</strong>
					<span>Import in the background from the installed app.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'browser'}
					aria-pressed={selectedImportSource === 'browser'}
					onclick={() => {
						chooseSource('browser');
					}}
				>
					<strong>Browser folder</strong>
					<span>Check a Gadgetbridge folder while runway is open.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'nextcloud'}
					aria-pressed={selectedImportSource === 'nextcloud'}
					onclick={() => {
						chooseSource('nextcloud');
					}}
				>
					<strong>Nextcloud</strong>
					<span>Sync a password-protected shared folder.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'upload'}
					aria-pressed={selectedImportSource === 'upload'}
					onclick={() => {
						chooseSource('upload');
					}}
				>
					<strong>Upload GPX</strong>
					<span>Choose one file from this device.</span>
				</button>
			</div>

			{#if selectedImportSource === 'android'}
				<AndroidSourceSetup
					{androidPairing}
					{androidApplicationId}
					{activeAction}
					{scopedEnhance}
				/>
				<ImportPrivacy {routeDataMode} />
			{:else if selectedImportSource === 'nextcloud'}
				<NextcloudSourceSetup {activeAction} {importTimeZoneConfigured} {scopedEnhance} />
				<ImportPrivacy {routeDataMode} />
			{:else if selectedImportSource === 'upload'}
				<GpxUploadSource
					{candidates}
					{activeAction}
					{importTimeZoneConfigured}
					{scopedResult}
					{scopedEnhance}
				/>
				<ImportPrivacy {routeDataMode} />
			{/if}
		</div>
	</details>

	<BrowserFolderSource
		{userId}
		{importTimeZoneConfigured}
		{routeDataMode}
		selected={setupOpen && selectedImportSource === 'browser'}
		onConnectionChange={setBrowserFolderConnection}
	/>

	<div class="connected-sources" aria-busy={activeSection === 'sources'}>
		{#each androidDevices as device (device.id)}
			<div class="import-source-row">
				<div class="source-copy">
					<strong>{device.label}</strong>
					<span class:source-error={new Date(device.expiresAt) <= new Date()}>
						{new Date(device.expiresAt) <= new Date()
							? 'Android app · pairing expired'
							: `Android app · seen ${dateTime(device.lastSeenAt)}`}
					</span>
					{#if device.lastImportedAt}
						<span>Last import {dateTime(device.lastImportedAt)}</span>
					{/if}
				</div>
				<div class="import-actions">
					<form
						method="post"
						action="?/revokeAndroidDevice"
						use:enhance={scopedEnhance(`revoke-android-${device.id}`, 'sources')}
					>
						<input type="hidden" name="deviceId" value={device.id} />
						<button disabled={activeAction !== null}>
							{actionPending(`revoke-android-${device.id}`) ? 'Disconnecting…' : 'Disconnect'}
						</button>
					</form>
				</div>
			</div>
		{/each}

		{#each sources as source (source.id)}
			<div class="import-source-row">
				<div class="source-copy">
					<strong>{source.label}</strong>
					<span>
						{source.enabled ? 'Nextcloud · connected' : 'Nextcloud · disconnected'} · checked {dateTime(
							source.lastCheckedAt
						)}
					</span>
					{#if source.lastImportedAt}
						<span>Last import {dateTime(source.lastImportedAt)}</span>
					{/if}
					{#if source.lastError}
						<span class="source-error">{source.lastError}</span>
					{/if}
				</div>
				{#if source.enabled}
					<div class="import-actions">
						<form
							method="post"
							action="?/testNextcloudSource"
							use:enhance={scopedEnhance(`test-${source.id}`, 'sources')}
						>
							<input type="hidden" name="sourceId" value={source.id} />
							<button disabled={activeAction !== null || !importTimeZoneConfigured}>
								{actionPending(`test-${source.id}`) ? 'Testing…' : 'Test'}
							</button>
						</form>
						<form
							method="post"
							action="?/syncNextcloudSource"
							use:enhance={scopedEnhance(`sync-${source.id}`, 'sources')}
						>
							<input type="hidden" name="sourceId" value={source.id} />
							<button class="primary" disabled={activeAction !== null || !importTimeZoneConfigured}>
								{actionPending(`sync-${source.id}`) ? 'Syncing…' : 'Sync now'}
							</button>
						</form>
						<form
							method="post"
							action="?/disconnectImportSource"
							use:enhance={scopedEnhance(`disconnect-${source.id}`, 'sources')}
						>
							<input type="hidden" name="sourceId" value={source.id} />
							<button disabled={activeAction !== null}>
								{actionPending(`disconnect-${source.id}`) ? 'Disconnecting…' : 'Disconnect'}
							</button>
						</form>
					</div>
				{/if}
			</div>
		{/each}

		{#if sources.length === 0 && androidDevices.length === 0 && !browserFolderConnected}
			<p class="no-sources">No import sources connected.</p>
		{/if}
	</div>
</section>

<style>
	.import-sources {
		display: grid;
		gap: 16px;
		min-width: 0;
	}

	.sources-heading {
		padding-bottom: 12px;
		border-bottom: 2px solid var(--line);
	}

	.sources-heading h2 {
		margin: 0;
		font-size: clamp(1.3rem, 3vw, 1.7rem);
		line-height: 1.05;
		letter-spacing: -0.035em;
	}

	.source-setup {
		display: grid;
		gap: 18px;
		margin-top: 4px;
	}

	.source-setup > summary {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		justify-self: start;
		min-height: 44px;
		padding: 7px 13px;
		border: 1px solid var(--line);
		border-radius: var(--radius-small);
		background: var(--surface-strong);
		color: var(--text);
		font-size: 0.9rem;
		font-weight: 760;
		cursor: pointer;
		list-style: none;
	}

	.source-setup > summary::-webkit-details-marker {
		display: none;
	}

	.source-setup > summary:focus-visible {
		outline: 3px solid color-mix(in oklab, var(--accent), transparent 35%);
		outline-offset: 3px;
	}

	.source-setup[open] > summary {
		border-color: var(--accent);
	}

	.source-setup-body {
		display: grid;
		gap: 18px;
	}

	.source-choices {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		border-block: 1px solid var(--line);
	}

	.source-choices button {
		display: grid;
		align-content: start;
		justify-content: start;
		gap: 5px;
		min-width: 0;
		min-height: 92px;
		padding: 16px;
		border: 0;
		border-right: 1px solid var(--line);
		border-radius: 0;
		background: transparent;
		text-align: left;
	}

	.source-choices button:last-child {
		border-right: 0;
	}

	.source-choices button.selected {
		background: color-mix(in oklab, var(--accent-soft), var(--surface) 62%);
		box-shadow: inset 0 3px var(--accent);
	}

	.source-choices strong {
		font-size: 0.95rem;
	}

	.source-choices span {
		color: var(--muted);
		font-size: 0.82rem;
		font-weight: 500;
		line-height: 1.35;
	}

	.connected-sources {
		display: grid;
		border-top: 1px solid var(--line);
	}

	.import-source-row {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: 20px;
		align-items: center;
		min-height: 70px;
		padding: 12px 4px;
		border-bottom: 1px solid var(--line);
	}

	.source-copy {
		display: grid;
		gap: 3px;
		min-width: 0;
	}

	.source-copy span,
	.no-sources {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.import-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		justify-content: flex-end;
	}

	.import-actions form {
		display: inline-flex;
	}

	.source-error {
		color: var(--danger) !important;
	}

	.no-sources {
		margin: 0;
		padding: 18px 4px;
		border-bottom: 1px solid var(--line);
	}

	@media (max-width: 820px) {
		.source-choices {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}

		.source-choices button:nth-child(2) {
			border-right: 0;
		}

		.source-choices button:nth-child(-n + 2) {
			border-bottom: 1px solid var(--line);
		}
	}

	@media (max-width: 680px) {
		.import-source-row {
			grid-template-columns: 1fr;
			align-items: start;
		}

		.import-actions,
		.import-actions form,
		.import-actions button {
			width: 100%;
		}

		.import-actions {
			justify-content: stretch;
		}

		.import-actions form {
			flex: 1 1 130px;
		}
	}
</style>
