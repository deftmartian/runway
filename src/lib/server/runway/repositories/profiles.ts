import { and, eq, isNotNull, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	athleteProfile,
	auditEvent,
	healthConnectExternalActivity
} from '$lib/server/db/schema';
import { addDays, isValidTimeZone } from '$lib/training/date';
import { buildHeartRateSettings } from '$lib/training/heart-rate';
import type { SexForEstimates } from '$lib/training/types';
import { lockActivityOwner } from './mutation-locks';
import type { RunwayTransaction } from './transaction';

export async function getAthleteProfile(userId: string) {
	const [profile] = await db
		.select()
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile ?? null;
}

export async function getActivityImportGeneration(userId: string): Promise<number> {
	const [profile] = await db
		.select({ generation: athleteProfile.activityImportGeneration })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile?.generation ?? 0;
}

export async function updateAthleteTimeZone(userId: string, timeZone: string) {
	if (!isValidTimeZone(timeZone)) throw new Error('Choose a valid IANA time zone.');
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		await tx
			.insert(athleteProfile)
			.values({ userId, timeZone })
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: { timeZone, updatedAt: new Date() }
			});
		return { timeZone };
	});
}

export async function updateRouteDataMode(userId: string, routeDataMode: 'discard' | 'private') {
	return db.transaction(async (tx) => {
		// Route erasure must serialize with Health Connect correction acceptance. Without
		// the same account lock, a correction that already read a private pending trace
		// could write it back after this transaction discarded every route.
		await lockActivityOwner(tx, userId);
		await tx
			.insert(athleteProfile)
			.values({ userId, routeDataMode })
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: { routeDataMode, updatedAt: new Date() }
			});

		const cleared =
			routeDataMode === 'discard'
				? await tx
						.update(activity)
						.set({
							routeTrace: null,
							routeSummary: sql`jsonb_set(jsonb_set(${activity.routeSummary}, '{traceRetained}', 'false'::jsonb, true), '{startEndRedacted}', 'true'::jsonb, true)`
						})
						.where(and(eq(activity.userId, userId), isNotNull(activity.routeTrace)))
						.returning({ id: activity.id })
				: [];
		const clearedPending =
			routeDataMode === 'discard'
				? await tx
						.update(healthConnectExternalActivity)
						.set({
							pendingActivity: sql`
								jsonb_set(
									jsonb_set(
										${healthConnectExternalActivity.pendingActivity},
										'{routeTrace}',
										'null'::jsonb,
										true
									),
									'{routeSummary}',
									coalesce(
										${healthConnectExternalActivity.pendingActivity} -> 'routeSummary',
										'{}'::jsonb
									) || '{"startEndRedacted":true,"traceRetained":false}'::jsonb,
									true
								)
							`
						})
						.where(
							and(
								eq(healthConnectExternalActivity.userId, userId),
								isNotNull(healthConnectExternalActivity.pendingActivity),
								sql`${healthConnectExternalActivity.pendingActivity} -> 'routeTrace' is not null`,
								sql`${healthConnectExternalActivity.pendingActivity} -> 'routeTrace' <> 'null'::jsonb`
							)
						)
						.returning({ id: healthConnectExternalActivity.id })
				: [];

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'profile.route_privacy_updated',
			detail: {
				routeDataMode,
				clearedRouteCount: cleared.length,
				clearedPendingRouteCount: clearedPending.length
			}
		});

		return {
			routeDataMode,
			clearedRouteCount: cleared.length,
			clearedPendingRouteCount: clearedPending.length
		};
	});
}

export async function updateTrainingProfile(
	userId: string,
	input: {
		sexForEstimates: SexForEstimates;
		ageYears?: number | undefined;
		heartRateSettingsSource: 'estimated' | 'custom';
		maxHeartRateBpm: number;
		zone2FloorBpm: number;
		zone3FloorBpm: number;
		zone4FloorBpm: number;
		zone5FloorBpm: number;
	}
) {
	const heartRateSettings = buildHeartRateSettings({
		maxHeartRateBpm: input.maxHeartRateBpm,
		source: input.heartRateSettingsSource,
		zone2FloorBpm: input.zone2FloorBpm,
		zone3FloorBpm: input.zone3FloorBpm,
		zone4FloorBpm: input.zone4FloorBpm,
		zone5FloorBpm: input.zone5FloorBpm
	});

	await db.transaction(async (tx) => {
		await tx
			.insert(athleteProfile)
			.values({
				userId,
				units: 'metric',
				sexForEstimates: input.sexForEstimates,
				...(input.ageYears === undefined ? {} : { ageYears: input.ageYears }),
				heartRateSettings
			})
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: {
					ageYears: input.ageYears ?? null,
					sexForEstimates: input.sexForEstimates,
					heartRateSettings,
					updatedAt: new Date()
				}
			});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'profile.heart_rate_updated',
			detail: {
				hasEstimateInputs: input.ageYears !== undefined,
				settingsSource: input.heartRateSettingsSource
			}
		});
	});

	return heartRateSettings;
}

export async function updateHealthContext(
	userId: string,
	injuryFlags: {
		recentInjury: boolean;
		currentPain: boolean;
		recurringPain: boolean;
		medicalRestriction: boolean;
		notes: string;
	}
) {
	await db.transaction(async (tx) => {
		await tx
			.insert(athleteProfile)
			.values({ userId, injuryFlags })
			.onConflictDoUpdate({
				target: athleteProfile.userId,
				set: { injuryFlags, updatedAt: new Date() }
			});

		await tx.insert(auditEvent).values({
			userId,
			eventType: 'profile.health_context_updated',
			detail: {
				activeFlagCount: [
					injuryFlags.recentInjury,
					injuryFlags.currentPain,
					injuryFlags.recurringPain,
					injuryFlags.medicalRestriction
				].filter(Boolean).length,
				hasNotes: injuryFlags.notes.length > 0
			}
		});
	});

	return injuryFlags;
}

/**
 * A recent pain report is held as current health context until the runner
 * clears it explicitly. Older activity remains historical evidence without
 * asserting that pain is present now.
 */
export function isCurrentPainReportDate(evidenceDate: string, today: string): boolean {
	return evidenceDate >= addDays(today, -7) && evidenceDate <= today;
}

export async function markCurrentPainInTransaction(tx: RunwayTransaction, userId: string) {
	const [profile] = await tx
		.select({ injuryFlags: athleteProfile.injuryFlags })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1)
		.for('update');
	if (!profile || profile.injuryFlags.currentPain) return;

	await tx
		.update(athleteProfile)
		.set({ injuryFlags: { ...profile.injuryFlags, currentPain: true }, updatedAt: new Date() })
		.where(eq(athleteProfile.userId, userId));
	await tx.insert(auditEvent).values({
		userId,
		eventType: 'profile.current_pain_reported',
		detail: { source: 'run_feedback' }
	});
}

export async function getAthleteTimeZone(userId: string): Promise<string | null> {
	const [profile] = await db
		.select({ timeZone: athleteProfile.timeZone })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile?.timeZone ?? null;
}

export async function requireAthleteTimeZone(
	userId: string,
	message = 'Set training time zone first.'
) {
	const timeZone = await getAthleteTimeZone(userId);
	if (!timeZone) throw new Error(message);
	return timeZone;
}

export async function requireAthleteTimeZoneInTransaction(
	tx: RunwayTransaction,
	userId: string,
	message = 'Set training time zone first.'
) {
	const [profile] = await tx
		.select({ timeZone: athleteProfile.timeZone })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	if (!profile?.timeZone) throw new Error(message);
	return profile.timeZone;
}
