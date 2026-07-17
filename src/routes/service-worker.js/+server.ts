import type { RequestHandler } from './$types';
import { version } from '$app/environment';
import { createServiceWorkerSource } from '$lib/pwa/service-worker';

export const GET: RequestHandler = () =>
	new Response(createServiceWorkerSource(version), {
		headers: {
			'Cache-Control': 'public, max-age=0, must-revalidate',
			'Content-Type': 'text/javascript; charset=utf-8'
		}
	});
