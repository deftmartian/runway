<script lang="ts">
	import { resolve } from '$app/paths';
	import StateMarker from '$lib/components/visual/StateMarker.svelte';
	import { presentConsequence } from '$lib/training/consequence-presentation';
	import { presentRampAssessment } from '$lib/training/training-assessment';
	import type { PageData } from './$types';

	let { data }: { data: PageData } = $props();
	const feedbackByWorkout = $derived(
		new Map(data.detail.feedback.map((feedback) => [feedback.workoutId, feedback]))
	);
	const activityByWorkout = $derived(
		new Map(
			data.detail.activities.flatMap((activity) =>
				activity.workoutId ? [[activity.workoutId, activity] as const] : []
			)
		)
	);
	const weekRecords = $derived(
		data.detail.weeks.map((week) => ({
			week,
			workouts: data.detail.workouts.filter((workout) => workout.weekId === week.id)
		}))
	);

	function km(meters: number | null | undefined): string {
		if (meters === null || meters === undefined) return 'Not recorded';
		return `${Math.round((meters / 1_000) * 10) / 10} km`;
	}

	function duration(seconds: number | null | undefined): string | null {
		if (!seconds) return null;
		const minutes = Math.round(seconds / 60);
		return minutes < 60
			? `${minutes} min`
			: `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
	}

	function formatDate(value: string | Date | null): string {
		if (!value) return 'Not recorded';
		const date =
			value instanceof Date
				? value
				: /^\d{4}-\d{2}-\d{2}$/.test(value)
					? new Date(`${value}T12:00:00`)
					: new Date(value);
		return new Intl.DateTimeFormat(undefined, {
			month: 'short',
			day: 'numeric',
			year: 'numeric'
		}).format(date);
	}

	function lifecycleLabel(reason: string | null, status: string): string {
		if (status === 'active') return 'Active';
		if (reason === 'completed') return 'Completed';
		if (reason === 'changed_goal') return 'Goal changed';
		if (reason === 'abandoned') return 'Stopped';
		return 'Archived';
	}

	function workoutState(type: string, status: string, scheduledDate: string): string {
		if (type === 'rest') return 'Rest';
		if (status === 'done') return 'Completed';
		if (status === 'shortened') return 'Shortened';
		if (status === 'skipped') return 'Skipped';
		if (status === 'planned' && scheduledDate < data.detail.cutoffDate) return 'Missed';
		return 'Planned';
	}

	function typeLabel(type: string): string {
		if (type === 'rest') return 'Rest';
		return type === 'race' ? 'Goal event' : `${type.slice(0, 1).toUpperCase()}${type.slice(1)} run`;
	}

	function goalLabel(distance: string | null): string {
		if (!distance) return 'Run continuously for 30 minutes';
		return (
			({ '5k': '5K', '10k': '10K', half: 'Half marathon', marathon: 'Marathon' } as const)[
				distance as '5k'
			] ?? distance
		);
	}

	function kmChange(meters: number): string {
		const value = Math.round((meters / 1_000) * 10) / 10;
		return `${value > 0 ? '+' : ''}${value} km`;
	}

	function adjustmentLabel(trigger: (typeof data.detail.adjustments)[number]['triggerType']) {
		switch (trigger) {
			case 'manual_edit':
				return 'User edit';
			case 'manual_add':
				return 'Workout added';
			case 'manual_remove':
				return 'Workout removed';
			case 'rebalance':
				return 'Week rebalanced';
			case 'feedback':
				return 'Feedback change';
			case 'link':
			case 'import_match':
				return 'Activity-linked change';
			case 'decision':
				return 'Confirmed decision';
			default:
				return 'Plan change';
		}
	}

	function adjustmentValue(state: (typeof data.detail.adjustments)[number]['newState']) {
		if (state.isRemoved) return 'Removed from current plan';
		if (state.prescriptionKind === 'rest' || state.type === 'rest')
			return `${state.scheduledDate} · Rest`;
		if (state.prescriptionKind === 'timed' || state.targetDurationSeconds) {
			return `${state.scheduledDate} · ${duration(state.targetDurationSeconds) ?? 'Timed workout'}`;
		}
		return `${state.scheduledDate} · ${km(state.targetDistanceMeters)}`;
	}
</script>

<main class="page history-detail-page">
	<nav aria-label="History breadcrumb">
		<a class="button ghost" href={resolve('/app/history')}>← Back to History</a>
	</nav>

	<header class="history-detail-header">
		<div class="panel-heading history-current-heading">
			<div>
				<h1>{data.detail.goal.title}</h1>
				<p class="muted">
					{formatDate(data.detail.plan.startDate)}–{formatDate(data.detail.plan.targetDate)} ·
					{data.detail.plan.weeks} weeks
				</p>
			</div>
			<span class="status-label">
				{lifecycleLabel(data.detail.plan.lifecycleReason, data.detail.plan.status)}
			</span>
		</div>

		<dl class="history-summary-grid history-detail-summary">
			<div>
				<dt>Goal</dt>
				<dd>{goalLabel(data.detail.goal.distance)}</dd>
			</div>
			<div>
				<dt>Priority</dt>
				<dd>{data.detail.goal.priority === 'finish_healthy' ? 'Finish healthy' : 'Consistency'}</dd>
			</div>
			<div>
				<dt>Ramp assessment</dt>
				<dd>{presentRampAssessment(data.detail.plan.risk).label}</dd>
			</div>
			<div>
				<dt>{data.detail.plan.status === 'active' ? 'State' : 'Closed'}</dt>
				<dd>
					{data.detail.plan.status === 'active'
						? 'In progress'
						: formatDate(data.detail.plan.completedAt ?? data.detail.plan.archivedAt)}
				</dd>
			</div>
		</dl>
		<p class="history-detail-explainer">
			Missed means a past workout has no result. Skipped means the result was explicitly saved as
			skipped.
		</p>
	</header>

	<section class="history-detail-section" aria-labelledby="plan-ledger-heading">
		<div>
			<h2 id="plan-ledger-heading" class="section-title">Plan ledger</h2>
			<p class="muted">Plan phase, user edits, and confirmed feedback changes in time order.</p>
		</div>
		<ol class="plan-ledger">
			<li>
				<div class="ledger-marker" aria-hidden="true"></div>
				<div>
					<header>
						<StateMarker label="Phase started" tone="planned" />
						<time datetime={data.detail.plan.startDate}
							>{formatDate(data.detail.plan.startDate)}</time
						>
					</header>
					<strong>{data.detail.plan.phase} phase</strong>
					<p>Generated {data.detail.plan.weeks}-week recommendation.</p>
				</div>
			</li>
			{#each data.detail.adjustments as adjustment (adjustment.id)}
				<li>
					<div class="ledger-marker" aria-hidden="true"></div>
					<div>
						<header>
							<StateMarker
								label={adjustment.reversedAt
									? `${adjustmentLabel(adjustment.triggerType)} · reversed`
									: adjustmentLabel(adjustment.triggerType)}
								tone={adjustment.reversedAt
									? 'neutral'
									: adjustment.triggerType.startsWith('manual_') ||
										  adjustment.triggerType === 'rebalance'
										? 'edited'
										: 'review'}
							/>
							<time datetime={String(adjustment.createdAt)}>{formatDate(adjustment.createdAt)}</time
							>
						</header>
						<strong>{adjustmentValue(adjustment.newState)}</strong>
						<p>{adjustment.reason}</p>
					</div>
				</li>
			{/each}
		</ol>
	</section>

	<section class="history-detail-section" aria-labelledby="plan-weeks-heading">
		<div>
			<h2 id="plan-weeks-heading" class="section-title">Weeks and results</h2>
		</div>

		<ol class="history-detail-weeks">
			{#each weekRecords as record (record.week.id)}
				<li>
					<details class="history-week-record" open={record.week.weekNumber === 1}>
						<summary>
							<span>
								<strong>Week {record.week.weekNumber}</strong>
								<small>Starts {formatDate(record.week.startDate)}</small>
							</span>
							<span class="history-week-facts">
								<small
									>Training {record.week.targetDurationSeconds > 0 &&
									record.week.targetDistanceMeters === 0
										? duration(record.week.targetDurationSeconds)
										: km(record.week.targetDistanceMeters)}</small
								>
								{#if record.week.isDownWeek}<span class="status-label">Down week</span>{/if}
								{#if record.week.isTaper}<span class="status-label">Taper</span>{/if}
							</span>
						</summary>

						<div class="history-workout-list">
							{#each record.workouts as workout (workout.id)}
								{@const feedback = feedbackByWorkout.get(workout.id)}
								{@const activity = activityByWorkout.get(workout.id)}
								{@const actualDistance =
									activity?.distanceMeters ?? feedback?.completedDistanceMeters}
								{@const actualDuration =
									activity?.durationSeconds ?? feedback?.completedDurationSeconds}
								{@const consequence = activity?.consequence ?? feedback?.consequence}
								{@const consequenceView = consequence ? presentConsequence(consequence) : null}
								<article class="history-workout-record">
									<header>
										<div>
											<strong
												>{formatDate(workout.scheduledDate)} · {typeLabel(workout.type)}</strong
											>
											<span class="muted">{workout.purpose}</span>
										</div>
										<span class="status-label" class:warn={workout.status === 'skipped'}>
											{workoutState(workout.type, workout.status, workout.scheduledDate)}
										</span>
									</header>

									<dl class="history-workout-facts">
										<div>
											<dt>Planned</dt>
											<dd>
												{workout.type === 'rest'
													? 'Recovery'
													: workout.prescriptionKind === 'timed'
														? duration(workout.targetDurationSeconds)
														: km(workout.targetDistanceMeters)}
											</dd>
										</div>
										<div>
											<dt>Actual</dt>
											<dd>{actualDistance === undefined ? 'Not recorded' : km(actualDistance)}</dd>
										</div>
										{#if duration(actualDuration)}
											<div>
												<dt>Duration</dt>
												<dd>{duration(actualDuration)}</dd>
											</div>
										{/if}
									</dl>

									{#if activity || feedback}
										<p class="muted history-result-flags">
											{activity ? `${activity.source.toUpperCase()} activity` : 'Reported result'}
											{activity?.feltHard || feedback?.feltHard ? ' · felt hard' : ''}
											{activity?.pain || feedback?.pain ? ' · pain flagged' : ''}
										</p>
									{/if}

									{#if consequence && consequenceView}
										<div class="message compact-message history-recorded-consequence">
											<strong>{consequenceView.outcome}</strong>
											<span>{consequenceView.planChange}</span>
											{#if consequenceView.safety}<span>{consequenceView.safety}</span>{/if}
											<small>
												Week {kmChange(consequence.weeklyDistanceDeltaMeters)} · next run
												{kmChange(consequence.nextRunAdjustmentMeters)}
											</small>
										</div>
									{/if}
								</article>
							{/each}
						</div>
					</details>
				</li>
			{/each}
		</ol>
	</section>
</main>

<style>
	.history-detail-page {
		display: grid;
		gap: 0;
	}

	.history-detail-header,
	.history-detail-section {
		display: grid;
		gap: 22px;
		padding: 28px 0;
		border-bottom: 1px solid var(--line);
	}

	.history-detail-header h1,
	.history-detail-section h2 {
		margin: 0;
	}

	.history-detail-header h1 {
		font-size: clamp(1.8rem, 5vw, 2.8rem);
	}

	.history-detail-explainer {
		max-width: 68ch;
		margin: 0;
		color: var(--muted);
	}

	.plan-ledger {
		display: grid;
		gap: 0;
		margin: 0;
		padding: 0;
		list-style: none;
	}

	.plan-ledger li {
		display: grid;
		grid-template-columns: 18px minmax(0, 1fr);
		gap: 14px;
		min-height: 92px;
	}

	.plan-ledger li > div:last-child {
		display: grid;
		gap: 6px;
		align-content: start;
		padding-bottom: 24px;
		border-bottom: 1px solid var(--line);
	}

	.plan-ledger header {
		display: flex;
		flex-wrap: wrap;
		gap: 8px 14px;
		align-items: center;
		justify-content: space-between;
	}

	.plan-ledger time,
	.plan-ledger p {
		color: var(--muted);
	}

	.plan-ledger p {
		margin: 0;
	}

	.ledger-marker {
		position: relative;
		border-left: 2px solid var(--line);
	}

	.ledger-marker::before {
		position: absolute;
		top: 2px;
		left: -5px;
		width: 8px;
		height: 8px;
		border: 1px solid var(--accent);
		background: var(--surface-strong);
		content: '';
	}

	.status-label {
		display: inline-flex;
		align-items: center;
		min-height: 28px;
		padding: 3px 9px;
		border: 1px solid var(--line);
		border-radius: 999px;
		font-size: 0.78rem;
		font-weight: 700;
	}

	.status-label.warn {
		border-color: color-mix(in oklab, var(--review), var(--line) 45%);
		color: var(--review);
	}
</style>
