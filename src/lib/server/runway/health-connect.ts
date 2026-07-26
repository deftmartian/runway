import { createHmac } from 'node:crypto';
import { env } from '$env/dynamic/private';
import { and, desc, eq, gt, inArray, isNull } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	androidDevice,
	athleteProfile,
	healthConnectConnection,
	healthConnectExternalActivity,
	healthConnectRequestReceipt,
	healthConnectTombstone
} from '$lib/server/db/schema';
import { toIsoDateInTimeZone } from '$lib/training/date';
import { summarizeHeartRateSeriesEffort } from '$lib/training/heart-rate';
import type { HeartRateSettings } from '$lib/training/types';
import type { AuthenticatedAndroidDevice } from './android-devices';
import { deleteActivityRecordInTransaction } from './repositories/activity-mutations';
import { lockActivityOwner } from './repositories/mutation-locks';

export type HealthConnectChange =
	| {
			op: 'upsert';
			recordId: string;
			originKey: string;
			originLabel: string;
			startedAt: Date;
			durationSeconds: number;
			distanceMeters: number;
			averageHeartRate?: number | undefined;
			maxHeartRate?: number | undefined;
			averageCadence?: number | undefined;
			elevationGainMeters?: number | undefined;
			averageSpeedMetersPerSecond?: number | undefined;
			heartRateSeries?:
				| {
						version: 1;
						sourceSampleCount: number;
						points: { elapsedSeconds: number; bpm: number }[];
				  }
				| undefined;
			routeTrace?:
				| {
						version: 1;
						sourcePointCount: number;
						points: {
							latitudeE6: number;
							longitudeE6: number;
							elapsedSeconds: number;
							segmentIndex: number;
							speedMetersPerSecond: number | null;
						}[];
				  }
				| undefined;
	  }
	| { op: 'delete'; recordId: string };
export type HealthConnectSyncResult =
	| 'imported'
	| 'duplicate'
	| 'updated-review'
	| 'accepted-correction-pending'
	| 'deleted-review'
	| 'accepted-source-delete-pending';

type HealthConnectProfile = {
	timeZone: string;
	routeDataMode: 'discard' | 'private';
	heartRateSettings: HeartRateSettings | null;
};

/** Deliberately contains no provider record identifiers or permission details. */
export async function getHealthConnectConnectionStatus(userId: string) {
	const [connection] = await db
		.select({
			deviceLabel: androidDevice.label,
			lastSyncedAt: healthConnectConnection.lastSyncedAt
		})
		.from(healthConnectConnection)
		.innerJoin(
			androidDevice,
			and(
				eq(androidDevice.id, healthConnectConnection.deviceId),
				eq(androidDevice.userId, healthConnectConnection.userId)
			)
		)
		.where(
			and(
				eq(healthConnectConnection.userId, userId),
				isNull(androidDevice.revokedAt),
				gt(androidDevice.expiresAt, new Date())
			)
		)
		.orderBy(desc(healthConnectConnection.updatedAt))
		.limit(1);
	if (!connection) {
		return {
			state: 'not_connected' as const,
			deviceLabel: null,
			lastSyncedAt: null,
			message: null
		};
	}
	return {
		state: connection.lastSyncedAt ? ('connected' as const) : ('needs_attention' as const),
		deviceLabel: connection.deviceLabel,
		lastSyncedAt: connection.lastSyncedAt,
		message: connection.lastSyncedAt ? null : 'Waiting for the paired Android app to send records.'
	};
}

export function blindHealthConnectId(
	userId: string,
	value: string,
	purpose: 'record' | 'fingerprint' | 'request'
) {
	return createHmac('sha256', healthConnectSecret())
		.update(`runway-health-connect-${purpose}-v1`)
		.update('\0')
		.update(userId)
		.update('\0')
		.update(value)
		.digest('hex');
}

