<script lang="ts">
	import { invalidateAll } from '$app/navigation';
	import {
		cancelDeviceFolderScan,
		connectDeviceFolder,
		disconnectDeviceFolder,
		getDeviceFolderConnectionState,
		getDeviceFolderSupportState,
		retainDeviceFolderForUser,
		restoreDeviceFolderPermission,
		scanDeviceFolder,
		type DeviceFolderConnectionState,
		type DeviceFolderScanProgress,
		type DeviceFolderScanResult
	} from '$lib/pwa/device-folder';
	import { onMount } from 'svelte';
	import ImportPrivacy from './ImportPrivacy.svelte';

	let {
		userId,
		importTimeZoneConfigured,
		routeDataMode,
		selected,
		onConnectionChange
	}: {
		userId: string;
		importTimeZoneConfigured: boolean;
		routeDataMode: 'private' | 'discard';
		selected: boolean;
		onConnectionChange?: (connected: boolean) => void;
	} = $props();

	let folderState = $state<DeviceFolderConnectionState | 'loading'>('loading');
	let busy = $state(false);
	let scanning = $state(false);
	let scanProgress = $state<DeviceFolderScanProgress | null>(null);
	let remaining = $state(0);
	let resultMessage = $state<{ message: string; failed: boolean } | null>(null);

	$effect(() => {
		onConnectionChange?.(folderState === 'linked' || folderState === 'permission-required');
	});

	onMount(() => {
		void initialize();
	});

	async function initialize() {
		const support = getDeviceFolderSupportState();
		if (support !== 'supported') {
			folderState = support;
			return;
		}
		try {
			await retainDeviceFolderForUser(userId);
		} catch {
			folderState = 'unlinked';
			resultMessage = {
				message: 'Folder access could not be saved for this account.',
				failed: true
			};
			return;
		}
		await refreshState();
	}

	async function refreshState() {
		try {
			folderState = await getDeviceFolderConnectionState(userId);
		} catch {
			folderState = 'unlinked';
			resultMessage = {
				message: 'Saved folder access could not be read in this browser.',
				failed: true
			};
		}
	}

	async function allowFolder() {
		if (busy) return;
		busy = true;
		resultMessage = null;
		try {
			folderState = await connectDeviceFolder(userId);
			remaining = 0;
			if (folderState === 'linked') await runScan(true);
		} catch (error) {
			resultMessage = {
				message:
					error instanceof DOMException && error.name === 'AbortError'
						? 'No folder was selected.'
						: 'Folder access could not be saved.',
				failed: !(error instanceof DOMException && error.name === 'AbortError')
			};
		} finally {
			busy = false;
		}
	}

	async function restoreFolder() {
		if (busy) return;
		busy = true;
		resultMessage = null;
		try {
			folderState = await restoreDeviceFolderPermission(userId);
			if (folderState === 'linked') {
				await runScan(true);
			} else {
				resultMessage = presentScanResult({ result: folderState });
			}
		} catch {
			folderState = 'permission-required';
			resultMessage = {
				message: 'Folder permission was not restored. Try again from this browser.',
				failed: true
			};
		} finally {
			busy = false;
		}
	}

	async function scanNow() {
		if (busy) return;
		busy = true;
		resultMessage = null;
		try {
			await runScan();
		} finally {
			busy = false;
		}
	}

	async function runScan(afterConnection = false) {
		scanning = true;
		scanProgress = null;
		let result: DeviceFolderScanResult;
		try {
			result = await scanDeviceFolder(userId, {
				onProgress: (progress) => (scanProgress = progress)
			});
			if (
				afterConnection &&
				folderState === 'linked' &&
				(result.result === 'unlinked' || result.result === 'permission-required')
			) {
				result = await scanDeviceFolder(userId, {
					onProgress: (progress) => (scanProgress = progress)
				});
			}
		} catch {
			result = { result: 'failed' };
		} finally {
			scanning = false;
		}
		if (typeof result.remaining === 'number') remaining = result.remaining;
		if (result.result === 'none') remaining = 0;
		resultMessage = presentScanResult(result);
		if (result.result === 'permission-required') folderState = 'permission-required';
		if (result.result === 'folder-missing') folderState = 'unlinked';
		if (result.result === 'disconnected') folderState = 'unlinked';
		if (result.result === 'imported') await invalidateAll();
	}

	function cancelScan() {
		cancelDeviceFolderScan(userId);
	}

	async function disconnectFolder() {
		if (busy) return;
		busy = true;
		resultMessage = null;
		try {
			await disconnectDeviceFolder(userId);
			folderState = 'unlinked';
			remaining = 0;
			resultMessage = {
				message: 'Gadgetbridge folder disconnected. No files were changed.',
				failed: false
			};
		} catch {
			resultMessage = {
				message: 'Gadgetbridge folder could not be disconnected.',
				failed: true
			};
		} finally {
			busy = false;
		}
	}

	function presentScanResult(result: DeviceFolderScanResult) {
		const waiting = result.remaining ?? 0;
		const moreFiles =
			waiting > 0
				? ` ${waiting} more ${waiting === 1 ? 'file is' : 'files are'} waiting; scan again when ready.`
				: '';
		const incomplete = result.scanIncomplete
			? ' Some GPX files could not be read; scan again to continue.'
			: '';
		switch (result.result) {
			case 'imported':
				return {
					message: `GPX added to the activity inbox.${moreFiles}${incomplete}`,
					failed: false
				};
			case 'duplicate':
				return {
					message: `That GPX was already imported.${moreFiles}${incomplete}`,
					failed: false
				};
			case 'deleted':
				return {
					message: `That activity was previously deleted and was not imported.${moreFiles}${incomplete}`,
					failed: false
				};
			case 'disconnected':
				return {
					message: 'The folder was disconnected before that GPX could be added.',
					failed: true
				};
			case 'rate-limited':
				return {
					message: result.retryAfterSeconds
						? `Too many imports are running. Try again in ${result.retryAfterSeconds} seconds.`
						: 'Too many imports are running. Try again shortly.',
					failed: true
				};
			case 'none':
				return { message: 'No new GPX files found.', failed: false };
			case 'permission-required':
				return { message: 'Restore access to the Gadgetbridge folder.', failed: true };
			case 'folder-missing':
				return { message: 'The saved folder moved or was removed. Choose it again.', failed: true };
			case 'folder-unavailable':
				return {
					message: 'The folder is temporarily unavailable. Unlock the device and try again.',
					failed: true
				};
			case 'time-zone-required':
				return { message: 'Set the training time zone before importing.', failed: true };
			case 'future':
				return {
					message: `The newest GPX has a future date and was skipped. Correct the device clock, then add a corrected export.${moreFiles}`,
					failed: true
				};
			case 'too-large':
				return { message: `The newest GPX exceeds the 10 MB limit.${moreFiles}`, failed: true };
			case 'invalid':
				return {
					message: `The newest file is not a valid GPX activity.${moreFiles}`,
					failed: true
				};
			case 'too-many-files':
				return {
					message: 'Choose a dedicated GPX export folder with fewer files.',
					failed: true
				};
			case 'timed-out': {
				const checked = result.checkedCandidates ?? 0;
				return {
					message:
						result.totalCandidates === undefined
							? 'The folder stopped responding before runway could list it. Unlock the device and try again.'
							: `The folder stopped responding after ${checked} of ${result.totalCandidates} GPX files. Try again to continue with the next files.`,
					failed: true
				};
			}
			case 'cancelled':
				return { message: 'Folder check cancelled. No files were changed.', failed: false };
			case 'unsupported':
				return { message: 'This browser does not support folder access.', failed: true };
			case 'https-required':
				return { message: 'Open runway over HTTPS to allow a device folder.', failed: true };
			case 'unlinked':
				return { message: 'Allow a Gadgetbridge folder before scanning.', failed: true };
			default:
				return { message: 'The Gadgetbridge folder could not be checked.', failed: true };
		}
	}

	function scanProgressMessage(progress: DeviceFolderScanProgress | null): string {
		if (!progress || progress.phase === 'enumerating') return 'Reading folder…';
		if (progress.phase === 'uploading') return 'Adding GPX…';
		return `Checking ${progress.completed} of ${progress.total ?? 0} GPX files…`;
	}
