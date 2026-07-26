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
	import { formatRampEvidence } from '$lib/training/training-assessment';
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
		requiredWeeklyIncreasePercent = null,
		activityCandidates = []
	}: {
		calendar: TrainingCalendarPayload;
		form: CalendarFormState;
		currentSignal?: TrainingSignal;
		hasActivePlan?: boolean;
		targetDate?: string | null;
		defaultWeeklyIncreasePercent?: number | null;
		requiredWeeklyIncreasePercent?: number | null;
		activityCandidates?: WorkoutCandidate[];
	} = $props();

	let selectedEventId = $state<string | null>(null);
	let focusedEventId = $state<string | null>(null);
	let returnFocus: HTMLElement | null = null;
	let calendarScroll = $state<HTMLDivElement>();
	let calendarGrid = $state<HTMLDivElement>();
	let calendarOverflowing = $state(false);
	let hydrated = $state(false);
	let compactCalendar = $state(false);

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
	const compactCalendarEvents = $derived(
		calendarDays
			.filter((day) => day.inSelectedMonth || day.isToday || !isQuietCalendarDay(day))
			.flatMap((day) => day.events)
	);
	const navigableCalendarEvents = $derived(
		compactCalendar ? compactCalendarEvents : calendarEvents
	);
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
	const todayEvent = $derived(todayEvents[0] ?? null);
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
	const currentPlanRampEvidence = $derived.by(() => {
		if (
			currentSignal?.source !== 'plan' ||
			requiredWeeklyIncreasePercent === null ||
			defaultWeeklyIncreasePercent === null
		) {
			return null;
		}
		return formatRampEvidence(requiredWeeklyIncreasePercent, defaultWeeklyIncreasePercent);
	});

	onMount(() => {
		hydrated = true;
		const compactQuery = window.matchMedia('(max-width: 820px)');
		const updateCompactCalendar = () => {
			compactCalendar = compactQuery.matches;
		};
		updateCompactCalendar();
		compactQuery.addEventListener('change', updateCompactCalendar);
		const updateOverflow = () => {
			calendarOverflowing = Boolean(
				!compactQuery.matches &&
				calendarScroll &&
				calendarScroll.scrollWidth > calendarScroll.clientWidth + 1
			);
		};
		const observer = new ResizeObserver(updateOverflow);
		if (calendarScroll) observer.observe(calendarScroll);
		if (calendarGrid) observer.observe(calendarGrid);
		const frame = requestAnimationFrame(updateOverflow);
		return () => {
			cancelAnimationFrame(frame);
			observer.disconnect();
			compactQuery.removeEventListener('change', updateCompactCalendar);
		};
	});
	const calendarQuery = (month: string) => `month=${month}`;
	const emptyDayLabel = (day: CalendarDay) =>
		day.isToday
			? 'Today. No training scheduled.'
			: `${day.weekday}, ${day.date}. No training scheduled.`;
	const dayHeadingLabel = (day: CalendarDay) =>
		day.inSelectedMonth
			? day.weekday
			: `${new Date(`${day.date}T00:00:00`).toLocaleDateString(undefined, { month: 'short' })} ${day.weekday}`;
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
	const weekDateRange = (row: CalendarWeekRow) => {
		const first = row.days[0]?.date;
		const last = row.days.at(-1)?.date;
		if (!first || !last) return '';
		const format = (date: string) =>
			new Date(`${date}T00:00:00`).toLocaleDateString(undefined, {
				month: 'short',
				day: 'numeric'
			});
		return `${format(first)}–${format(last)}`;
	};
	const loadValue = (value: number, metric: NonNullable<CalendarWeekRow['load']>['metric']) =>
		metric === 'duration' ? minutes(value) : km(value);

	$effect(() => {
		if (selectedEventId && !calendarEvents.some((event) => event.id === selectedEventId)) {
			selectedEventId = null;
		}
	});

	$effect(() => {
		if (!navigableCalendarEvents.some((event) => event.id === focusedEventId)) {
			focusedEventId =
				navigableCalendarEvents.find((event) => event.isToday)?.id ??
				navigableCalendarEvents[0]?.id ??
				null;
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
		const currentIndex = navigableCalendarEvents.findIndex(
			(candidate) => candidate.id === event.id
		);
		if (currentIndex < 0) return;
		let nextIndex: number | null = null;
		if (compactCalendar) {
			switch (keyboardEvent.key) {
				case 'ArrowRight':
				case 'ArrowDown':
					nextIndex = Math.min(navigableCalendarEvents.length - 1, currentIndex + 1);
					break;
				case 'ArrowLeft':
				case 'ArrowUp':
					nextIndex = Math.max(0, currentIndex - 1);
					break;
				case 'Home':
					nextIndex = 0;
					break;
				case 'End':
					nextIndex = navigableCalendarEvents.length - 1;
					break;
			}
			if (nextIndex === null || nextIndex === currentIndex) return;
			keyboardEvent.preventDefault();
			focusedEventId = navigableCalendarEvents[nextIndex]?.id ?? focusedEventId;
			void tick().then(() => {
				focusCalendarEvent(focusedEventId);
			});
			return;
		}
		switch (keyboardEvent.key) {
			case 'ArrowRight':
				nextIndex = Math.min(navigableCalendarEvents.length - 1, currentIndex + 1);
				break;
			case 'ArrowLeft':
				nextIndex = Math.max(0, currentIndex - 1);
				break;
			case 'ArrowDown':
				nextIndex = navigableCalendarEvents.findIndex(
					(candidate) => candidate.date === addIsoDays(event.date, 7)
				);
				break;
			case 'ArrowUp':
				nextIndex = navigableCalendarEvents.findIndex(
					(candidate) => candidate.date === addIsoDays(event.date, -7)
				);
				break;
			case 'Home':
				nextIndex = 0;
				break;
			case 'End':
				nextIndex = navigableCalendarEvents.length - 1;
				break;
		}
		if (nextIndex === null || nextIndex < 0 || nextIndex === currentIndex) return;
		keyboardEvent.preventDefault();
		focusedEventId = navigableCalendarEvents[nextIndex]?.id ?? focusedEventId;
		void tick().then(() => {
			focusCalendarEvent(focusedEventId);
		});
	}

	function focusCalendarEvent(eventId: string | null) {
		if (!eventId) return;
		const selector = `[data-calendar-event-id="${CSS.escape(eventId)}"]`;
		const target = document.querySelector<HTMLElement>(selector);
		const disclosure = target?.closest<HTMLDetailsElement>('details.quiet-calendar-week');
		if (disclosure && !disclosure.open) {
			disclosure.open = true;
			void tick().then(() => {
				requestAnimationFrame(() => {
					document.querySelector<HTMLElement>(selector)?.focus();
				});
			});
			return;
		}
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

<section class="training-shell" data-hydrated={hydrated}>
	<div class="training-calendar-panel">
		<div class="calendar-toolbar">
			<div class="calendar-heading-row">
				<div class="training-title-block">
					<h1 class="section-title">Training calendar</h1>
					<p class="current-week-readout">
						<span>This week</span><strong>{currentWeekLabel}</strong>
					</p>
				</div>
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
					<a
						class="button quiet-today"
						href={resolve(`/app?${calendarQuery(calendar.currentMonth)}`)}
						aria-label="Current month"
						aria-current={calendar.month === calendar.currentMonth ? 'date' : undefined}>Today</a
					>
				</div>
			</div>
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
			{#if targetReached}
				<div class="message compact-message" role="status">
					<strong>Target date reached.</strong>
					<span
						>Review this training block in History and decide whether to complete or stop it.</span
					>
				</div>
			{/if}
			<section class="training-decision-sequence" aria-labelledby="training-decision-title">
				<header>
					<h2 id="training-decision-title">Current decision</h2>
					<span>Today → next → review</span>
				</header>
				<div class="training-command-strip">
					<button
						type="button"
						class="command-readout current interactive"
						disabled={!todayEvent}
						onclick={(mouseEvent) => {
							if (todayEvent) selectEvent(todayEvent, mouseEvent.currentTarget);
						}}
					>
						<span><i aria-hidden="true">1</i>Today</span>
						<strong>{todayStatus}</strong>
					</button>
					<button
						type="button"
						class="command-readout next interactive"
						disabled={!nextRun}
						onclick={(mouseEvent) => {
							if (nextRun) selectEvent(nextRun, mouseEvent.currentTarget);
						}}
					>
						<span><i aria-hidden="true">2</i>Next</span>
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
						<span><i aria-hidden="true">3</i>Review</span>
						<strong>{openItems.length === 0 ? 'Clear' : openItemsLabel}</strong>
					</button>
				</div>
			</section>
			<div class="calendar-context-row">
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
				{#if currentPlanRampEvidence}
					<p class="plan-assessment-evidence" role="status">
						<strong>{currentPlanRampEvidence}</strong>
						<span>Planning comparison, not medical guidance.</span>
					</p>
				{/if}
				{#if targetReached}
					<a class="button primary context-action" href={resolve('/app/history')}
						>Review ended plan</a
					>
				{:else}
					<a class="button ghost context-action" href={resolve('/app/onboarding')}
						>{hasActivePlan ? 'Change goal' : 'Build plan'}</a
					>
				{/if}
			</div>
		</div>

		<p id="calendar-keyboard-help" class="sr-only">
			{compactCalendar
				? 'Use Up or Left for the previous training day and Down or Right for the next. Press Enter to open a day.'
				: 'Use Left and Right for adjacent training days and Up and Down for the same weekday. Press Enter to open a day.'}
		</p>
		{#if calendarOverflowing}
			<p id="calendar-scroll-help" class="calendar-scroll-help">
				Swipe sideways to read every workout and see all seven days.
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
								{@const weekIndex = calendar.weeks.findIndex((week) => week.id === load.week.id)}
								{@const weekAssessment = presentCalendarWeekAssessment({
									week: load.week,
									previousWeek: weekIndex > 0 ? (calendar.weeks[weekIndex - 1] ?? null) : null,
									baselineMeters: calendar.planScale?.baselineMeters ?? null,
									defaultWeeklyIncreasePercent
								})}
								<div class="calendar-week-load" class:current={load.isCurrent}>
									<div class="week-load-meta">
										<p>
											<span>{load.label}</span>
											{#if load.isCurrent}<strong>This week</strong>{/if}
											{#if load.isEdited}<em>Plan changed</em>{/if}
										</p>
										<h2>{weekDateRange(row)}</h2>
										{#if load.week.eventDistanceMeters > 0}
											<small>
												Goal event {km(load.week.eventCompletedDistanceMeters)} of {km(
													load.week.eventDistanceMeters
												)}
											</small>
										{/if}
									</div>
									{#if load.metric === 'mixed'}
										<p class="mixed-load-note">
											Mixed distance and timed runs. Compare the individual prescriptions below.
										</p>
									{:else}
										<div
											class="week-load-comparison"
											role="group"
											aria-label={`${load.label} generated, current, and actual ${load.metric} load`}
										>
											<div class="week-load-lane generated">
												<span>Generated</span>
												<i aria-hidden="true"
													><b style={`--week-load: ${load.generatedPercent}%`}></b></i
												>
												<strong>{loadValue(load.generatedValue, load.metric)}</strong>
											</div>
											<div class="week-load-lane planned">
												<span>Current</span>
												<i aria-hidden="true"
													><b style={`--week-load: ${load.currentPercent}%`}></b></i
												>
												<strong>{loadValue(load.currentValue, load.metric)}</strong>
											</div>
											<div class="week-load-lane actual">
												<span>Actual</span>
												<i aria-hidden="true"
													><b style={`--week-load: ${load.actualPercent}%`}></b></i
												>
												<strong>{loadValue(load.actualValue, load.metric)}</strong>
											</div>
										</div>
									{/if}
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
												>Pain reported · {load.week.painFlags}</span
											>{/if}
										{#if load.week.hardFlags > 0}<span class="badge warn"
												>Hard effort · {load.week.hardFlags}</span
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
											<span>{dayHeadingLabel(day)}</span>
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
		<details class="calendar-state-key calendar-state-key-bottom">
			<summary>Calendar state key</summary>
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
		border-bottom: 1px solid var(--line-passive);
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
		background: color-mix(in oklab, var(--surface-soft), transparent 16%);
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
		font-weight: 680;
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
			font-size: 0.76rem;
		}
	}
</style>
