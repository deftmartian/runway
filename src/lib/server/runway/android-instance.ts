import { buildIdentity } from './build-identity';
import { env } from '$env/dynamic/private';

const androidApplicationIdPattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const defaultAndroidApplicationId = 'dev.deftmartian.runway';
const supportedAndroidClients = new Set(['runway-android/1', 'runway-android/2']);

export const androidApiCompatibility = Object.freeze({
	minimum: 1,
	maximum: 2
});

export function buildAndroidInstanceDescriptor() {
	return {
		result: 'runway-instance' as const,
		product: 'runway' as const,
		minimumAndroidApi: androidApiCompatibility.minimum,
		maximumAndroidApi: androidApiCompatibility.maximum,
		nativeUi: true,
		nativeAuthorization: 'local_and_device_authorization' as const,
		auth: {
			local: env['LOCAL_AUTH_ENABLED'] !== 'false',
			localSignups: env['ALLOW_LOCAL_SIGNUPS'] === 'true',
			oidc: Boolean(
				env['OIDC_ISSUER'] && env['OIDC_CLIENT_ID'] && env['OIDC_CLIENT_SECRET']
			),
			passkeys: true
		},
		release: buildIdentity.release
	};
}

export function isSupportedAndroidClient(input: string | null): boolean {
	return input !== null && supportedAndroidClients.has(input);
}

export function resolveAndroidApplicationId(input: string | undefined): string | null {
	const candidate = input?.trim() || defaultAndroidApplicationId;
	return androidApplicationIdPattern.test(candidate) ? candidate : null;
}
