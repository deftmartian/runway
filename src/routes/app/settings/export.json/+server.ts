import { error, json, redirect, type RequestHandler } from '@sveltejs/kit';
import { isFreshAuthSession } from '$lib/server/runway/auth-config';
import { exportUserData } from '$lib/server/runway/repository';
import {
	accountSecurityRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';

export const GET: RequestHandler = async (event) => {
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
	return json(await exportUserData(event.locals.user.id), {
		headers: {
			'Cache-Control': 'private, no-store',
			'Content-Disposition': 'attachment; filename="runway-export.json"'
		}
	});
};
