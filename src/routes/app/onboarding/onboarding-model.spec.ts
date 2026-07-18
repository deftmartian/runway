import { describe, expect, it } from 'vitest';
import {
	errorStep,
	modeLabel,
	requiresConcentratedSchedule,
	targetWindowsForTimeZone,
	validationMessage,
	validationStep,
	type OnboardingValues,
	type TargetWindows
} from './onboarding-model';

const windows: TargetWindows = {
	established: '2026-03-01',
	calibration: '2026-03-15',
	foundation: '2026-05-03',
	maximum: '2026-12-31'
};

function validValues(overrides: Partial<OnboardingValues> = {}): OnboardingValues {
	return {
		goalKind: 'race',
		startMode: 'established',
		raceDistance: 'half',
		targetDate: '2026-08-01',
		priority: 'finish_healthy',
		currentWeeklyDistanceKm: '24',
		currentRunsPerWeek: '3',
		longestRecentRunKm: '12',
		experience: 'returning',
		calibrationDurationMinutes: '20',
		availability: [1, 3, 6],
		preferredLongRunDay: '6',
		timeZone: 'America/Halifax',
		recentInjury: false,
		currentPain: false,
		recurringPain: false,
		medicalRestriction: false,
		injuryNotes: '',
		confirmConcentratedSchedule: false,
		confirmReplace: false,
		...overrides
	};
}

describe('onboarding presentation model', () => {
	it('keeps each of the four start modes distinct', () => {
		expect(modeLabel(validValues())).toContain('established week');
		expect(modeLabel(validValues({ startMode: 'foundation_to_goal' }))).toContain(
			'confirm a race baseline'
		);
		expect(modeLabel(validValues({ startMode: 'foundation_only' }))).toContain(
			'30 continuous minutes'
		);
		expect(
			modeLabel(validValues({ startMode: 'calibration', calibrationDurationMinutes: '15' }))
		).toBe('Two-week 15 minute calibration');
	});

	it('routes invalid setup to the owning stage', () => {
		expect(validationStep(validValues({ targetDate: '2026-02-01' }), windows, false)).toBe(0);
		expect(validationStep(validValues({ experience: '' }), windows, false)).toBe(1);
		expect(validationStep(validValues({ availability: [1] }), windows, false)).toBe(2);
		expect(
			validationStep(validValues({ currentRunsPerWeek: '2', availability: [1, 6] }), windows, false)
		).toBe(3);
		expect(validationStep(validValues(), windows, true)).toBe(3);
	});

	it('requires acknowledgement only for an unblocked two-day long-distance plan', () => {
		const concentrated = validValues({ currentRunsPerWeek: '2', availability: [1, 6] });
		expect(requiresConcentratedSchedule(concentrated)).toBe(true);
		expect(requiresConcentratedSchedule({ ...concentrated, currentPain: true })).toBe(false);
		expect(requiresConcentratedSchedule({ ...concentrated, raceDistance: '10k' })).toBe(false);
		expect(validationMessage(3, concentrated, windows)).toContain('two-day concentration');
	});

	it('maps server errors back to the hidden stage that owns the field', () => {
		expect(errorStep({ raceDistance: 'Choose a distance' })).toBe(0);
		expect(errorStep({ healthFlags: 'Review health answers' })).toBe(1);
		expect(errorStep({ timeZone: 'Choose a zone' })).toBe(2);
		expect(errorStep({ confirmReplace: 'Confirm replacement' })).toBe(3);
	});

	it('computes target bounds using the selected training zone', () => {
		expect(
			targetWindowsForTimeZone('America/Halifax', new Date('2026-01-01T02:30:00.000Z'))
		).toEqual({
			established: '2026-02-25',
			calibration: '2026-03-11',
			foundation: '2026-04-29',
			maximum: '2026-12-29'
		});
		expect(targetWindowsForTimeZone('Not/AZone')).toBeNull();
	});
});
