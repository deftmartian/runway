import { json } from '@sveltejs/kit';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import {
	createAndroidPairingRequest,
	normalizeAndroidDeviceLabel
} from '$lib/server/runway/android-devices';
import { authenticateMobileRequest } from '$lib/server/runway/mobile-api';
import {
	androidPairingCreateRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

const maximumPairingBodyBytes = 2_048;

/**
 * Creates the one-time code that is immediately exchanged at /api/android/pair
 * for the separate, least-privilege rwy1_ import credential. The code is not
 * sent through the idempotent mobile-action ledger: that ledger intentionally
 * retains response bodies, while a pairing code must remain short lived.
 */
export const POST: RequestHandler = async ({ request, getClientAddress }) => {
	const session = await authenticateMobileRequest(request);
	if (!session) return mobileJson({ ok: false, error: 'unauthorized' }, 401);

	const rateLimit = await consumeSecurityRateLimit(
		androidPairingCreateRateLimitBuckets(session.user.id, getClientAddress())
	);
	if (!rateLimit.allowed) {
		return mobileJson(
			{
				ok: false,
				error: 'rate_limited',
				message: 'Too many pairing codes were requested. Wait before trying again.'
			},
			429,
			{ 'Retry-After': String(rateLimit.retryAfterSeconds) }
		);
	}
	if (request.headers.get('content-encoding')) {
		return mobileJson({ ok: false, error: 'unsupported' }, 415);
	}
	if (!request.headers.get('content-type')?.toLowerCase().startsWith('application/json')) {
		return mobileJson({ ok: false, error: 'unsupported' }, 415);
	}
	const body = await readBoundedRequestBody(request, maximumPairingBodyBytes);
	if (body.result !== 'ok') {
		return mobileJson(
			{ ok: false, error: body.result === 'too-large' ? 'too_large' : 'invalid_json' },
			body.result === 'too-large' ? 413 : 400
		);
	}

	let payload: unknown;
	try {
		payload = JSON.parse(body.buffer.toString('utf8'));
	} catch {
		return mobileJson({ ok: false, error: 'invalid_json', message: 'Send valid JSON.' }, 400);
	}
	const label = pairingLabel(payload);
	if (!label) {
		return mobileJson(
			{
				ok: false,
				error: 'validation',
				message: 'Give this Android device a label of 1 to 60 visible characters.'
			},
			400
		);
	}

	const pairing = await createAndroidPairingRequest(session.user.id);
	return mobileJson({
		ok: true,
		code: pairing.code,
		expiresAt: pairing.expiresAt.toISOString(),
		label
	});
};

function pairingLabel(payload: unknown): string | null {
	if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
	const label = (payload as Record<string, unknown>)['label'];
	return typeof label === 'string' ? normalizeAndroidDeviceLabel(label) : null;
}

function mobileJson(
	body: Record<string, unknown>,
	status = 200,
	extraHeaders: Record<string, string> = {}
) {
	return json(body, {
		status,
		headers: {
			'Cache-Control': 'private, no-store',
			Vary: 'Authorization, X-Runway-Client',
			...extraHeaders
		}
	});
}
