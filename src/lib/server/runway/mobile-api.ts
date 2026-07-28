import { auth } from '$lib/server/auth';
import { buildIdentity } from './build-identity';
import { getSettingsHealthConnectConnectionStatus } from './health-connect';
import { listAndroidDevices } from './android-devices';
import { listImportSources } from './import-sources';
import { getActivityRecords, getImportWorkoutCandidates } from './repositories/activity-queries';
import { getTrainingCalendar } from './repositories/calendar';
import { getHistory } from './repositories/history';
import {
	getPlanTrace,
	getPlanWeeks,
	getPlanDetail,
	hasPlanHistory,
	listPlanHistory
} from './repositories/plan-queries';
import { getPhaseCompletionReview } from './repositories/plan-lifecycle';
import { getAthleteProfile } from './repositories/profiles';
import { getTrainingReadContext } from './repositories/training-read-context';
import { getGoalSetupView } from './plan-setup';
import { zoneFloors } from '$lib/training/heart-rate';
import { isAndroidMobileSession } from './mobile-session-scope';
import { mobileActivityDetail } from './mobile-activity-detail';
import { getActivityTraceDetail } from './repositories/activity-queries';
import { getMobileAccountSecurity } from './mobile-account-security';
import { mobileStatsPayload } from './mobile-stats';

export const mobileClientHeader = 'runway-android/2';
export const mobileSchemaVersion = 1;

export async function authenticateMobileRequest(request: Request) {
	if (request.headers.get('x-runway-client') !== mobileClientHeader) return null;
	const authorization = request.headers.get('authorization');
	if (!authorization?.startsWith('Bearer ') || authorization.startsWith('Bearer rwy1_')) {
		return null;
	}
	const session = await auth.api.getSession({ headers: request.headers });
	return isAndroidMobileSession(session?.session ?? null) ? session : null;
}

export async function getMobileView(
	view: string,
	userId: string,
	request: Request,
	url: URL
): Promise<Record<string, unknown> | null> {
	switch (view) {
		case 'bootstrap':
			return getMobileBootstrap(userId, request, url);
		case 'calendar':
			return getMobileCalendar(userId, url);
		case 'review':
			return getMobileReview(userId, url);
		case 'activity-trace':
			return getMobileActivityTrace(userId, url);
		case 'stats':
			return getMobileStats(userId);
		case 'history':
			return getMobileHistory(userId, url);
		case 'history-detail':
			return getMobileHistoryDetail(userId, url);
		case 'settings':
			return getMobileSettings(userId, url);
		case 'account-security':
			return getMobileAccountSecurity(userId, request);
		case 'onboarding':
			return getMobileOnboarding(userId);
		default:
			return null;
	}
}

async function getMobileActivityTrace(userId: string, url: URL) {
	const activityId = boundedActivityId(url.searchParams.get('activityId'));
	if (!activityId) return null;
	const detail = await getActivityTraceDetail(userId, activityId);
	if (!detail) return null;
	return {
		activityId,
		...mobileActivityEvidenceDetail(detail)
	};
}

/**
 * The only mobile response that carries route coordinates or HR samples. Its caller has already
 * validated a UUID and `getActivityTraceDetail` scopes the row to the authenticated user.
 */
export function mobileActivityEvidenceDetail(detail: Awaited<ReturnType<typeof getActivityTraceDetail>>) {
	if (!detail) return null;
	const routeTrace = detail.routeTrace
		? {
				version: detail.routeTrace.version,
				sourcePointCount: detail.routeTrace.sourcePointCount,
				points: detail.routeTrace.points.slice(0, 600).map((point) => ({
					latitudeE6: point.latitudeE6,
					longitudeE6: point.longitudeE6,
					elapsedSeconds: point.elapsedSeconds,
					segmentIndex: point.segmentIndex,
					speedMetersPerSecond: point.speedMetersPerSecond
				}))
			}
		: null;
	const heartRateSeries = detail.heartRateSeries
		? {
				version: detail.heartRateSeries.version,
				sourceSampleCount: detail.heartRateSeries.sourceSampleCount,
				points: detail.heartRateSeries.points.slice(0, 1_000).map((point) => ({
					elapsedSeconds: point.elapsedSeconds,
					bpm: point.bpm
				}))
			}
		: null;
	return {
		routeTrace,
		heartRateSeries,
		averageCadence: detail.averageCadence,
		disclosure: {
			routeTraceRetained: detail.routeSummary.traceRetained === true,
			routePointCount: detail.routeSummary.pointCount,
			startEndRedacted: detail.routeSummary.startEndRedacted,
			hasElevation: detail.routeSummary.hasElevation,
			heartRateSeriesRetained: heartRateSeries !== null,
			heartRateSampleCount: heartRateSeries?.sourceSampleCount ?? 0
		}
	};
}

