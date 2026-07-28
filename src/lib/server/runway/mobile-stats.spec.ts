import { describe, expect, test } from 'vitest';
import { mobileStatsPayload } from './mobile-stats';

describe('mobile stats payload', () => {
	test('preserves generated, current, and accepted weekly values without collapsing them', () => {
		const payload = mobileStatsPayload({
			active: { plan: { id: 'plan-1' } },
			weeks: [{ weekNumber: 1, startDate: '2026-07-27' }],
			history: {
				weeklySummaries: [
					{
						weekNumber: 1,
						startDate: '2026-07-27',
						completedDistanceMeters: 4_500,
						completedDurationSeconds: 1_650,
						averagePaceSecondsPerKm: 366.67,
						averageHeartRate: 142
					}
				]
			},
			planTrace: [
				{
					weekNumber: 1,
					startDate: '2026-07-27',
					recommendedDistanceMeters: 5_000,
					currentDistanceMeters: 5_500,
					recommendedDurationSeconds: 0,
					currentDurationSeconds: 0
				}
			],
			planHistory: { items: [] },
			phaseReview: null
		});

		expect(payload.planTrace[0]).toMatchObject({
			recommendedDistanceMeters: 5_000,
			currentDistanceMeters: 5_500
		});
		expect(payload.history.weeklySummaries[0]).toMatchObject({
			completedDistanceMeters: 4_500,
			completedDurationSeconds: 1_650,
			averagePaceSecondsPerKm: 366.67,
			averageHeartRate: 142
		});
	});
});
