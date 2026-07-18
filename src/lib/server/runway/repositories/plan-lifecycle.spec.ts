import { describe, expect, test } from 'vitest';
import { createGoalAndPlan, PlanCreationInputError } from './plan-lifecycle';
import type { PlanIntake } from '$lib/training/types';

const establishedIntake: PlanIntake = {
	startMode: 'established',
	goalKind: 'race',
	raceDistance: '5k',
	targetDate: '2026-10-01',
	priority: 'finish_healthy',
	units: 'metric',
	experience: 'returning',
	availability: [1, 3, 6],
	injuryFlags: {
		recentInjury: false,
		currentPain: false,
		recurringPain: false,
		medicalRestriction: false,
		notes: ''
	},
	startDate: '2026-07-20',
	currentWeeklyDistanceMeters: 12_000,
	currentRunsPerWeek: 3,
	longestRecentRunMeters: 5_000,
	preferredLongRunDay: 6
};

describe('plan creation input boundary', () => {
	test('classifies an invalid time zone without touching persistence', async () => {
		expect.assertions(3);
		const error = await createGoalAndPlan('runner', establishedIntake, 'not/a-zone').catch(
			(failure: unknown) => failure
		);

		expect(error).toBeInstanceOf(PlanCreationInputError);
		expect(error).toMatchObject({ code: 'invalid_time_zone', field: 'timeZone' });
		expect((error as Error).message).toBe('Select a valid training time zone.');
	});

	test('classifies planner baseline rejections as correctable input', async () => {
		expect.assertions(3);
		const error = await createGoalAndPlan(
			'runner',
			{ ...establishedIntake, currentWeeklyDistanceMeters: 2_000 },
			'America/Halifax'
		).catch((failure: unknown) => failure);

		expect(error).toBeInstanceOf(PlanCreationInputError);
		expect(error).toMatchObject({ code: 'invalid_baseline', field: 'baseline' });
		expect((error as Error).message).toBe(
			'The planner requires a current weekly baseline of at least 3 km.'
		);
	});
});
