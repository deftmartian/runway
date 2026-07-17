const mutationMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const webShareTargetPath = '/app/import/share';

export function isMutationRequest(method: string): boolean {
	return mutationMethods.has(method.toUpperCase());
}

export function hasExactRequestOrigin(origin: string | null, expectedOrigin: string): boolean {
	return origin === expectedOrigin;
}

/**
 * Web Share Target POSTs are user-agent-created top-level navigations. The
 * Web Share Target algorithm does not add an Origin header, so the normal
 * exact-Origin check cannot identify a native-app share. Keep the exception
 * limited to the one action URL and Fetch Metadata values that a cross-site
 * HTML form cannot choose or forge in a browser.
 *
 * Authentication and multipart/file validation still happen in the route.
 */
export function isWebShareTargetNavigation(request: Request, pathname: string): boolean {
	if (pathname !== webShareTargetPath || request.method.toUpperCase() !== 'POST') return false;
	if (request.headers.has('origin')) return false;
	if (!request.headers.get('content-type')?.toLowerCase().startsWith('multipart/form-data;')) {
		return false;
	}
	return (
		request.headers.get('sec-fetch-site') === 'none' &&
		request.headers.get('sec-fetch-mode') === 'navigate' &&
		request.headers.get('sec-fetch-dest') === 'document'
	);
}
