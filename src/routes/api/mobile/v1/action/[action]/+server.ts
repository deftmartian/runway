import { json } from '@sveltejs/kit';
import { z } from 'zod';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import { isMobileActionName, runMobileAction } from '$lib/server/runway/mobile-mutations';
import {
	claimMobileRequest,
	completeMobileRequest
} from '$lib/server/runway/mobile-request-receipts';
import { authenticateMobileRequest } from '$lib/server/runway/mobile-api';
import {
	consumeSecurityRateLimit,
	mobileMutationRateLimitBuckets,
	nextcloudImportRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

const requestIdSchema = z.uuid();
const maximumActionBytes = 64 * 1024;

export const POST: RequestHandler = async ({ request, params, getClientAddress }) => {
	const session = await authenticateMobileRequest(request);
	if (!session) return mobileJson({ ok: false, error: 'unauthorized' }, 401);
	if (!isMobileActionName(params.action)) {
		return mobileJson({ ok: false, error: 'not_found' }, 404);
	}
	const rateLimit = await consumeSecurityRateLimit([
		...mobileMutationRateLimitBuckets(session.user.id, getClientAddress()),
		...nextcloudRateLimitBuckets(params.action, session.user.id, getClientAddress())
	]);
	if (!rateLimit.allowed) {
		return mobileJson(
			{
				ok: false,
				error: 'rate_limited',
				message: 'Too many changes were requested. Wait before trying again.'
			},
			429,
			{ 'Retry-After': String(rateLimit.retryAfterSeconds) }
		);
	}

	const requestId = request.headers.get('idempotency-key');
	if (requestId === null || !requestIdSchema.safeParse(requestId).success) {
		return mobileJson(
			{
				ok: false,
				error: 'invalid_request_id',
				message: 'Send a UUID Idempotency-Key for every native change.'
			},
			400
		);
	}
	const body = await readBoundedRequestBody(request, maximumActionBytes);
	if (body.result === 'too-large') {
		return mobileJson(
			{ ok: false, error: 'too_large', message: 'Request body is too large.' },
			413
		);
	}
	if (body.result !== 'ok') {
		return mobileJson({ ok: false, error: 'invalid_json', message: 'Send one JSON object.' }, 400);
	}
	let parsedBody: unknown;
	try {
		parsedBody = JSON.parse(body.buffer.toString('utf8'));
	} catch {
		return mobileJson({ ok: false, error: 'invalid_json', message: 'Send valid JSON.' }, 400);
	}
	if (typeof parsedBody !== 'object' || parsedBody === null || Array.isArray(parsedBody)) {
		return mobileJson({ ok: false, error: 'invalid_json', message: 'Send one JSON object.' }, 400);
	}

	const claim = await claimMobileRequest({
		userId: session.user.id,
		requestId,
		action: params.action,
		payload: body.buffer
	});
	if (claim.result === 'conflict') {
		return mobileJson(
			{
				ok: false,
				error: 'idempotency_conflict',
				message: 'That request ID was already used for a different change.'
			},
			409
		);
	}
	if (claim.result === 'processing') {
		return mobileJson(
			{
				ok: false,
				error: 'request_in_progress',
				message: 'The server may still be applying that change. Refresh before trying again.'
			},
			409
		);
	}
	if (claim.result === 'replay' || claim.result === 'recovered') {
		return mobileJson(claim.response.body, claim.response.status, {
			...(claim.result === 'replay' ? { 'Idempotency-Replayed': 'true' } : {}),
			...(claim.result === 'recovered' ? { 'Idempotency-Recovered': 'true' } : {})
		});
	}

	const response = await runMobileActionSafely(params.action, session.user.id, parsedBody);
	await completeMobileRequest({
		userId: session.user.id,
		requestId,
		payloadHash: claim.payloadHash,
		response
	});
	return mobileJson(response.body, response.status);
};

function nextcloudRateLimitBuckets(action: string, userId: string, clientAddress: string) {
	const kind =
		action === 'connect_nextcloud'
			? 'connect'
			: action === 'test_nextcloud'
				? 'test'
				: action === 'sync_nextcloud'
					? 'sync'
					: null;
	return kind ? nextcloudImportRateLimitBuckets(kind, userId, clientAddress) : [];
}

async function runMobileActionSafely(
	action: Parameters<typeof runMobileAction>[0],
	userId: string,
	body: unknown
) {
	try {
		return await runMobileAction(action, userId, body);
	} catch {
		// A thrown action is intentionally made terminal. The throw might have
		// happened after a database write, so retrying it would be less safe than
		// asking the client to refresh and inspect the current training state.
		return {
			status: 409,
			body: {
				ok: false,
				error: 'request_outcome_unknown',
				message: 'The change could not be confirmed. Refresh before trying again.'
			}
		};
	}
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