async function getMobileBootstrap(userId: string, request: Request, url: URL) {
	const [profile, setupComplete] = await Promise.all([
		getAthleteProfile(userId),
		hasPlanHistory(userId)
	]);
	const session = await auth.api.getSession({ headers: request.headers });
	return {
		user: session
			? { id: session.user.id, name: session.user.name, email: session.user.email }
			: null,
		setupComplete,
		timeZone: profile?.timeZone ?? null,
		release: buildIdentity.release,
		commit: buildIdentity.commit,
		serverOrigin: url.origin,
		androidApi: 2,
		features: {
			nativeUi: true,
			deviceAuthorization: true,
			healthConnect: true,
			backgroundFolderImport: true
		}
	};
}

async function getMobileCalendar(userId: string, url: URL) {
	const context = await getTrainingReadContext(userId);
	if (!context.timeZone) return { onboardingRequired: true };
	const [calendar, activityCandidates] = await Promise.all([
		getTrainingCalendar(userId, { month: url.searchParams.get('month'), context }),
		getImportWorkoutCandidates(userId, context)
	]);
	return { onboardingRequired: false, ...calendar, activityCandidates };
}

async function getMobileReview(userId: string, url: URL) {
	const offset = boundedOffset(url.searchParams.get('offset'));
	const [candidates, activityPage, sources, profile, androidDevices] = await Promise.all([
		getImportWorkoutCandidates(userId),
		getActivityRecords(userId, { limit: 50, offset }),
		listImportSources(userId),
		getAthleteProfile(userId),
		listAndroidDevices(userId)
	]);
	return {
		candidates,
		activities: activityPage.items.map(mobileActivityDetail),
		activityPage: {
			total: activityPage.total,
			nextOffset: activityPage.nextOffset,
			offset
		},
		sources,
		androidDevices,
		routeDataMode: profile?.routeDataMode ?? 'private'
	};
}

async function getMobileStats(userId: string) {
	const context = await getTrainingReadContext(userId);
	if (!context.timeZone) return { onboardingRequired: true };
	const active = context.activePlan;
	const [weeks, history, planTrace, planHistory, phaseReview] = await Promise.all([
		active ? getPlanWeeks(userId, active.plan.id) : Promise.resolve(null),
		getHistory(userId, context),
		active ? getPlanTrace(userId, active.plan.id) : Promise.resolve([]),
		listPlanHistory(userId, { limit: 20, offset: 0, context }),
		getPhaseCompletionReview(userId)
	]);
	return mobileStatsPayload({ active, weeks, history, planTrace, planHistory, phaseReview });
}

async function getMobileHistory(userId: string, url: URL) {
	const context = await getTrainingReadContext(userId);
	if (!context.timeZone) return { onboardingRequired: true };
	const offset = boundedOffset(url.searchParams.get('offset'));
	const [history, current, phaseReview] = await Promise.all([
		listPlanHistory(userId, { limit: 20, offset, context }),
		offset === 0
			? Promise.resolve(null)
			: listPlanHistory(userId, { limit: 1, offset: 0, context }),
		offset === 0 ? getPhaseCompletionReview(userId) : Promise.resolve(null)
	]);
	const firstItem = (current ?? history).items[0] ?? null;
	return {
		onboardingRequired: false,
		history,
		activeItem: firstItem?.plan.status === 'active' ? firstItem : null,
		phaseReview,
		offset,
		pageSize: 20
	};
}

