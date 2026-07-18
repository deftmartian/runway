const blockedBetterAuthHttpPaths = new Set(['/api/auth/sign-in/email', '/api/auth/sign-up/email']);

const blockedBetterAuthHttpPrefixes = ['/api/auth/two-factor/'];

/**
 * Password and two-factor flows are exposed only through runway's server-side
 * page actions, where persistent per-account and per-address limits apply.
 * Passkey and OAuth callbacks must continue through Better Auth's HTTP router.
 */
export function isBlockedBetterAuthHttpPath(pathname: string): boolean {
	return (
		blockedBetterAuthHttpPaths.has(pathname) ||
		blockedBetterAuthHttpPrefixes.some((prefix) => pathname.startsWith(prefix))
	);
}
