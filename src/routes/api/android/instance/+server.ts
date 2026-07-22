import { json } from '@sveltejs/kit';
import { buildAndroidInstanceDescriptor } from '$lib/server/runway/android-instance';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = ({ request }) => {
	if (request.headers.get('x-runway-client') !== 'runway-android/1') {
		return json({ result: 'unsupported-client' }, { status: 400 });
	}
	return json(buildAndroidInstanceDescriptor(), {
		headers: {
			'Cache-Control': 'private, no-store',
			Vary: 'X-Runway-Client'
		}
	});
};
