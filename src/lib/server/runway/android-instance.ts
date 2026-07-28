import { buildIdentity } from './build-identity';

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
		nativeAuthorization: 'device_authorization' as const,
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
