export const oauthTokenStorageOptions = Object.freeze({
	encryptOAuthTokens: true as const
});

export function omitStoredOidcIdToken(account: Record<string, unknown>): Record<string, unknown> {
	if (!Object.hasOwn(account, 'idToken')) return account;
	return { ...account, idToken: null };
}

export function canonicalAppOrigin(rawValue: string, name: string): string {
	let parsed: URL;
	try {
		parsed = new URL(rawValue);
	} catch {
		throw new Error(`${name} must be an absolute URL.`);
	}
	if (
		parsed.username ||
		parsed.password ||
		parsed.search ||
		parsed.hash ||
		parsed.pathname !== '/'
	) {
		throw new Error(`${name} must contain only a scheme, host, and optional port.`);
	}
	return parsed.origin;
}

export function oidcDiscoveryUrl(issuer: string): string {
	let parsed: URL;
	try {
		parsed = new URL(issuer);
	} catch {
		throw new Error('OIDC_ISSUER must be an absolute URL.');
	}
	if (parsed.username || parsed.password || parsed.search || parsed.hash) {
		throw new Error('OIDC_ISSUER must not contain credentials, a query, or a fragment.');
	}
	return `${issuer.replace(/\/+$/, '')}/.well-known/openid-configuration`;
}

export function passkeyRpIdProblem(origin: string, rpId: string): string | null {
	const hostname = new URL(origin).hostname.toLowerCase();
	const normalizedRpId = rpId.trim().toLowerCase();
	if (!normalizedRpId) return 'PASSKEY_RP_ID must not be empty.';
	if (normalizedRpId !== hostname) {
		return `PASSKEY_RP_ID must exactly match the PUBLIC_APP_ORIGIN hostname (${hostname}).`;
	}
	return null;
}
