<script lang="ts">
	import type { SubmitFunction } from '@sveltejs/kit';
	import { onMount, tick } from 'svelte';
	import type { TrainingCalendarWorkout } from '$lib/training/calendar-view';
	import EventActivityRecord from './EventActivityRecord.svelte';
	import EventDetailOverview from './EventDetailOverview.svelte';
	import EventFeedbackDecisions from './EventFeedbackDecisions.svelte';
	import EventRunRecording from './EventRunRecording.svelte';
	import EventWorkoutPlanControls from './EventWorkoutPlanControls.svelte';
	import type { CalendarEvent, CalendarFormState, WorkoutCandidate } from './calendar-types';
	import {
		decisionRecordForEvent,
		formForEvent,
		formatDay,
		type EventActionEnhancer
	} from './event-detail-model';

	let {
		event,
		candidates = [],
		form,
		today,
		targetDate,
		hasActivePlan,
		futureWorkouts = [],
		onclose
	}: {
		event: CalendarEvent;
		candidates?: WorkoutCandidate[];
		form: CalendarFormState;
		today: string;
		targetDate: string;
		hasActivePlan: boolean;
		futureWorkouts?: TrainingCalendarWorkout[];
		onclose: () => void;
	} = $props();

	const decisionRecord = $derived(decisionRecordForEvent(event));
	const eventForm = $derived(formForEvent(form, event, decisionRecord));

	let panel: HTMLDivElement | undefined;
	let desktopInspector = $state(false);
	let focusedEventId = '';
	let pendingAction = $state<string | null>(null);

	const enhanceEventAction: EventActionEnhancer =
		(key: string, confirmation?: string): SubmitFunction =>
		({ cancel }) => {
			if (pendingAction || (confirmation && !confirm(confirmation))) {
				cancel();
				return;
			}
			pendingAction = key;
			return async ({ update }) => {
				try {
					await update();
				} finally {
					pendingAction = null;
				}
			};
		};

	$effect(() => {
		if (event.id === focusedEventId) return;
		focusedEventId = event.id;
		void tick().then(() => panel?.focus());
	});

	onMount(() => {
		const mediaQuery = window.matchMedia('(min-width: 1180px)');
		const updateInspectorMode = () => {
			desktopInspector = mediaQuery.matches;
		};
		updateInspectorMode();
		mediaQuery.addEventListener('change', updateInspectorMode);
		return () => {
			mediaQuery.removeEventListener('change', updateInspectorMode);
		};
	});

	$effect(() => {
		if (desktopInspector) return;
		const previousBodyOverflow = document.body.style.overflow;
		const previousHtmlOverflow = document.documentElement.style.overflow;
		document.body.style.overflow = 'hidden';
		document.documentElement.style.overflow = 'hidden';
		return () => {
			document.body.style.overflow = previousBodyOverflow;
			document.documentElement.style.overflow = previousHtmlOverflow;
		};
	});

	function visibleFocusableElements(): HTMLElement[] {
		if (!panel) return [];
		return Array.from(
			panel.querySelectorAll<HTMLElement>(
				'a[href], button:not([disabled]), details summary, input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
			)
		).filter((element) => element.offsetParent !== null);
	}

	function handleModalFocus(focusEvent: FocusEvent) {
		if (desktopInspector || !panel || panel.contains(focusEvent.target as Node)) return;
		(visibleFocusableElements()[0] ?? panel).focus();
	}

	function handleModalKeydown(keyboardEvent: KeyboardEvent) {
		if (keyboardEvent.key === 'Escape') {
			onclose();
			return;
		}

		if (desktopInspector || keyboardEvent.key !== 'Tab' || !panel) return;
		const focusable = visibleFocusableElements();

		if (focusable.length === 0) {
			keyboardEvent.preventDefault();
			panel.focus();
			return;
		}

		const first = focusable[0];
		const last = focusable[focusable.length - 1];
		if (!panel.contains(document.activeElement)) {
			keyboardEvent.preventDefault();
			first?.focus();
			return;
		}
		if (keyboardEvent.shiftKey && document.activeElement === first) {
			keyboardEvent.preventDefault();
			last?.focus();
		} else if (!keyboardEvent.shiftKey && document.activeElement === last) {
			keyboardEvent.preventDefault();
			first?.focus();
		}
	}
</script>

<svelte:window onkeydown={handleModalKeydown} onfocusin={handleModalFocus} />

<div class="event-detail-backdrop" aria-hidden="true"></div>

<div
	id="event-detail-panel"
	class="event-detail-panel"
	role={desktopInspector ? 'region' : 'dialog'}
	aria-modal={desktopInspector ? undefined : 'true'}
	aria-labelledby="event-detail-heading"
	tabindex="-1"
	bind:this={panel}
>
	<div class="event-detail-header">
		<div>
			<div class="badge">{formatDay(event.date)}</div>
			<h2 id="event-detail-heading" class="section-title">{event.title}</h2>
		</div>
		<button
			type="button"
			class="ghost close-button"
			aria-label="Close training detail"
			onclick={onclose}
		>
			Close
		</button>
	</div>

	<EventDetailOverview {event} />
	<EventWorkoutPlanControls
		{event}
		{form}
		{eventForm}
		{today}
		{targetDate}
		{hasActivePlan}
		{pendingAction}
		enhanceAction={enhanceEventAction}
	/>
	<EventRunRecording
		{event}
		{eventForm}
		{today}
		{pendingAction}
		enhanceAction={enhanceEventAction}
	/>
	<EventActivityRecord {event} {candidates} {pendingAction} enhanceAction={enhanceEventAction} />
	<EventFeedbackDecisions
		{event}
		{eventForm}
		{futureWorkouts}
		{today}
		{pendingAction}
		enhanceAction={enhanceEventAction}
	/>
</div>
