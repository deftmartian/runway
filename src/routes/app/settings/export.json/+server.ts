import { error, redirect, type RequestHandler } from '@sveltejs/kit';
import { isFreshAuthSession } from '$lib/server/runway/auth-config';
import { prepareUserDataExport } from '$lib/server/runway/repositories/training-data-export';
import {
	accountSecurityRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';

export const POST: RequestHandler = async (event) => {
	if (!event.locals.user || !event.locals.session) throw redirect(302, '/login');
	if (!isFreshAuthSession(event.locals.session.createdAt)) {
		throw error(403, 'Sign out and sign in again before exporting your data.');
	}
	const rateLimit = await consumeSecurityRateLimit(
		accountSecurityRateLimitBuckets('export-data', event.locals.user.id, event.getClientAddress())
	);
	if (!rateLimit.allowed) {
		event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
		throw error(429, 'Too many export requests. Try again later.');
	}
	const artifact = await prepareUserDataExport(event.locals.user.id);
	try {
		return new Response(artifact.openBody(), {
			headers: {
				'Cache-Control': 'private, no-store',
				'Content-Disposition': 'attachment; filename="runway-training-data.json"',
				'Content-Length': String(artifact.byteLength),
				'Content-Type': 'application/json; charset=utf-8'
			}
		});
	} catch (cause) {
		await artifact.cleanup();
		throw cause;
	}
};
