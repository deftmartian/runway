import { json } from '@sveltejs/kit';
import { auth } from '$lib/server/auth';
import { authenticateMobileRequest } from '$lib/server/runway/mobile-api';
import type { RequestHandler } from './$types';

export const DELETE: RequestHandler = async ({ request }) => {
	const session = await authenticateMobileRequest(request);
	if (!session) return mobileJson({ ok: false, error: 'unauthorized' }, 401);
	await auth.api.signOut({ headers: request.headers });
	return mobileJson({ ok: true });
};

function mobileJson(body: Record<string, unknown>, status = 200) {
	return json(body, {
		status,
		headers: {
			'Cache-Control': 'private, no-store',
			Vary: 'Authorization, X-Runway-Client'
		}
	});
}
