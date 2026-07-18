import { describe, expect, test } from 'vitest';
import { isAllowedBetterAuthHttpRequest } from './auth-http-boundary';

describe('Better Auth HTTP boundary', () => {
	test.each([
		'/api/auth/sign-in/email',
		'/api/auth/sign-up/email',
		'/api/auth/sign-in/oauth2',
		'/api/auth/two-factor/enable',
		'/api/auth/two-factor/disable',
		'/api/auth/two-factor/get-totp-uri',
		'/api/auth/two-factor/verify-totp',
		'/api/auth/two-factor/verify-backup-code',
		'/api/auth/two-factor/generate-backup-codes',
		'/api/auth/oauth2/link',
		'/api/auth/change-password',
		'/api/auth/passkey/delete-passkey',
		'/api/auth/revoke-sessions'
	])('blocks %s so page actions and product boundaries cannot be bypassed', (pathname) => {
		expect(isAllowedBetterAuthHttpRequest(pathname, 'POST')).toBe(false);
	});

	test.each([
		['GET', '/api/auth/get-session'],
		['GET', '/api/auth/error'],
		['GET', '/api/auth/passkey/generate-authenticate-options'],
		['POST', '/api/auth/passkey/verify-authentication'],
		['GET', '/api/auth/passkey/generate-register-options'],
		['POST', '/api/auth/passkey/verify-registration'],
		['GET', '/api/auth/oauth2/callback/authentik']
	])('keeps the browser-required %s %s endpoint available', (method, pathname) => {
		expect(isAllowedBetterAuthHttpRequest(pathname, method)).toBe(true);
	});

	test('does not block similarly named paths outside the Better Auth router', () => {
		expect(isAllowedBetterAuthHttpRequest('/login/two-factor', 'POST')).toBe(true);
		expect(isAllowedBetterAuthHttpRequest('/api/auth/passkey/two-factor/verify-totp', 'POST')).toBe(
			false
		);
	});

	test('does not allow an endpoint under the wrong method or a sibling OIDC provider', () => {
		expect(isAllowedBetterAuthHttpRequest('/api/auth/passkey/verify-authentication', 'GET')).toBe(
			false
		);
		expect(
			isAllowedBetterAuthHttpRequest('/api/auth/oauth2/callback/unreviewed-provider', 'GET')
		).toBe(false);
	});

	test('classifies only the public passkey authentication calls', async () => {
		const { passkeyAuthenticationAction } = await import('./auth-http-boundary');
		expect(
			passkeyAuthenticationAction('/api/auth/passkey/generate-authenticate-options', 'GET')
		).toBe('options');
		expect(passkeyAuthenticationAction('/api/auth/passkey/verify-authentication', 'POST')).toBe(
			'verify'
		);
		expect(
			passkeyAuthenticationAction('/api/auth/passkey/verify-authentication', 'GET')
		).toBeNull();
	});
});
