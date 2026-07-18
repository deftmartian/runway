import { describe, expect, test } from 'vitest';
import { parseGoalSetupForm } from './validation';

function validGoalForm(): FormData {
	const form = new FormData();
	form.set('goalKind', 'race');
	form.set('startMode', 'established');
	form.set('raceDistance', 'half');
	form.set('targetDate', '2026-10-01');
	form.set('priority', 'finish_healthy');
	form.set('currentWeeklyDistanceKm', '12');
	form.set('currentRunsPerWeek', '3');
	form.set('longestRecentRunKm', '8');
	form.set('experience', 'returning');
	form.set('calibrationDurationMinutes', '20');
	form.append('availability', '1');
	form.append('availability', '3');
	form.append('availability', '6');
	form.set('preferredLongRunDay', '6');
	form.set('timeZone', 'America/Halifax');
	form.set('injuryNotes', '');
	return form;
}

describe('onboarding action form boundary', () => {
	test('parses the current onboarding transport without coercing numeric fields early', () => {
		const parsed = parseGoalSetupForm(validGoalForm());

		expect(parsed.fieldErrors).toEqual({});
		expect(parsed.values).toMatchObject({
			goalKind: 'race',
			startMode: 'established',
			priority: 'finish_healthy',
			currentWeeklyDistanceKm: '12',
			availability: [1, 3, 6],
			recentInjury: false
		});
	});

	test('rejects an unsupported priority instead of silently accepting a default', () => {
		const form = validGoalForm();
		form.set('priority', 'time');

		const parsed = parseGoalSetupForm(form);

		expect(parsed.fieldErrors.priority).toBe('Choose a supported planning priority.');
		expect(parsed.values.priority).toBe('finish_healthy');
	});

	test('rejects duplicate availability and scalar parameter pollution', () => {
		const duplicateDays = validGoalForm();
		duplicateDays.append('availability', '3');
		const duplicatePriority = validGoalForm();
		duplicatePriority.append('priority', 'consistency');

		expect(parseGoalSetupForm(duplicateDays).fieldErrors.availability).toBe(
			'Choose each available run day only once.'
		);
		expect(parseGoalSetupForm(duplicatePriority).fieldErrors.priority).toBe(
			'Choose a supported planning priority.'
		);
	});

	test('accepts omitted controls that do not apply to the selected path', () => {
		const form = validGoalForm();
		form.delete('calibrationDurationMinutes');
		form.delete('injuryNotes');

		const parsed = parseGoalSetupForm(form);

		expect(parsed.fieldErrors).toEqual({});
		expect(parsed.values.calibrationDurationMinutes).toBe('');
		expect(parsed.values.injuryNotes).toBe('');
	});
});