export async function syncHealthConnectChanges(
	device: AuthenticatedAndroidDevice,
	requestId: string,
	payloadKey: string,
	expectedGeneration: number,
	changes: HealthConnectChange[]
): Promise<{ replayed: boolean; result: HealthConnectSyncResult[] }> {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, device.userId);
		const [activeDevice] = await tx
			.select({
				id: androidDevice.id,
				expiresAt: androidDevice.expiresAt,
				revokedAt: androidDevice.revokedAt
			})
			.from(androidDevice)
			.where(and(eq(androidDevice.id, device.id), eq(androidDevice.userId, device.userId)))
			.limit(1)
			.for('update');
		requireActiveHealthConnectDevice(activeDevice);
		const [profile] = await tx
			.select({
				timeZone: athleteProfile.timeZone,
				generation: athleteProfile.activityImportGeneration,
				routeDataMode: athleteProfile.routeDataMode,
				heartRateSettings: athleteProfile.heartRateSettings
			})
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, device.userId))
			.limit(1)
			.for('update');
		if (!profile?.timeZone) throw new Error('time-zone-required');
		requireHealthConnectGeneration(profile.generation, expectedGeneration);
		const [receipt] = await tx
			.select()
			.from(healthConnectRequestReceipt)
			.where(
				and(
					eq(healthConnectRequestReceipt.deviceId, device.id),
					eq(healthConnectRequestReceipt.requestId, requestId)
				)
			)
			.limit(1)
			.for('update');
		if (receipt) {
			requireHealthConnectReplayPayload(receipt.payloadKey, payloadKey);
			return { replayed: true, result: receipt.result.split(',') as HealthConnectSyncResult[] };
		}
		const [connection] = await tx
			.insert(healthConnectConnection)
			.values({ userId: device.userId, deviceId: device.id })
			.onConflictDoUpdate({
				target: healthConnectConnection.deviceId,
				set: { updatedAt: new Date() }
			})
			.returning();
		if (!connection) throw new Error('connection-failed');
		const result: HealthConnectSyncResult[] = [];
		for (const change of changes) {
			const externalKey = blindHealthConnectId(device.userId, change.recordId, 'record');
			const [mapping] = await tx
				.select()
				.from(healthConnectExternalActivity)
				.where(
					and(
						eq(healthConnectExternalActivity.connectionId, connection.id),
						eq(healthConnectExternalActivity.externalKey, externalKey)
					)
				)
				.limit(1)
				.for('update');
			if (change.op === 'delete') {
				if (mapping?.deletedAt) {
					result.push('duplicate');
					continue;
				}
				if (!mapping?.activityId) {
					result.push('duplicate');
					continue;
				}
				await tx
					.insert(healthConnectTombstone)
					.values({ userId: device.userId, externalKey })
					.onConflictDoNothing();
				const [linked] = await tx
					.select({ reviewState: activity.reviewState })
					.from(activity)
					.where(and(eq(activity.id, mapping.activityId), eq(activity.userId, device.userId)))
					.limit(1)
					.for('update');
				if (linked?.reviewState === 'accepted') {
					await tx
						.update(healthConnectExternalActivity)
						.set({
							pendingAction: 'source_delete',
							pendingActivity: null,
							deletedAt: new Date(),
							updatedAt: new Date()
						})
						.where(eq(healthConnectExternalActivity.id, mapping.id));
					result.push('accepted-source-delete-pending');
				} else {
					await deleteActivityRecordInTransaction(tx, device.userId, mapping.activityId);
					result.push('deleted-review');
				}
				continue;
			}
			const [tombstone] = await tx
				.select({ id: healthConnectTombstone.id })
				.from(healthConnectTombstone)
				.where(
					and(
						eq(healthConnectTombstone.userId, device.userId),
						eq(healthConnectTombstone.externalKey, externalKey)
					)
				)
				.limit(1)
				.for('update');
			const fingerprint = blindHealthConnectId(
				device.userId,
				JSON.stringify([
					change.startedAt.toISOString(),
					change.durationSeconds,
					change.distanceMeters,
					change.averageHeartRate ?? null,
					change.maxHeartRate ?? null,
					change.averageCadence ?? null,
					change.elevationGainMeters ?? null,
					change.averageSpeedMetersPerSecond ?? null,
					change.heartRateSeries ?? null,
					change.routeTrace ?? null
				]),
				'fingerprint'
			);
			if (isHealthConnectUpsertDuplicate(mapping, tombstone, fingerprint)) {
				result.push('duplicate');
				continue;
			}
			const values = buildHealthConnectActivityValues(profile as HealthConnectProfile, change);
			const occurredAt = values.occurredAt;
			const [existing] = mapping?.activityId
				? await tx
						.select({ id: activity.id, reviewState: activity.reviewState })
						.from(activity)
						.where(and(eq(activity.id, mapping.activityId), eq(activity.userId, device.userId)))
						.limit(1)
						.for('update')
				: [];
			if (existing?.reviewState === 'accepted') {
				if (!mapping) throw new Error('Health Connect mapping disappeared during import.');
				await tx
					.update(healthConnectExternalActivity)
					.set({
						fingerprint,
						pendingAction: 'correction',
						pendingActivity: { ...values, occurredAt: occurredAt.toISOString() },
						deletedAt: null,
						updatedAt: new Date()
					})
					.where(eq(healthConnectExternalActivity.id, mapping.id));
				result.push('accepted-correction-pending');
				continue;
			}
			let activityId = existing?.id;
			const [duplicateCandidate] = activityId
				? []
				: await tx
						.select({ id: activity.id })
						.from(activity)
						.where(
							and(
								eq(activity.userId, device.userId),
								eq(activity.occurredAt, occurredAt),
								eq(activity.distanceMeters, change.distanceMeters)
							)
						)
						.limit(1);
			if (activityId) {
				await tx.update(activity).set(values).where(eq(activity.id, activityId));
				result.push('updated-review');
			} else {
				const [created] = await tx
					.insert(activity)
					.values({
						userId: device.userId,
						source: 'health_connect',
						reviewState: 'review',
						feltHard: false,
						...values
					})
					.returning({ id: activity.id });
				activityId = created?.id;
				if (!activityId) throw new Error('activity-create-failed');
				result.push('imported');
			}
			const originKey = blindHealthConnectId(device.userId, change.originKey, 'record');
			if (mapping)
				await tx
					.update(healthConnectExternalActivity)
					.set({
						originKey,
						originLabel: change.originLabel,
						fingerprint,
						activityId,
						pendingAction: 'none',
						pendingActivity: null,
						duplicateCandidateActivityId: duplicateCandidate?.id ?? null,
						deletedAt: null,
						updatedAt: new Date()
					})
					.where(eq(healthConnectExternalActivity.id, mapping.id));
			else
				await tx.insert(healthConnectExternalActivity).values({
					userId: device.userId,
					connectionId: connection.id,
					externalKey,
					originKey,
					originLabel: change.originLabel,
					fingerprint,
					activityId,
					duplicateCandidateActivityId: duplicateCandidate?.id ?? null
				});
		}
		await tx
			.update(healthConnectConnection)
			.set({ lastSyncedAt: new Date(), updatedAt: new Date() })
			.where(eq(healthConnectConnection.id, connection.id));
		await tx.insert(healthConnectRequestReceipt).values({
			userId: device.userId,
			deviceId: device.id,
			requestId,
			payloadKey,
			result: result.join(',')
		});
		return { replayed: false, result };
	});
}

