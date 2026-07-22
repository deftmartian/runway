<script lang="ts">
	import { resolve } from '$app/paths';
	import { onMount, tick } from 'svelte';
	import CalendarEventButton from './CalendarEvent.svelte';
	import EventDetailPanel from './EventDetailPanel.svelte';
	import {
		isQuietCalendarDay,
		presentCalendarEvent,
		presentCalendarTrainingAssessment,
		presentCalendarWeekAssessment
	} from './calendar-presentation';
	import { addIsoDays, buildTrainingCalendarModel, type CalendarWeekRow } from './calendar-model';
	import type { ConsequenceResult, RiskRating, TrainingHealthNotice } from '$lib/training/types';
	import type { TrainingCalendarPayload } from '$lib/training/calendar-view';
	import type {
		CalendarDay,
		CalendarEvent,
		CalendarFormState,
		WorkoutCandidate
	} from './calendar-types';
	type TrainingSignal =
		| {
				risk: RiskRating;
				source?: 'plan' | 'feedback' | 'activity';
				reasons?: string[];
				consequence?: ConsequenceResult | null;
				planComparisonStatus?: 'comparable' | 'mixed';
				healthNotice?: TrainingHealthNotice | null;
		  }
		| null
		| undefined;
	let {
		calendar,
		form,
		currentSignal,
		hasActivePlan = false,
		targetDate = null,
		defaultWeeklyIncreasePercent = null,
		activityCandidates = []
	}: {
		calendar: TrainingCalendarPayload;
		form: CalendarFormState;
		currentSignal?: TrainingSignal;
		hasActivePlan?: boolean;
		targetDate?: string | null;
		defaultWeeklyIncreasePercent?: number | null;
		activityCandidates?: WorkoutCandidate[];
	} = $props();

	let selectedEventId = $state<string | null>(null);
	let focusedEventId = $state<string | null>(null);
	let returnFocus: HTMLElement | null = null;
	let calendarScroll = $state<HTMLDivElement>();
	let calendarGrid = $state<HTMLDivElement>();
	let calendarOverflowing = $state(false);

	const weekdayLabels = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const minutes = (seconds: number) => `${Math.round(seconds / 60)} min`;

	const calendarModel = $derived.by(() =>
		buildTrainingCalendarModel(calendar, { hasActivePlan, targetDate })
	);
	const allEvents = $derived(calendarModel.events);
	const calendarDays = $derived(calendarModel.days);
	const calendarRows = $derived(calendarModel.rows);
	const calendarEvents = $derived(calendarDays.flatMap((day) => day.events));
	const selectedEvent = $derived(
		calendarEvents.find((event) => event.id === selectedEventId) ?? null
	);
	const futureWorkouts = $derived(
		calendar.workouts.filter(
			(workout) =>
				workout.status === 'planned' &&
				!workout.isRemoved &&
				workout.type !== 'rest' &&
				workout.type !== 'race' &&
				workout.scheduledDate >= calendar.today
		)
	);
	const nextRun = $derived(
		allEvents.find(
			(event) =>
				event.workout?.status === 'planned' &&
				!event.workout.isRemoved &&
				event.kind !== 'rest' &&
				!event.activity &&
				!event.feedback &&
				event.date >= calendar.today
		) ?? null
	);
	const nextRunLabel = $derived(
		nextRun?.workout
			? `Next: ${nextRun.workout.purpose} ${nextRun.workout.targetDurationSeconds ? `${Math.round(nextRun.workout.targetDurationSeconds / 60)} min` : km(nextRun.workout.targetDistanceMeters)}`
			: 'Next run'
	);
	const openItems = $derived(
		allEvents.filter((event) => event.isRecordable && event.date < calendar.today)
	);
	const openItemsLabel = $derived(
		openItems.length === 1 ? 'Review 1 missed run' : `Review ${openItems.length} missed runs`
	);
	const targetReached = $derived(Boolean(targetDate && targetDate <= calendar.today));
	const monthTitle = $derived(
		new Date(`${calendar.month}-01T00:00:00`).toLocaleDateString(undefined, {
			month: 'long',
			year: 'numeric'
		})
	);
	const currentWeekLoad = $derived(calendarRows.find((row) => row.load?.isCurrent)?.load ?? null);
	const todayEvents = $derived(allEvents.filter((event) => event.date === calendar.today));
	const todayStatus = $derived.by(() => {
		const primary = todayEvents[0];
		if (!primary) return 'Open day';
		const presentation = presentCalendarEvent(primary);
		if (primary.kind === 'rest') return 'Recovery day';
		if (presentation.state === 'needs_review') return 'Activity needs review';
		if (primary.activity) return `Recorded ${km(primary.activity.distanceMeters)}`;
		if (primary.workout?.status === 'skipped') return 'Skipped — review the next run';
		if (primary.workout?.status === 'shortened') {
			return primary.feedback?.completedDistanceMeters
				? `Shortened to ${km(primary.feedback.completedDistanceMeters)}`
				: 'Shortened';
		}
		if (primary.workout?.status === 'done') {
			return primary.feedback?.completedDistanceMeters
				? `Completed ${km(primary.feedback.completedDistanceMeters)}`
				: 'Completed';
		}
		if (primary.isRecordable) return `Record ${primary.title}`;
		if (primary.workout) return `${primary.title} ${km(primary.workout.targetDistanceMeters)}`;
		return primary.title;
	});
	const currentWeekLabel = $derived(
		currentWeekLoad
			? `${km(currentWeekLoad.week.completedDistanceMeters)} of ${km(currentWeekLoad.week.targetDistanceMeters)}`
			: calendar.month === calendar.currentMonth
				? 'No active week'
				: 'Not in this view'
	);
	const currentSignalReasons = $derived(currentSignal?.reasons?.filter(Boolean) ?? []);
	const currentTrainingAssessment = $derived(
		currentSignal
			? presentCalendarTrainingAssessment(
					currentSignal.risk,
					currentSignal.source,
					currentSignal.consequence,
					currentSignal.planComparisonStatus === 'mixed'
				)
			: null
	);

	onMount(() => {
		const updateOverflow = () => {
			calendarOverflowing = Boolean(
				calendarScroll && calendarScroll.scrollWidth > calendarScroll.clientWidth + 1
			);
		};
		const observer = new ResizeObserver(updateOverflow);
		if (calendarScroll) observer.observe(calendarScroll);
		if (calendarGrid) observer.observe(calendarGrid);
		const frame = requestAnimationFrame(updateOverflow);
		return () => {
			cancelAnimationFrame(frame);
			observer.disconnect();
		};
	});
	const calendarQuery = (month: string) => `month=${month}`;
	const emptyDayLabel = (day: CalendarDay) =>
		day.isToday
			? 'Today. No training scheduled.'
			: `${day.weekday}, ${day.date}. No training scheduled.`;
	const quietWeekLabel = (row: CalendarWeekRow) => {
		const selectedDays = row.days.filter((day) => day.inSelectedMonth);
		const first = selectedDays[0]?.date ?? row.days[0]?.date;
		const last = selectedDays.at(-1)?.date ?? row.days.at(-1)?.date;
		if (!first || !last) return 'Earlier quiet week';
		const format = (date: string, includeMonth: boolean) =>
			new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
				month: includeMonth ? 'short' : undefined,
				day: 'numeric'
			});
		return `${format(first, true)}–${format(last, first.slice(0, 7) !== last.slice(0, 7))}`;
	};

	$effect(() => {
		if (selectedEventId && !calendarEvents.some((event) => event.id === selectedEventId)) {
			selectedEventId = null;
		}
	});

	$effect(() => {
		if (!calendarEvents.some((event) => event.id === focusedEventId)) {
			focusedEventId =
				calendarEvents.find((event) => event.isToday)?.id ?? calendarEvents[0]?.id ?? null;
		}
	});

	function selectEvent(event: CalendarEvent, trigger?: HTMLElement) {
		returnFocus =
			trigger ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null);
		focusedEventId = event.id;
		selectedEventId = event.id;
	}

	function closeEvent() {
		selectedEventId = null;
		const target = returnFocus;
		void tick().then(() => {
			if (target?.isConnected) {
				target.focus();
				return;
			}
			focusCalendarEvent(focusedEventId);
		});
	}

	function handleCalendarKeydown(event: CalendarEvent, keyboardEvent: KeyboardEvent) {
		const currentIndex = calendarEvents.findIndex((candidate) => candidate.id === event.id);
		if (currentIndex < 0) return;
		let nextIndex: number | null = null;
		switch (keyboardEvent.key) {
			case 'ArrowRight':
				nextIndex = Math.min(calendarEvents.length - 1, currentIndex + 1);
				break;
			case 'ArrowLeft':
				nextIndex = Math.max(0, currentIndex - 1);
				break;
			case 'ArrowDown':
				nextIndex = calendarEvents.findIndex(
					(candidate) => candidate.date === addIsoDays(event.date, 7)
				);
				break;
			case 'ArrowUp':
				nextIndex = calendarEvents.findIndex(
					(candidate) => candidate.date === addIsoDays(event.date, -7)
				);
				break;
			case 'Home':
				nextIndex = 0;
				break;
			case 'End':
				nextIndex = calendarEvents.length - 1;
				break;
		}
		if (nextIndex === null || nextIndex < 0 || nextIndex === currentIndex) return;
		keyboardEvent.preventDefault();
		focusedEventId = calendarEvents[nextIndex]?.id ?? focusedEventId;
		void tick().then(() => {
			focusCalendarEvent(focusedEventId);
		});
	}

	function focusCalendarEvent(eventId: string | null) {
		if (!eventId) return;
		const target = document.querySelector<HTMLElement>(
			`[data-calendar-event-id="${CSS.escape(eventId)}"]`
		);
		const disclosure = target?.closest<HTMLDetailsElement>('details.quiet-calendar-week');
		if (disclosure && !disclosure.open) disclosure.open = true;
		target?.focus();
	}

	function handleQuietWeekToggle(row: CalendarWeekRow, toggleEvent: Event) {
		const disclosure = toggleEvent.currentTarget;
		if (!(disclosure instanceof HTMLDetailsElement)) return;
		const rowEvents = row.days.flatMap((day) => day.events);
		if (disclosure.open) {
			if (!rowEvents.some((event) => event.id === focusedEventId)) {
				focusedEventId = rowEvents[0]?.id ?? focusedEventId;
			}
			return;
		}
		if (!rowEvents.some((event) => event.id === focusedEventId)) return;
		focusedEventId =
			calendarEvents.find((event) => event.isToday)?.id ??
			calendarEvents.find((event) => {
				const eventRow = calendarRows.find((candidate) =>
					candidate.days.some((day) => day.date === event.date)
				);
				return !eventRow?.isQuietEarlier;
			})?.id ??
			null;
	}
