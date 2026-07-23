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
				<strong>Set the training time zone before importing.</strong>
				<span>Activity dates and workout matches depend on it.</span>
			</div>
			<a class="button" href={resolve('/app/settings')}>Open Settings</a>
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
		gap: clamp(28px, 5vw, 56px);
		max-width: 1120px;
		margin-inline: auto;
	}

	@media (max-width: 680px) {
		.import-page {
			gap: 36px;
		}
	}
</style>
