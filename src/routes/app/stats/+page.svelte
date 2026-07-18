<script lang="ts">
	import { resolve } from '$app/paths';
	import PlanTrace from '$lib/components/visual/PlanTrace.svelte';
	import { formatPace } from '$lib/training/format';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();

	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const duration = (seconds: number) => {
		const totalMinutes = Math.round(seconds / 60);
		if (totalMinutes < 60) return `${totalMinutes} min`;
		const hours = Math.floor(totalMinutes / 60);
		const minutes = totalMinutes % 60;
		return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
	};
	const weeksToDate = $derived(data.history.weeklySummaries);
	const completedRuns = $derived(weeksToDate.reduce((sum, week) => sum + week.completedRuns, 0));
	const plannedRuns = $derived(weeksToDate.reduce((sum, week) => sum + week.plannedRuns, 0));
	const completedDurationSeconds = $derived(
		weeksToDate.reduce((sum, week) => sum + week.completedDurationSeconds, 0)
	);
	const completedMeters = $derived(
		weeksToDate.reduce((sum, week) => sum + week.completedDistanceMeters, 0)
	);
	const plannedMeters = $derived(
		weeksToDate.reduce((sum, week) => sum + week.targetDistanceMeters, 0)
	);
	const painFlags = $derived(weeksToDate.reduce((sum, week) => sum + week.painFlags, 0));
	const hardFlags = $derived(weeksToDate.reduce((sum, week) => sum + week.hardFlags, 0));
	const changedRuns = $derived(weeksToDate.reduce((sum, week) => sum + week.changedRuns, 0));
	const missedRuns = $derived(weeksToDate.reduce((sum, week) => sum + week.missedRuns, 0));
	const skippedRuns = $derived(weeksToDate.reduce((sum, week) => sum + week.skippedRuns, 0));
	const longestCompletedRun = $derived(
		weeksToDate.reduce((peak, week) => Math.max(peak, week.longestRunMeters), 0)
	);
	const recordedSummary = $derived(data.history.recordedSummary);
	const longRunPeak = $derived(
		data.detail?.weeks.reduce((peak, week) => Math.max(peak, week.longRunMeters), 0) ?? 0
	);
	const currentPlanRecordedShare = $derived(
		recordedSummary.totalDistanceMeters > 0
			? Math.round(
					(recordedSummary.currentPlanDistanceMeters / recordedSummary.totalDistanceMeters) * 100
				)
			: 0
	);
	const rampPressure = $derived(
		data.active?.plan.summary.kind === 'distance'
			? data.active.plan.summary.requiredWeeklyIncreasePercent
			: null
	);
	const currentRisk = $derived(data.history.currentSignal?.risk ?? 'none');
	const currentRiskReasons = $derived(data.history.currentSignal?.reasons ?? []);
	const currentRiskSource = $derived(
		data.history.currentSignal?.source === 'feedback'
			? 'Based on recent feedback'
			: data.history.currentSignal?.source === 'activity'
				? 'Based on recent activity'
				: 'Based on the current plan'
	);
	const traceUsesDuration = $derived(data.active?.plan.summary.kind !== 'distance');
	const timedProgramWeeks = $derived(
		data.active?.plan.summary.kind === 'foundation' ||
			data.active?.plan.summary.kind === 'calibration'
			? (data.detail?.weeks.length ?? data.active.plan.summary.programWeeks)
			: 0
	);
	const plannedDurationThroughCurrentWeek = $derived(
		data.planTrace
			.filter((week) => week.startDate <= data.history.todayIso)
			.reduce((sum, week) => sum + week.currentDurationSeconds, 0)
	);
	const peakPlannedDurationSeconds = $derived(
		data.planTrace.reduce((peak, week) => Math.max(peak, week.currentDurationSeconds), 0)
	);
	const plannedDurationForWeek = (weekNumber: number, startDate: string) =>
		data.planTrace.find((week) => week.weekNumber === weekNumber && week.startDate === startDate)
			?.currentDurationSeconds ?? 0;
	const planTracePoints = $derived(
		data.planTrace.map((week) => {
			const actual = weeksToDate.find(
				(record) => record.weekNumber === week.weekNumber && record.startDate === week.startDate
			);
			return {
				label: `W${week.weekNumber}`,
				recommended: traceUsesDuration
					? Math.round((week.recommendedDurationSeconds / 60) * 10) / 10
					: Math.round((week.recommendedDistanceMeters / 1_000) * 10) / 10,
				current: traceUsesDuration
					? Math.round((week.currentDurationSeconds / 60) * 10) / 10
					: Math.round((week.currentDistanceMeters / 1_000) * 10) / 10,
				actual:
					week.startDate > data.history.todayIso
						? null
						: traceUsesDuration
							? Math.round(((actual?.completedDurationSeconds ?? 0) / 60) * 10) / 10
							: Math.round(((actual?.completedDistanceMeters ?? 0) / 1_000) * 10) / 10
			};
		})
	);
	const paceTrend = $derived.by(() => {
		const pacedWeeks = data.history.weeklySummaries.filter(
			(week) => week.averagePaceSecondsPerKm !== null
		);
		if (pacedWeeks.length < 2) return null;
		const first = pacedWeeks[0]?.averagePaceSecondsPerKm;
		const last = pacedWeeks[pacedWeeks.length - 1]?.averagePaceSecondsPerKm;
		if (first === null || first === undefined || last === null || last === undefined) return null;
		return first - last;
	});
	const paceTrendLabel = $derived(
		paceTrend === null
			? 'Not enough runs'
			: paceTrend > 0
				? `${Math.round(paceTrend)} sec/km faster`
				: paceTrend < 0
					? `${Math.abs(Math.round(paceTrend))} sec/km slower`
					: 'No change'
	);
	const hasRecordedHistory = $derived(
		recordedSummary.totalRuns > 0 ||
			data.history.hasAcceptedActivities ||
			data.history.recentFeedback.length > 0
	);
	const heartRateSample = $derived(data.history.heartRateSample);
	const averageHeartRate = $derived(heartRateSample.averageHeartRate);
	const hasHeartRateSample = $derived(heartRateSample.sampleCount > 0 && averageHeartRate !== null);
	const highZoneMinutes = $derived(Math.round(heartRateSample.highZoneSeconds / 60));
	const heartRateTrendLabel = $derived.by(() => {
		if (heartRateSample.sampleCount < 2) return 'Not enough runs';
		const oldest = heartRateSample.oldest?.averageHeartRate;
		const latest = heartRateSample.latest?.averageHeartRate;
		if (oldest === null || oldest === undefined || latest === null || latest === undefined) {
			return 'Not enough runs';
		}
		const delta = latest - oldest;
		if (delta === 0) return 'No change';
		return `${Math.abs(delta)} bpm ${delta > 0 ? 'higher' : 'lower'}`;
	});
