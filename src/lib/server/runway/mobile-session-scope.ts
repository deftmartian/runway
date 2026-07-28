/**
 * A mobile bearer must be distinguishable from an ordinary Better Auth
 * session. The device-authorization plugin creates a normal session, so the
 * session creation hook stamps this server-controlled value at the exact
 * Better Auth device-token endpoint.
 */
export const androidDeviceAuthorizationClientId = 'runway-android';
export const androidMobileSessionClientId = androidDeviceAuthorizationClientId;
export const deviceAuthorizationTokenPath = '/device/token';

type DeviceAuthorizationContext = {
	path?: unknown;
	body?: unknown;
};

export function androidDeviceAuthorizationSessionScope(
	context: DeviceAuthorizationContext | null
): { mobileClientId: string } | null {
	if (context?.path !== deviceAuthorizationTokenPath || !isRecord(context.body)) return null;
	if (context.body['client_id'] !== androidDeviceAuthorizationClientId) return null;
	return { mobileClientId: androidMobileSessionClientId };
}

export function isAndroidMobileSession(session: { mobileClientId?: unknown } | null): boolean {
	return session?.mobileClientId === androidMobileSessionClientId;
}

function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === 'object' && value !== null && !Array.isArray(value);
}
