export const oauthTokenStorageOptions = Object.freeze({
	encryptOAuthTokens: true as const
});

export const authFreshSessionSeconds = 10 * 60;

export function isFreshAuthSession(
	createdAt: Date | string,
	now = Date.now(),
	maxAgeSeconds = authFreshSessionSeconds
): boolean {
	const createdAtMs =
		createdAt instanceof Date ? createdAt.getTime() : new Date(createdAt).getTime();
	const ageMs = now - createdAtMs;
	return Number.isFinite(createdAtMs) && ageMs >= -30_000 && ageMs < maxAgeSeconds * 1_000;
}

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

export function publicOriginMismatchProblem(
	requestOrigin: string,
	passkeyOrigin: string
): string | null {
	if (requestOrigin === passkeyOrigin) return null;
	return 'PUBLIC_APP_ORIGIN must match ORIGIN for a single public runway instance.';
}
