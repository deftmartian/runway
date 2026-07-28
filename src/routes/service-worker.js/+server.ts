import type { RequestHandler } from './$types';
import { createServiceWorkerRetirementSource } from '$lib/pwa/service-worker';

export const GET: RequestHandler = () =>
	new Response(createServiceWorkerRetirementSource(), {
		headers: {
			'Cache-Control': 'public, max-age=0, must-revalidate',
			'Content-Type': 'text/javascript; charset=utf-8'
		}
	});
