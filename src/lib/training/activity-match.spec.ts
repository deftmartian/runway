import { describe, expect, test } from 'vitest';
import { selectAutoWorkoutMatch, type AutoMatchWorkout } from './activity-match';

const workout = (
	id: string,
	scheduledDate: string,
	targetDistanceMeters: number,
	targetDurationSeconds: number | null = null
): AutoMatchWorkout => ({ id, scheduledDate, targetDistanceMeters, targetDurationSeconds });

describe('selectAutoWorkoutMatch', () => {
	test('selects a unique nearby distance prescription within the material threshold', () => {
		expect(
			selectAutoWorkoutMatch(
				{ activityDate: '2026-05-15', distanceMeters: 4_700, durationSeconds: 1_800 },
				[workout('later', '2026-05-17', 4_700), workout('same-day', '2026-05-15', 5_000)]
			)
		).toBe('same-day');
	});

	test('leaves equally plausible candidates for review', () => {
		expect(
			selectAutoWorkoutMatch(
				{ activityDate: '2026-05-12', distanceMeters: 2_000, durationSeconds: 900 },
				[workout('monday', '2026-05-11', 2_000), workout('wednesday', '2026-05-13', 2_000)]
			)
		).toBeNull();
	});

	test('does not match an amount outside the material threshold', () => {
		expect(
			selectAutoWorkoutMatch(
				{ activityDate: '2026-05-15', distanceMeters: 8_000, durationSeconds: 2_400 },
				[workout('planned', '2026-05-15', 5_000)]
			)
		).toBeNull();
	});

	test('uses duration for timed prescriptions without invented distance', () => {
		expect(
			selectAutoWorkoutMatch(
				{ activityDate: '2026-05-15', distanceMeters: 1_800, durationSeconds: 1_200 },
				[workout('timed', '2026-05-15', 0, 1_200)]
			)
		).toBe('timed');
	});
});
