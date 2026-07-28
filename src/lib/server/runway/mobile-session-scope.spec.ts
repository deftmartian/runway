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

	test.each([
		'/sign-in/email',
		'/sign-up/email',
		'/two-factor/verify-totp',
		'/two-factor/verify-backup-code'
	])('stamps a native originless Better Auth session created by %s', (path) => {
		expect(
			androidDeviceAuthorizationSessionScope({
				path,
				body: {},
				headers: new Headers({ 'x-runway-client': 'runway-android/2' })
			})
		).toEqual({ mobileClientId: 'runway-android' });
	});

	test('does not trust a browser-origin request that spoofs the Android marker', () => {
		expect(
			androidDeviceAuthorizationSessionScope({
				path: '/sign-in/email',
				body: {},
				headers: new Headers({
					origin: 'https://runway.example.test',
					'x-runway-client': 'runway-android/2'
				})
			})
		).toBeNull();
	});

	test('preserves a marked same-user replacement created by password rotation', () => {
		expect(
			androidDeviceAuthorizationSessionScope(
				{
					path: '/change-password',
					body: {
						revokeOtherSessions: true,
						mobileClientId: 'runway-android'
					},
					headers: new Headers({ 'x-runway-client': 'spoofed-client' }),
					context: {
						session: {
							session: {
								userId: 'runner-1',
								mobileClientId: 'runway-android'
							},
							user: { id: 'runner-1' }
						}
					}
				},
				{ userId: 'runner-1' }
			)
		).toEqual({ mobileClientId: 'runway-android' });
	});

	test.each([
		{
			name: 'unmarked current session',
			path: '/change-password',
			body: { revokeOtherSessions: true, mobileClientId: 'runway-android' },
			currentUserId: 'runner-1',
			currentSessionUserId: 'runner-1',
			mobileClientId: null,
			replacementUserId: 'runner-1'
		},
		{
			name: 'different replacement user',
			path: '/change-password',
			body: { revokeOtherSessions: true },
			currentUserId: 'runner-1',
			currentSessionUserId: 'runner-1',
			mobileClientId: 'runway-android',
			replacementUserId: 'runner-2'
		},
		{
			name: 'different authenticated user',
			path: '/change-password',
			body: { revokeOtherSessions: true },
			currentUserId: 'runner-2',
			currentSessionUserId: 'runner-1',
			mobileClientId: 'runway-android',
			replacementUserId: 'runner-1'
		},
		{
			name: 'no session rotation',
			path: '/change-password',
			body: { revokeOtherSessions: false, mobileClientId: 'runway-android' },
			currentUserId: 'runner-1',
			currentSessionUserId: 'runner-1',
			mobileClientId: 'runway-android',
			replacementUserId: 'runner-1'
		},
		{
			name: 'update-session cannot promote a session',
			path: '/update-session',
			body: { mobileClientId: 'runway-android' },
			currentUserId: 'runner-1',
			currentSessionUserId: 'runner-1',
			mobileClientId: 'runway-android',
			replacementUserId: 'runner-1'
		}
	])('does not promote from unsupported replacement context: $name', (input) => {
		expect(
			androidDeviceAuthorizationSessionScope(
				{
					path: input.path,
					body: input.body,
					headers: new Headers({ 'x-runway-client': 'runway-android/2' }),
					context: {
						session: {
							session: {
								userId: input.currentSessionUserId,
								mobileClientId: input.mobileClientId
							},
							user: { id: input.currentUserId }
						}
					}
				},
				{ userId: input.replacementUserId }
			)
		).toBeNull();
	});

	test('does not promote an authenticated browser session during TOTP verification', () => {
		expect(
			androidDeviceAuthorizationSessionScope(
				{
					path: '/two-factor/verify-totp',
					body: { code: '123456' },
					headers: new Headers({ 'x-runway-client': 'runway-android/2' }),
					context: {
						session: {
							session: { userId: 'runner-1', mobileClientId: null },
							user: { id: 'runner-1' }
						}
					}
				},
				{ userId: 'runner-1' }
			)
		).toBeNull();
	});

	test('recognizes only the stamped mobile session marker', () => {
		expect(isAndroidMobileSession({ mobileClientId: 'runway-android' })).toBe(true);
		expect(isAndroidMobileSession({ mobileClientId: 'browser' })).toBe(false);
		expect(isAndroidMobileSession(null)).toBe(false);
	});
});
