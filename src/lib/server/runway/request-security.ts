const mutationMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const androidClientHeaders = new Set(['runway-android/1', 'runway-android/2']);
const androidNativeAuthPaths = new Set([
	'/api/auth/sign-in/email',
	'/api/auth/sign-up/email',
	'/api/auth/two-factor/verify-totp',
	'/api/auth/two-factor/verify-backup-code'
]);

export function isMutationRequest(method: string): boolean {
	return mutationMethods.has(method.toUpperCase());
}

export function hasExactRequestOrigin(origin: string | null, expectedOrigin: string): boolean {
	return origin === expectedOrigin;
}

/**
 * Android cannot send a browser Origin header. Keep the exception to versioned,
 * non-cookie APIs with an explicit client marker, a deliberately scoped bearer,
 * and content types a cross-site HTML form cannot submit without a CORS preflight.
 */
export function isAndroidNativeApiRequest(request: Request, pathname: string): boolean {
	const method = request.method.toUpperCase();
	if (request.headers.has('origin')) return false;
	const client = request.headers.get('x-runway-client');
	if (!client || !androidClientHeaders.has(client)) return false;
	const contentType = request.headers.get('content-type')?.toLowerCase() ?? '';

	if (
		client === 'runway-android/2' &&
		method === 'POST' &&
		(pathname === '/api/auth/device/code' || pathname === '/api/auth/device/token')
	) {
		return contentType.startsWith('application/json');
	}
	if (
		client === 'runway-android/2' &&
		method === 'POST' &&
		androidNativeAuthPaths.has(pathname)
	) {
		return contentType.startsWith('application/json');
	}
	if (client === 'runway-android/2' && pathname.startsWith('/api/mobile/v1/')) {
		const authorization = request.headers.get('authorization');
		return (
			method !== 'GET' &&
			authorization?.startsWith('Bearer ') === true &&
			!authorization.startsWith('Bearer rwy1_') &&
			contentType.startsWith('application/json')
		);
	}
	if (method === 'DELETE' && pathname === '/api/android/status') {
		return request.headers.get('authorization')?.startsWith('Bearer rwy1_') === true;
	}
	if (method !== 'POST') return false;
	if (pathname === '/api/android/pair') return contentType.startsWith('application/json');
	if (!request.headers.get('authorization')?.startsWith('Bearer rwy1_')) return false;
	if (pathname === '/api/android/health-connect/changes')
		return contentType.startsWith('application/json');
	if (pathname !== '/api/android/import') return false;
	return ['application/gpx+xml', 'application/x-gpx+xml'].some(
		(allowed) => contentType === allowed || contentType.startsWith(`${allowed};`)
	);
}
