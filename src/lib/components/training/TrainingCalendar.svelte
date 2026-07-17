<script lang="ts">
	import { resolve } from '$app/paths';
	import { tick } from 'svelte';
	import CalendarEventButton from './CalendarEvent.svelte';
	import EventDetailPanel from './EventDetailPanel.svelte';
	import { presentCalendarEvent } from './calendar-presentation';
	import type { ConsequenceResult, RiskRating } from '$lib/training/types';
	import type {
		TrainingCalendarActivity,
		TrainingCalendarFeedback,
		TrainingCalendarPayload,
		TrainingCalendarWeek,
		TrainingCalendarWorkout
	} from '$lib/training/calendar-view';
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
		  }
		| null
		| undefined;
	type WeekLoad = {
		id: string;
		label: string;
		week: TrainingCalendarWeek;
		rampValue: number;
		completionValue: number;
		isCurrent: boolean;
	};
	type CalendarWeekRow = {
		id: string;
		label: string;
		load: WeekLoad | null;
		days: CalendarDay[];
	};

	let {
		calendar,
		form,
		currentSignal,
		hasActivePlan = false,
		targetDate = null,
		activityCandidates = []
	}: {
		calendar: TrainingCalendarPayload;
		form: CalendarFormState;
		currentSignal?: TrainingSignal;
		hasActivePlan?: boolean;
		targetDate?: string | null;
		activityCandidates?: WorkoutCandidate[];
	} = $props();

	let selectedEventId = $state<string | null>(null);
	let focusedEventId = $state<string | null>(null);
	let returnFocus: HTMLElement | null = null;

	const weekdayLabels = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
	const addIsoDays = (date: string, days: number) => {
		const timestamp = Date.parse(`${date}T00:00:00.000Z`);
		return new Date(timestamp + days * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
	};
	const weekdayLabel = (date: string) =>
		new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { weekday: 'short' });
	const dayNumber = (date: string) =>
		new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric' });
	const isTrainingRun = (workout: TrainingCalendarWorkout) =>
		workout.type !== 'rest' && workout.prescriptionKind !== 'rest' && !workout.isRemoved;
	const canRecordWorkout = (workout: TrainingCalendarWorkout) =>
		isTrainingRun(workout) &&
		workout.status === 'planned' &&
		workout.scheduledDate <= calendar.today;
	const weekForDate = (date: string) =>
		calendar.weeks.find(
			(week) => date >= week.startDate && date <= addIsoDays(week.startDate, 6)
		) ?? null;
	const weekForWorkout = (workout: TrainingCalendarWorkout) =>
		calendar.weeks.find((week) => week.id === workout.weekId) ?? weekForDate(workout.scheduledDate);
	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const minutes = (seconds: number) => `${Math.round(seconds / 60)} min`;

	const allEvents = $derived.by(() => buildEvents(calendar));
	const calendarDays = $derived.by(() => buildCalendarDays(calendar, allEvents));
	const calendarRows = $derived.by(() => buildCalendarRows(calendar, calendarDays));
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
	const currentSignalSourceLabel = $derived(
		currentSignal?.source === 'feedback'
			? 'Recent feedback'
			: currentSignal?.source === 'activity'
				? 'Recent activity'
				: 'Current plan'
	);
	const calendarQuery = (month: string) => `month=${month}`;
	const emptyDayLabel = (day: CalendarDay) =>
		day.isToday
			? 'Today. No training scheduled.'
			: `${day.weekday}, ${day.date}. No training scheduled.`;

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
		document
			.querySelector<HTMLElement>(`[data-calendar-event-id="${CSS.escape(eventId)}"]`)
			?.focus();
	}

	function openDayEvent(date: string, payload: TrainingCalendarPayload): CalendarEvent {
		return {
			id: `open-${date}`,
			date,
			kind: 'open',
			title: 'Open day',
			workout: null,
			activity: null,
			feedback: null,
			week: weekForDate(date),
			isRecordable: false,
			isToday: date === payload.today
		};
	}

	function buildEvents(payload: TrainingCalendarPayload): CalendarEvent[] {
		const feedbackByWorkout: Record<string, TrainingCalendarFeedback> = {};
		for (const record of payload.feedback) {
			feedbackByWorkout[record.workoutId] = record;
		}
		const activitiesByWorkout: Record<string, TrainingCalendarActivity[]> = {};
		for (const record of payload.activities) {
			if (!record.workoutId) continue;
			const records = activitiesByWorkout[record.workoutId] ?? [];
			records.push(record);
			activitiesByWorkout[record.workoutId] = records;
		}

		const workoutIds = payload.workouts.map((workout) => workout.id);
		const events: CalendarEvent[] = payload.workouts.map((workout) => {
			const activity = activitiesByWorkout[workout.id]?.[0] ?? null;
			const feedback = feedbackByWorkout[workout.id] ?? null;
			const date = activity?.occurredDate ?? workout.scheduledDate;
			return {
				id: `workout-${workout.id}`,
				date,
				kind:
					workout.status === 'skipped' && feedback
						? 'review'
						: activity || feedback
							? 'actual'
							: workout.type === 'rest'
								? 'rest'
								: 'planned',
				title: workout.type === 'rest' ? 'Rest' : workout.purpose,
				workout,
				activity,
				feedback,
				week: weekForWorkout(workout),
				isRecordable: canRecordWorkout(workout),
				isToday: date === payload.today
			};
		});

		for (const record of payload.activities) {
			if (record.workoutId && workoutIds.includes(record.workoutId)) continue;
			const week = weekForDate(record.occurredDate);
			events.push({
				id: `activity-${record.id}`,
				date: record.occurredDate,
				kind: 'actual',
				title: record.matchedWorkoutPurpose ?? 'Imported run',
				workout: null,
				activity: record,
				feedback: null,
				week,
				isRecordable: false,
				isToday: record.occurredDate === payload.today
			});
		}

		return events.sort((left, right) => {
			if (left.date !== right.date) return left.date.localeCompare(right.date);
			return eventPriority(left) - eventPriority(right);
		});
	}

	function eventPriority(event: CalendarEvent): number {
		if (event.kind === 'actual') return 0;
		if (event.isRecordable) return 1;
		if (event.kind === 'planned') return 2;
		return 3;
	}

	function buildCalendarDays(
		payload: TrainingCalendarPayload,
		events: CalendarEvent[]
	): CalendarDay[] {
		const eventMap: Record<string, CalendarEvent[]> = {};
		for (const event of events) {
			const records = eventMap[event.date] ?? [];
			records.push(event);
			eventMap[event.date] = records;
		}

		const days: CalendarDay[] = [];
		for (
			let currentDate = payload.rangeStart;
			currentDate <= payload.rangeEnd;
			currentDate = addIsoDays(currentDate, 1)
		) {
			const dayEvents = eventMap[currentDate] ?? [];
			days.push({
				date: currentDate,
				weekday: weekdayLabel(currentDate),
				dayNumber: dayNumber(currentDate),
				inSelectedMonth: currentDate.startsWith(`${payload.month}-`),
				isToday: currentDate === payload.today,
				events:
					dayEvents.length === 0 &&
					(currentDate <= payload.today ||
						(hasActivePlan &&
							currentDate >= payload.today &&
							(!targetDate || currentDate <= targetDate)))
						? [openDayEvent(currentDate, payload)]
						: dayEvents
			});
		}

		return days;
	}

	function buildCalendarRows(
		payload: TrainingCalendarPayload,
		days: CalendarDay[]
	): CalendarWeekRow[] {
		const loadByWeekStart: Record<string, WeekLoad> = {};
		for (const load of buildWeekLoad(payload)) {
			loadByWeekStart[load.week.startDate] = load;
		}

		const rows: CalendarWeekRow[] = [];
		for (let index = 0; index < days.length; index += 7) {
			const rowDays = days.slice(index, index + 7);
			const startDate = rowDays[0]?.date ?? payload.rangeStart;
			const load = loadByWeekStart[startDate] ?? null;
			rows.push({
				id: startDate,
				label: load?.label ?? `Week of ${startDate}`,
				load,
				days: rowDays
			});
		}
		return rows;
	}

	function buildWeekLoad(payload: TrainingCalendarPayload): WeekLoad[] {
		if (payload.weeks.length === 0 || !payload.planScale) return [];
		const usesDuration = payload.weeks.some(
			(week) => week.targetDurationSeconds > 0 && week.targetDistanceMeters === 0
		);
		const peak = Math.max(
			1,
			usesDuration
				? Math.max(...payload.weeks.map((week) => week.targetDurationSeconds))
				: payload.planScale.peakMeters
		);

		return payload.weeks.map((week) => {
			return {
				id: week.id,
				label: `Week ${week.weekNumber}`,
				week,
				rampValue: Math.max(
					8,
					Math.min(
						100,
						Math.round(
							((usesDuration ? week.targetDurationSeconds : week.targetDistanceMeters) / peak) * 100
						)
					)
				),
				completionValue: Math.min(
					100,
					Math.round(
						((usesDuration ? week.completedDurationSeconds : week.completedDistanceMeters) / peak) *
							100
					)
				),
				isCurrent: payload.today >= week.startDate && payload.today <= addIsoDays(week.startDate, 6)
			};
		});
	}