</script>

{#if resultMessage}
	<p
		class="message compact-message"
		class:bad-message={resultMessage.failed}
		role={resultMessage.failed ? 'alert' : 'status'}
		aria-live="polite"
	>
		{resultMessage.message}
	</p>
{/if}

{#if selected}
	<section class="setup-section" aria-labelledby="device-folder-heading" aria-busy={busy}>
		<h3 id="device-folder-heading">Browser folder</h3>
		<p>
			Choose the Gadgetbridge export folder. runway checks it when this app opens or returns to the
			foreground.
		</p>
		{#if folderState === 'https-required'}
			<p class="source-error">
				Folder access requires HTTPS. Open the secure runway address, then try again.
			</p>
		{:else if folderState === 'unsupported'}
			<p class="source-error">
				This browser cannot retain folder access. Share or upload the GPX instead.
			</p>
		{:else if folderState === 'loading'}
			<p>Checking folder access…</p>
		{:else if folderState === 'unlinked'}
			<button
				type="button"
				class="primary"
				disabled={busy || !importTimeZoneConfigured}
				onclick={allowFolder}
			>
				{busy ? 'Opening…' : 'Allow device folder'}
			</button>
		{:else}
			<p>Already connected.</p>
		{/if}
	</section>
	<ImportPrivacy {routeDataMode} />
{/if}

{#if scanning}
	<p class="scan-progress" role="status" aria-live="polite">
		{scanProgressMessage(scanProgress)}
	</p>
{/if}

{#if folderState === 'linked' || folderState === 'permission-required'}
	<div class="import-source-row" aria-busy={busy}>
		<div class="source-copy">
			<strong>Gadgetbridge folder</strong>
			<span class:source-error={folderState === 'permission-required'}>
				{folderState === 'linked'
					? remaining > 0
						? `Connected · ${remaining} ${remaining === 1 ? 'file' : 'files'} waiting`
						: 'Connected on this browser · one file per scan'
					: 'Folder access required'}
			</span>
		</div>
		<div class="import-actions">
			{#if scanning}
				<button type="button" onclick={cancelScan}>Cancel check</button>
			{/if}
			{#if folderState === 'permission-required'}
				<button
					type="button"
					class="primary"
					disabled={busy || !importTimeZoneConfigured}
					onclick={restoreFolder}
				>
					{busy ? 'Restoring…' : 'Restore access'}
				</button>
			{:else}
				<button
					type="button"
					class="primary"
					disabled={busy || !importTimeZoneConfigured}
					onclick={scanNow}
				>
					{busy ? 'Checking…' : remaining > 0 ? `Scan next (${remaining})` : 'Scan now'}
				</button>
			{/if}
			<button type="button" disabled={busy} onclick={disconnectFolder}>Disconnect</button>
		</div>
	</div>
{/if}

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

	.setup-section p,
	.source-copy span {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.setup-section > button {
		justify-self: start;
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

	.import-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 8px;
		justify-content: flex-end;
	}

	.source-error {
		color: var(--danger) !important;
	}

	.scan-progress {
		margin: 0;
		padding: 10px 4px;
		color: var(--muted);
		font-size: 0.9rem;
	}

	@media (max-width: 680px) {
		.import-source-row {
			grid-template-columns: 1fr;
			align-items: start;
		}

		.import-actions,
		.import-actions button {
			width: 100%;
		}
	}
</style>
