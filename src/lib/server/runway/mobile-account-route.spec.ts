import { beforeEach, describe, expect, test, vi } from 'vitest';
import { APIError } from 'better-auth/api';

const dependencies = vi.hoisted(() => ({
	authenticate: vi.fn(),
	consumeRateLimit: vi.fn(),
	rateLimitBuckets: vi.fn(),
	readBody: vi.fn(),
	requestPasswordReset: vi.fn(),
	changePassword: vi.fn(),
	enableTwoFactor: vi.fn(),
	verifyTOTP: vi.fn(),
	viewBackupCodes: vi.fn(),
	disableTwoFactor: vi.fn(),
	listUserAccounts: vi.fn(),
	generateBackupCodes: vi.fn(),
	validateReplacementToken: vi.fn(),
	revokeTrustedDevices: vi.fn(),
	deleteUser: vi.fn(),
	revokeSession: vi.fn(),
	select: vi.fn(),
	from: vi.fn(),
	where: vi.fn(),
	limit: vi.fn()
}));

vi.mock('$lib/server/runway/mobile-api', () => ({
	authenticateMobileRequest: dependencies.authenticate
}));
vi.mock('$lib/server/runway/security-rate-limit', () => ({
	accountSecurityRateLimitBuckets: dependencies.rateLimitBuckets,
	consumeSecurityRateLimit: dependencies.consumeRateLimit
}));
vi.mock('$lib/server/runway/bounded-request-body', () => ({
	readBoundedRequestBody: dependencies.readBody
}));
vi.mock('$lib/server/runway/password-reset', () => ({
	requestPasswordReset: dependencies.requestPasswordReset
}));
vi.mock('$lib/server/runway/mobile-account-security', () => ({
	validateMobileReplacementToken: dependencies.validateReplacementToken
}));
vi.mock('$lib/server/runway/trusted-devices', () => ({
	revokeTrustedDevices: dependencies.revokeTrustedDevices
}));
vi.mock('$lib/server/auth', () => ({
	auth: {
		api: {
			changePassword: dependencies.changePassword,
			enableTwoFactor: dependencies.enableTwoFactor,
			verifyTOTP: dependencies.verifyTOTP,
			viewBackupCodes: dependencies.viewBackupCodes,
			disableTwoFactor: dependencies.disableTwoFactor,
			listUserAccounts: dependencies.listUserAccounts,
			generateBackupCodes: dependencies.generateBackupCodes,
			deleteUser: dependencies.deleteUser,
			revokeSession: dependencies.revokeSession
		}
	}
}));
vi.mock('$lib/server/db', () => ({
	db: { select: dependencies.select }
}));

import { POST } from '../../../routes/api/mobile/v1/account/[operation]/+server';

const freshSession = {
	user: {
		id: 'runner-1',
		email: 'runner@example.invalid',
		twoFactorEnabled: false
	},
	session: {
		id: 'current-native-session',
		createdAt: new Date(),
		mobileClientId: 'runway-android'
	}
};

function accountEvent(operation: string, body: string) {
	return {
		request: new Request(`https://runway.example.test/api/mobile/v1/account/${operation}`, {
			method: 'POST',
			headers: {
				authorization: 'Bearer opaque-native-session',
				'content-type': 'application/json',
				'x-runway-client': 'runway-android/2'
			},
			body
		}),
		params: { operation },
		url: new URL(`https://runway.example.test/api/mobile/v1/account/${operation}`),
		getClientAddress: () => '192.0.2.25'
	} as Parameters<typeof POST>[0];
}

