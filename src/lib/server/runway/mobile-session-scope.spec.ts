import { describe, expect, test } from 'vitest';
import {
	androidDeviceAuthorizationSessionScope,
	isAndroidMobileSession
} from './mobile-session-scope';

describe('Android device session scope', () => {
	test('stamps only the configured Better Auth device-token exchange', () => {
		expect(
			androidDeviceAuthorizationSessionScope({
				path: '/device/token',
				body: { client_id: 'runway-android' }
			})
		).toEqual({ mobileClientId: 'runway-android' });
	});

	test.each([
		null,
		{ path: '/sign-in/email', body: { client_id: 'runway-android' } },
		{ path: '/device/token', body: { client_id: 'another-client' } },
		{ path: '/device/token', body: null }
	])('does not stamp unrelated session creation contexts: %o', (context) => {
		expect(androidDeviceAuthorizationSessionScope(context)).toBeNull();
	});

	test('recognizes only the stamped mobile session marker', () => {
		expect(isAndroidMobileSession({ mobileClientId: 'runway-android' })).toBe(true);
		expect(isAndroidMobileSession({ mobileClientId: 'browser' })).toBe(false);
		expect(isAndroidMobileSession(null)).toBe(false);
	});
});