export function requireHealthConnectGeneration(actual: number, expected: number): void {
	if (actual !== expected) throw new Error('generation-changed');
}

export function requireActiveHealthConnectDevice(
	device:
		| {
				expiresAt: Date;
				revokedAt: Date | null;
		  }
		| undefined,
	now = new Date()
): void {
	if (!device || device.revokedAt || device.expiresAt.getTime() <= now.getTime()) {
		throw new Error('device-revoked');
	}
}

export function requireHealthConnectReplayPayload(
	storedPayloadKey: string,
	payloadKey: string
): void {
	if (storedPayloadKey !== payloadKey) throw new Error('request-conflict');
}

export function isHealthConnectUpsertDuplicate(
	mapping: { fingerprint: string; deletedAt: Date | null } | undefined,
	tombstone: { id: string } | undefined,
	fingerprint: string
): boolean {
	return Boolean(tombstone || (mapping?.fingerprint === fingerprint && !mapping.deletedAt));
}

export function buildHealthConnectActivityValues(
	profile: HealthConnectProfile,
	change: Extract<HealthConnectChange, { op: 'upsert' }>
) {
	const occurredAt = change.startedAt;
	const routeTrace = profile.routeDataMode === 'private' ? (change.routeTrace ?? null) : null;
	const heartRateSeries = change.heartRateSeries ?? null;
	return {
		occurredAt,
		activityDate: toIsoDateInTimeZone(occurredAt, profile.timeZone),
		distanceMeters: change.distanceMeters,
		durationSeconds: change.durationSeconds,
		averagePaceSecondsPerKm:
			change.averageSpeedMetersPerSecond && change.averageSpeedMetersPerSecond > 0
				? 1000 / change.averageSpeedMetersPerSecond
				: change.durationSeconds / (change.distanceMeters / 1000),
		averageHeartRate: change.averageHeartRate ?? null,
		maxHeartRate: change.maxHeartRate ?? null,
		heartRateSummary: summarizeHeartRateSeriesEffort(
			heartRateSeries,
			change.durationSeconds,
			profile.heartRateSettings
		),
		averageCadence: change.averageCadence ?? null,
		heartRateSeries,
		routeTrace,
		routeSummary: {
			pointCount: change.routeTrace?.points.length ?? 0,
			startEndRedacted: routeTrace === null,
			hasElevation: Boolean(change.elevationGainMeters),
			traceRetained: routeTrace !== null
		}
	};
}

