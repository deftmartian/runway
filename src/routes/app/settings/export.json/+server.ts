import { json, redirect, type RequestHandler } from '@sveltejs/kit';
import { exportUserData } from '$lib/server/runway/repository';

export const GET: RequestHandler = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	return json(await exportUserData(event.locals.user.id), {
		headers: {
			'Cache-Control': 'private, no-store',
			'Content-Disposition': 'attachment; filename="runway-export.json"'
		}
	});
};
