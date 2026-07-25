<script lang="ts">
	import { resolve } from '$app/paths';
	import ActivityInbox from '$lib/components/import/ActivityInbox.svelte';
	import ImportSourceSetup from '$lib/components/import/ImportSourceSetup.svelte';
	import type { ImportSection, ScopedImportResult } from '$lib/components/import/import-view-model';
	import type { SubmitFunction } from '@sveltejs/kit';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	let activeAction = $state<string | null>(null);
	let activeSection = $state<ImportSection | null>(null);
	let scopedResult = $state<ScopedImportResult | null>(null);

	const formMessage = $derived(
		form && 'message' in form && typeof form.message === 'string' ? form.message : null
	);
	const androidPairing = $derived(
		form &&
			'pairingCode' in form &&
			typeof form.pairingCode === 'string' &&
			'pairingExpiresAt' in form &&
			typeof form.pairingExpiresAt === 'string'
			? { code: form.pairingCode, expiresAt: form.pairingExpiresAt }
			: null
	);

	const scopedEnhance =
		(key: string, section: ImportSection): SubmitFunction =>
		({ cancel }) => {
			if (activeAction) {
				cancel();
				return;
			}
			activeAction = key;
			activeSection = section;
			scopedResult = null;
			return async ({ result, update }) => {
				try {
					const resultData =
						result.type === 'success' || result.type === 'failure'
							? (result.data as { message?: unknown } | undefined)
							: undefined;
					const message =
						typeof resultData?.message === 'string'
							? resultData.message
							: result.type === 'error'
								? 'The request could not be completed.'
								: 'Import data updated.';
					const nextScopedResult: ScopedImportResult = {
						section,
						message,
						failed: result.type === 'failure' || result.type === 'error'
					};
					await update();
					scopedResult = nextScopedResult;
				} finally {
					activeAction = null;
					activeSection = null;
				}
			};
		};
</script>

<main class="page import-page">
	{#if !data.importTimeZoneConfigured}
		<section class="message time-zone-warning" role="alert">
			<div>
				<strong>Choose a training time zone before importing.</strong>
				<span>Import controls stay locked until activity dates can be interpreted.</span>
			</div>
			<a class="button primary" href={resolve('/app/settings')}>Open Settings</a>
		</section>
	{/if}

	<ActivityInbox
		activities={data.activities}
		candidates={data.candidates}
		shareNotice={data.shareNotice}
		{formMessage}
		importTimeZoneConfigured={data.importTimeZoneConfigured}
		{activeAction}
		{activeSection}
		{scopedResult}
		{scopedEnhance}
	/>

	<ImportSourceSetup
		userId={data.user.id}
		candidates={data.candidates}
		sources={data.sources}
		androidDevices={data.androidDevices}
		androidApplicationId={data.androidApplicationId}
		importTimeZoneConfigured={data.importTimeZoneConfigured}
		routeDataMode={data.routeDataMode}
		{androidPairing}
		startOpen={data.activities.items.length === 0 &&
			data.sources.length === 0 &&
			data.androidDevices.length === 0}
		{activeAction}
		{activeSection}
		{scopedResult}
		{scopedEnhance}
	/>
</main>

<style>
	.import-page {
		display: grid;
		gap: clamp(24px, 4vw, 44px);
		max-width: 1120px;
		margin-inline: auto;
	}

	.import-page :global(.import-inbox),
	.import-page :global(.import-sources) {
		padding: 0;
		border: 0;
		border-radius: 0;
		background: transparent;
		box-shadow: none;
	}

	.import-page :global(.inbox-heading h1),
	.import-page :global(.sources-heading h2) {
		font-size: clamp(1.4rem, 3vw, 1.75rem);
		letter-spacing: -0.03em;
	}

	.import-page :global(.activity-record),
	.import-page :global(.source-row) {
		border-color: var(--line-passive);
		border-radius: 0;
		background: transparent;
		box-shadow: none;
	}

	.import-page :global(.import-summary-grid) {
		border-block: 1px solid var(--line-passive);
	}

	.import-page :global(.import-summary-grid dd),
	.import-page :global(.activity-record time) {
		font-family: var(--measure-font);
	}

	.import-page :global(.time-zone-warning) {
		border-color: var(--line-control);
		border-radius: var(--radius-small);
		background: var(--surface-soft);
		box-shadow: none;
	}

	@media (max-width: 680px) {
		.import-page {
			gap: 30px;
		}
	}
</style>
