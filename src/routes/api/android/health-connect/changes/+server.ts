import { createHash } from 'node:crypto';
import { json } from '@sveltejs/kit';
import { authenticateAndroidDevice } from '$lib/server/runway/android-devices';
import { isSupportedAndroidClient } from '$lib/server/runway/android-instance';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import { blindHealthConnectId, syncHealthConnectChanges } from '$lib/server/runway/health-connect';
import {
	androidApiDeviceRateLimitBuckets,
	androidApiPreAuthRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import { healthConnectChangesSchema } from '$lib/server/runway/validation';
import type { RequestHandler } from './$types';

const maximumJsonBytes = 256 * 1024;
export const POST: RequestHandler = async (event) => {
	if (!isSupportedAndroidClient(event.request.headers.get('x-runway-client')))
		return json({ result: 'unsupported-client' }, { status: 400 });
	const preAuth = await consumeSecurityRateLimit(
		androidApiPreAuthRateLimitBuckets(event.getClientAddress(), 'import')
	);
	if (!preAuth.allowed) return limited(preAuth.retryAfterSeconds);
	const device = await authenticateAndroidDevice(event.request.headers.get('authorization'));
	if (!device) return json({ result: 'unauthorized' }, { status: 401 });
	const deviceLimit = await consumeSecurityRateLimit(
		androidApiDeviceRateLimitBuckets(device.id, 'import')
	);
	if (!deviceLimit.allowed) return limited(deviceLimit.retryAfterSeconds);
	if (
		event.request.headers.get('content-encoding') ||
		!event.request.headers.get('content-type')?.toLowerCase().startsWith('application/json')
	)
		return json({ result: 'unsupported' }, { status: 415 });
	const body = await readBoundedRequestBody(event.request, maximumJsonBytes);
	if (body.result !== 'ok') return json({ result: 'invalid' }, { status: 400 });
	let raw: unknown;
	try {
		raw = JSON.parse(body.buffer.toString('utf8'));
	} catch {
		return json({ result: 'invalid' }, { status: 400 });
	}
	const parsed = healthConnectChangesSchema.safeParse(raw);
	if (!parsed.success) return json({ result: 'invalid' }, { status: 400 });
	const requestId = event.request.headers.get('x-runway-request-id')?.trim() ?? '';
	const expectedGeneration = Number(event.request.headers.get('x-runway-activity-generation'));
	if (!zUuid(requestId) || !Number.isSafeInteger(expectedGeneration) || expectedGeneration < 0)
		return json({ result: 'invalid-headers' }, { status: 400 });
	const payloadKey = blindHealthConnectId(
		device.userId,
		createHash('sha256').update(body.buffer).digest('hex'),
		'request'
	);
	try {
		const outcome = await syncHealthConnectChanges(
			device,
			requestId,
			payloadKey,
			expectedGeneration,
			parsed.data.changes.map((change) =>
				change.op === 'delete' ? change : { ...change, startedAt: new Date(change.startedAt) }
			)
		);
		return json(
			{ result: 'processed', requestId, replayed: outcome.replayed, changes: outcome.result },
			{ status: outcome.replayed ? 200 : 201, headers: { 'Cache-Control': 'no-store' } }
		);
	} catch (error) {
		const message = error instanceof Error ? error.message : '';
		if (message === 'request-conflict')
			return json({ result: 'request-conflict', requestId }, { status: 409 });
		if (message === 'device-revoked')
			return json({ result: 'unauthorized', requestId }, { status: 401 });
		if (message === 'time-zone-required' || message === 'generation-changed')
			return json({ result: message, requestId }, { status: 409 });
		return json(
			{ result: 'retryable', requestId },
			{ status: 503, headers: { 'Retry-After': '60' } }
		);
	}
};
function limited(retryAfterSeconds: number) {
	return json(
		{ result: 'rate-limited' },
		{ status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } }
	);
}
function zUuid(value: string) {
	return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
}
