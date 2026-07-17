import { version } from '$app/environment';
import type { RequestHandler } from './$types';
import { databaseIsReady } from '$lib/server/db/readiness';

export const GET: RequestHandler = async () => {
	try {
		if (await databaseIsReady()) return healthResponse(200, { status: 'ready', version });
	} catch {
		// The response intentionally omits database and migration details.
	}

	return healthResponse(503, { status: 'not-ready', version });
};

function healthResponse(status: number, body: Record<string, unknown>): Response {
	return Response.json(body, {
		status,
		headers: { 'Cache-Control': 'private, no-store' }
	});
}
