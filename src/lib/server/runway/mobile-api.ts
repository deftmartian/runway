import { env } from '$env/dynamic/private';
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
	hasPlanHistory,
	listPlanHistory
} from './repositories/plan-queries';
import { getPhaseCompletionReview } from './repositories/plan-lifecycle';
import { getAthleteProfile } from './repositories/profiles';
import { getTrainingReadContext } from './repositories/training-read-context';
import { getGoalSetupView } from './plan-setup';
import { zoneFloors } from '$lib/training/heart-rate';
import { isAndroidMobileSession } from './mobile-session-scope';

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
		case 'stats':
			return getMobileStats(userId);
		case 'history':
			return getMobileHistory(userId, url);
		case 'settings':
			return getMobileSettings(userId, url);
		case 'onboarding':
			return getMobileOnboarding(userId);
		default:
			return null;
	}
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
		activities: activityPage.items,
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
	return {
		onboardingRequired: false,
		active,
		detail: weeks ? { weeks } : null,
		history,
		planTrace,
		planHistory,
		phaseReview
	};
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
		accountSecurityUrl: `${url.origin}/app/settings`,
		localAuthEnabled: env['LOCAL_AUTH_ENABLED'] !== 'false'
	};
}

async function getMobileOnboarding(userId: string) {
	return getGoalSetupView(userId);
}

function boundedOffset(value: string | null): number {
	const parsed = Number(value ?? 0);
	return Number.isSafeInteger(parsed) && parsed >= 0 ? Math.min(parsed, 10_000) : 0;
}
