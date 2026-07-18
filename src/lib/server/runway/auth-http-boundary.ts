const allowedBetterAuthHttpRequests = new Set([
	'GET /api/auth/error',
	'GET /api/auth/get-session',
	'GET /api/auth/oauth2/callback/authentik',
	'GET /api/auth/passkey/generate-authenticate-options',
	'GET /api/auth/passkey/generate-register-options',
	'POST /api/auth/passkey/verify-authentication',
	'POST /api/auth/passkey/verify-registration'
]);

/**
 * Keep Better Auth's raw HTTP surface explicit. Password, signup, two-factor,
 * OAuth-start, account-linking, passkey mutation, and session-management flows
 * use runway server actions or are not product features. Browser passkey calls
 * and the one configured OIDC callback must continue through the HTTP router.
 */
export function isAllowedBetterAuthHttpRequest(pathname: string, method: string): boolean {
	if (!pathname.startsWith('/api/auth')) return true;
	return allowedBetterAuthHttpRequests.has(`${method.toUpperCase()} ${pathname}`);
}

export function passkeyAuthenticationAction(
	pathname: string,
	method: string
): 'options' | 'verify' | null {
	if (pathname === '/api/auth/passkey/generate-authenticate-options' && method === 'GET') {
		return 'options';
	}
	if (pathname === '/api/auth/passkey/verify-authentication' && method === 'POST') {
		return 'verify';
	}
	return null;
}

export function isPasskeyRegistrationRequest(pathname: string, method: string): boolean {
	return (
		(pathname === '/api/auth/passkey/generate-register-options' && method === 'GET') ||
		(pathname === '/api/auth/passkey/verify-registration' && method === 'POST')
	);
}
