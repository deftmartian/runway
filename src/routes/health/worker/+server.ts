import { version } from '$app/environment';
import type { RequestHandler } from './$types';
import { getImportWorkerStatus } from '$lib/server/runway/import-worker';

export const GET: RequestHandler = () => {
	const worker = getImportWorkerStatus();
	const hasSuccessfulPass =
		worker.lastCompletedAt !== null &&
		(worker.lastFailureAt === null || worker.lastCompletedAt >= worker.lastFailureAt);
	const ready = worker.started && hasSuccessfulPass;

	return Response.json(
		{
			status: ready ? 'ready' : 'not-ready',
			version,
			worker
		},
		{
			status: ready ? 200 : 503,
			headers: { 'Cache-Control': 'private, no-store' }
		}
	);
};
