import { buildIdentity } from './build-identity';

const androidApplicationIdPattern = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const defaultAndroidApplicationId = 'com.deftmartian.runway';

export const androidApiCompatibility = Object.freeze({
	minimum: 1,
	maximum: 1
});

export function buildAndroidInstanceDescriptor() {
	return {
		result: 'runway-instance' as const,
		product: 'runway' as const,
		minimumAndroidApi: androidApiCompatibility.minimum,
		maximumAndroidApi: androidApiCompatibility.maximum,
		release: buildIdentity.release
	};
}

export function resolveAndroidApplicationId(input: string | undefined): string | null {
	const candidate = input?.trim() || defaultAndroidApplicationId;
	return androidApplicationIdPattern.test(candidate) ? candidate : null;
}
