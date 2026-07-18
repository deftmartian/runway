<script lang="ts">
	import { enhance } from '$app/forms';
	import { invalidateAll } from '$app/navigation';
	import { resolve } from '$app/paths';
	import StateMarker from '$lib/components/visual/StateMarker.svelte';
	import ActivityVisuals from '$lib/components/training/ActivityVisuals.svelte';
	import {
		connectDeviceFolder,
		disconnectDeviceFolder,
		getDeviceFolderConnectionState,
		retainDeviceFolderForUser,
		restoreDeviceFolderPermission,
		scanDeviceFolder,
		supportsDeviceFolderImport,
		type DeviceFolderConnectionState,
		type DeviceFolderScanResult
	} from '$lib/pwa/device-folder';
	import type { SubmitFunction } from '@sveltejs/kit';
	import type { ActivityRouteTrace, HeartRateSeries } from '$lib/training/types';
	import { onMount } from 'svelte';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	type ImportSection = 'activities' | 'sources' | 'gpx';
	type ScopedResult = { section: ImportSection; message: string; failed: boolean };
	let activeAction = $state<string | null>(null);
	let activeSection = $state<ImportSection | null>(null);
	let scopedResult = $state<ScopedResult | null>(null);
	let gpxMatchMode = $state<'unlinked' | 'auto' | 'workout'>('unlinked');
	let gpxWorkoutId = $state('');
	let deviceFolderState = $state<DeviceFolderConnectionState | 'loading'>('loading');
	let deviceFolderBusy = $state(false);
	let deviceFolderResult = $state<{ message: string; failed: boolean } | null>(null);
	type ActivityTraceDetail = {
		id: string;
		routeTrace: ActivityRouteTrace | null;
		heartRateSeries: HeartRateSeries | null;
	};
	let activityTraceDetails = $state<
		Record<string, ActivityTraceDetail | 'loading' | 'failed' | undefined>
	>({});

	onMount(() => {
		void initializeDeviceFolder();
	});

	async function initializeDeviceFolder() {
		if (!supportsDeviceFolderImport()) {
			deviceFolderState = 'unsupported';
			return;
		}
		try {
			await retainDeviceFolderForUser(data.user.id);
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
	const duration = (seconds: number | null | undefined) => {
		if (!seconds) return 'No duration';
		const minutes = Math.round(seconds / 60);
		if (minutes < 60) return `${minutes} min`;
		return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
	};
	const dateDistanceDays = (left: string, right: string) =>
		Math.abs(
			(Date.parse(`${left}T00:00:00.000Z`) - Date.parse(`${right}T00:00:00.000Z`)) /
				(24 * 60 * 60 * 1000)
		);
	const candidatesForActivity = (occurredDate: string) =>
		data.candidates.filter(
			(candidate) => dateDistanceDays(candidate.scheduledDate, occurredDate) <= 3
		);
	const confirmDeleteActivity = (event: SubmitEvent) => {
		if (!confirm('Delete this activity? This cannot be undone.')) event.preventDefault();
	};
	const unlinkedCount = $derived(
		data.activities.items.filter(
			(activity) => !activity.workoutId && !activity.extraPlanImpactConfirmed
		).length
	);
	const sectionResult = (section: ImportSection) =>
		scopedResult?.section === section ? scopedResult : null;
	const actionPending = (key: string) => activeAction === key;
	const activityTraceDetail = (activityId: string) => activityTraceDetails[activityId];
	async function loadActivityTrace(event: Event, activityId: string) {
		const disclosure = event.currentTarget;
		if (!(disclosure instanceof HTMLDetailsElement) || !disclosure.open) return;
		if (activityTraceDetails[activityId]) return;
		activityTraceDetails[activityId] = 'loading';
		try {
			const response = await fetch(resolve('/app/import/activity/[activityId]', { activityId }), {
				headers: { accept: 'application/json' }
			});
			if (!response.ok) throw new Error('Activity detail request failed.');
			activityTraceDetails[activityId] = (await response.json()) as ActivityTraceDetail;
		} catch {
			activityTraceDetails[activityId] = 'failed';
		}
	}
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
					scopedResult = {
						section,
						message,
						failed: result.type === 'failure' || result.type === 'error'
					};
					await update();
				} finally {
					activeAction = null;
					activeSection = null;
				}
			};
		};

	async function refreshDeviceFolderState() {
		try {
			deviceFolderState = await getDeviceFolderConnectionState(data.user.id);
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
			deviceFolderState = await connectDeviceFolder(data.user.id);
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
			deviceFolderState = await restoreDeviceFolderPermission(data.user.id);
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

	async function runDeviceFolderScan(afterConnection = false) {
		let result: DeviceFolderScanResult;
		try {
			result = await scanDeviceFolder(data.user.id);
			// Returning from the operating-system picker can trigger the global focus scan
			// just before the selected handle is stored. Retry that narrow race once.
			if (
				afterConnection &&
				deviceFolderState === 'linked' &&
				(result.result === 'unlinked' || result.result === 'permission-required')
			) {
				result = await scanDeviceFolder(data.user.id);
			}
		} catch {
			result = { result: 'failed' };
		}
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
			await disconnectDeviceFolder(data.user.id);
			deviceFolderState = 'unlinked';
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
		switch (result.result) {
			case 'imported':
				return { message: 'GPX added to the activity inbox.', failed: false };
			case 'duplicate':
				return { message: 'That GPX was already imported.', failed: false };
			case 'deleted':
				return {
					message: 'That activity was previously deleted and was not imported.',
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
					message: 'The newest GPX has a future date. Correct the device clock, then try again.',
					failed: true
				};
			case 'too-large':
				return { message: 'The newest GPX exceeds the 10 MB limit.', failed: true };
			case 'invalid':
				return { message: 'The newest file is not a valid GPX activity.', failed: true };
			case 'too-many-files':
				return {
					message: 'Choose a dedicated GPX export folder with fewer files.',
					failed: true
				};
			case 'unsupported':
				return { message: 'This browser does not support folder access.', failed: true };
			case 'unlinked':
				return { message: 'Allow a Gadgetbridge folder before scanning.', failed: true };
			default:
				return { message: 'The Gadgetbridge folder could not be checked.', failed: true };
		}
	}
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

	<section class="import-inbox" aria-labelledby="activity-inbox-title">
		<header class="inbox-heading">
			<div>
				<h1 id="activity-inbox-title">Activity inbox</h1>
				<p>Link each imported run, count it as extra training, or delete it.</p>
			</div>
			<span class:clear={unlinkedCount === 0} class="review-count">
				{unlinkedCount === 1
					? '1 to review on this page'
					: `${unlinkedCount} to review on this page`}
			</span>
		</header>

		{#if data.shareNotice}
			<p
				class="message"
				class:bad-message={data.shareNotice.failed}
				role={data.shareNotice.failed ? 'alert' : 'status'}
				aria-live="polite"
			>
				{data.shareNotice.message}
			</p>
		{/if}
		{#if form?.message && !scopedResult}
			<p class="message" role="status" aria-live="polite">{form.message}</p>
		{/if}
		{#if sectionResult('activities')}
			<p
				class="message compact-message"
				class:bad-message={sectionResult('activities')?.failed}
				role="status"
				aria-live="polite"
			>
				{sectionResult('activities')?.message}
			</p>
		{/if}

		<div class="activity-list" aria-busy={activeSection === 'activities'}>
			{#each data.activities.items as activity (activity.id)}
				{@const candidates = candidatesForActivity(activity.activityDate)}
				{@const traceDetail = activityTraceDetail(activity.id)}
				<details
					class="activity-record"
					class:needs-review={!activity.workoutId && !activity.extraPlanImpactConfirmed}
					ontoggle={(event) => loadActivityTrace(event, activity.id)}
				>
					<summary>
						<StateMarker
							label={activity.workoutId
								? 'Linked'
								: activity.extraPlanImpactConfirmed
									? 'Counted as extra'
									: 'Needs review'}
							tone={activity.workoutId || activity.extraPlanImpactConfirmed
								? 'completed'
								: 'review'}
						/>
						<span class="record-copy">
							<strong>{day(activity.activityDate)} · {km(activity.distanceMeters)}</strong>
							<span class="record-meta">
								{activity.source.toUpperCase()} · {duration(activity.durationSeconds)}
								{activity.source === 'gpx' ? 'elapsed' : 'reported'}{activity.source === 'gpx'
									? ` · ${activity.routeSummary.pointCount} route points`
									: ''}
							</span>
							{#if activity.matchedWorkoutPurpose}
								<span class="record-outcome">
									{activity.matchedWorkoutPurpose} · {day(
										activity.matchedWorkoutDate ?? activity.activityDate
									)}
								</span>
							{:else if activity.extraPlanImpactConfirmed}
								<span class="record-outcome">Included in training load</span>
							{/if}
						</span>
						<span class="summary-action">
							{!activity.workoutId && !activity.extraPlanImpactConfirmed ? 'Review' : 'Manage'}
						</span>
					</summary>

					<div class="record-visuals" aria-live="polite">
						{#if traceDetail && traceDetail !== 'loading' && traceDetail !== 'failed'}
							<ActivityVisuals
								id={`inbox-${activity.id}`}
								routeTrace={traceDetail.routeTrace}
								heartRateSeries={traceDetail.heartRateSeries}
								heartRateSummary={activity.heartRateSummary}
								averageHeartRate={activity.averageHeartRate}
								maxHeartRate={activity.maxHeartRate}
								durationSeconds={activity.durationSeconds}
							/>
						{:else if traceDetail === 'loading'}
							<p class="muted activity-trace-note">Loading private activity detail…</p>
						{:else if traceDetail === 'failed'}
							<p class="message compact-message">Activity visuals could not be loaded.</p>
						{/if}
						{#if activity.source === 'gpx' && traceDetail && traceDetail !== 'loading' && traceDetail !== 'failed' && !traceDetail.routeTrace}
							<p class="muted activity-trace-note">
								This import predates saved route traces. Future GPX imports can include the route
								map.
							</p>
						{/if}
					</div>

					<div class="record-decisions">
						{#if !activity.workoutId}
							<section class="decision-group" aria-labelledby={`match-${activity.id}`}>
								<h2 id={`match-${activity.id}`}>Link to the plan</h2>
								{#if candidates.length > 0}
									<form
										method="post"
										action="?/linkActivity"
										use:enhance={scopedEnhance(`link-${activity.id}`, 'activities')}
										class="match-form"
									>
										<input type="hidden" name="activityId" value={activity.id} />
										<label>
											Planned workout
											<select name="workoutId" required>
												{#each candidates as workout (workout.id)}
													<option value={workout.id}>
														{day(workout.scheduledDate)} · {workout.purpose} · {km(
															workout.targetDistanceMeters
														)}
													</option>
												{/each}
											</select>
										</label>
										<button class="primary" disabled={activeAction !== null}>
											{actionPending(`link-${activity.id}`) ? 'Linking…' : 'Link to workout'}
										</button>
									</form>
								{:else}
									<p>No planned workout is available within three days of this activity.</p>
								{/if}
							</section>

							{#if !activity.extraPlanImpactConfirmed}
								<section class="decision-group" aria-labelledby={`extra-${activity.id}`}>
									<h2 id={`extra-${activity.id}`}>Count it separately</h2>
									<p>
										This adds the activity to training load. The current plan stays unchanged until
										you choose a separate plan decision.
									</p>
									<form
										method="post"
										action="?/confirmActivityExtra"
										use:enhance={scopedEnhance(`extra-${activity.id}`, 'activities')}
										onsubmit={(event) => {
											if (
												!confirm(
													'Count this as extra training? The current plan will stay unchanged.'
												)
											) {
												event.preventDefault();
											}
										}}
									>
										<input type="hidden" name="activityId" value={activity.id} />
										<button disabled={activeAction !== null}>
											{actionPending(`extra-${activity.id}`)
												? 'Counting…'
												: 'Count as extra training'}
										</button>
									</form>
								</section>
							{/if}
						{/if}

						<section class="decision-group" aria-labelledby={`feedback-${activity.id}`}>
							<h2 id={`feedback-${activity.id}`}>Run feedback</h2>
							<form
								method="post"
								action="?/updateActivityFeedback"
								use:enhance={scopedEnhance(`feedback-${activity.id}`, 'activities')}
								class="feedback-form"
							>
								<input type="hidden" name="activityId" value={activity.id} />
								<fieldset>
									<label>
										<input type="checkbox" name="feltHard" checked={activity.feltHard} />
										Effort was unusually hard
									</label>
									<label>
										<input type="checkbox" name="pain" checked={activity.pain} />
										Pain changed or limited this run
									</label>
								</fieldset>
								<p class="muted">Hard effort changes load advice. Pain triggers the safety path.</p>
								<button disabled={activeAction !== null}>
									{actionPending(`feedback-${activity.id}`) ? 'Saving…' : 'Save feedback'}
								</button>
							</form>
						</section>

						{#if activity.workoutId}
							<section class="decision-group" aria-labelledby={`unlink-${activity.id}`}>
								<h2 id={`unlink-${activity.id}`}>Plan link</h2>
								<p>Unlinking returns this activity to the inbox for review.</p>
								<form
									method="post"
									action="?/unlinkActivity"
									use:enhance={scopedEnhance(`unlink-${activity.id}`, 'activities')}
								>
									<input type="hidden" name="activityId" value={activity.id} />
									<button disabled={activeAction !== null}>
										{actionPending(`unlink-${activity.id}`) ? 'Unlinking…' : 'Unlink'}
									</button>
								</form>
							</section>
						{/if}

						<section class="decision-group delete-group" aria-labelledby={`delete-${activity.id}`}>
							<h2 id={`delete-${activity.id}`}>Delete activity</h2>
							<p>This removes the imported activity. It cannot be undone.</p>
							<form
								method="post"
								action="?/deleteActivity"
								use:enhance={scopedEnhance(`delete-${activity.id}`, 'activities')}
								onsubmit={confirmDeleteActivity}
							>
								<input type="hidden" name="activityId" value={activity.id} />
								<button class="danger" disabled={activeAction !== null}>
									{actionPending(`delete-${activity.id}`) ? 'Deleting…' : 'Delete activity'}
								</button>
							</form>
						</section>
					</div>
				</details>
			{:else}
				<div class="empty-state">
					<strong>No imported activities.</strong>
					<p>Add an import source or upload a GPX file below.</p>
				</div>
			{/each}
		</div>

		{#if data.activities.nextOffset !== null}
			<a class="button-link" href={resolve(`/app/import?offset=${data.activities.nextOffset}`)}>
				Older activities
			</a>
		{/if}
	</section>

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
								? 'Connected on this browser'
								: 'Folder access required'}
						</span>
					</div>
					<div class="import-actions">
						{#if deviceFolderState === 'permission-required'}
							<button
								type="button"
								class="primary"
								disabled={deviceFolderBusy || !data.importTimeZoneConfigured}
								onclick={restoreDeviceFolder}
							>
								{deviceFolderBusy ? 'Restoring…' : 'Restore access'}
							</button>
						{:else}
							<button
								type="button"
								class="primary"
								disabled={deviceFolderBusy || !data.importTimeZoneConfigured}
								onclick={scanDeviceFolderNow}
							>
								{deviceFolderBusy ? 'Checking…' : 'Scan now'}
							</button>
						{/if}
						<button type="button" disabled={deviceFolderBusy} onclick={removeDeviceFolder}>
							Disconnect
						</button>
					</div>
				</div>
			{/if}

			{#each data.sources as source (source.id)}
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
								<button disabled={activeAction !== null || !data.importTimeZoneConfigured}>
									{actionPending(`test-${source.id}`) ? 'Testing…' : 'Test'}
								</button>
							</form>
							<form
								method="post"
								action="?/syncNextcloudSource"
								use:enhance={scopedEnhance(`sync-${source.id}`, 'sources')}
							>
								<input type="hidden" name="sourceId" value={source.id} />
								<button
									class="primary"
									disabled={activeAction !== null || !data.importTimeZoneConfigured}
								>
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

			{#if data.sources.length === 0 && deviceFolderState !== 'linked' && deviceFolderState !== 'permission-required'}
				<p class="no-sources">No import sources connected.</p>
			{/if}
		</div>

		<details class="source-setup">
			<summary>Add import source</summary>
			<div class="setup-sections">
				<section class="setup-section" aria-labelledby="device-folder-heading">
					<h3 id="device-folder-heading">Gadgetbridge folder</h3>
					<p>Checks one new GPX when the app opens or returns to the foreground.</p>
					<p class="privacy-note">
						Folder access stays in this browser and files are not changed. New GPX files are
						uploaded to runway; {data.routeDataMode === 'private'
							? 'a bounded private route trace is retained.'
							: 'route points are discarded after totals are calculated.'}
					</p>
					{#if deviceFolderState === 'unsupported'}
						<p class="source-error">
							This browser cannot retain folder access. Share or upload the GPX instead.
						</p>
					{:else if deviceFolderState === 'loading'}
						<p>Checking folder access…</p>
					{:else if deviceFolderState === 'unlinked'}
						<button
							type="button"
							class="primary"
							disabled={deviceFolderBusy || !data.importTimeZoneConfigured}
							onclick={allowDeviceFolder}
						>
							{deviceFolderBusy ? 'Opening…' : 'Allow device folder'}
						</button>
					{:else}
						<p>Already connected.</p>
					{/if}
				</section>

				<section class="setup-section" aria-labelledby="nextcloud-heading">
					<h3 id="nextcloud-heading">Nextcloud folder</h3>
					<p>
						Reads GPX files from a password-protected folder share. {data.routeDataMode ===
						'private'
							? 'A bounded private route trace is retained.'
							: 'Route points are discarded after totals are calculated.'}
					</p>
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
						<button
							class="primary"
							disabled={activeAction !== null || !data.importTimeZoneConfigured}
						>
							{actionPending('connect-source') ? 'Connecting…' : 'Connect folder'}
						</button>
					</form>
				</section>

				<section class="setup-section" aria-labelledby="manual-gpx-heading">
					<h3 id="manual-gpx-heading">Manual GPX upload</h3>
					<p>
						The default leaves the activity in the inbox for review. Raw GPX bytes are discarded;
						{data.routeDataMode === 'private'
							? ' a bounded private route trace is retained.'
							: ' route points are discarded after totals are calculated.'}
					</p>
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
							{#if data.candidates.length > 0}
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
										{#each data.candidates as workout (workout.id)}
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
						<button
							class="primary"
							disabled={activeAction !== null || !data.importTimeZoneConfigured}
						>
							{actionPending('import-gpx') ? 'Importing…' : 'Import'}
						</button>
					</form>
				</section>
			</div>
		</details>
	</section>
</main>

<style>
	.import-page {
		display: grid;
		gap: clamp(28px, 5vw, 56px);
		max-width: 1120px;
		margin-inline: auto;
	}

	.import-inbox,
	.import-sources {
		display: grid;
		gap: 16px;
		min-width: 0;
	}

	.inbox-heading,
	.import-source-row {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: 20px;
		align-items: center;
	}

	.inbox-heading {
		align-items: end;
		padding-bottom: 18px;
		border-bottom: 2px solid var(--line);
	}

	.inbox-heading h1,
	.sources-heading h2 {
		margin: 0;
		font-size: clamp(1.55rem, 4vw, 2.25rem);
		line-height: 1.05;
		letter-spacing: -0.035em;
	}

	.inbox-heading p {
		max-width: 58ch;
		margin: 8px 0 0;
		color: var(--muted);
	}

	.review-count {
		padding-left: 10px;
		border-left: 3px solid var(--review);
		font-size: 0.82rem;
		font-weight: 780;
		white-space: nowrap;
	}

	.review-count.clear {
		border-color: var(--completed);
	}

	.activity-list,
	.connected-sources {
		display: grid;
		border-top: 1px solid var(--line);
	}

	.activity-record,
	.import-source-row {
		border-bottom: 1px solid var(--line);
	}

	.activity-record > summary {
		display: grid;
		grid-template-columns: 116px minmax(0, 1fr) auto;
		gap: 16px;
		align-items: center;
		min-height: 84px;
		padding: 14px 4px;
		cursor: pointer;
		list-style: none;
	}

	.activity-record > summary::-webkit-details-marker,
	.source-setup > summary::-webkit-details-marker {
		display: none;
	}

	.activity-record > summary:focus-visible,
	.source-setup > summary:focus-visible {
		outline: 3px solid color-mix(in oklab, var(--accent), transparent 35%);
		outline-offset: 3px;
	}

	.record-copy,
	.source-copy {
		display: grid;
		gap: 3px;
		min-width: 0;
	}

	.record-copy > strong {
		font-size: 1.02rem;
		font-variant-numeric: tabular-nums;
	}

	.record-meta,
	.source-copy span,
	.decision-group p,
	.setup-section > p,
	.no-sources {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.record-outcome {
		font-size: 0.9rem;
	}

	.summary-action,
	.source-setup > summary {
		display: inline-flex;
		align-items: center;
		justify-content: center;
		min-height: 44px;
		padding: 7px 13px;
		border: 1px solid var(--line);
		border-radius: var(--radius-small);
		background: var(--surface-strong);
		color: var(--text);
		font-size: 0.9rem;
		font-weight: 760;
	}

	.needs-review .summary-action {
		border-color: var(--accent);
		background: var(--accent);
		color: var(--on-accent);
	}

	.record-decisions {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		border-top: 1px solid var(--line);
		background: color-mix(in oklab, var(--surface-strong), transparent 46%);
	}

	.record-visuals {
		display: grid;
		gap: 10px;
		padding: 18px;
		border-top: 1px solid var(--line);
		background: color-mix(in oklab, var(--surface-strong), transparent 62%);
	}

	.activity-trace-note {
		margin: 0;
		font-size: 0.85rem;
	}

	.decision-group {
		display: grid;
		align-content: start;
		gap: 10px;
		padding: 18px;
		border-bottom: 1px solid var(--line);
	}

	.decision-group:nth-child(odd) {
		border-right: 1px solid var(--line);
	}

	.decision-group h2,
	.setup-section h3 {
		margin: 0;
		font-size: 1rem;
	}

	.decision-group p,
	.setup-section > p {
		margin: 0;
	}

	.setup-section > .message {
		color: var(--text);
	}

	.match-form,
	.feedback-form,
	.feedback-form fieldset,
	.setup-section form {
		display: grid;
		gap: 10px;
	}

	.match-form {
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
	}

	.feedback-form fieldset {
		padding: 0;
		border: 0;
	}

	.feedback-form label {
		font-weight: 600;
	}

	.delete-group {
		border-bottom-color: color-mix(in oklab, var(--danger), var(--line) 80%);
	}

	.sources-heading {
		padding-bottom: 12px;
		border-bottom: 2px solid var(--line);
	}

	.sources-heading h2 {
		font-size: clamp(1.3rem, 3vw, 1.7rem);
	}

	.import-source-row {
		min-height: 70px;
		padding: 12px 4px;
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
		justify-self: start;
		cursor: pointer;
	}

	.source-setup[open] > summary {
		border-color: var(--accent);
	}

	.setup-sections {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		border-block: 1px solid var(--line);
	}

	.setup-section {
		display: grid;
		align-content: start;
		gap: 12px;
		min-width: 0;
		padding: 20px;
		border-right: 1px solid var(--line);
	}

	.setup-section:last-child {
		border-right: 0;
	}

	.privacy-note {
		padding-left: 10px;
		border-left: 2px solid var(--line);
	}

	.gpx-match-options {
		padding: 10px;
	}

	@media (max-width: 820px) {
		.setup-sections {
			grid-template-columns: 1fr;
		}

		.setup-section {
			border-right: 0;
			border-bottom: 1px solid var(--line);
		}

		.setup-section:last-child {
			border-bottom: 0;
		}
	}

	@media (max-width: 680px) {
		.import-page {
			gap: 36px;
		}

		.inbox-heading,
		.import-source-row {
			grid-template-columns: 1fr;
			align-items: start;
		}

		.review-count {
			justify-self: start;
		}

		.activity-record > summary {
			grid-template-columns: minmax(0, 1fr) auto;
			gap: 10px;
			padding-block: 16px;
		}

		.activity-record > summary > :first-child {
			grid-column: 1 / -1;
		}

		.record-meta {
			font-size: 0.83rem;
		}

		.record-decisions {
			grid-template-columns: 1fr;
		}

		.decision-group:nth-child(odd) {
			border-right: 0;
		}

		.match-form {
			grid-template-columns: 1fr;
		}

		.match-form button,
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