describe('native account-security operation route', () => {
	beforeEach(() => {
		for (const dependency of Object.values(dependencies)) dependency.mockReset();
		dependencies.authenticate.mockResolvedValue(freshSession);
		dependencies.consumeRateLimit.mockResolvedValue({ allowed: true, retryAfterSeconds: 0 });
		dependencies.rateLimitBuckets.mockReturnValue([]);
		dependencies.readBody.mockImplementation(async (request: Request) => ({
			result: 'ok',
			buffer: Buffer.from(await request.text())
		}));
		dependencies.requestPasswordReset.mockResolvedValue('sent_or_unknown');
		dependencies.validateReplacementToken.mockResolvedValue('signed-replacement-token');
		dependencies.listUserAccounts.mockResolvedValue([{ providerId: 'credential' }]);
		dependencies.select.mockReturnValue({ from: dependencies.from });
		dependencies.from.mockReturnValue({ where: dependencies.where });
		dependencies.where.mockReturnValue({ limit: dependencies.limit });
		dependencies.limit.mockResolvedValue([]);
	});

	test('password-reset initiation stays generic and uses the authenticated address', async () => {
		dependencies.requestPasswordReset.mockResolvedValue('email_not_configured');

		const response = await POST(accountEvent('request-password-reset', '{}'));

		expect(response.status).toBe(202);
		expect(response.headers.get('cache-control')).toBe('private, no-store');
		await expect(response.json()).resolves.toEqual({
			ok: true,
			message:
				'If password reset is available for this account, instructions will be sent by email.'
		});
		expect(dependencies.requestPasswordReset).toHaveBeenCalledWith(
			'runner@example.invalid',
			'https://runway.example.test',
			'192.0.2.25'
		);
	});

	test('changes the password with forced rotation and returns only the validated header token', async () => {
		const responseHeaders = new Headers({ 'set-auth-token': 'signed-replacement-token' });
		dependencies.changePassword.mockResolvedValue({
			headers: responseHeaders,
			response: { token: 'untrusted-response-body-token' }
		});
		const event = accountEvent(
			'change-password',
			'{"currentPassword":"old password","newPassword":"new password long enough"}'
		);

		const response = await POST(event);

		expect(response.status).toBe(200);
		const responseText = await response.text();
		expect(responseText).not.toContain('untrusted-response-body-token');
		expect(JSON.parse(responseText)).toEqual({
			ok: true,
			sessionToken: 'signed-replacement-token',
			message: 'Password changed. Other sessions were ended.'
		});
		expect(dependencies.changePassword).toHaveBeenCalledWith({
			body: {
				currentPassword: 'old password',
				newPassword: 'new password long enough',
				revokeOtherSessions: true
			},
			headers: event.request.headers,
			returnHeaders: true
		});
		expect(dependencies.validateReplacementToken).toHaveBeenCalledWith(
			responseHeaders,
			'runner-1',
			'current-native-session'
		);
		expect(dependencies.revokeTrustedDevices).toHaveBeenCalledWith('runner-1');
	});

	test('does not call Better Auth for an unmarked or stale password-change request', async () => {
		dependencies.authenticate.mockResolvedValueOnce(null);
		const spoofed = await POST(
			accountEvent(
				'change-password',
				'{"currentPassword":"old password","newPassword":"new password long enough"}'
			)
		);
		expect(spoofed.status).toBe(401);

		dependencies.authenticate.mockResolvedValueOnce({
			...freshSession,
			session: {
				...freshSession.session,
				createdAt: new Date(Date.now() - 11 * 60 * 1_000)
			}
		});
		const stale = await POST(
			accountEvent(
				'change-password',
				'{"currentPassword":"old password","newPassword":"new password long enough"}'
			)
		);
		expect(stale.status).toBe(403);
		expect(dependencies.changePassword).not.toHaveBeenCalled();
		expect(dependencies.readBody).not.toHaveBeenCalled();
	});

	test('returns a generic failure for a wrong current password without rotating a token', async () => {
		dependencies.changePassword.mockRejectedValue(
			new APIError('BAD_REQUEST', { message: 'INVALID_PASSWORD' })
		);

		const response = await POST(
			accountEvent(
				'change-password',
				'{"currentPassword":"wrong password","newPassword":"new password long enough"}'
			)
		);

		expect(response.status).toBe(400);
		await expect(response.json()).resolves.toEqual({
			ok: false,
			error: 'account_security_failed',
			message: 'The password could not be changed.'
		});
		expect(dependencies.validateReplacementToken).not.toHaveBeenCalled();
		expect(dependencies.revokeTrustedDevices).not.toHaveBeenCalled();
	});

	test('withholds every token when the replacement session cannot be validated', async () => {
		dependencies.changePassword.mockResolvedValue({
			headers: new Headers({ 'set-auth-token': 'unvalidated-token' }),
			response: { token: 'also-untrusted' }
		});
		dependencies.validateReplacementToken.mockResolvedValue(null);

		const response = await POST(
			accountEvent(
				'change-password',
				'{"currentPassword":"old password","newPassword":"new password long enough"}'
			)
		);

		expect(response.status).toBe(500);
		const responseText = await response.text();
		expect(responseText).not.toContain('unvalidated-token');
		expect(responseText).not.toContain('also-untrusted');
		expect(JSON.parse(responseText)).toMatchObject({ error: 'session_replacement_failed' });
	});

	test('starts TOTP setup without exposing the generated recovery codes', async () => {
		dependencies.enableTwoFactor.mockResolvedValue({
			totpURI: 'otpauth://totp/runway:runner?secret=SETUPSECRET',
			backupCodes: ['must-not-be-returned-yet']
		});
		const event = accountEvent('enable-two-factor', '{"password":"current password"}');

		const response = await POST(event);

		expect(response.status).toBe(200);
		const responseText = await response.text();
		expect(responseText).not.toContain('must-not-be-returned-yet');
		expect(JSON.parse(responseText)).toEqual({
			ok: true,
			totpUri: 'otpauth://totp/runway:runner?secret=SETUPSECRET',
			message: 'Authenticator setup started.'
		});
		expect(dependencies.enableTwoFactor).toHaveBeenCalledWith({
			body: { password: 'current password' },
			headers: event.request.headers
		});
		expect(dependencies.revokeTrustedDevices).toHaveBeenCalledWith('runner-1');
	});

	test('does not change TOTP state or trusted-device records after a wrong password', async () => {
		dependencies.enableTwoFactor.mockRejectedValue(
			new APIError('BAD_REQUEST', { message: 'INVALID_PASSWORD' })
		);

		const response = await POST(accountEvent('enable-two-factor', '{"password":"wrong password"}'));

		expect(response.status).toBe(400);
		const responseText = await response.text();
		expect(responseText).not.toContain('secret');
		expect(JSON.parse(responseText)).toMatchObject({ error: 'account_security_failed' });
		expect(dependencies.revokeTrustedDevices).not.toHaveBeenCalled();
	});

	test('returns recovery codes only after setup verification and validated token rotation', async () => {
		const responseHeaders = new Headers({ 'set-auth-token': 'signed-totp-session' });
		dependencies.verifyTOTP.mockResolvedValue({
			headers: responseHeaders,
			response: { status: true }
		});
		dependencies.validateReplacementToken.mockResolvedValue('signed-totp-session');
		dependencies.viewBackupCodes.mockResolvedValue({
			status: true,
			backupCodes: ['ABCDE-FGHIJ', 'KLMNO-PQRST']
		});
		const event = accountEvent('verify-two-factor-setup', '{"code":"123456"}');

		const response = await POST(event);

		expect(response.status).toBe(200);
		await expect(response.json()).resolves.toEqual({
			ok: true,
			sessionToken: 'signed-totp-session',
			recoveryCodes: ['ABCDE-FGHIJ', 'KLMNO-PQRST'],
			message: 'Two-factor authentication enabled. Save the recovery codes now.'
		});
		expect(dependencies.verifyTOTP).toHaveBeenCalledWith({
			body: { code: '123456' },
			headers: event.request.headers,
			returnHeaders: true
		});
		expect(dependencies.viewBackupCodes).toHaveBeenCalledWith({
			body: { userId: 'runner-1' }
		});
	});

	test('withholds recovery codes when setup session rotation is not validated', async () => {
		dependencies.verifyTOTP.mockResolvedValue({
			headers: new Headers({ 'set-auth-token': 'unvalidated-totp-token' }),
			response: { status: true }
		});
		dependencies.validateReplacementToken.mockResolvedValue(null);

		const response = await POST(accountEvent('verify-two-factor-setup', '{"code":"123456"}'));

		expect(response.status).toBe(500);
		const responseText = await response.text();
		expect(responseText).not.toContain('unvalidated-totp-token');
		expect(JSON.parse(responseText)).toMatchObject({ error: 'session_replacement_failed' });
		expect(dependencies.viewBackupCodes).not.toHaveBeenCalled();
	});

	test('disables TOTP, revokes trusted devices, and rotates the marked session', async () => {
		dependencies.authenticate.mockResolvedValue({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true }
		});
		const responseHeaders = new Headers({ 'set-auth-token': 'signed-disabled-session' });
		dependencies.disableTwoFactor.mockResolvedValue({
			headers: responseHeaders,
			response: { status: true }
		});
		dependencies.validateReplacementToken.mockResolvedValue('signed-disabled-session');
		const event = accountEvent('disable-two-factor', '{"password":"current password"}');

		const response = await POST(event);

		expect(response.status).toBe(200);
		await expect(response.json()).resolves.toEqual({
			ok: true,
			sessionToken: 'signed-disabled-session',
			message: 'Two-factor authentication disabled.'
		});
		expect(dependencies.disableTwoFactor).toHaveBeenCalledWith({
			body: { password: 'current password' },
			headers: event.request.headers,
			returnHeaders: true
		});
		expect(dependencies.revokeTrustedDevices).toHaveBeenCalledWith('runner-1');
	});

	test('replaces recovery codes only after fresh local-password verification', async () => {
		dependencies.authenticate.mockResolvedValue({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true }
		});
		dependencies.generateBackupCodes.mockResolvedValue({
			status: true,
			backupCodes: ['ABCDE-FGHIJ', 'KLMNO-PQRST']
		});
		const event = accountEvent('regenerate-recovery-codes', '{"password":"current password"}');

		const response = await POST(event);

		expect(response.status).toBe(200);
		await expect(response.json()).resolves.toEqual({
			ok: true,
			recoveryCodes: ['ABCDE-FGHIJ', 'KLMNO-PQRST'],
			message:
				'Recovery codes replaced. Save the new codes now; every previous code has stopped working.'
		});
		expect(dependencies.rateLimitBuckets).toHaveBeenCalledWith(
			'regenerate-recovery-codes',
			'runner-1',
			'192.0.2.25'
		);
		expect(dependencies.listUserAccounts).toHaveBeenCalledWith({
			headers: event.request.headers
		});
		expect(dependencies.generateBackupCodes).toHaveBeenCalledWith({
			body: { password: 'current password' },
			headers: event.request.headers
		});
	});

	test('does not replace recovery codes for stale, non-TOTP, or OIDC-only accounts', async () => {
		dependencies.authenticate.mockResolvedValueOnce({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true },
			session: {
				...freshSession.session,
				createdAt: new Date(Date.now() - 11 * 60 * 1_000)
			}
		});
		const stale = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"current password"}')
		);
		expect(stale.status).toBe(403);

		const notEnabled = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"current password"}')
		);
		expect(notEnabled.status).toBe(409);

		dependencies.authenticate.mockResolvedValueOnce({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true }
		});
		dependencies.listUserAccounts.mockResolvedValueOnce([{ providerId: 'authentik' }]);
		const oidcOnly = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"current password"}')
		);
		expect(oidcOnly.status).toBe(409);
		await expect(oidcOnly.json()).resolves.toMatchObject({
			error: 'local_password_required'
		});
		expect(dependencies.generateBackupCodes).not.toHaveBeenCalled();
	});

	test('withholds malformed replacement codes and supports a subsequent replacement attempt', async () => {
		dependencies.authenticate.mockResolvedValue({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true }
		});
		dependencies.generateBackupCodes
			.mockResolvedValueOnce({
				status: true,
				backupCodes: ['malformed-provider-value']
			})
			.mockResolvedValueOnce({
				status: true,
				backupCodes: ['ABCDE-FGHIJ']
			});

		const malformed = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"current password"}')
		);
		expect(malformed.status).toBe(500);
		const malformedText = await malformed.text();
		expect(malformedText).not.toContain('malformed-provider-value');
		expect(JSON.parse(malformedText)).toMatchObject({
			error: 'recovery_code_generation_failed'
		});

		const retried = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"current password"}')
		);
		expect(retried.status).toBe(200);
		await expect(retried.json()).resolves.toMatchObject({
			recoveryCodes: ['ABCDE-FGHIJ']
		});
		expect(dependencies.generateBackupCodes).toHaveBeenCalledTimes(2);
	});

	test('wrong-password recovery replacement never returns provider details or codes', async () => {
		dependencies.authenticate.mockResolvedValue({
			...freshSession,
			user: { ...freshSession.user, twoFactorEnabled: true }
		});
		dependencies.generateBackupCodes.mockRejectedValue(
			new APIError('BAD_REQUEST', { message: 'INVALID_PASSWORD' })
		);

		const response = await POST(
			accountEvent('regenerate-recovery-codes', '{"password":"wrong password"}')
		);

		expect(response.status).toBe(400);
		const responseText = await response.text();
		expect(responseText).not.toContain('INVALID_PASSWORD');
		expect(responseText).not.toContain('backup');
		expect(JSON.parse(responseText)).toMatchObject({
			error: 'account_security_failed',
			message: 'Recovery codes could not be replaced.'
		});
	});

	test('revokes an owned non-current session without returning its bearer token', async () => {
		dependencies.limit.mockResolvedValue([{ token: 'server-only-target-token' }]);
		dependencies.revokeSession.mockResolvedValue({ status: true });
		const event = accountEvent('revoke-session', '{"sessionId":"other-session-id"}');

		const response = await POST(event);

		expect(response.status).toBe(200);
		const responseText = await response.text();
		expect(responseText).not.toContain('server-only-target-token');
		expect(JSON.parse(responseText)).toEqual({ ok: true, message: 'Session ended.' });
		expect(dependencies.revokeSession).toHaveBeenCalledWith({
			body: { token: 'server-only-target-token' },
			headers: event.request.headers
		});
	});

	test('will not revoke the marked session through the other-session action', async () => {
		const response = await POST(
			accountEvent('revoke-session', '{"sessionId":"current-native-session"}')
		);

		expect(response.status).toBe(409);
		await expect(response.json()).resolves.toMatchObject({ error: 'current_session' });
		expect(dependencies.select).not.toHaveBeenCalled();
		expect(dependencies.revokeSession).not.toHaveBeenCalled();
	});

	test('requires a fresh marked session and exact confirmation before account deletion', async () => {
		dependencies.authenticate.mockResolvedValueOnce({
			...freshSession,
			session: {
				...freshSession.session,
				createdAt: new Date(Date.now() - 11 * 60 * 1_000)
			}
		});
		const stale = await POST(accountEvent('delete-account', '{"confirmation":"DELETE"}'));
		expect(stale.status).toBe(403);
		expect(dependencies.deleteUser).not.toHaveBeenCalled();

		const wrong = await POST(accountEvent('delete-account', '{"confirmation":"delete"}'));
		expect(wrong.status).toBe(400);
		expect(dependencies.deleteUser).not.toHaveBeenCalled();

		dependencies.deleteUser.mockResolvedValue({ success: true });
		const event = accountEvent('delete-account', '{"confirmation":"DELETE"}');
		const confirmed = await POST(event);
		expect(confirmed.status).toBe(200);
		await expect(confirmed.json()).resolves.toEqual({
			ok: true,
			accountDeleted: true,
			message: 'Account deleted.'
		});
		expect(dependencies.deleteUser).toHaveBeenCalledWith({
			body: {},
			headers: event.request.headers
		});
	});

	test('returns the persistent limiter Retry-After before reading a request body', async () => {
		dependencies.consumeRateLimit.mockResolvedValue({
			allowed: false,
			retryAfterSeconds: 417
		});

		const response = await POST(accountEvent('delete-account', '{"confirmation":"DELETE"}'));

		expect(response.status).toBe(429);
		expect(response.headers.get('retry-after')).toBe('417');
		expect(dependencies.readBody).not.toHaveBeenCalled();
		expect(dependencies.deleteUser).not.toHaveBeenCalled();
	});

	test('enforces the shared bounded-body parser before credential mutation', async () => {
		dependencies.readBody.mockResolvedValue({ result: 'too-large' });

		const response = await POST(
			accountEvent(
				'change-password',
				'{"currentPassword":"old password","newPassword":"new password long enough"}'
			)
		);

		expect(response.status).toBe(413);
		expect(response.headers.get('cache-control')).toBe('private, no-store');
		expect(dependencies.changePassword).not.toHaveBeenCalled();
	});
});
