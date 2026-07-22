import type { RequestHandler } from './$types';
import { runwayManifest } from '$lib/pwa/manifest';

export const GET: RequestHandler = () =>
	new Response(JSON.stringify(runwayManifest), {
		headers: {
			'Cache-Control': 'public, max-age=0, must-revalidate',
			'Content-Type': 'application/manifest+json; charset=utf-8'
		}
	});
