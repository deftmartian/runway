import { json } from '@sveltejs/kit';
import {
	authenticateMobileRequest,
	getMobileView,
	mobileSchemaVersion
} from '$lib/server/runway/mobile-api';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ request, params, url }) => {
	const session = await authenticateMobileRequest(request);
	if (!session) return mobileJson({ error: 'unauthorized' }, 401);
	const view = await getMobileView(params.view, session.user.id, request, url);
	if (!view) return mobileJson({ error: 'not-found' }, 404);
	return mobileJson({ schemaVersion: mobileSchemaVersion, view: params.view, ...view });
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