</script>

<section class="training-shell">
	<div class="training-calendar-panel">
		<div class="calendar-toolbar">
			<div class="training-title-block">
				<h1 class="section-title">Training calendar</h1>
				{#if currentSignal}
					<details
						class="plan-assessment"
						class:bad-message={currentSignal.risk === 'unsafe'}
						open={currentSignal.risk === 'unsafe'}
					>
						<summary>
							<span>Plan risk · {currentSignalSourceLabel}</span>
							<strong>{currentSignal.risk}</strong>
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
		<div class="calendar-month-scroll">
			<div class="calendar-weekday-row" aria-hidden="true">
				{#each weekdayLabels as label (label)}
					<span class="calendar-weekday">{label}</span>
				{/each}
			</div>

			<div
				class="calendar-month-grid"
				role="region"
				aria-label={`${monthTitle} training calendar`}
				aria-describedby="calendar-keyboard-help"
			>
				{#each calendarRows as row (row.id)}
					<section class="calendar-month-week" aria-label={row.label}>
						{#if row.load}
							{@const load = row.load}
							{@const durationLoad =
								load.week.targetDurationSeconds > 0 && load.week.targetDistanceMeters === 0}
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
										durationLoad ? load.week.targetDurationSeconds : load.week.targetDistanceMeters
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
									<span
										class="badge"
										class:warn={load.week.risk === 'aggressive'}
										class:bad={load.week.risk === 'unsafe'}
										class:good={load.week.risk === 'conservative'}
										>{load.week.isTaper
											? 'taper'
											: load.week.isDownWeek
												? 'down week'
												: load.week.risk}</span
									>
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
									class:compact-empty={day.events.length === 0 && !day.isToday}
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
