import type { getActivityRecords } from './repositories/activity-queries';

type ActivityRecord = Awaited<ReturnType<typeof getActivityRecords>>['items'][number];

/**
 * Coordinate-free activity detail for the native client.  Route traces and heart-rate sample
 * series stay behind the authenticated web detail endpoint; native receives only the bounded
 * summaries needed to disclose what was retained.
 */
export function mobileActivityDetail(record: ActivityRecord) {
	return {
		id: record.id,
		workoutId: record.workoutId,
		source: record.source,
		reviewState: record.reviewState,
		occurredDate: record.occurredAt,
		activityDate: record.activityDate,
		distanceMeters: record.distanceMeters,
		durationSeconds: record.durationSeconds,
		averagePaceSecondsPerKm: record.averagePaceSecondsPerKm,
		averageHeartRate: record.averageHeartRate,
		maxHeartRate: record.maxHeartRate,
		heartRateSummary: record.heartRateSummary
			? {
					highSeconds: record.heartRateSummary.highSeconds,
					highShare: record.heartRateSummary.highShare,
					secondsByZone: record.heartRateSummary.secondsByZone,
					settingsSource: record.heartRateSummary.settingsSource
				}
			: null,
		feltHard: record.feltHard,
		pain: record.pain,
		extraPlanImpactConfirmed: record.extraPlanImpactConfirmed,
		consequence: record.consequence,
		routeSummary: {
			pointCount: record.routeSummary.pointCount,
			startEndRedacted: record.routeSummary.startEndRedacted,
			hasElevation: record.routeSummary.hasElevation,
			traceRetained: record.routeSummary.traceRetained === true
		},
		matchedWorkoutPurpose: record.matchedWorkoutPurpose,
		matchedWorkoutDate: record.matchedWorkoutDate,
		healthConnect: record.healthConnect
	};
}
