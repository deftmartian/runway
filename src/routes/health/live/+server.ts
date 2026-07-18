import type { RequestHandler } from './$types';
import { buildIdentity } from '$lib/server/runway/build-identity';

export const GET: RequestHandler = () => healthResponse(200, { status: 'ok', ...buildIdentity });

function healthResponse(status: number, body: Record<string, unknown>): Response {
	return Response.json(body, {
		status,
		headers: { 'Cache-Control': 'private, no-store' }
	});
}
