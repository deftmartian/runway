/**
 * A mobile bearer must be distinguishable from an ordinary Better Auth
 * session. The session-creation hook stamps this server-controlled value only
 * for exact native credential paths or the Better Auth device-token endpoint.
 */
export const androidDeviceAuthorizationClientId = 'runway-android';
export const androidMobileSessionClientId = androidDeviceAuthorizationClientId;
export const deviceAuthorizationTokenPath = '/device/token';
export const androidNativeAuthClientHeader = 'runway-android/2';

const androidNativeSessionPaths = new Set([
	'/sign-in/email',
	'/sign-up/email',
	'/two-factor/verify-totp',
	'/two-factor/verify-backup-code'
]);

type DeviceAuthorizationContext = {
	path?: unknown;
	body?: unknown;
	headers?: Headers | undefined;
	context?: {
		session?: {
			session?: {
				userId?: unknown;
				mobileClientId?: unknown;
			};
			user?: {
				id?: unknown;
			};
		} | null;
	};
};

export function androidDeviceAuthorizationSessionScope(
	context: DeviceAuthorizationContext | null,
	replacementSession?: { userId?: unknown }
): { mobileClientId: string } | null {
	if (
		context?.path === deviceAuthorizationTokenPath &&
		isRecord(context.body) &&
		context.body['client_id'] === androidDeviceAuthorizationClientId
	) {
		return { mobileClientId: androidMobileSessionClientId };
	}

	const authenticatedSession = context?.context?.session;
	const authenticatedReplacementPath =
		context?.path === '/two-factor/disable' ||
		context?.path === '/two-factor/verify-totp' ||
		(context?.path === '/change-password' &&
			isRecord(context.body) &&
			context.body['revokeOtherSessions'] === true);
	if (
		authenticatedReplacementPath &&
		authenticatedSession?.user?.id === replacementSession?.userId &&
		authenticatedSession?.session?.userId === replacementSession?.userId &&
		isAndroidMobileSession(authenticatedSession?.session ?? null)
	) {
		return { mobileClientId: androidMobileSessionClientId };
	}

	if (
		typeof context?.path === 'string' &&
		androidNativeSessionPaths.has(context.path) &&
		!authenticatedSession &&
		context.headers?.get('x-runway-client') === androidNativeAuthClientHeader &&
		!context.headers.has('origin')
	) {
		return { mobileClientId: androidMobileSessionClientId };
	}
	return null;
}

export function isAndroidMobileSession(session: { mobileClientId?: unknown } | null): boolean {
	return session?.mobileClientId === androidMobileSessionClientId;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === 'object' && value !== null && !Array.isArray(value);
}
