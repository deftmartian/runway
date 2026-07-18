import { describe, expect, test } from 'vitest';
import { isBlockedBetterAuthHttpPath } from './auth-http-boundary';

describe('Better Auth HTTP boundary', () => {
	test.each([
		'/api/auth/sign-in/email',
		'/api/auth/sign-up/email',
		'/api/auth/two-factor/enable',
		'/api/auth/two-factor/disable',
		'/api/auth/two-factor/get-totp-uri',
		'/api/auth/two-factor/verify-totp',
		'/api/auth/two-factor/verify-backup-code',
		'/api/auth/two-factor/generate-backup-codes'
	])('blocks %s so the page-action rate limits cannot be bypassed', (pathname) => {
		expect(isBlockedBetterAuthHttpPath(pathname)).toBe(true);
	});

	test.each([
		'/api/auth/passkey/generate-authenticate-options',
		'/api/auth/passkey/verify-authentication',
		'/api/auth/passkey/generate-register-options',
		'/api/auth/oauth2/callback/authentik',
		'/api/auth/sign-in/oauth2'
	])('keeps the browser-required endpoint %s available', (pathname) => {
		expect(isBlockedBetterAuthHttpPath(pathname)).toBe(false);
	});

	test('does not block similarly named paths outside the Better Auth router', () => {
		expect(isBlockedBetterAuthHttpPath('/login/two-factor')).toBe(false);
		expect(isBlockedBetterAuthHttpPath('/api/auth/passkey/two-factor/verify-totp')).toBe(false);
	});
});
