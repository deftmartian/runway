import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { and, desc, eq, gt, isNull, lt, sql } from 'drizzle-orm';
import { env } from '$env/dynamic/private';
import { db } from '$lib/server/db';
import {
	androidDevice,
	androidImportRequest,
	androidPairingRequest,
	auditEvent,
	user as authUser
} from '$lib/server/db/schema';

const pairingLifetimeMs = 10 * 60 * 1_000;
const deviceLifetimeMs = 365 * 24 * 60 * 60 * 1_000;
const staleImportClaimMs = 5 * 60 * 1_000;
const androidTokenPattern =
	/^rwy1_([0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})_([A-Za-z0-9_-]{43})$/i;
const pairingCodePattern = /^[0-9A-F]{16}$/;

export type AndroidDeviceSummary = {
	id: string;
	label: string;
	expiresAt: Date;
	lastSeenAt: Date | null;
	lastImportedAt: Date | null;
	createdAt: Date;
};

export type AuthenticatedAndroidDevice = AndroidDeviceSummary & { userId: string };

export type AndroidImportResult = 'imported' | 'duplicate' | 'quarantined';

type ImportClaim =
	| { state: 'claimed'; receiptId: string }
	| { state: 'processing' }
	| { state: 'conflict' }
	| { state: 'revoked' }
	| { state: 'completed'; result: AndroidImportResult; reason: string | null };

export async function createAndroidPairingRequest(userId: string): Promise<{
	code: string;
	expiresAt: Date;
}> {
	const code = randomBytes(8).toString('hex').toUpperCase();
	const expiresAt = new Date(Date.now() + pairingLifetimeMs);
	await db.transaction(async (tx) => {
		await tx
			.select({ id: authUser.id })
			.from(authUser)
			.where(eq(authUser.id, userId))
			.limit(1)
			.for('update');
		await tx.delete(androidPairingRequest).where(eq(androidPairingRequest.userId, userId));
		await tx.insert(androidPairingRequest).values({
			userId,
			codeHash: hashPairingCode(code),
			expiresAt
		});
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'android.pairing.created',
			detail: { expiresAt: expiresAt.toISOString() }
		});
	});
	return { code: formatPairingCode(code), expiresAt };
}

export async function exchangeAndroidPairingCode(
	codeInput: string,
	labelInput: string
): Promise<
	| { result: 'paired'; token: string; deviceId: string; expiresAt: Date }
	| { result: 'device-limit' }
	| { result: 'invalid' }
> {
	const code = normalizePairingCode(codeInput);
	const label = normalizeAndroidDeviceLabel(labelInput);
	if (!code || !label) return { result: 'invalid' };

	const deviceId = randomUUID();
	const secret = randomBytes(32).toString('base64url');
	const token = `rwy1_${deviceId}_${secret}`;
	const tokenHash = hashAndroidToken(token);
	const expiresAt = new Date(Date.now() + deviceLifetimeMs);
	const now = new Date();

	return db.transaction(async (tx) => {
		const [candidate] = await tx
			.select({
				id: androidPairingRequest.id,
				userId: androidPairingRequest.userId,
				expiresAt: androidPairingRequest.expiresAt,
				consumedAt: androidPairingRequest.consumedAt
			})
			.from(androidPairingRequest)
			.where(eq(androidPairingRequest.codeHash, hashPairingCode(code)))
			.limit(1);
		if (!candidate || candidate.consumedAt || candidate.expiresAt <= now) {
			return { result: 'invalid' };
		}
		await tx
			.select({ id: authUser.id })
			.from(authUser)
			.where(eq(authUser.id, candidate.userId))
			.limit(1)
			.for('update');
		const [pairing] = await tx
			.select({
				id: androidPairingRequest.id,
				userId: androidPairingRequest.userId,
				expiresAt: androidPairingRequest.expiresAt,
				consumedAt: androidPairingRequest.consumedAt
			})
			.from(androidPairingRequest)
			.where(eq(androidPairingRequest.id, candidate.id))
			.limit(1)
			.for('update');
		if (!pairing || pairing.consumedAt || pairing.expiresAt <= now) return { result: 'invalid' };
		const [activeDevices] = await tx
			.select({ count: sql<number>`count(*)::int` })
			.from(androidDevice)
			.where(
				and(
					eq(androidDevice.userId, pairing.userId),
					isNull(androidDevice.revokedAt),
					gt(androidDevice.expiresAt, now)
				)
			);
		if ((activeDevices?.count ?? 0) >= maximumActiveDevices) return { result: 'device-limit' };

		await tx
			.update(androidPairingRequest)
			.set({ consumedAt: now })
			.where(eq(androidPairingRequest.id, pairing.id));
		await tx.insert(androidDevice).values({
			id: deviceId,
			userId: pairing.userId,
			label,
			tokenHash,
			expiresAt,
			lastSeenAt: now
		});
		await tx.insert(auditEvent).values({
			userId: pairing.userId,
			eventType: 'android.device.paired',
			detail: { deviceId, expiresAt: expiresAt.toISOString() }
		});
		return { result: 'paired', token, deviceId, expiresAt };
	});
}