function healthConnectSecret() {
	return (
		env['ANDROID_CREDENTIAL_SECRET'] ||
		env['BETTER_AUTH_SECRET'] ||
		(env['NODE_ENV'] === 'production'
			? (() => {
					throw new Error('ANDROID_CREDENTIAL_SECRET or BETTER_AUTH_SECRET is required.');
				})()
			: 'runway-dev-android-credential-secret')
	);
}

/** Resolve a pending source deletion through the normal activity erasure path,
 * which reverses linked plan ledger entries before deleting the activity. */
export async function resolveHealthConnectRecord(
	userId: string,
	mappingId: string,
	decision: 'accept_correction' | 'keep_current' | 'delete_from_runway' | 'retain_in_runway'
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const [mapping] = await tx
			.select()
			.from(healthConnectExternalActivity)
			.where(
				and(
					eq(healthConnectExternalActivity.id, mappingId),
					eq(healthConnectExternalActivity.userId, userId)
				)
			)
			.limit(1)
			.for('update');
		if (!mapping?.activityId) throw new Error('Health Connect record is not available.');
		if (decision === 'delete_from_runway') {
			if (mapping.pendingAction !== 'source_delete')
				throw new Error('Source deletion is not pending.');
			await deleteActivityRecordInTransaction(tx, userId, mapping.activityId);
			return;
		}
		if (decision === 'retain_in_runway') {
			if (mapping.pendingAction !== 'source_delete')
				throw new Error('Source deletion is not pending.');
			await tx
				.update(healthConnectExternalActivity)
				.set({ pendingAction: 'none', pendingActivity: null, updatedAt: new Date() })
				.where(
					and(
						eq(healthConnectExternalActivity.id, mappingId),
						eq(healthConnectExternalActivity.userId, userId)
					)
				);
			return;
		}
		if (decision === 'keep_current') {
			if (mapping.pendingAction !== 'correction' || !mapping.pendingActivity)
				throw new Error('Correction is not pending.');
			await tx
				.update(healthConnectExternalActivity)
				.set({ pendingAction: 'none', pendingActivity: null, updatedAt: new Date() })
				.where(
					and(
						eq(healthConnectExternalActivity.id, mappingId),
						eq(healthConnectExternalActivity.userId, userId)
					)
				);
			return;
		}
		if (mapping.pendingAction !== 'correction' || !mapping.pendingActivity)
			throw new Error('Correction is not pending.');
		const [profile] = await tx
			.select({ routeDataMode: athleteProfile.routeDataMode })
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1);
		const [target] = await tx
			.select({
				workoutId: activity.workoutId,
				extraPlanImpactConfirmed: activity.extraPlanImpactConfirmed
			})
			.from(activity)
			.where(and(eq(activity.id, mapping.activityId), eq(activity.userId, userId)))
			.limit(1)
			.for('update');
		if (!target) throw new Error('Health Connect record is not available.');
		if (target.workoutId)
			throw new Error('Unlink this accepted activity before applying a correction.');
		if (target.extraPlanImpactConfirmed)
			throw new Error('Stop counting this activity as extra before applying a correction.');
		const pending = applyHealthConnectRoutePrivacy(
			mapping.pendingActivity,
			profile?.routeDataMode ?? 'discard'
		);
		await tx
			.update(activity)
			.set({
				occurredAt: new Date(pending.occurredAt),
				activityDate: pending.activityDate,
				distanceMeters: pending.distanceMeters,
				durationSeconds: pending.durationSeconds,
				averagePaceSecondsPerKm: pending.averagePaceSecondsPerKm,
				averageHeartRate: pending.averageHeartRate,
				maxHeartRate: pending.maxHeartRate,
				heartRateSummary: pending.heartRateSummary,
				averageCadence: pending.averageCadence,
				heartRateSeries: pending.heartRateSeries,
				routeTrace: pending.routeTrace,
				routeSummary: pending.routeSummary
			})
			.where(and(eq(activity.id, mapping.activityId), eq(activity.userId, userId)));
		await tx
			.update(healthConnectExternalActivity)
			.set({ pendingAction: 'none', pendingActivity: null, updatedAt: new Date() })
			.where(
				and(
					eq(healthConnectExternalActivity.id, mappingId),
					eq(healthConnectExternalActivity.userId, userId)
				)
			);
	});
}

