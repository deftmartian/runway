<script lang="ts">
	import type { CalendarEvent } from './calendar-types';
	import { calendarFlagLabel, presentCalendarEvent } from './calendar-presentation';

	let {
		event,
		selected = false,
		tabindex = -1,
		onselect,
		onfocus,
		onkeydown
	}: {
		event: CalendarEvent;
		selected?: boolean;
		tabindex?: number;
		onselect: (event: CalendarEvent, trigger: HTMLButtonElement) => void;
		onfocus?: (event: CalendarEvent) => void;
		onkeydown?: (event: CalendarEvent, keyboardEvent: KeyboardEvent) => void;
	} = $props();

	const km = (meters: number) => `${Math.round((meters / 1000) * 10) / 10} km`;
	const shortKm = (meters: number) => `${Math.round((meters / 1000) * 10) / 10}`;
	const minutes = (seconds: number) => `${Math.round(seconds / 60)} min`;
	const distanceLabel = $derived.by(() => {
		if (event.activity) return km(event.activity.distanceMeters);
		if (event.feedback) {
			if (event.workout?.status === 'skipped') return 'Skipped';
			if (event.feedback.completedDistanceMeters !== null) {
				return km(event.feedback.completedDistanceMeters);
			}
		}
		if (event.workout && event.workout.targetDistanceMeters > 0) {
			return km(event.workout.targetDistanceMeters);
		}
		if (event.workout?.targetDurationSeconds) return minutes(event.workout.targetDurationSeconds);
		if (event.kind === 'open') return 'No plan';
		return 'Rest';
	});
	const presentation = $derived(presentCalendarEvent(event));
	const statusLabel = $derived(presentation.label);
	const flagLabels = $derived(presentation.flags.map(calendarFlagLabel));
	const compactLabel = $derived.by(() => {
		if (event.workout?.isRemoved) return '—';
		if (event.kind === 'rest') return '—';
		if (event.kind === 'open') return '+';
		if (event.activity) return shortKm(event.activity.distanceMeters);
		if (event.feedback) {
			if (event.workout?.status === 'skipped') return 'Skip';
			if (event.feedback.completedDistanceMeters !== null) {
				return shortKm(event.feedback.completedDistanceMeters);
			}
		}
		if (event.workout?.targetDistanceMeters) return shortKm(event.workout.targetDistanceMeters);
		if (event.workout?.targetDurationSeconds) {
			return `${Math.round(event.workout.targetDurationSeconds / 60)}m`;
		}
		return event.title;
	});
	const compactStatusLabel = $derived(presentation.compactLabel);
	const accessibleFlags = $derived(flagLabels.length > 0 ? `, ${flagLabels.join(', ')}` : '');
</script>

<button
	type="button"
	class="calendar-event {event.kind}"
	class:selected
	class:needs-feedback={presentation.state === 'missed'}
	class:needs-review={presentation.state === 'needs_review'}
	class:pain={presentation.flags.includes('pain')}
	class:hard-effort={presentation.flags.includes('hard_effort') &&
		!presentation.flags.includes('pain')}
	data-compact={compactLabel}
	data-status={presentation.state ?? 'open'}
	data-compact-status={compactStatusLabel}
	data-calendar-event-id={event.id}
	{tabindex}
	aria-expanded={selected}
	aria-controls={selected ? 'event-detail-panel' : undefined}
	aria-label={`${event.date}: ${event.title}, ${distanceLabel}, ${statusLabel}${accessibleFlags}`}
	onfocus={() => onfocus?.(event)}
	onkeydown={(keyboardEvent) => onkeydown?.(event, keyboardEvent)}
	onclick={(mouseEvent) => {
		onselect(event, mouseEvent.currentTarget);
	}}
>
	<span class="event-title">{event.title}</span>
	<small class="event-meta">{distanceLabel} · {statusLabel}</small>
	<span class="event-compact" aria-hidden="true">
		<strong>{compactLabel}</strong>
		<em>{compactStatusLabel}</em>
	</span>
</button>