</script>

<section class="training-shell">
	<div class="training-calendar-panel">
		<div class="calendar-toolbar">
			<div class="training-title-block">
				<h1 class="section-title">Training calendar</h1>
				{#if currentSignal?.healthNotice}
					<aside
						class="training-health-notice"
						class:paused={currentSignal.healthNotice.level === 'paused'}
						aria-label="Current health context"
					>
						<strong>{currentSignal.healthNotice.heading}</strong>
						<span>{currentSignal.healthNotice.message}</span>
					</aside>
				{/if}
				{#if currentSignal}
					<details
						class="plan-assessment"
						class:bad-message={currentTrainingAssessment?.presentation.attention === 'blocked'}
						open={currentTrainingAssessment?.presentation.attention === 'blocked'}
					>
						<summary>
							<span
								>{currentTrainingAssessment?.heading} · {currentTrainingAssessment?.sourceLabel}</span
							>
							<strong>{currentTrainingAssessment?.presentation.label}</strong>
						</summary>
						{#if currentSignalReasons.length > 0}
							<ul>
								{#each currentSignalReasons as reason (reason)}
									<li>{reason}</li>
								{/each}
							</ul>
						{:else}
							<p>No current warnings.</p>
						{/if}
					</details>
				{/if}
				<details class="calendar-state-key">
					<summary>Calendar states</summary>
					<div class="calendar-state-legend" aria-label="Calendar state legend">
						<span data-state="planned">Planned</span>
						<span data-state="completed">Completed</span>
						<span data-state="shortened">Shortened</span>
						<span data-state="skipped">Skipped</span>
						<span data-state="missed">Missed</span>
						<span data-state="review">Review</span>
						<span data-state="rest">Rest</span>
						<span data-state="removed">Removed</span>
					</div>
				</details>
			</div>
			{#if targetReached}
				<div class="message compact-message" role="status">
					<strong>Target date reached.</strong>
					<span
						>Review this training block in History and decide whether to complete or stop it.</span
					>
				</div>
			{/if}
			<div class="training-command-strip" aria-label="Training summary">
				<div class="command-readout current">
					<span>Today</span>
					<strong>{todayStatus}</strong>
				</div>
				<button
					type="button"
					class="command-readout interactive"
					disabled={!nextRun}
					onclick={(mouseEvent) => {
						if (nextRun) selectEvent(nextRun, mouseEvent.currentTarget);
					}}
				>
					<span>Next</span>
					<strong>{nextRun ? nextRunLabel.replace('Next: ', '') : 'No planned run'}</strong>
				</button>
				<button
					type="button"
					class="command-readout interactive review"
					disabled={openItems.length === 0}
					onclick={(mouseEvent) => {
						const firstOpenItem = openItems[0];
						if (firstOpenItem) selectEvent(firstOpenItem, mouseEvent.currentTarget);
					}}
				>
					<span>Review</span>
					<strong>{openItems.length === 0 ? 'Clear' : openItemsLabel}</strong>
				</button>
			</div>
			<p class="current-week-readout"><span>This week</span><strong>{currentWeekLabel}</strong></p>
			<div class="calendar-toolbar-actions">
				<div class="calendar-month-control" aria-label="Calendar month">
					<a
						class="button ghost"
						href={resolve(`/app?${calendarQuery(calendar.previousMonth)}`)}
						aria-label="Previous month">Previous</a
					>
					<strong>{monthTitle}</strong>
					<a
						class="button ghost"
						href={resolve(`/app?${calendarQuery(calendar.nextMonth)}`)}
						aria-label="Next month">Next</a
					>
				</div>
				<a
					class="button"
					href={resolve(`/app?${calendarQuery(calendar.currentMonth)}`)}
					aria-label="Current month"
					aria-current={calendar.month === calendar.currentMonth ? 'date' : undefined}>Today</a
				>
				{#if targetReached}
					<a class="button primary" href={resolve('/app/history')}>Review ended plan</a>
				{:else}
					<a class="button ghost" href={resolve('/app/onboarding')}
						>{hasActivePlan ? 'Change goal' : 'Build plan'}</a
					>
				{/if}
			</div>
		</div>

		<p id="calendar-keyboard-help" class="sr-only">
			Use the arrow keys to move between training days. Press Enter to open a day.
		</p>
		{#if calendarOverflowing}
			<p id="calendar-scroll-help" class="calendar-scroll-help">
				Scroll sideways to see all seven days.
			</p>
		{/if}
		<div class="calendar-month-scroll" bind:this={calendarScroll}>
			<div class="calendar-weekday-row" aria-hidden="true">
				{#each weekdayLabels as label (label)}
					<span class="calendar-weekday">{label}</span>
				{/each}
			</div>

			<div
				class="calendar-month-grid"
				role="region"
				aria-label={`${monthTitle} training calendar`}
				aria-describedby={calendarOverflowing
					? 'calendar-keyboard-help calendar-scroll-help'
					: 'calendar-keyboard-help'}
				bind:this={calendarGrid}
			>
				{#each calendarRows as row (row.id)}
					<svelte:element
						this={row.isQuietEarlier ? 'details' : 'div'}
						class:quiet-calendar-week={row.isQuietEarlier}
						ontoggle={(toggleEvent: Event) => {
							handleQuietWeekToggle(row, toggleEvent);
						}}
					>
						{#if row.isQuietEarlier}
							<summary>
								<span>{quietWeekLabel(row)}</span>
								<strong>Nothing recorded</strong>
							</summary>
						{/if}
						<section class="calendar-month-week" aria-label={row.label}>
							{#if row.load}
								{@const load = row.load}
								{@const durationLoad =
									load.week.targetDurationSeconds > 0 && load.week.targetDistanceMeters === 0}
								{@const weekIndex = calendar.weeks.findIndex((week) => week.id === load.week.id)}
								{@const weekAssessment = presentCalendarWeekAssessment({
									week: load.week,
									previousWeek: weekIndex > 0 ? (calendar.weeks[weekIndex - 1] ?? null) : null,
									baselineMeters: calendar.planScale?.baselineMeters ?? null,
									defaultWeeklyIncreasePercent
								})}
								<div class="calendar-week-load" class:current={load.isCurrent}>
									<div class="week-load-meta">
										<span>{load.label}</span>
										<strong
											>{durationLoad
												? `${minutes(load.week.completedDurationSeconds)} done of ${minutes(load.week.targetDurationSeconds)}`
												: `${km(load.week.completedDistanceMeters)} done of ${km(load.week.targetDistanceMeters)}`}</strong
										>
										{#if load.week.eventDistanceMeters > 0}
											<small>
												Goal event {km(load.week.eventCompletedDistanceMeters)} of {km(
													load.week.eventDistanceMeters
												)}
											</small>
										{/if}
									</div>
									<div
										class="week-load-track"
										role="progressbar"
										aria-label={`${load.label}: ${durationLoad ? minutes(load.week.completedDurationSeconds) : km(load.week.completedDistanceMeters)} done of ${durationLoad ? minutes(load.week.targetDurationSeconds) : km(load.week.targetDistanceMeters)}`}
										aria-valuemin="0"
										aria-valuemax={durationLoad
											? load.week.targetDurationSeconds
											: load.week.targetDistanceMeters}
										aria-valuenow={Math.min(
											durationLoad
												? load.week.completedDurationSeconds
												: load.week.completedDistanceMeters,
											durationLoad
												? load.week.targetDurationSeconds
												: load.week.targetDistanceMeters
										)}
									>
										<svg
											aria-hidden="true"
											focusable="false"
											viewBox="0 0 100 8"
											preserveAspectRatio="none"
										>
											<rect
												class="week-load-target"
												x="0"
												y="0"
												width={load.rampValue}
												height="8"
												rx="4"
											/>
											<rect
												class="week-load-completion"
												x="0"
												y="0"
												width={load.completionValue}
												height="8"
												rx="4"
											/>
										</svg>
									</div>
									<div class="week-load-tags">
										{#if weekAssessment.phaseLabel}<span class="badge"
												>{weekAssessment.phaseLabel}</span
											>{/if}
										{#if weekAssessment.presentation}
											<span
												class="badge"
												class:warn={weekAssessment.presentation.attention === 'high' ||
													weekAssessment.presentation.attention === 'review'}
												class:bad={weekAssessment.presentation.attention === 'blocked'}
												class:good={weekAssessment.presentation.attention === 'none'}
												>{weekAssessment.presentation.label} · {weekAssessment.evidence}</span
											>
										{:else}
											<span class="badge">{weekAssessment.evidence}</span>
										{/if}
										{#if load.week.painFlags > 0}<span class="badge bad"
												>{load.week.painFlags} pain</span
											>{/if}
										{#if load.week.hardFlags > 0}<span class="badge warn"
												>{load.week.hardFlags} hard</span
											>{/if}
									</div>
								</div>
							{/if}
							<div class="calendar-month-row">
								{#each row.days as day (day.date)}
									<article
										class="calendar-month-day"
										class:outside-month={!day.inSelectedMonth}
										class:today={day.isToday}
										class:compact-empty={isQuietCalendarDay(day) && !day.isToday}
										aria-label={`${day.weekday}, ${day.date}`}
									>
										<div class="calendar-day-heading">
											<span>{day.weekday}</span>
											<strong>{day.dayNumber}</strong>
										</div>
										<div class="calendar-day-events">
											{#each day.events as event (event.id)}
												<CalendarEventButton
													{event}
													selected={event.id === selectedEventId}
													tabindex={event.id === focusedEventId ? 0 : -1}
													onfocus={(focused: CalendarEvent) => {
														focusedEventId = focused.id;
													}}
													onkeydown={handleCalendarKeydown}
													onselect={selectEvent}
												/>
											{:else}
												{#if day.isToday}
													<span class="calendar-empty current-day-empty">Today</span>
												{:else}
													<span class="sr-only">{emptyDayLabel(day)}</span>
												{/if}
											{/each}
										</div>
									</article>
								{/each}
							</div>
						</section>
					</svelte:element>
				{/each}
			</div>
		</div>
	</div>

	{#if selectedEvent}
		<EventDetailPanel
			event={selectedEvent}
			candidates={activityCandidates}
			{form}
			today={calendar.today}
			targetDate={targetDate ?? calendar.today}
			{hasActivePlan}
			{futureWorkouts}
			onclose={closeEvent}
		/>
	{/if}
</section>

<style>
	.quiet-calendar-week {
		min-width: 0;
		border-bottom: 1px solid var(--line);
	}

	.calendar-scroll-help {
		margin: 0 0 8px;
		color: var(--muted);
		font-size: 0.8rem;
		font-weight: 620;
	}

	.quiet-calendar-week > summary {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 1rem;
		min-height: 44px;
		padding: 0.55rem 0.75rem;
		color: var(--muted);
		background: color-mix(in oklab, var(--surface-strong), transparent 30%);
		cursor: pointer;
		list-style: none;
	}

	.quiet-calendar-week > summary::-webkit-details-marker {
		display: none;
	}

	.quiet-calendar-week > summary::before {
		content: '+';
		font-size: 1rem;
		font-weight: 500;
	}

	.quiet-calendar-week > summary span {
		margin-inline-end: auto;
		font-size: 0.8rem;
		font-weight: 720;
		letter-spacing: 0.03em;
		text-transform: uppercase;
	}

	.quiet-calendar-week > summary strong {
		font-size: 0.82rem;
		font-weight: 620;
	}

	.quiet-calendar-week > summary:hover,
	.quiet-calendar-week > summary:focus-visible {
		color: var(--text);
		background: color-mix(in oklab, var(--accent), var(--surface-strong) 95%);
	}

	@media (max-width: 520px) {
		.quiet-calendar-week > summary strong {
			display: none;
		}
	}
</style>
