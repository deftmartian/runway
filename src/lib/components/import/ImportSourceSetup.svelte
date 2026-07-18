<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import {
		connectDeviceFolder,
		disconnectDeviceFolder,
		getDeviceFolderConnectionState,
		getDeviceFolderSupportState,
		retainDeviceFolderForUser,
		restoreDeviceFolderPermission,
		scanDeviceFolder,
		type DeviceFolderConnectionState,
		type DeviceFolderScanResult
	} from '$lib/pwa/device-folder';
	import { onMount } from 'svelte';
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

	let chosenImportSource = $state<ImportSourceKind | null>(null);
	let gpxMatchMode = $state<'unlinked' | 'auto' | 'workout'>('unlinked');
	let gpxWorkoutId = $state('');
	let deviceFolderState = $state<DeviceFolderConnectionState | 'loading'>('loading');
	let deviceFolderBusy = $state(false);
	let deviceFolderRemaining = $state(0);
	let deviceFolderResult = $state<{ message: string; failed: boolean } | null>(null);

	const selectedImportSource = $derived<ImportSourceKind | null>(
		chosenImportSource ?? (androidPairing ? 'android' : null)
	);
	const actionPending = (key: string) => activeAction === key;
	const sectionResult = (section: ImportSection) =>
		scopedResult?.section === section ? scopedResult : null;

	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const isoDay = (date: Date | string) =>
		date instanceof Date ? date.toISOString().slice(0, 10) : date.slice(0, 10);
	const day = (date: Date | string) =>
		new Date(`${isoDay(date)}T00:00:00`).toLocaleDateString(undefined, {
			weekday: 'short',
			month: 'short',
			day: 'numeric'
		});
	const dateTime = (value: Date | string | null) =>
		value
			? new Date(value).toLocaleString(undefined, {
					month: 'short',
					day: 'numeric',
					hour: 'numeric',
					minute: '2-digit'
				})
			: 'Not yet';

	onMount(() => {
		void initializeDeviceFolder();
	});

	async function initializeDeviceFolder() {
		const support = getDeviceFolderSupportState();
		if (support !== 'supported') {
			deviceFolderState = support;
			return;
		}
		try {
			await retainDeviceFolderForUser(userId);
		} catch {
			deviceFolderState = 'unlinked';
			deviceFolderResult = {
				message: 'Folder access could not be saved for this account.',
				failed: true
			};
			return;
		}
		await refreshDeviceFolderState();
	}

	async function refreshDeviceFolderState() {
		try {
			deviceFolderState = await getDeviceFolderConnectionState(userId);
		} catch {
			deviceFolderState = 'unlinked';
			deviceFolderResult = {
				message: 'Saved folder access could not be read in this browser.',
				failed: true
			};
		}
	}

	async function allowDeviceFolder() {
		if (deviceFolderBusy) return;
		deviceFolderBusy = true;
		deviceFolderResult = null;
		try {
			deviceFolderState = await connectDeviceFolder(userId);
			deviceFolderRemaining = 0;
			if (deviceFolderState === 'linked') await runDeviceFolderScan(true);
		} catch (error) {
			deviceFolderResult = {
				message:
					error instanceof DOMException && error.name === 'AbortError'
						? 'No folder was selected.'
						: 'Folder access could not be saved.',
				failed: !(error instanceof DOMException && error.name === 'AbortError')
			};
		} finally {
			deviceFolderBusy = false;
		}
	}

	async function restoreDeviceFolder() {
		if (deviceFolderBusy) return;
		deviceFolderBusy = true;
		deviceFolderResult = null;
		try {
			deviceFolderState = await restoreDeviceFolderPermission(userId);
			if (deviceFolderState === 'linked') {
				await runDeviceFolderScan(true);
			} else {
				deviceFolderResult = deviceFolderMessage({ result: deviceFolderState });
			}
		} catch {
			deviceFolderState = 'permission-required';
			deviceFolderResult = {
				message: 'Folder permission was not restored. Try again from this browser.',
				failed: true
			};
		} finally {
			deviceFolderBusy = false;
		}
	}

	async function scanDeviceFolderNow() {
		if (deviceFolderBusy) return;
		deviceFolderBusy = true;
		deviceFolderResult = null;
		try {
			await runDeviceFolderScan();
		} finally {
			deviceFolderBusy = false;
		}
	}

	function openAndroidFolderSettings() {
		if (!androidApplicationId) return;
		window.location.href = `intent://folder#Intent;scheme=runway-native;package=${androidApplicationId};end`;
	}

	async function runDeviceFolderScan(afterConnection = false) {
		let result: DeviceFolderScanResult;
		try {
			result = await scanDeviceFolder(userId);
			if (
				afterConnection &&
				deviceFolderState === 'linked' &&
				(result.result === 'unlinked' || result.result === 'permission-required')
			) {
				result = await scanDeviceFolder(userId);
			}
		} catch {
			result = { result: 'failed' };
		}
		if (typeof result.remaining === 'number') deviceFolderRemaining = result.remaining;
		if (result.result === 'none') deviceFolderRemaining = 0;
		deviceFolderResult = deviceFolderMessage(result);
		if (result.result === 'permission-required') deviceFolderState = 'permission-required';
		if (result.result === 'folder-missing') deviceFolderState = 'unlinked';
		if (result.result === 'imported') await invalidateAll();
	}

	async function removeDeviceFolder() {
		if (deviceFolderBusy) return;
		deviceFolderBusy = true;
		deviceFolderResult = null;
		try {
			await disconnectDeviceFolder(userId);
			deviceFolderState = 'unlinked';
			deviceFolderRemaining = 0;
			deviceFolderResult = {
				message: 'Gadgetbridge folder disconnected. No files were changed.',
				failed: false
			};
		} catch {
			deviceFolderResult = {
				message: 'Gadgetbridge folder could not be disconnected.',
				failed: true
			};
		} finally {
			deviceFolderBusy = false;
		}
	}

	function deviceFolderMessage(result: DeviceFolderScanResult) {
		const waiting = result.remaining ?? 0;
		const moreFiles =
			waiting > 0
				? ` ${waiting} more ${waiting === 1 ? 'file is' : 'files are'} waiting; scan again when ready.`
				: '';
		switch (result.result) {
			case 'imported':
				return { message: `GPX added to the activity inbox.${moreFiles}`, failed: false };
			case 'duplicate':
				return { message: `That GPX was already imported.${moreFiles}`, failed: false };
			case 'deleted':
				return {
					message: `That activity was previously deleted and was not imported.${moreFiles}`,
					failed: false
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
</script>

<section class="import-sources" aria-labelledby="import-sources-title">
	<header class="sources-heading">
		<h2 id="import-sources-title">Import sources</h2>
	</header>

	{#if deviceFolderResult}
		<p
			class="message compact-message"
			class:bad-message={deviceFolderResult.failed}
			role={deviceFolderResult.failed ? 'alert' : 'status'}
			aria-live="polite"
		>
			{deviceFolderResult.message}
		</p>
	{/if}
	{#if sectionResult('sources')}
		<p
			class="message compact-message"
			class:bad-message={sectionResult('sources')?.failed}
			role="status"
			aria-live="polite"
		>
			{sectionResult('sources')?.message}
		</p>
	{/if}

	<div class="connected-sources" aria-busy={deviceFolderBusy || activeSection === 'sources'}>
		{#if deviceFolderState === 'linked' || deviceFolderState === 'permission-required'}
			<div class="import-source-row">
				<div class="source-copy">
					<strong>Gadgetbridge folder</strong>
					<span class:source-error={deviceFolderState === 'permission-required'}>
						{deviceFolderState === 'linked'
							? deviceFolderRemaining > 0
								? `Connected · ${deviceFolderRemaining} ${deviceFolderRemaining === 1 ? 'file' : 'files'} waiting`
								: 'Connected on this browser · one file per scan'
							: 'Folder access required'}
					</span>
				</div>
				<div class="import-actions">
					{#if deviceFolderState === 'permission-required'}
						<button
							type="button"
							class="primary"
							disabled={deviceFolderBusy || !importTimeZoneConfigured}
							onclick={restoreDeviceFolder}
						>
							{deviceFolderBusy ? 'Restoring…' : 'Restore access'}
						</button>
					{:else}
						<button
							type="button"
							class="primary"
							disabled={deviceFolderBusy || !importTimeZoneConfigured}
							onclick={scanDeviceFolderNow}
						>
							{deviceFolderBusy
								? 'Checking…'
								: deviceFolderRemaining > 0
									? `Scan next (${deviceFolderRemaining})`
									: 'Scan now'}
						</button>
					{/if}
					<button type="button" disabled={deviceFolderBusy} onclick={removeDeviceFolder}>
						Disconnect
					</button>
				</div>
			</div>
		{/if}

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

		{#if sources.length === 0 && androidDevices.length === 0 && deviceFolderState !== 'linked' && deviceFolderState !== 'permission-required'}
			<p class="no-sources">No import sources connected.</p>
		{/if}
	</div>

	<details class="source-setup">
		<summary>Add import source</summary>
		<div class="source-setup-body">
			<div class="source-choices" role="group" aria-label="Choose an import source">
				<button
					type="button"
					class:selected={selectedImportSource === 'android'}
					aria-pressed={selectedImportSource === 'android'}
					onclick={() => (chosenImportSource = 'android')}
				>
					<strong>Android folder</strong>
					<span>Import in the background from the installed app.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'browser'}
					aria-pressed={selectedImportSource === 'browser'}
					onclick={() => (chosenImportSource = 'browser')}
				>
					<strong>Browser folder</strong>
					<span>Check a Gadgetbridge folder while runway is open.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'nextcloud'}
					aria-pressed={selectedImportSource === 'nextcloud'}
					onclick={() => (chosenImportSource = 'nextcloud')}
				>
					<strong>Nextcloud</strong>
					<span>Sync a password-protected shared folder.</span>
				</button>
				<button
					type="button"
					class:selected={selectedImportSource === 'upload'}
					aria-pressed={selectedImportSource === 'upload'}
					onclick={() => (chosenImportSource = 'upload')}
				>
					<strong>Upload GPX</strong>
					<span>Choose one file from this device.</span>
				</button>
			</div>

			{#if selectedImportSource === 'android'}
				<section class="setup-section selected-setup" aria-labelledby="android-app-heading">
					<h3 id="android-app-heading">Android folder</h3>
					<p>
						Pair this account with the installed runway app, then choose the folder that receives
						GPX files.
					</p>
					{#if androidPairing}
						<div class="pairing-code" role="status" aria-live="polite">
							<span>Pairing code</span>
							<strong>{androidPairing.code}</strong>
							<small
								>Enter it in the Android Folder screen by {dateTime(
									androidPairing.expiresAt
								)}.</small
							>
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
			{:else if selectedImportSource === 'browser'}
				<section class="setup-section selected-setup" aria-labelledby="device-folder-heading">
					<h3 id="device-folder-heading">Browser folder</h3>
					<p>
						Choose the Gadgetbridge export folder. runway checks it when this app opens or returns
						to the foreground.
					</p>
					{#if deviceFolderState === 'https-required'}
						<p class="source-error">
							Folder access requires HTTPS. Open the secure runway address, then try again.
						</p>
					{:else if deviceFolderState === 'unsupported'}
						<p class="source-error">
							This browser cannot retain folder access. Share or upload the GPX instead.
						</p>
					{:else if deviceFolderState === 'loading'}
						<p>Checking folder access…</p>
					{:else if deviceFolderState === 'unlinked'}
						<button
							type="button"
							class="primary"
							disabled={deviceFolderBusy || !importTimeZoneConfigured}
							onclick={allowDeviceFolder}
						>
							{deviceFolderBusy ? 'Opening…' : 'Allow device folder'}
						</button>
					{:else}
						<p>Already connected.</p>
					{/if}
				</section>
			{:else if selectedImportSource === 'nextcloud'}
				<section class="setup-section selected-setup" aria-labelledby="nextcloud-heading">
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
			{:else if selectedImportSource === 'upload'}
				<section class="setup-section selected-setup" aria-labelledby="manual-gpx-heading">
					<h3 id="manual-gpx-heading">Upload GPX</h3>
					<p>Choose a GPX file. Nothing affects your plan until you review or match the run.</p>
					{#if sectionResult('gpx')}
						<p
							class="message compact-message"
							class:bad-message={sectionResult('gpx')?.failed}
							role="status"
							aria-live="polite"
						>
							{sectionResult('gpx')?.message}
						</p>
					{/if}
					<form
						method="post"
						action="?/importGpx"
						enctype="multipart/form-data"
						use:enhance={scopedEnhance('import-gpx', 'gpx')}
					>
						<label>
							GPX file
							<input
								type="file"
								name="file"
								accept=".gpx,application/gpx+xml,application/xml,text/xml"
								required
							/>
						</label>
						<fieldset class="gpx-match-options">
							<legend>Plan matching</legend>
							<label class="gpx-match-option">
								<input type="radio" name="matchMode" value="unlinked" bind:group={gpxMatchMode} />
								<span>
									<strong>Leave in inbox for review</strong>
									<small>No workout is completed until you review it.</small>
								</span>
							</label>
							<label class="gpx-match-option">
								<input type="radio" name="matchMode" value="auto" bind:group={gpxMatchMode} />
								<span>
									<strong>Auto-match by date and distance</strong>
									<small>A close available workout will be completed.</small>
								</span>
							</label>
							{#if candidates.length > 0}
								<label class="gpx-match-option">
									<input type="radio" name="matchMode" value="workout" bind:group={gpxMatchMode} />
									<span>
										<strong>Choose a planned workout</strong>
										<small>Completes the selected workout.</small>
									</span>
								</label>
								<label class="gpx-workout-select">
									Planned workout
									<select
										name="workoutId"
										bind:value={gpxWorkoutId}
										required={gpxMatchMode === 'workout'}
										onchange={() => {
											if (gpxWorkoutId) gpxMatchMode = 'workout';
										}}
									>
										<option value="">Choose a workout</option>
										{#each candidates as workout (workout.id)}
											<option value={workout.id}>
												{day(workout.scheduledDate)} · {workout.purpose} · {km(
													workout.targetDistanceMeters
												)}
											</option>
										{/each}
									</select>
								</label>
							{/if}
						</fieldset>
						<button class="primary" disabled={activeAction !== null || !importTimeZoneConfigured}>
							{actionPending('import-gpx') ? 'Importing…' : 'Import'}
						</button>
					</form>
				</section>
			{/if}

			<details class="import-privacy">
				<summary>What runway stores</summary>
				<p>
					Raw GPX files are discarded after import. runway keeps the activity totals and
					{routeDataMode === 'private'
						? ' a simplified private route trace.'
						: ' discards route points after calculating those totals.'}
					Browser folder permission stays in this browser. Android pairing does not store your password
					or browser session on the device.
				</p>
			</details>
		</div>
	</details>
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
	.setup-section > p,
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

	.selected-setup {
		display: grid;
		align-content: start;
		gap: 12px;
		width: min(100%, 700px);
		min-width: 0;
		padding: 20px;
		border-block: 1px solid var(--line);
	}

	.setup-section h3 {
		margin: 0;
		font-size: 1rem;
	}

	.setup-section > p {
		margin: 0;
	}

	.setup-section > .message {
		color: var(--text);
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

	.import-privacy {
		width: min(100%, 700px);
		padding-top: 2px;
		color: var(--muted);
		font-size: 0.86rem;
	}

	.import-privacy > summary {
		display: inline-flex;
		align-items: center;
		min-height: 44px;
		color: var(--text);
		font-weight: 700;
		cursor: pointer;
	}

	.import-privacy p {
		max-width: 72ch;
		margin: 4px 0 0;
		line-height: 1.5;
	}

	.gpx-match-options {
		padding: 10px;
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
