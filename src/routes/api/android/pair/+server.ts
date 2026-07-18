import { json } from '@sveltejs/kit';
import { exchangeAndroidPairingCode } from '$lib/server/runway/android-devices';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import {
	androidPairingExchangeRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

const maximumPairingBodyBytes = 2_048;

export const POST: RequestHandler = async (event) => {
	if (event.request.headers.get('x-runway-client') !== 'runway-android/1') {
		return json({ result: 'unsupported-client' }, { status: 400 });
	}
	const rateLimit = await consumeSecurityRateLimit(
		androidPairingExchangeRateLimitBuckets(event.getClientAddress())
	);
	if (!rateLimit.allowed) {
		return json(
			{ result: 'rate-limited' },
			{ status: 429, headers: { 'Retry-After': String(rateLimit.retryAfterSeconds) } }
		);
	}
	if (event.request.headers.get('content-encoding')) {
		return json({ result: 'unsupported' }, { status: 415 });
	}
	if (!event.request.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
		return json({ result: 'unsupported' }, { status: 415 });
	}
	const body = await readBoundedRequestBody(event.request, maximumPairingBodyBytes);
	if (body.result !== 'ok') {
		return json({ result: body.result }, { status: body.result === 'too-large' ? 413 : 400 });
	}

	let payload: unknown;
	try {
		payload = JSON.parse(body.buffer.toString('utf8'));
	} catch {
		return json({ result: 'invalid' }, { status: 400 });
	}
	if (!isPairingPayload(payload)) return json({ result: 'invalid' }, { status: 400 });

	const result = await exchangeAndroidPairingCode(payload.code, payload.label);
	if (result.result === 'device-limit') {
		return json({ result: 'device-limit' }, { status: 409 });
	}
	if (result.result !== 'paired') {
		return json({ result: 'invalid-or-expired' }, { status: 400 });
	}
	return json(
		{
			result: 'paired',
			deviceId: result.deviceId,
			token: result.token,
			expiresAt: result.expiresAt.toISOString(),
			expiresAtEpochMs: result.expiresAt.getTime()
		},
		{ status: 201 }
	);
};

function isPairingPayload(value: unknown): value is { code: string; label: string } {
	if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
	const record = value as Record<string, unknown>;
	return typeof record['code'] === 'string' && typeof record['label'] === 'string';
}
