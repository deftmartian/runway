import type { RequestHandler } from './$types';
import { databaseIsReady } from '$lib/server/db/readiness';
import { buildIdentity } from '$lib/server/runway/build-identity';

export const GET: RequestHandler = async () => {
	try {
		if (await databaseIsReady()) return healthResponse(200, { status: 'ready', ...buildIdentity });
	} catch {
		// The response intentionally omits database and migration details.
	}

	return healthResponse(503, { status: 'not-ready', ...buildIdentity });
};

function healthResponse(status: number, body: Record<string, unknown>): Response {
	return Response.json(body, {
		status,
		headers: { 'Cache-Control': 'private, no-store' }
	});
}
