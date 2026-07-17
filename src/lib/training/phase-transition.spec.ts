import { describe, expect, test } from 'vitest';
import {
	canUseDistancePlannerBaseline,
	derivePhaseBaseline,
	phaseTransitionOptions
} from './phase-transition';

describe('phase completion baseline', () => {
	test('derives count, duration, distance, and longest activity without inventing values', () => {
		const baseline = derivePhaseBaseline(
			[
				{ distanceMeters: 2_800, durationSeconds: 2_400, completed: true },
				{ distanceMeters: 3_100, durationSeconds: 2_350, completed: true },
				{ distanceMeters: null, durationSeconds: 2_500, completed: true },
				{ distanceMeters: 4_000, durationSeconds: 2_600, completed: false }
			],
			1
		);

		expect(baseline).toEqual({
			activityCount: 3,
			totalDurationSeconds: 7_250,
			totalDistanceMeters: 5_900,
			longestActivityMeters: 3_100,
			weeklyDistanceMeters: 5_900,
			runsPerWeek: 3
		});
	});

	test('keeps zero observations finite and unsupported', () => {
		const baseline = derivePhaseBaseline([], 2);
		expect(Object.values(baseline).every(Number.isFinite)).toBe(true);
		expect(canUseDistancePlannerBaseline(baseline)).toBe(false);
		expect(phaseTransitionOptions('calibration', 'race', baseline, false)).toEqual({
			recommended: 'continue_calibration',
			options: ['continue_calibration', 'later_date', 'shorter_goal']
		});
	});

	test('offers race conversion only after explicit supported-baseline review', () => {
		const baseline = derivePhaseBaseline(
			[
				{ distanceMeters: 3_000, durationSeconds: 1_800, completed: true },
				{ distanceMeters: 3_200, durationSeconds: 1_900, completed: true },
				{ distanceMeters: 3_500, durationSeconds: 2_000, completed: true }
			],
			1
		);
		expect(phaseTransitionOptions('foundation', 'race', baseline, true)).toEqual({
			recommended: 'confirm_race_baseline',
			options: ['confirm_race_baseline', 'another_foundation_week', 'later_date', 'shorter_goal']
		});
	});
});
