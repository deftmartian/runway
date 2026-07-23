<script lang="ts">
	import { enhance } from '$app/forms';
	import { resolve } from '$app/paths';
	import ActivityVisuals from '$lib/components/training/ActivityVisuals.svelte';
	import StateMarker from '$lib/components/visual/StateMarker.svelte';
	import type { ActivityRouteTrace, HeartRateSeries } from '$lib/training/types';
	import type {
		ImportedActivityPage,
		ImportSection,
		ImportShareNotice,
		ImportWorkoutCandidate,
		ScopedEnhanceFactory,
		ScopedImportResult
	} from './import-view-model';
	import { tick } from 'svelte';

	let {
		activities,
		candidates,
		shareNotice,
		formMessage,
		importTimeZoneConfigured,
		activeAction,
		activeSection,
		scopedResult,
		scopedEnhance
	}: {
		activities: ImportedActivityPage;
		candidates: ImportWorkoutCandidate[];
		shareNotice: ImportShareNotice | null;
		formMessage: string | null;
		importTimeZoneConfigured: boolean;
		activeAction: string | null;
		activeSection: ImportSection | null;
		scopedResult: ScopedImportResult | null;
		scopedEnhance: ScopedEnhanceFactory;
	} = $props();

	type ActivityTraceDetail = {
		id: string;
		routeTrace: ActivityRouteTrace | null;
		heartRateSeries: HeartRateSeries | null;
	};

	let emptyGpxInput = $state<HTMLInputElement>();
	let activityList = $state<HTMLDivElement>();
	let handledGpxResult = $state<ScopedImportResult | null>(null);
	let activityTraceDetails = $state<
		Record<string, ActivityTraceDetail | 'loading' | 'failed' | undefined>
	>({});

	const unlinkedCount = $derived(
		activities.items.filter((activity) => activity.reviewState === 'review').length
	);
	const sectionResult = (section: ImportSection) =>
		scopedResult?.section === section ? scopedResult : null;
	const actionPending = (key: string) => activeAction === key;
	const activityTraceDetail = (activityId: string) => activityTraceDetails[activityId];
	const gpxResult = $derived(sectionResult('gpx'));
	const inboxResult = $derived(
		sectionResult('activities') ??
			sectionResult('empty-gpx') ??
			(gpxResult && !gpxResult.failed ? gpxResult : null)
	);
	const reviewImportResult = $derived(
		inboxResult?.section === 'gpx' || inboxResult?.section === 'empty-gpx'
	);

	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const isoDay = (date: Date | string) =>
		date instanceof Date ? date.toISOString().slice(0, 10) : date.slice(0, 10);
	const day = (date: Date | string) =>
		new Date(`${isoDay(date)}T00:00:00`).toLocaleDateString(undefined, {
			weekday: 'short',
			month: 'short',
			day: 'numeric'
		});
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
	const candidatesForActivity = (occurredDate: Date | string) => {
		const activityDate = isoDay(occurredDate);
		return candidates.filter(
			(candidate) => dateDistanceDays(isoDay(candidate.scheduledDate), activityDate) <= 3
		);
	};

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

	function confirmDeleteActivity(event: SubmitEvent) {
		if (!confirm('Delete this activity? This cannot be undone.')) event.preventDefault();
	}

	function chooseEmptyGpx() {
		if (!emptyGpxInput) return;
		emptyGpxInput.value = '';
		emptyGpxInput.click();
	}

	function submitEmptyGpx(event: Event) {
		const input = event.currentTarget;
		if (!(input instanceof HTMLInputElement) || !input.files?.length) return;
		input.form?.requestSubmit();
	}

	function firstReviewSummary(): HTMLElement | null {
		return (
			activityList?.querySelector<HTMLElement>('details.activity-record.needs-review > summary') ??
			null
		);
	}

	function focusFirstReview(open = false) {
		const summary = firstReviewSummary();
		if (!summary) return;
		if (open && summary.parentElement instanceof HTMLDetailsElement) {
			summary.parentElement.open = true;
		}
		summary.focus({ preventScroll: true });
		summary.scrollIntoView({ block: 'start' });
	}

	$effect(() => {
		const result = sectionResult('gpx') ?? sectionResult('empty-gpx');
		if (!result || result.failed || result === handledGpxResult) return;
		handledGpxResult = result;
		void tick().then(() =>
			requestAnimationFrame(() => {
				focusFirstReview(false);
			})
		);
	});
</script>