export function applyHealthConnectRoutePrivacy<
	T extends {
		routeTrace: unknown;
		routeSummary: {
			startEndRedacted: boolean;
			traceRetained: boolean;
		};
	}
>(pending: T, routeDataMode: 'discard' | 'private'): T {
	if (routeDataMode === 'private' || pending.routeTrace === null) return pending;
	return {
		...pending,
		routeTrace: null,
		routeSummary: {
			...pending.routeSummary,
			startEndRedacted: true,
			traceRetained: false
		}
	};
}

export async function resolveHealthConnectDuplicate(
	userId: string,
	mappingId: string,
	decision: 'keep_health_connect' | 'use_existing'
) {
	return db.transaction(async (tx) => {
		await lockActivityOwner(tx, userId);
		const [mapping] = await tx
			.select()
			.from(healthConnectExternalActivity)
			.where(
				and(
					eq(healthConnectExternalActivity.id, mappingId),
					eq(healthConnectExternalActivity.userId, userId)
				)
			)
			.limit(1)
			.for('update');
		if (!mapping?.activityId || !mapping.duplicateCandidateActivityId)
			throw new Error('Duplicate candidate is not available.');
		const lockedActivities = await tx
			.select({ id: activity.id })
			.from(activity)
			.where(
				and(
					eq(activity.userId, userId),
					inArray(activity.id, [mapping.activityId, mapping.duplicateCandidateActivityId])
				)
			)
			.for('update');
		if (lockedActivities.length !== 2) throw new Error('Duplicate candidate is not available.');
		if (decision === 'use_existing') {
			await deleteActivityRecordInTransaction(tx, userId, mapping.activityId);
		}
		await tx
			.update(healthConnectExternalActivity)
			.set({
				activityId:
					decision === 'use_existing' ? mapping.duplicateCandidateActivityId : mapping.activityId,
				duplicateCandidateActivityId: null,
				pendingAction: 'none',
				pendingActivity: null,
				deletedAt: decision === 'use_existing' ? new Date() : null,
				updatedAt: new Date()
			})
			.where(
				and(
					eq(healthConnectExternalActivity.id, mappingId),
					eq(healthConnectExternalActivity.userId, userId)
				)
			);
	});
}