</script>

<main class="page stats-page">
	<header class="stats-page-header">
		<div>
			<h1 class="section-title">Stats</h1>
			<p>{data.active ? 'Current plan and recorded runs.' : 'Recorded runs and past plans.'}</p>
		</div>
		<div class="stats-signal" aria-label="Current plan status">
			<span>Plan status</span>
			<strong>{data.active ? currentRisk : 'no active plan'}</strong>
			{#if data.active}<small>{currentRiskSource}</small>{/if}
		</div>
	</header>

	{#if data.active}
		<section class="stats-section plan-attention" aria-labelledby="plan-attention-title">
			<header class="section-heading">
				<h2 id="plan-attention-title">Does the current plan need attention?</h2>
				<strong
					class="risk-value"
					class:moderate={currentRisk === 'moderate'}
					class:aggressive={currentRisk === 'aggressive'}
					class:unsafe={currentRisk === 'unsafe'}>{currentRisk}</strong
				>
			</header>

			{#if currentRiskReasons.length > 0}
				<ul class="reason-list">
					{#each currentRiskReasons as reason (reason)}
						<li>{reason}</li>
					{/each}
				</ul>
			{:else}
				<p class="plain-status">No current warnings.</p>
			{/if}

			<dl class="data-strip plan-measures">
				{#if traceUsesDuration}
					<div>
						<dt>Program length</dt>
						<dd>{timedProgramWeeks} weeks</dd>
					</div>
					<div>
						<dt>Peak planned week</dt>
						<dd>
							{peakPlannedDurationSeconds > 0
								? duration(peakPlannedDurationSeconds)
								: 'Not scheduled'}
						</dd>
					</div>
				{:else}
					<div>
						<dt>Required weekly increase</dt>
						<dd>{Math.round((rampPressure ?? 0) * 10) / 10}%</dd>
					</div>
					<div>
						<dt>Peak planned long run</dt>
						<dd>{km(longRunPeak)}</dd>
					</div>
				{/if}
			</dl>
		</section>

		{#if planTracePoints.length > 0}
			<section class="stats-section trace-section">
				<PlanTrace
					title={traceUsesDuration ? 'Weekly training time' : 'Weekly training distance'}
					points={planTracePoints}
					unit={traceUsesDuration ? 'min' : 'km'}
				/>
			</section>
		{/if}

		<section class="stats-section" aria-labelledby="plan-actual-title">
			<header class="section-heading">
				<h2 id="plan-actual-title">Plan versus actual</h2>
				{#if hasRecordedHistory}
					<strong>{completedRuns} recorded / {plannedRuns} scheduled</strong>
				{/if}
			</header>

			{#if !hasRecordedHistory}
				<p class="empty-copy">No runs have been recorded for this plan.</p>
			{/if}

			<dl class="data-strip comparison-data">
				{#if traceUsesDuration}
					<div>
						<dt>Training time</dt>
						{#if plannedDurationThroughCurrentWeek > 0}
							<dd>
								{duration(completedDurationSeconds)}
								<small>of {duration(plannedDurationThroughCurrentWeek)} through this week</small>
							</dd>
						{:else}
							<dd>None due yet</dd>
						{/if}
					</div>
					<div>
						<dt>Sessions</dt>
						{#if plannedRuns > 0}
							<dd>{completedRuns} <small>of {plannedRuns} scheduled</small></dd>
						{:else}
							<dd>None due yet</dd>
						{/if}
					</div>
					{#if completedMeters > 0}
						<div>
							<dt>Recorded distance</dt>
							<dd>{km(completedMeters)}</dd>
						</div>
					{:else}
						<div>
							<dt>Changed sessions</dt>
							<dd>{changedRuns}</dd>
						</div>
					{/if}
				{:else}
					<div>
						<dt>Recorded distance</dt>
						<dd>{km(completedMeters)} <small>of {km(plannedMeters)}</small></dd>
					</div>
					<div>
						<dt>Runs</dt>
						<dd>{completedRuns} <small>of {plannedRuns}</small></dd>
					</div>
					<div>
						<dt>Longest completed run</dt>
						<dd>{km(longestCompletedRun)}</dd>
					</div>
				{/if}
				<div>
					<dt>Missed</dt>
					<dd>{missedRuns}</dd>
				</div>
				<div>
					<dt>Skipped</dt>
					<dd>{skippedRuns}</dd>
				</div>
			</dl>
		</section>
	{/if}

	{#if recordedSummary.totalDistanceMeters > 0 || (data.active?.plan.summary.kind === 'distance' && recordedSummary.totalRuns > 0)}
		<section class="stats-section recorded-history" aria-label="Recorded history">
			<header class="section-heading">
				<h2>Recorded history</h2>
				<strong>{km(recordedSummary.totalDistanceMeters)} total</strong>
			</header>
			<dl class="history-breakdown">
				<div>
					<dt>Current-plan distance</dt>
					<dd>{data.active ? km(recordedSummary.currentPlanDistanceMeters) : 'None'}</dd>
					{#if data.active}
						<small>
							{recordedSummary.currentPlanRuns} run{recordedSummary.currentPlanRuns === 1
								? ''
								: 's'} · {currentPlanRecordedShare}% of recorded distance
						</small>
					{/if}
				</div>
				<div>
					<dt>Archived plans</dt>
					<dd>{km(recordedSummary.archivedPlanDistanceMeters)}</dd>
					<small>
						{recordedSummary.archivedPlanRuns} archived run{recordedSummary.archivedPlanRuns === 1
							? ''
							: 's'} still counted.
					</small>
				</div>
				<div>
					<dt>Unmatched records</dt>
					<dd>{km(recordedSummary.unlinkedDistanceMeters)}</dd>
					<small>
						{recordedSummary.unlinkedRuns} run{recordedSummary.unlinkedRuns === 1 ? '' : 's'} not attached
						to a planned workout.
					</small>
				</div>
			</dl>
		</section>
	{/if}

	{#if hasHeartRateSample}
		<section class="stats-section heart-rate" aria-labelledby="heart-rate-title">
			<header class="section-heading">
				<div>
					<h2 id="heart-rate-title">Heart rate</h2>
					<span>{heartRateSample.windowDays} days ending {heartRateSample.windowEnd}</span>
				</div>
				{#if heartRateSample.latest}
					<strong>Latest max {heartRateSample.latest.maxHeartRate ?? 'unknown'} bpm</strong>
				{/if}
			</header>
			<dl class="data-strip heart-rate-data">
				<div>
					<dt>Average heart rate</dt>
					<dd>{averageHeartRate} bpm</dd>
				</div>
				<div>
					<dt>Latest vs earliest</dt>
					<dd>{heartRateTrendLabel}</dd>
				</div>
				<div>
					<dt>Runs with HR</dt>
					<dd>{heartRateSample.sampleCount}</dd>
				</div>
				<div>
					<dt>High-zone time</dt>
					<dd>{highZoneMinutes} min</dd>
				</div>
			</dl>
			<p>Recorded context for comparing runs. Any plan change remains your decision.</p>
		</section>
	{/if}

	{#if !data.active}
		<section class="stats-section no-plan" aria-labelledby="no-active-plan-title">
			<h2 id="no-active-plan-title">No active plan</h2>
			<p>
				{hasRecordedHistory
					? 'Build a plan to compare scheduled and completed work.'
					: 'Record a run or build a plan to see comparisons here.'}
			</p>
			<div class="history-actions">
				<a class="button ghost" href={resolve('/app/history')}>Review plan history</a>
				<a class="button primary" href={resolve('/app/onboarding')}>Build a plan</a>
			</div>
		</section>
	{:else if hasRecordedHistory}
		<section class="stats-section" aria-labelledby="recent-changes-title">
			<header class="section-heading">
				<h2 id="recent-changes-title">What changed across recent runs?</h2>
			</header>

			<dl class="data-strip recent-data">
				<div>
					<dt>Recorded pace</dt>
					<dd>{paceTrendLabel}</dd>
				</div>
				<div>
					<dt>Changed workouts</dt>
					<dd>{changedRuns}</dd>
				</div>
				<div>
					<dt>Hard effort reported</dt>
					<dd>{hardFlags}</dd>
				</div>
				<div>
					<dt>Pain reported</dt>
					<dd>{painFlags}</dd>
				</div>
			</dl>

			{#if weeksToDate.length > 0}
				<div class="weekly-breakdown">
					<h3>Weeks so far</h3>
					<div>
						{#each weeksToDate as week (`stats-${week.weekNumber}-${week.startDate}`)}
							<article class="week-row">
								<header>
									<strong>Week {week.weekNumber}</strong>
									<time datetime={week.startDate}>{week.startDate}</time>
								</header>
								<dl>
									{#if traceUsesDuration}
										<div>
											<dt>Training time</dt>
											<dd>
												{duration(week.completedDurationSeconds)} / {duration(
													plannedDurationForWeek(week.weekNumber, week.startDate)
												)}
											</dd>
										</div>
										{#if week.completedDistanceMeters > 0}
											<div>
												<dt>Recorded distance</dt>
												<dd>{km(week.completedDistanceMeters)}</dd>
											</div>
										{/if}
									{:else}
										<div>
											<dt>Distance</dt>
											<dd>{km(week.completedDistanceMeters)} / {km(week.targetDistanceMeters)}</dd>
										</div>
									{/if}
									{#if week.eventDistanceMeters > 0}
										<div>
											<dt>Goal event</dt>
											<dd>
												{km(week.eventCompletedDistanceMeters)} / {km(week.eventDistanceMeters)}
											</dd>
										</div>
									{/if}
									<div>
										<dt>Workouts</dt>
										<dd>{week.completedRuns} / {week.plannedRuns}</dd>
									</div>
									{#if !traceUsesDuration || week.longestRunMeters > 0}
										<div>
											<dt>{traceUsesDuration ? 'Longest recorded' : 'Longest'}</dt>
											<dd>{km(week.longestRunMeters)}</dd>
										</div>
									{/if}
									{#if week.averagePaceSecondsPerKm !== null}
										<div>
											<dt>Recorded pace</dt>
											<dd>{formatPace(week.averagePaceSecondsPerKm)}</dd>
										</div>
									{/if}
									{#if week.averageHeartRate !== null}
										<div>
											<dt>Average HR</dt>
											<dd>{week.averageHeartRate} bpm</dd>
										</div>
									{/if}
									{#if week.changedRuns > 0}
										<div>
											<dt>Changed</dt>
											<dd>{week.changedRuns}</dd>
										</div>
									{/if}
									{#if week.missedRuns > 0}
										<div>
											<dt>Missed</dt>
											<dd>{week.missedRuns}</dd>
										</div>
									{/if}
									{#if week.skippedRuns > 0}
										<div>
											<dt>Skipped</dt>
											<dd>{week.skippedRuns}</dd>
										</div>
									{/if}
									{#if week.painFlags > 0}
										<div>
											<dt>Pain reported</dt>
											<dd>{week.painFlags}</dd>
										</div>
									{/if}
								</dl>
							</article>
						{/each}
					</div>
				</div>
			{/if}
		</section>
	{:else}
		<section class="stats-section empty-recent" aria-labelledby="recent-changes-title">
			<h2 id="recent-changes-title">What changed across recent runs?</h2>
			<p>No runs have been recorded yet.</p>
		</section>
	{/if}
</main>

<style>
	.stats-page {
		display: grid;
		gap: 0;
		max-width: 1180px;
	}

	.stats-page-header {
		display: grid;
		grid-template-columns: minmax(0, 1fr) minmax(180px, 260px);
		gap: 32px;
		align-items: end;
		padding: 20px 0 32px;
	}

	.stats-page-header h1,
	.stats-page-header p,
	.section-heading h2,
	.weekly-breakdown h3,
	.no-plan h2,
	.no-plan p,
	.empty-recent h2,
	.empty-recent p {
		margin: 0;
	}

	.stats-page-header p,
	.no-plan p,
	.empty-recent p {
		margin-top: 8px;
		color: var(--muted);
	}

	.stats-page-header .stats-signal {
		display: grid;
		gap: 3px;
		padding: 2px 0 2px 16px;
		border: 0;
		border-left: 3px solid var(--accent);
		border-radius: 0;
		background: transparent;
	}

	.stats-signal span,
	.stats-signal small {
		color: var(--muted);
		font-size: 0.82rem;
	}

	.stats-signal span {
		font-weight: 700;
	}

	.stats-signal strong,
	.risk-value {
		font-size: 1.18rem;
		text-transform: capitalize;
	}

	.stats-section {
		display: grid;
		gap: 24px;
		padding: 34px 0;
		border-top: 1px solid var(--line);
	}

	.section-heading {
		display: flex;
		gap: 20px;
		align-items: baseline;
		justify-content: space-between;
	}

	.section-heading h2 {
		font-size: clamp(1.3rem, 2.4vw, 1.75rem);
		letter-spacing: -0.025em;
	}

	.section-heading > strong {
		color: var(--muted);
		font-size: 0.92rem;
		font-weight: 650;
	}

	.section-heading > .risk-value {
		color: var(--completed);
	}

	.section-heading > .risk-value.moderate {
		color: var(--review);
	}

	.section-heading > .risk-value.aggressive,
	.section-heading > .risk-value.unsafe {
		color: var(--danger);
	}

	.reason-list {
		display: grid;
		gap: 8px;
		max-width: 76ch;
		margin: 0;
		padding-left: 22px;
		line-height: 1.5;
	}

	.plain-status,
	.empty-copy {
		margin: 0;
		color: var(--muted);
	}

	.data-strip,
	.history-breakdown,
	.week-row dl {
		margin: 0;
	}

	.data-strip {
		display: grid;
		grid-template-columns: repeat(4, minmax(0, 1fr));
		border-block: 1px solid var(--line);
	}

	.data-strip > div {
		display: grid;
		align-content: start;
		gap: 7px;
		min-width: 0;
		padding: 18px;
		border-left: 1px solid var(--line);
	}

	.data-strip > div:first-child {
		padding-left: 0;
		border-left: 0;
	}

	.data-strip dt,
	.history-breakdown dt,
	.week-row dt {
		color: var(--muted);
		font-size: 0.8rem;
		font-weight: 650;
	}

	.data-strip dd,
	.history-breakdown dd,
	.week-row dd {
		margin: 0;
		font-weight: 730;
	}

	.data-strip dd {
		font-size: clamp(1.08rem, 2vw, 1.35rem);
	}

	.data-strip dd small {
		color: var(--muted);
		font-size: 0.82rem;
		font-weight: 500;
	}

	.plan-measures {
		grid-template-columns: repeat(2, minmax(0, 1fr));
		max-width: 650px;
	}

	.weekly-breakdown h3 {
		font-size: 1rem;
	}

	.history-breakdown {
		display: grid;
		grid-template-columns: repeat(3, minmax(0, 1fr));
		gap: 0;
	}

	.history-breakdown > div {
		display: grid;
		gap: 6px;
		min-width: 0;
		padding: 0 24px;
		border-left: 1px solid var(--line);
	}

	.history-breakdown > div:first-child {
		padding-left: 0;
		border-left: 0;
	}

	.history-breakdown dd {
		font-size: 1.18rem;
	}

	.history-breakdown small,
	.heart-rate header span,
	.heart-rate p,
	.week-row time {
		color: var(--muted);
		font-size: 0.82rem;
		line-height: 1.4;
	}

	.weekly-breakdown {
		display: grid;
		gap: 14px;
		padding-top: 4px;
	}

	.heart-rate p {
		margin: -4px 0 0;
	}

	.weekly-breakdown > div {
		border-top: 1px solid var(--line);
	}

	.week-row {
		display: grid;
		grid-template-columns: minmax(120px, 0.24fr) minmax(0, 1fr);
		gap: 24px;
		padding: 18px 0;
		border-bottom: 1px solid var(--line);
	}

	.week-row header {
		display: grid;
		align-content: start;
		gap: 3px;
	}

	.week-row dl {
		display: flex;
		flex-wrap: wrap;
		gap: 14px 28px;
	}

	.week-row dl > div {
		display: grid;
		gap: 3px;
		min-width: 96px;
	}

	.history-actions {
		display: flex;
		flex-wrap: wrap;
		gap: 10px;
	}

	@media (max-width: 760px) {
		.stats-page-header {
			grid-template-columns: 1fr;
			gap: 18px;
		}

		.section-heading,
		.heart-rate header {
			align-items: flex-start;
			flex-direction: column;
			gap: 8px;
		}

		.comparison-data,
		.recent-data,
		.heart-rate-data {
			grid-template-columns: repeat(2, minmax(0, 1fr));
		}

		.data-strip > div:nth-child(3) {
			padding-left: 0;
			border-left: 0;
		}

		.data-strip > div:nth-child(n + 3) {
			border-top: 1px solid var(--line);
		}

		.history-breakdown {
			grid-template-columns: 1fr;
			gap: 18px;
		}

		.history-breakdown > div {
			padding: 18px 0 0;
			border-top: 1px solid var(--line);
			border-left: 0;
		}

		.history-breakdown > div:first-child {
			padding-top: 0;
			border-top: 0;
		}

		.week-row {
			grid-template-columns: 1fr;
			gap: 12px;
		}
	}

	@media (max-width: 480px) {
		.stats-section {
			gap: 20px;
			padding: 28px 0;
		}

		.plan-measures,
		.comparison-data,
		.recent-data,
		.heart-rate-data {
			grid-template-columns: 1fr;
		}

		.data-strip > div,
		.data-strip > div:first-child,
		.data-strip > div:nth-child(3) {
			padding: 14px 0;
			border-top: 1px solid var(--line);
			border-left: 0;
		}

		.data-strip > div:first-child {
			border-top: 0;
		}
	}
</style>