<section class="import-inbox" aria-labelledby="activity-inbox-title">
	<header class="inbox-heading">
		<div>
			<h1 id="activity-inbox-title">Activity inbox</h1>
			<p>Link each imported run, count it as extra training, or delete it.</p>
		</div>
		<span class:clear={unlinkedCount === 0} class="review-count">
			{unlinkedCount === 1 ? '1 to review on this page' : `${unlinkedCount} to review on this page`}
		</span>
	</header>

	{#if shareNotice}
		<p
			class="message"
			class:bad-message={shareNotice.failed}
			role={shareNotice.failed ? 'alert' : 'status'}
			aria-live="polite"
		>
			{shareNotice.message}
		</p>
	{/if}
	{#if formMessage && !scopedResult}
		<p class="message" role="status" aria-live="polite">{formMessage}</p>
	{/if}
	{#if inboxResult}
		<div
			class="message compact-message"
			class:bad-message={inboxResult.failed}
			class:inbox-import-result={reviewImportResult && !inboxResult.failed}
			role="status"
			aria-live="polite"
		>
			<span>{inboxResult.message}</span>
			{#if reviewImportResult && !inboxResult.failed && unlinkedCount > 0}
				<button
					type="button"
					class="primary"
					onclick={() => {
						focusFirstReview(true);
					}}
				>
					Review imported activity
				</button>
			{/if}
		</div>
	{/if}

	<div class="activity-list" aria-busy={activeSection === 'activities'} bind:this={activityList}>
		{#each activities.items as activity (activity.id)}
			{@const matchingCandidates = candidatesForActivity(activity.activityDate)}
			{@const traceDetail = activityTraceDetail(activity.id)}
			<details
				class="activity-record"
				class:needs-review={activity.reviewState === 'review'}
				ontoggle={(event) => loadActivityTrace(event, activity.id)}
			>
				<summary>
					<StateMarker
						label={activity.reviewState === 'review'
							? 'Needs review'
							: activity.workoutId
								? 'Linked'
								: activity.extraPlanImpactConfirmed
									? 'Counted as extra'
									: 'Recorded'}
						tone={activity.reviewState === 'review' ? 'review' : 'completed'}
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
							headingLevel={2}
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
							This import predates saved route traces. Future GPX imports can include the route map.
						</p>
					{/if}
				</div>

				<div class="record-decisions">
					{#if !activity.workoutId}
						<section class="decision-group" aria-labelledby={`match-${activity.id}`}>
							<h2 id={`match-${activity.id}`}>Link to the plan</h2>
							{#if matchingCandidates.length > 0}
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
											{#each matchingCandidates as workout (workout.id)}
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
							<p>
								{activity.source === 'gpx' && !activity.extraPlanImpactConfirmed
									? 'Unlinking returns this imported activity to Review. It will stop counting in calendar actuals and training summaries until you accept a new role.'
									: 'Unlinking removes the plan match. The already accepted activity remains part of actual training.'}
							</p>
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
				<p>Choose an import source below, or upload one GPX now.</p>
				<form
					method="post"
					action="?/importGpx"
					enctype="multipart/form-data"
					use:enhance={scopedEnhance('empty-import-gpx', 'empty-gpx')}
					class="empty-state-action"
				>
					<input type="hidden" name="matchMode" value="unlinked" />
					<input
						hidden
						type="file"
						name="file"
						accept=".gpx,application/gpx+xml,application/xml,text/xml"
						required
						bind:this={emptyGpxInput}
						onchange={submitEmptyGpx}
					/>
					<button
						type="button"
						class="primary"
						disabled={activeAction !== null || !importTimeZoneConfigured}
						onclick={chooseEmptyGpx}
					>
						{actionPending('empty-import-gpx') ? 'Importing…' : 'Upload GPX'}
					</button>
				</form>
			</div>
		{/each}
	</div>

	{#if activities.nextOffset !== null}
		<a class="button-link" href={resolve(`/app/import?offset=${activities.nextOffset}`)}>
			Older activities
		</a>
	{/if}
</section>

<style>
	.import-inbox {
		display: grid;
		gap: 16px;
		min-width: 0;
	}

	.inbox-heading {
		display: grid;
		grid-template-columns: minmax(0, 1fr) auto;
		gap: 20px;
		align-items: end;
		padding-bottom: 18px;
		border-bottom: 2px solid var(--line);
	}

	.inbox-heading h1 {
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

	.activity-list {
		display: grid;
		border-top: 1px solid var(--line);
	}

	.empty-state-action {
		justify-self: start;
		margin-top: 6px;
	}

	.inbox-import-result {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
	}

	.inbox-import-result span {
		min-width: 0;
	}

	@media (max-width: 560px) {
		.inbox-import-result {
			align-items: stretch;
			flex-direction: column;
		}
	}

	.activity-record {
		border-bottom: 1px solid var(--line);
	}

	.activity-record > summary {
		display: grid;
		grid-template-columns: 116px minmax(0, 1fr) auto;
		gap: 16px;
		align-items: center;
		min-height: 84px;
		padding: 14px 4px;
		scroll-margin-top: 76px;
		cursor: pointer;
		list-style: none;
	}

	.activity-record > summary::-webkit-details-marker {
		display: none;
	}

	.activity-record > summary:focus-visible {
		outline: 3px solid color-mix(in oklab, var(--accent), transparent 35%);
		outline-offset: 3px;
	}

	.record-copy {
		display: grid;
		gap: 3px;
		min-width: 0;
	}

	.record-copy > strong {
		font-size: 1.02rem;
		font-variant-numeric: tabular-nums;
	}

	.record-meta,
	.decision-group p {
		color: var(--muted);
		font-size: 0.9rem;
		line-height: 1.45;
	}

	.record-outcome {
		font-size: 0.9rem;
	}

	.summary-action {
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

	.record-decisions {
		display: grid;
		grid-template-columns: repeat(2, minmax(0, 1fr));
		border-top: 1px solid var(--line);
		background: color-mix(in oklab, var(--surface-strong), transparent 46%);
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

	.decision-group h2 {
		margin: 0;
		font-size: 1rem;
	}

	.decision-group p {
		margin: 0;
	}

	.match-form,
	.feedback-form,
	.feedback-form fieldset {
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

	@media (max-width: 680px) {
		.inbox-heading {
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

		.match-form button {
			width: 100%;
		}
	}
</style>
