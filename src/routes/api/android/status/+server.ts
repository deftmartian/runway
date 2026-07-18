import { json } from '@sveltejs/kit';
import { authenticateAndroidDevice, touchAndroidDevice } from '$lib/server/runway/android-devices';
import {
	androidApiDeviceRateLimitBuckets,
	androidApiPreAuthRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async (event) => {
	if (event.request.headers.get('x-runway-client') !== 'runway-android/1') {
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
	return json({
		result: 'connected',
		deviceId: device.id,
		label: device.label,
		expiresAt: device.expiresAt.toISOString(),
		expiresAtEpochMs: device.expiresAt.getTime(),
		lastImportedAt: device.lastImportedAt?.toISOString() ?? null
	});
};

function rateLimited(retryAfterSeconds: number) {
	return json(
		{ result: 'rate-limited' },
		{ status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } }
	);
}
