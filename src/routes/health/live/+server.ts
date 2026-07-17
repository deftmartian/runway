import { version } from '$app/environment';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = () => healthResponse(200, { status: 'ok', version });

function healthResponse(status: number, body: Record<string, unknown>): Response {
	return Response.json(body, {
		status,
		headers: { 'Cache-Control': 'private, no-store' }
	});
}
