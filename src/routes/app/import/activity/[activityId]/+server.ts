import { json } from '@sveltejs/kit';
import { getActivityTraceDetail } from '$lib/server/runway/repositories/activity-queries';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = async ({ locals, params }) => {
	if (!locals.user) return new Response(null, { status: 401 });
	const detail = await getActivityTraceDetail(locals.user.id, params.activityId);
	if (!detail) return new Response(null, { status: 404 });
	return json(detail, { headers: { 'cache-control': 'private, no-store' } });
};
