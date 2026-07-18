import { env } from '$env/dynamic/private';
import { buildAndroidAssetLinks } from '$lib/server/runway/android-asset-links';
import type { RequestHandler } from './$types';

export const GET: RequestHandler = ({ params }) => {
	if (params.wellKnown !== '.well-known/assetlinks.json') return notFound();

	const statements = buildAndroidAssetLinks(
		env['ANDROID_APPLICATION_ID'],
		env['ANDROID_CERTIFICATE_SHA256']
	);
	if (!statements) return notFound();

	return new Response(JSON.stringify(statements), {
		headers: {
			'Cache-Control': 'public, max-age=300',
			'Content-Type': 'application/json; charset=utf-8'
		}
	});
};

function notFound() {
	return new Response('Not found', {
		status: 404,
		headers: {
			'Cache-Control': 'private, no-store',
			'Content-Type': 'text/plain; charset=utf-8'
		}
	});
}
