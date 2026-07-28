import { describe, expect, test } from 'vitest';
import { mobileActivityDetail } from './mobile-activity-detail';

describe('mobile activity detail contract', () => {
	test('keeps useful summaries while excluding route and heart-rate sample series', () => {
		const detail = mobileActivityDetail({
			id: 'activity-1', workoutId: 'workout-1', source: 'gpx', reviewState: 'accepted',
			occurredAt: new Date('2026-07-28T09:00:00.000Z'), activityDate: '2026-07-28',
			distanceMeters: 5_000, durationSeconds: 1_500, averagePaceSecondsPerKm: 300,
			averageHeartRate: 142, maxHeartRate: 166,
			heartRateSummary: { effort: 'unknown', highSeconds: 180, highShare: 0.12,
				secondsByZone: { z1: 60, z2: 900, z3: 360, z4: 120, z5: 60 }, settingsSource: 'custom' },
			feltHard: false, pain: false, extraPlanImpactConfirmed: false, consequence: null,
			routeSummary: { pointCount: 42, startEndRedacted: false, hasElevation: true, traceRetained: true },
			matchedWorkoutPurpose: 'Easy run', matchedWorkoutDate: '2026-07-28', healthConnect: null,
			// These are deliberately not part of the query result or this mobile contract.
		} as never);

		expect(detail).toMatchObject({
			averagePaceSecondsPerKm: 300,
			heartRateSummary: { highSeconds: 180, settingsSource: 'custom' },
			routeSummary: { traceRetained: true, pointCount: 42 },
			matchedWorkoutPurpose: 'Easy run'
		});
		expect(detail).not.toHaveProperty('routeTrace');
		expect(detail).not.toHaveProperty('heartRateSeries');
	});
});