async function getMobileHistoryDetail(userId: string, url: URL) {
	const planId = boundedPlanId(url.searchParams.get('planId'));
	if (!planId) return null;
	const detail = await getPlanDetail(userId, planId);
	if (!detail) return null;

	const latestFeedbackByWorkout = new Map<string, (typeof detail.feedback)[number]>();
	for (const feedback of detail.feedback) {
		if (!latestFeedbackByWorkout.has(feedback.workoutId)) {
			latestFeedbackByWorkout.set(feedback.workoutId, feedback);
		}
	}
	const activityByWorkout = new Map<string, (typeof detail.activities)[number]>();
	for (const activity of detail.activities) {
		if (activity.workoutId && !activityByWorkout.has(activity.workoutId)) {
			activityByWorkout.set(activity.workoutId, activity);
		}
	}

	return {
		onboardingRequired: false,
		detail: {
			plan: {
				id: detail.plan.id,
				status: detail.plan.status,
				phase: detail.plan.phase,
				startDate: detail.plan.startDate,
				targetDate: detail.plan.targetDate,
				weeks: detail.plan.weeks,
				risk: detail.plan.risk,
				completedAt: detail.plan.completedAt,
				archivedAt: detail.plan.archivedAt,
				lifecycleReason: detail.plan.lifecycleReason,
				summary: {
					kind: detail.plan.summary.kind,
					requiredWeeklyIncreasePercent:
						detail.plan.summary.kind === 'distance'
							? detail.plan.summary.requiredWeeklyIncreasePercent
							: null,
					defaultWeeklyIncreasePercent:
						detail.plan.summary.kind === 'distance'
							? detail.plan.summary.defaultWeeklyIncreasePercent
							: null
				}
			},
			goal: {
				title: detail.goal.title,
				distance: detail.goal.distance,
				priority: detail.goal.priority
			},
			cutoffDate: detail.cutoffDate,
			timeline: detail.adjustments.map((adjustment) => ({
				id: adjustment.id,
				triggerType: adjustment.triggerType,
				createdAt: adjustment.createdAt,
				reversedAt: adjustment.reversedAt,
				reversalReason: adjustment.reversalReason,
				reason: adjustment.reason,
				newState: adjustment.newState
			})),
			weeks: detail.weeks.map((week) => ({
				id: week.id,
				weekNumber: week.weekNumber,
				startDate: week.startDate,
				targetDistanceMeters: week.targetDistanceMeters,
				targetDurationSeconds: week.targetDurationSeconds,
				risk: week.risk,
				isDownWeek: week.isDownWeek,
				isTaper: week.isTaper,
				workouts: detail.workouts
					.filter((workout) => workout.weekId === week.id)
					.map((workout) => {
						const feedback = latestFeedbackByWorkout.get(workout.id);
						const activity = activityByWorkout.get(workout.id);
						const result = activity ?? feedback;
						return {
							id: workout.id,
							scheduledDate: workout.scheduledDate,
							type: workout.type,
							status: workout.status,
							prescriptionKind: workout.prescriptionKind,
							targetDistanceMeters: workout.targetDistanceMeters,
							targetDurationSeconds: workout.targetDurationSeconds,
							purpose: workout.purpose,
							isRemoved: workout.isRemoved,
							result: result
								? {
										source: activity?.source ?? 'feedback',
										completedDistanceMeters:
											activity?.distanceMeters ?? feedback?.completedDistanceMeters,
										completedDurationSeconds:
											activity?.durationSeconds ?? feedback?.completedDurationSeconds,
										feltHard: activity?.feltHard ?? feedback?.feltHard,
										pain: activity?.pain ?? feedback?.pain,
										consequence: activity?.consequence ?? feedback?.consequence
									}
								: null
						};
					})
			}))
		}
	};
}

async function getMobileSettings(userId: string, url: URL) {
	const [profile, healthConnect, androidDevices, sources] = await Promise.all([
		getAthleteProfile(userId),
		getSettingsHealthConnectConnectionStatus(userId),
		listAndroidDevices(userId),
		listImportSources(userId)
	]);
	const heartRate = zoneFloors(profile?.heartRateSettings);
	return {
		profile: profile
			? {
					timeZone: profile.timeZone,
					routeDataMode: profile.routeDataMode,
					sexForEstimates: profile.sexForEstimates,
					ageYears: profile.ageYears,
					heartRateSettingsSource: profile.heartRateSettings?.source ?? 'not_configured',
					maxHeartRateBpm: heartRate?.maxHeartRateBpm ?? null,
					zone2FloorBpm: heartRate?.zone2FloorBpm ?? null,
					zone3FloorBpm: heartRate?.zone3FloorBpm ?? null,
					zone4FloorBpm: heartRate?.zone4FloorBpm ?? null,
					zone5FloorBpm: heartRate?.zone5FloorBpm ?? null,
					injuryFlags: profile.injuryFlags
				}
			: null,
		healthConnect,
		androidDevices,
		sources,
		about: {
			release: buildIdentity.release,
			commit: buildIdentity.commit,
			serverOrigin: url.origin
		},
		// Account security has a native summary view. Credential administration remains a
		// separate fresh-auth browser boundary and is intentionally not linked from Android.
		accountSecurityAvailable: true
	};
}

async function getMobileOnboarding(userId: string) {
	return getGoalSetupView(userId);
}

function boundedOffset(value: string | null): number {
	const parsed = Number(value ?? 0);
	return Number.isSafeInteger(parsed) && parsed >= 0 ? Math.min(parsed, 10_000) : 0;
}

export function boundedPlanId(value: string | null): string | null {
	if (!value || value.length !== 36) return null;
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
		? value
		: null;
}

export const boundedActivityId = boundedPlanId;
