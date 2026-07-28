const deviceCodePattern = /^[A-HJ-NP-Z2-9]{8}$/;
const nativeDeviceReturnParameter = 'return_to_app';
const nativeDeviceReturnValue = 'runway-native';

export type NativeDeviceAuthorizationResult = 'approved' | 'denied';

export function safeAuthReturnTo(input: string | null | undefined): string {
	const candidate = input?.trim();
	if (!candidate || candidate === '/app') return '/app';
	if (!candidate.startsWith('/') || candidate.startsWith('//')) return '/app';

	let parsed: URL;
	try {
		parsed = new URL(candidate, 'https://runway.invalid');
	} catch {
		return '/app';
	}
	if (parsed.origin !== 'https://runway.invalid' || parsed.hash || parsed.pathname !== '/device') {
		return '/app';
	}
	const userCode = parsed.searchParams.get('user_code')?.replaceAll('-', '').toUpperCase();
	const nativeAppReturn =
		parsed.searchParams.get(nativeDeviceReturnParameter) === nativeDeviceReturnValue;
	const expectedParameterCount = nativeAppReturn ? 2 : 1;
	if (
		!userCode ||
		!deviceCodePattern.test(userCode) ||
		Array.from(parsed.searchParams.keys()).length !== expectedParameterCount ||
		parsed.searchParams.getAll('user_code').length !== 1 ||
		(nativeAppReturn
			? parsed.searchParams.getAll(nativeDeviceReturnParameter).length !== 1 ||
				Array.from(parsed.searchParams.keys()).some(
					(key) => key !== 'user_code' && key !== nativeDeviceReturnParameter
				)
			: Array.from(parsed.searchParams.keys()).some((key) => key !== 'user_code'))
	) {
		return '/app';
	}
	const canonical = `/device?user_code=${encodeURIComponent(userCode)}`;
	return nativeAppReturn
		? `${canonical}&${nativeDeviceReturnParameter}=${nativeDeviceReturnValue}`
		: canonical;
}

export function isNativeDeviceAuthorizationReturn(input: string | null | undefined): boolean {
	return safeAuthReturnTo(input).endsWith(
		`&${nativeDeviceReturnParameter}=${nativeDeviceReturnValue}`
	);
}

/**
 * This is deliberately a fixed app link: it only tells the app to resume its
 * existing device-code poll. Device codes, browser sessions, identities, and
 * bearer tokens must never cross this boundary.
 */
export function nativeDeviceAuthorizationCallback(
	result: NativeDeviceAuthorizationResult
): string {
	return `runway-native://auth?result=${result}`;
}
