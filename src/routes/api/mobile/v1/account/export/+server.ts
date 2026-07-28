import { json } from '@sveltejs/kit';
import { isFreshAuthSession } from '$lib/server/runway/auth-config';
import { authenticateMobileRequest } from '$lib/server/runway/mobile-api';
import { prepareUserDataExport } from '$lib/server/runway/repositories/training-data-export';
import {
	accountSecurityRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async (event) => {
	const mobileSession = await authenticateMobileRequest(event.request);
	if (!mobileSession) return mobileJson({ ok: false, error: 'unauthorized' }, 401);
	if (!isFreshAuthSession(mobileSession.session.createdAt)) {
		return mobileJson(
			{
				ok: false,
				error: 'fresh_session_required',
				message: 'Sign out and sign in again before exporting your data.'
			},
			403
		);
	}
	const rateLimit = await consumeSecurityRateLimit(
		accountSecurityRateLimitBuckets('export-data', mobileSession.user.id, event.getClientAddress())
	);
	if (!rateLimit.allowed) {
		return mobileJson(
			{
				ok: false,
				error: 'rate_limited',
				message: 'Too many export requests. Wait before trying again.'
			},
			429,
			{ 'Retry-After': String(rateLimit.retryAfterSeconds) }
		);
	}

	const artifact = await prepareUserDataExport(mobileSession.user.id);
	try {
		return new Response(artifact.openBody(), {
			headers: {
				'Cache-Control': 'private, no-store',
				'Content-Disposition': 'attachment; filename="runway-training-data.json"',
				'Content-Length': String(artifact.byteLength),
				'Content-Type': 'application/json; charset=utf-8',
				Vary: 'Authorization, X-Runway-Client'
			}
		});
	} catch (cause) {
		await artifact.cleanup();
		throw cause;
	}
};

function mobileJson(
	body: Record<string, unknown>,
	status: number,
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
