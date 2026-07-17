import { error, redirect, type RequestHandler } from '@sveltejs/kit';
import { auth } from '$lib/server/auth';

export const POST: RequestHandler = async (event) => {
	const origin = event.request.headers.get('origin');
	const fetchSite = event.request.headers.get('sec-fetch-site');
	if (
		(origin && origin !== event.url.origin) ||
		(!origin && fetchSite !== 'same-origin' && fetchSite !== 'same-site')
	) {
		throw error(403, 'Cross-site logout blocked.');
	}
	await auth.api.signOut({ headers: event.request.headers });
	throw redirect(302, '/');
};