export async function authenticateAndroidDevice(
	authorization: string | null
): Promise<AuthenticatedAndroidDevice | null> {
	const parsed = parseAndroidBearerToken(authorization);
	if (!parsed) return null;
	const [record] = await db
		.select({
			id: androidDevice.id,
			userId: androidDevice.userId,
			label: androidDevice.label,
			tokenHash: androidDevice.tokenHash,
			expiresAt: androidDevice.expiresAt,
			lastSeenAt: androidDevice.lastSeenAt,
			lastImportedAt: androidDevice.lastImportedAt,
			createdAt: androidDevice.createdAt,
			revokedAt: androidDevice.revokedAt
		})
		.from(androidDevice)
		.where(eq(androidDevice.id, parsed.deviceId))
		.limit(1);
	if (!record || record.revokedAt || record.expiresAt <= new Date()) return null;
	if (!constantTimeHexEqual(record.tokenHash, hashAndroidToken(parsed.token))) return null;

	return {
		id: record.id,
		userId: record.userId,
		label: record.label,
		expiresAt: record.expiresAt,
		lastSeenAt: record.lastSeenAt,
		lastImportedAt: record.lastImportedAt,
		createdAt: record.createdAt
	};
}

export async function touchAndroidDevice(deviceId: string): Promise<boolean> {
	const touched = await db
		.update(androidDevice)
		.set({ lastSeenAt: new Date(), updatedAt: new Date() })
		.where(
			and(
				eq(androidDevice.id, deviceId),
				isNull(androidDevice.revokedAt),
				gt(androidDevice.expiresAt, new Date())
			)
		)
		.returning({ id: androidDevice.id });
	return touched.length > 0;
}

export async function listAndroidDevices(userId: string): Promise<AndroidDeviceSummary[]> {
	return db
		.select({
			id: androidDevice.id,
			label: androidDevice.label,
			expiresAt: androidDevice.expiresAt,
			lastSeenAt: androidDevice.lastSeenAt,
			lastImportedAt: androidDevice.lastImportedAt,
			createdAt: androidDevice.createdAt
		})
		.from(androidDevice)
		.where(and(eq(androidDevice.userId, userId), isNull(androidDevice.revokedAt)))
		.orderBy(desc(androidDevice.createdAt))
		.limit(20);
}

export async function revokeAndroidDevice(userId: string, deviceId: string): Promise<boolean> {
	return db.transaction(async (tx) => {
		const revoked = await tx
			.update(androidDevice)
			.set({ revokedAt: new Date(), updatedAt: new Date() })
			.where(
				and(
					eq(androidDevice.id, deviceId),
					eq(androidDevice.userId, userId),
					isNull(androidDevice.revokedAt)
				)
			)
			.returning({ id: androidDevice.id });
		if (revoked.length === 0) return false;
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'android.device.revoked',
			detail: { deviceId }
		});
		return true;
	});
}

export async function claimAndroidImportRequest(
	device: AuthenticatedAndroidDevice,
	requestId: string,
	contentDigest: string
): Promise<ImportClaim> {
	const now = new Date();
	const staleBefore = new Date(now.getTime() - staleImportClaimMs);
	const contentKey = hashAndroidContentDigest(device.userId, contentDigest);
	return db.transaction(async (tx) => {
		const [owner] = await tx
			.select({ id: authUser.id })
			.from(authUser)
			.where(eq(authUser.id, device.userId))
			.limit(1)
			.for('update');
		if (!owner) return { state: 'revoked' };
		const [activeDevice] = await tx
			.select({ id: androidDevice.id })
			.from(androidDevice)
			.where(
				and(
					eq(androidDevice.id, device.id),
					eq(androidDevice.userId, device.userId),
					isNull(androidDevice.revokedAt),
					gt(androidDevice.expiresAt, now)
				)
			)
			.limit(1)
			.for('update');
		if (!activeDevice) return { state: 'revoked' };

		const [created] = await tx
			.insert(androidImportRequest)
			.values({
				userId: device.userId,
				deviceId: device.id,
				requestId,
				contentKey,
				updatedAt: now
			})
			.onConflictDoNothing()
			.returning({ id: androidImportRequest.id });
		if (created) return { state: 'claimed', receiptId: created.id };

		const [existing] = await tx
			.select({
				id: androidImportRequest.id,
				contentKey: androidImportRequest.contentKey,
				state: androidImportRequest.state,
				result: androidImportRequest.result,
				reason: androidImportRequest.reason,
				updatedAt: androidImportRequest.updatedAt
			})
			.from(androidImportRequest)
			.where(
				and(
					eq(androidImportRequest.deviceId, device.id),
					eq(androidImportRequest.requestId, requestId)
				)
			)
			.limit(1);
		if (existing?.contentKey !== contentKey) return { state: 'conflict' };
		if (existing.state === 'completed' && isAndroidImportResult(existing.result)) {
			return { state: 'completed', result: existing.result, reason: existing.reason };
		}
		if (existing.updatedAt > staleBefore) return { state: 'processing' };

		const [reclaimed] = await tx
			.update(androidImportRequest)
			.set({ updatedAt: now })
			.where(
				and(
					eq(androidImportRequest.id, existing.id),
					eq(androidImportRequest.state, 'processing'),
					lt(androidImportRequest.updatedAt, staleBefore)
				)
			)
			.returning({ id: androidImportRequest.id });
		return reclaimed ? { state: 'claimed', receiptId: reclaimed.id } : { state: 'processing' };
	});
}

