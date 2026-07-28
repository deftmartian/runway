import { json } from '@sveltejs/kit';
import { eq } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { athleteProfile } from '$lib/server/db/schema';
import {
	authenticateAndroidDevice,
	revokeAndroidDevice,
	touchAndroidDevice
} from '$lib/server/runway/android-devices';
import { isSupportedAndroidClient } from '$lib/server/runway/android-instance';
import {
	androidApiDeviceRateLimitBuckets,
	androidApiPreAuthRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async (event) => {
	if (!isSupportedAndroidClient(event.request.headers.get('x-runway-client'))) {
		return json({ result: 'unsupported-client' }, { status: 400 });
	}
	const preAuthLimit = await consumeSecurityRateLimit(
		androidApiPreAuthRateLimitBuckets(event.getClientAddress(), 'status')
	);
	if (!preAuthLimit.allowed) return rateLimited(preAuthLimit.retryAfterSeconds);

	const device = await authenticateAndroidDevice(event.request.headers.get('authorization'));
	if (!device) return json({ result: 'unauthorized' }, { status: 401 });
	const deviceLimit = await consumeSecurityRateLimit(
		androidApiDeviceRateLimitBuckets(device.id, 'status')
	);
	if (!deviceLimit.allowed) return rateLimited(deviceLimit.retryAfterSeconds);

	if (!(await touchAndroidDevice(device.id))) {
		return json({ result: 'unauthorized' }, { status: 401 });
	}
	const [profile] = await db
		.select({ activityImportGeneration: athleteProfile.activityImportGeneration })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, device.userId))
		.limit(1);
	return json({
		result: 'connected',
		deviceId: device.id,
		label: device.label,
		expiresAt: device.expiresAt.toISOString(),
		expiresAtEpochMs: device.expiresAt.getTime(),
		lastImportedAt: device.lastImportedAt?.toISOString() ?? null,
		activityImportGeneration: profile?.activityImportGeneration ?? 0
	});
};

export const DELETE: RequestHandler = async (event) => {
	if (!isSupportedAndroidClient(event.request.headers.get('x-runway-client'))) {
		return json({ result: 'unsupported-client' }, { status: 400 });
	}
	const preAuthLimit = await consumeSecurityRateLimit(
		androidApiPreAuthRateLimitBuckets(event.getClientAddress(), 'disconnect')
	);
	if (!preAuthLimit.allowed) return rateLimited(preAuthLimit.retryAfterSeconds);

	const device = await authenticateAndroidDevice(event.request.headers.get('authorization'));
	if (!device) return json({ result: 'unauthorized' }, { status: 401 });
	const deviceLimit = await consumeSecurityRateLimit(
		androidApiDeviceRateLimitBuckets(device.id, 'disconnect')
	);
	if (!deviceLimit.allowed) return rateLimited(deviceLimit.retryAfterSeconds);

	await revokeAndroidDevice(device.userId, device.id);
	return json({ result: 'disconnected' });
};

function rateLimited(retryAfterSeconds: number) {
	return json(
		{ result: 'rate-limited' },
		{ status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } }
	);
}
