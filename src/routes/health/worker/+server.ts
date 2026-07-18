import type { RequestHandler } from './$types';
import { buildIdentity } from '$lib/server/runway/build-identity';
import { getImportWorkerStatus } from '$lib/server/runway/import-worker';
import { assessWorkerHealth } from '$lib/server/runway/worker-health';

export const GET: RequestHandler = () => {
	const worker = getImportWorkerStatus();
	const health = assessWorkerHealth(worker);

	return Response.json(
		{
			status: health.ready ? 'ready' : 'not-ready',
			...buildIdentity,
			worker,
			health
		},
		{
			status: health.ready ? 200 : 503,
			headers: { 'Cache-Control': 'private, no-store' }
		}
	);
};