export async function completeAndroidImportRequest(
	device: AuthenticatedAndroidDevice,
	receiptId: string,
	result: AndroidImportResult,
	reason: string | null = null
): Promise<boolean> {
	const now = new Date();
	return db.transaction(async (tx) => {
		const completed = await tx
			.update(androidImportRequest)
			.set({ state: 'completed', result, reason, completedAt: now, updatedAt: now })
			.where(
				and(
					eq(androidImportRequest.id, receiptId),
					eq(androidImportRequest.deviceId, device.id),
					eq(androidImportRequest.userId, device.userId),
					eq(androidImportRequest.state, 'processing')
				)
			)
			.returning({ id: androidImportRequest.id });
		if (completed.length === 0) return false;
		await tx
			.update(androidDevice)
			.set({
				lastSeenAt: now,
				lastImportedAt: result === 'imported' ? now : device.lastImportedAt,
				updatedAt: now
			})
			.where(eq(androidDevice.id, device.id));
		await tx.insert(auditEvent).values({
			userId: device.userId,
			eventType: 'android.import.completed',
			detail: { deviceId: device.id, receiptId, result, reason }
		});
		return true;
	});
}

export async function abandonAndroidImportRequest(
	device: AuthenticatedAndroidDevice,
	receiptId: string
): Promise<void> {
	await db
		.delete(androidImportRequest)
		.where(
			and(
				eq(androidImportRequest.id, receiptId),
				eq(androidImportRequest.deviceId, device.id),
				eq(androidImportRequest.userId, device.userId),
				eq(androidImportRequest.state, 'processing')
			)
		);
}

export function normalizePairingCode(value: string): string | null {
	const normalized = value.trim().replace(/[\s-]/g, '').toUpperCase();
	return pairingCodePattern.test(normalized) ? normalized : null;
}

export function normalizeAndroidDeviceLabel(value: string): string | null {
	const trimmed = value.trim();
	for (let index = 0; index < trimmed.length; index += 1) {
		const codeUnit = trimmed.charCodeAt(index);
		if (codeUnit < 32 || codeUnit === 127) return null;
	}
	const normalized = trimmed.replace(/\s+/g, ' ');
	if (normalized.length < 1 || normalized.length > 60) {
		return null;
	}
	return normalized;
}

export function parseAndroidBearerToken(
	authorization: string | null
): { deviceId: string; token: string } | null {
	if (!authorization?.startsWith('Bearer ')) return null;
	const token = authorization.slice('Bearer '.length);
	const match = androidTokenPattern.exec(token);
	return match?.[1] ? { deviceId: match[1].toLowerCase(), token } : null;
}

function formatPairingCode(code: string): string {
	return [code.slice(0, 4), code.slice(4, 8), code.slice(8, 12), code.slice(12, 16)].join('-');
}

function hashPairingCode(code: string): string {
	return createHmac('sha256', androidCredentialSecret())
		.update('runway-android-pairing-v1')
		.update('\0')
		.update(code)
		.digest('hex');
}

function hashAndroidContentDigest(userId: string, digest: string): string {
	return createHmac('sha256', androidCredentialSecret())
		.update('runway-android-content-v1')
		.update('\0')
		.update(userId)
		.update('\0')
		.update(digest)
		.digest('hex');
}

function hashAndroidToken(token: string): string {
	return createHash('sha256').update(token).digest('hex');
}

function constantTimeHexEqual(left: string, right: string): boolean {
	if (!/^[0-9a-f]{64}$/.test(left) || !/^[0-9a-f]{64}$/.test(right)) return false;
	return timingSafeEqual(Buffer.from(left, 'hex'), Buffer.from(right, 'hex'));
}

function isAndroidImportResult(value: string | null): value is AndroidImportResult {
	return value === 'imported' || value === 'duplicate' || value === 'quarantined';
}

function androidCredentialSecret(): string {
	const secret =
		env['ANDROID_CREDENTIAL_SECRET'] ||
		env['BETTER_AUTH_SECRET'] ||
		(env['NODE_ENV'] === 'production' ? undefined : 'runway-dev-android-credential-secret');
	if (!secret) throw new Error('ANDROID_CREDENTIAL_SECRET or BETTER_AUTH_SECRET is required.');
	return secret;
}

const maximumActiveDevices = 20;
