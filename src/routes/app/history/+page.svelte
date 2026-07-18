<script lang="ts">
	import { enhance } from '$app/forms';
	import { resolve } from '$app/paths';
	import StateMarker from '$lib/components/visual/StateMarker.svelte';
	import { formatRampEvidence, presentRampAssessment } from '$lib/training/training-assessment';
	import type { PlanSummary, RiskRating } from '$lib/training/types';
	import { flushSync } from 'svelte';
	import type { ActionData, PageData } from './$types';

	let { data, form }: { data: PageData; form: ActionData } = $props();
	const active = $derived(data.activeItem);
	const phaseReview = $derived(data.phaseReview);
	const pastPlans = $derived(data.history.items.filter((item) => item.plan.status !== 'active'));
	const targetReached = $derived(Boolean(active && active.plan.targetDate <= data.history.today));
	const previousOffset = $derived(Math.max(0, data.offset - data.pageSize));
	let activeAction = $state<'complete' | 'stop' | null>(null);

	function km(meters: number): string {
		return `${Math.round((meters / 1_000) * 10) / 10} km`;
	}

	function trainingTime(seconds: number): string {
		const minutes = Math.round(seconds / 60);
		return minutes < 60
			? `${minutes} min`
			: `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
	}

	function weekday(day: number): string {
		return (
			['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'][day] ??
			'Selected day'
		);
	}

	function rampEvidence(plan: { risk: RiskRating; summary: PlanSummary }): string {
		const assessment = presentRampAssessment(plan.risk).label;
		return plan.summary.kind === 'distance'
			? `${assessment} · ${formatRampEvidence(
					plan.summary.requiredWeeklyIncreasePercent,
					plan.summary.defaultWeeklyIncreasePercent
				)}`
			: assessment;
	}

	function planState(reason: string | null): string {
		if (reason === 'completed') return 'Completed';
		if (reason === 'changed_goal') return 'Goal changed';
		if (reason === 'abandoned') return 'Stopped';
		return 'Archived';
	}

	function submitting(action: 'complete' | 'stop') {
		return () => {
			flushSync(() => (activeAction = action));
			return async ({ update }: { update: () => Promise<void> }) => {
				await update();
				activeAction = null;
			};
		};
	}

	function closedOn(plan: (typeof data.history.items)[number]['plan']): string | null {
		return formatDate(plan.completedAt ?? plan.archivedAt);
	}

	function formatDate(value: string | Date | null): string | null {
		if (!value) return null;
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
</script>

<main class="page history-page">
	<header class="history-intro">
		<div>
			<h1>History</h1>
			<p>Current and past training plans.</p>
		</div>
		<a class="button ghost" href={resolve('/app/stats')}>Open stats</a>
	</header>

	{#if form?.error}
		<p class="message" role="alert">{form.error}</p>
	{:else if form?.message}
		<p class="message" role="status">{form.message}</p>
	{/if}

	{#if active}
		<section class="history-section" aria-labelledby="current-plan-heading">
			<div class="panel-heading history-current-heading">
				<div>
					<h2 id="current-plan-heading" class="section-title">{active.goal.title}</h2>
					<p class="muted">
						{formatDate(active.plan.startDate)}–{formatDate(active.plan.targetDate)} · {active.plan
							.weeks}
						weeks · {rampEvidence(active.plan)}
					</p>
				</div>
				<StateMarker
					label={targetReached ? 'Target date reached' : 'In progress'}
					tone={targetReached ? 'review' : 'planned'}
				/>
			</div>

			<dl class="history-summary-grid">
				<div>
					<dt>Runs completed</dt>
					<dd>{active.summary.completedRuns} / {active.summary.plannedRuns}</dd>
				</div>
				<div>
					<dt>Distance recorded</dt>
					<dd>{km(active.summary.completedDistanceMeters)}</dd>
				</div>
				<div>
					<dt>Missed</dt>
					<dd>{active.summary.missedRuns}</dd>
				</div>
				<div>
					<dt>Skipped</dt>
					<dd>{active.summary.skippedRuns}</dd>
				</div>
				<div>
					<dt>Pain flags</dt>
					<dd>{active.summary.painFlags}</dd>
				</div>
			</dl>

			<div class="history-actions">
				<a class="button primary" href={resolve('/app')}>Open calendar</a>
				<a class="button ghost" href={resolve('/app/history/[planId]', { planId: active.plan.id })}
					>Plan record</a
				>
				<a class="button ghost" href={resolve('/app/onboarding')}>Change goal</a>
			</div>

			{#if phaseReview}
				<section class="history-decision phase-review" aria-labelledby="phase-review-heading">
					<div>
						<h3 id="phase-review-heading">Confirm the recorded starting point</h3>
						<p>
							These values use accepted activities from the final two weeks of the completed
							{phaseReview.phase} phase. Earlier run/walk weeks do not dilute the current starting point.
						</p>
					</div>
					<dl class="history-summary-grid compact phase-measures">
						<div>
							<dt>Activities</dt>
							<dd>{phaseReview.baseline.activityCount}</dd>
						</div>
						<div>
							<dt>Total time</dt>
							<dd>{trainingTime(phaseReview.baseline.totalDurationSeconds)}</dd>
						</div>
						<div>
							<dt>Total distance</dt>
							<dd>{km(phaseReview.baseline.totalDistanceMeters)}</dd>
						</div>
						<div>
							<dt>Longest activity</dt>
							<dd>{km(phaseReview.baseline.longestActivityMeters)}</dd>
						</div>
						<div>
							<dt>Recent weekly average</dt>
							<dd>{km(phaseReview.baseline.weeklyDistanceMeters)}</dd>
						</div>
						<div>
							<dt>Activities / week</dt>
							<dd>{phaseReview.baseline.runsPerWeek}</dd>
						</div>
					</dl>

					{#if phaseReview.racePlan}
						<div class="phase-race-preview">
							<h4>Proposed race phase</h4>
							<p>
								{phaseReview.racePlan.weeks} weeks from {formatDate(phaseReview.racePlan.startDate)} to
								{formatDate(phaseReview.racePlan.targetDate)} · {rampEvidence(phaseReview.racePlan)}
							</p>
							<p>
								Long-run day: {weekday(phaseReview.preferredLongRunDay)}. runway chose this from
								your available days to preserve a recovery day where possible; individual workouts
								remain editable.
							</p>
							{#if phaseReview.racePlan.warnings.length > 0}
								<ul>
									{#each phaseReview.racePlan.warnings as warning (warning)}<li>
											{warning}
										</li>{/each}
								</ul>
							{/if}
						</div>
						<form method="post" action="?/confirmPhaseBaseline" class="history-lifecycle-form">
							<label>
								<input type="checkbox" name="confirmBaseline" required />
								Use these recorded values as the race-plan baseline
							</label>
							<button class="primary">Confirm and build race phase</button>
						</form>
					{:else if phaseReview.goalKind === 'race'}
						<p class="message compact-message">
							The recorded work does not support the retained race ramp yet. No baseline was
							created.
						</p>
					{/if}

					<div class="phase-alternatives">
						<form method="post" action="?/continuePhase" class="history-lifecycle-form">
							<label>
								<input type="checkbox" name="confirmContinuation" required />
								Repeat the latest {phaseReview.phase} week
							</label>
							<button class="secondary">Add another beginner week</button>
						</form>
						{#if phaseReview.goalKind === 'race'}
							<a class="button ghost" href={resolve('/app/onboarding')}
								>Choose a later date or shorter goal</a
							>
						{/if}
					</div>
				</section>
			{/if}

			{#if targetReached && (!phaseReview || phaseReview.goalKind === 'foundation')}
				<section class="history-decision" aria-labelledby="complete-plan-heading">
					<div>
						<h3 id="complete-plan-heading">Close this plan as completed</h3>
						<p>
							Use this after the target date when this training block reached its intended end. This
							closes the schedule; it does not claim that every workout was done.
						</p>
					</div>
					<form
						method="post"
						action="?/completePlan"
						class="history-lifecycle-form"
						aria-busy={activeAction === 'complete'}
						use:enhance={submitting('complete')}
					>
						<label>
							<input type="checkbox" name="confirmLifecycle" required />
							Close this training block
						</label>
						<button class="primary" disabled={activeAction !== null}
							>{activeAction === 'complete' ? 'Completing plan…' : 'Mark plan complete'}</button
						>
					</form>
				</section>
			{/if}

			<details class="history-stop-plan">
				<summary>Stop this plan without marking it complete</summary>
				<p>
					Use this when you are ending the goal rather than replacing it. Recorded work stays in
					History, and no plan becomes active until you build another one.
				</p>
				<form
					method="post"
					action="?/archivePlan"
					class="history-lifecycle-form"
					aria-busy={activeAction === 'stop'}
					use:enhance={submitting('stop')}
				>
					<label>
						<input type="checkbox" name="confirmLifecycle" required />
						Stop {active.goal.title}
					</label>
					<button class="danger" disabled={activeAction !== null}
						>{activeAction === 'stop' ? 'Stopping plan…' : 'Stop plan'}</button
					>
				</form>
			</details>
		</section>
	{:else}
		<section class="history-section history-next-decision" aria-labelledby="next-plan-heading">
			<div>
				<h2 id="next-plan-heading" class="section-title">No active plan</h2>
				{#if pastPlans[0]}
					<p>The last plan is closed. Its recorded work remains below.</p>
				{:else}
					<p>Create a plan when you are ready to schedule a goal.</p>
				{/if}
			</div>
			<a class="button primary" href={resolve('/app/onboarding')}>Build a plan</a>
		</section>
	{/if}

	<section class="history-section" aria-labelledby="past-plans-heading">
		<div>
			<h2 id="past-plans-heading" class="section-title">Past plans</h2>
		</div>

		{#if pastPlans.length > 0}
			<div class="history-plan-list">
				{#each pastPlans as item (item.plan.id)}
					<article class="history-plan-record">
						<header>
							<div>
								<h3>{item.goal.title}</h3>
								<p>
									{formatDate(item.plan.startDate)}–{formatDate(item.plan.targetDate)} · {item.plan
										.weeks}
									weeks
								</p>
							</div>
							<div class="history-plan-state">
								<StateMarker
									label={planState(item.plan.lifecycleReason)}
									tone={item.plan.lifecycleReason === 'completed' ? 'completed' : 'neutral'}
								/>
								{#if closedOn(item.plan)}<small>Closed {closedOn(item.plan)}</small>{/if}
							</div>
						</header>
						<dl class="history-summary-grid compact">
							<div>
								<dt>Completed</dt>
								<dd>{item.summary.completedRuns} / {item.summary.plannedRuns}</dd>
							</div>
							<div>
								<dt>Recorded</dt>
								<dd>{km(item.summary.completedDistanceMeters)}</dd>
							</div>
							<div>
								<dt>Missed</dt>
								<dd>{item.summary.missedRuns}</dd>
							</div>
							<div>
								<dt>Skipped</dt>
								<dd>{item.summary.skippedRuns}</dd>
							</div>
							<div>
								<dt>Pain flags</dt>
								<dd>{item.summary.painFlags}</dd>
							</div>
						</dl>
						<a
							class="button ghost"
							href={resolve('/app/history/[planId]', { planId: item.plan.id })}>Open plan record</a
						>
					</article>
				{/each}
			</div>
		{:else}
			<div class="empty-state">
				<strong>No closed plans yet.</strong>
				<p>Completed, changed, and stopped plans will appear here.</p>
			</div>
		{/if}

		{#if data.offset > 0 || data.history.nextOffset !== null}
			<nav class="history-pagination" aria-label="Plan history pages">
				{#if data.offset > 0}
					<form method="get" action={resolve('/app/history')}>
						<input type="hidden" name="offset" value={previousOffset} />
						<button class="ghost">Newer plans</button>
					</form>
				{/if}
				{#if data.history.nextOffset !== null}
					<form method="get" action={resolve('/app/history')}>
						<input type="hidden" name="offset" value={data.history.nextOffset} />
						<button class="ghost">Older plans</button>
					</form>
				{/if}
			</nav>
		{/if}
	</section>
</main>

<style>
	.history-page {
		display: grid;
		gap: 0;
	}

	.history-intro,
	.history-section {
		display: grid;
		gap: 22px;
		padding: 28px 0;
		border-bottom: 1px solid var(--line);
	}

	.history-intro {
		grid-template-columns: minmax(0, 1fr) auto;
		align-items: end;
		padding-top: 8px;
	}

	.history-intro h1,
	.history-section h2,
	.history-section h3,
	.history-intro p {
		margin: 0;
	}

	.history-intro h1 {
		font-size: clamp(2rem, 5vw, 3rem);
	}

	.history-intro p {
		margin-top: 6px;
		color: var(--muted);
	}

	.history-plan-record {
		display: grid;
		gap: 16px;
		padding: 20px 0;
		border-top: 1px solid var(--line);
	}

	.history-plan-record:first-child {
		border-top: 0;
	}

	.history-plan-record header {
		display: flex;
		align-items: start;
		justify-content: space-between;
		gap: 16px;
	}

	.history-plan-record h3,
	.history-plan-record p {
		margin: 0;
	}

	.history-plan-record p,
	.history-plan-state small {
		color: var(--muted);
	}

	@media (max-width: 680px) {
		.history-intro {
			grid-template-columns: 1fr;
			align-items: start;
		}

		.history-plan-record header {
			display: grid;
		}
	}
</style>
