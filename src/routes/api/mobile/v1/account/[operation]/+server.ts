import { json } from '@sveltejs/kit';
import { APIError } from 'better-auth/api';
import { and, eq } from 'drizzle-orm';
import { z } from 'zod';
import { auth } from '$lib/server/auth';
import { db } from '$lib/server/db';
import { session as authSession } from '$lib/server/db/auth.schema';
import { isFreshAuthSession } from '$lib/server/runway/auth-config';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import { validateMobileReplacementToken } from '$lib/server/runway/mobile-account-security';
import { authenticateMobileRequest } from '$lib/server/runway/mobile-api';
import { requestPasswordReset } from '$lib/server/runway/password-reset';
import {
	accountSecurityRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import { revokeTrustedDevices } from '$lib/server/runway/trusted-devices';
import {
	authPasswordSchema,
	backupCodeSchema,
	newPasswordSchema,
	totpCodeSchema
} from '$lib/server/runway/validation';
import type { RequestHandler } from './$types';

const maximumBodyBytes = 4 * 1024;
const emptyBody = z.strictObject({});
const revokeSessionBody = z.strictObject({ sessionId: z.string().min(1).max(128) });
const deleteAccountBody = z.strictObject({ confirmation: z.literal('DELETE') });
const passwordBody = z.strictObject({ password: authPasswordSchema });
const changePasswordBody = z.strictObject({
	currentPassword: authPasswordSchema,
	newPassword: newPasswordSchema
});
const verifyTotpSetupBody = z.strictObject({ code: totpCodeSchema });
const generatedRecoveryCodes = z
	.array(backupCodeSchema)
	.min(1)
	.max(20)
	.refine((codes) => new Set(codes).size === codes.length);

type MobileAccountOperation =
	| 'request-password-reset'
	| 'change-password'
	| 'enable-two-factor'
	| 'verify-two-factor-setup'
	| 'disable-two-factor'
	| 'regenerate-recovery-codes'
	| 'revoke-session'
	| 'delete-account';

const freshSessionOperations = new Set<MobileAccountOperation>([
	'change-password',
	'enable-two-factor',
	'verify-two-factor-setup',
	'disable-two-factor',
	'regenerate-recovery-codes',
	'revoke-session',
	'delete-account'
]);

export const POST: RequestHandler = async (event) => {
	const mobileSession = await authenticateMobileRequest(event.request);
	if (!mobileSession) return mobileJson({ ok: false, error: 'unauthorized' }, 401);
	if (!isMobileAccountOperation(event.params.operation)) {
		return mobileJson({ ok: false, error: 'not_found' }, 404);
	}

	const rateLimit = await consumeSecurityRateLimit(
		accountSecurityRateLimitBuckets(
			event.params.operation,
			mobileSession.user.id,
			event.getClientAddress()
		)
	);
	if (!rateLimit.allowed) {
		return mobileJson(
			{
				ok: false,
				error: 'rate_limited',
				message: 'Too many account-security requests. Wait before trying again.'
			},
			429,
			{ 'Retry-After': String(rateLimit.retryAfterSeconds) }
		);
	}
	if (
		freshSessionOperations.has(event.params.operation) &&
		!isFreshAuthSession(mobileSession.session.createdAt)
	) {
		return freshSessionRequired();
	}

	const parsedBody = await parseBody(event.request);
	if (!parsedBody.ok) return parsedBody.response;

	switch (event.params.operation) {
		case 'request-password-reset': {
			if (!emptyBody.safeParse(parsedBody.value).success) return invalidRequest();
			const resetResult = await requestPasswordReset(
				mobileSession.user.email,
				event.url.origin,
				event.getClientAddress()
			);
			if (resetResult === 'rate_limited') {
				return mobileJson(
					{
						ok: false,
						error: 'rate_limited',
						message:
							'If password reset is available for this account, instructions will be sent by email.'
					},
					429,
					{ 'Retry-After': '60' }
				);
			}
			return mobileJson(
				{
					ok: true,
					message:
						'If password reset is available for this account, instructions will be sent by email.'
				},
				202
			);
		}
		case 'change-password': {
			const parsed = changePasswordBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			try {
				const result = await auth.api.changePassword({
					body: {
						currentPassword: parsed.data.currentPassword,
						newPassword: parsed.data.newPassword,
						revokeOtherSessions: true
					},
					headers: event.request.headers,
					returnHeaders: true
				});
				await revokeTrustedDevices(mobileSession.user.id);
				const sessionToken = await validateMobileReplacementToken(
					result.headers,
					mobileSession.user.id,
					mobileSession.session.id
				);
				if (!sessionToken) return replacementSessionFailure();
				return mobileJson({
					ok: true,
					sessionToken,
					message: 'Password changed. Other sessions were ended.'
				});
			} catch (error) {
				return betterAuthFailure(error, 'The password could not be changed.');
			}
		}
		case 'enable-two-factor': {
			if (mobileSession.user.twoFactorEnabled) return twoFactorStateConflict();
			const parsed = passwordBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			try {
				const result = await auth.api.enableTwoFactor({
					body: { password: parsed.data.password },
					headers: event.request.headers
				});
				await revokeTrustedDevices(mobileSession.user.id);
				return mobileJson({
					ok: true,
					totpUri: result.totpURI,
					message: 'Authenticator setup started.'
				});
			} catch (error) {
				return betterAuthFailure(error, 'Authenticator setup could not be started.');
			}
		}
		case 'verify-two-factor-setup': {
			if (mobileSession.user.twoFactorEnabled) return twoFactorStateConflict();
			const parsed = verifyTotpSetupBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			try {
				const result = await auth.api.verifyTOTP({
					body: { code: parsed.data.code },
					headers: event.request.headers,
					returnHeaders: true
				});
				const sessionToken = await validateMobileReplacementToken(
					result.headers,
					mobileSession.user.id,
					mobileSession.session.id
				);
				if (!sessionToken) return replacementSessionFailure();
				const recovery = await auth.api.viewBackupCodes({
					body: { userId: mobileSession.user.id }
				});
				return mobileJson({
					ok: true,
					sessionToken,
					recoveryCodes: recovery.backupCodes,
					message: 'Two-factor authentication enabled. Save the recovery codes now.'
				});
			} catch (error) {
				return betterAuthFailure(error, 'The authenticator code could not be verified.');
			}
		}
		case 'disable-two-factor': {
			if (!mobileSession.user.twoFactorEnabled) return twoFactorStateConflict();
			const parsed = passwordBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			try {
				const result = await auth.api.disableTwoFactor({
					body: { password: parsed.data.password },
					headers: event.request.headers,
					returnHeaders: true
				});
				await revokeTrustedDevices(mobileSession.user.id);
				const sessionToken = await validateMobileReplacementToken(
					result.headers,
					mobileSession.user.id,
					mobileSession.session.id
				);
				if (!sessionToken) return replacementSessionFailure();
				return mobileJson({
					ok: true,
					sessionToken,
					message: 'Two-factor authentication disabled.'
				});
			} catch (error) {
				return betterAuthFailure(error, 'Two-factor authentication could not be disabled.');
			}
		}
		case 'regenerate-recovery-codes': {
			if (!mobileSession.user.twoFactorEnabled) return twoFactorStateConflict();
			const parsed = passwordBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			try {
				const accounts = await auth.api.listUserAccounts({
					headers: event.request.headers
				});
				if (!accounts.some((account) => account.providerId === 'credential')) {
					return localPasswordRequired();
				}
				const result = await auth.api.generateBackupCodes({
					body: { password: parsed.data.password },
					headers: event.request.headers
				});
				const recoveryCodes = generatedRecoveryCodes.safeParse(result.backupCodes);
				if (!recoveryCodes.success) return recoveryCodeGenerationFailure();
				return mobileJson({
					ok: true,
					recoveryCodes: recoveryCodes.data,
					message:
						'Recovery codes replaced. Save the new codes now; every previous code has stopped working.'
				});
			} catch (error) {
				return betterAuthFailure(error, 'Recovery codes could not be replaced.');
			}
		}
		case 'revoke-session': {
			const parsed = revokeSessionBody.safeParse(parsedBody.value);
			if (!parsed.success) return invalidRequest();
			if (parsed.data.sessionId === mobileSession.session.id) {
				return mobileJson(
					{
						ok: false,
						error: 'current_session',
						message: 'Use Sign out to end this Android session.'
					},
					409
				);
			}
			const [target] = await db
				.select({ token: authSession.token })
				.from(authSession)
				.where(
					and(
						eq(authSession.id, parsed.data.sessionId),
						eq(authSession.userId, mobileSession.user.id)
					)
				)
				.limit(1);
			if (target) {
				await auth.api.revokeSession({
					body: { token: target.token },
					headers: event.request.headers
				});
			}
			return mobileJson({ ok: true, message: 'Session ended.' });
		}
		case 'delete-account': {
			const parsed = deleteAccountBody.safeParse(parsedBody.value);
			if (!parsed.success) {
				return mobileJson(
					{
						ok: false,
						error: 'confirmation_required',
						message: 'Type DELETE exactly to confirm account deletion.'
					},
					400
				);
			}
			try {
				await auth.api.deleteUser({ body: {}, headers: event.request.headers });
			} catch (error) {
				return betterAuthFailure(error, 'The account could not be deleted.');
			}
			return mobileJson({ ok: true, accountDeleted: true, message: 'Account deleted.' });
		}
	}
};

function isMobileAccountOperation(input: string): input is MobileAccountOperation {
	return (
		input === 'request-password-reset' ||
		input === 'change-password' ||
		input === 'enable-two-factor' ||
		input === 'verify-two-factor-setup' ||
		input === 'disable-two-factor' ||
		input === 'regenerate-recovery-codes' ||
		input === 'revoke-session' ||
		input === 'delete-account'
	);
}

async function parseBody(
	request: Request
): Promise<{ ok: true; value: unknown } | { ok: false; response: Response }> {
	const body = await readBoundedRequestBody(request, maximumBodyBytes);
	if (body.result === 'too-large') {
		return {
			ok: false,
			response: mobileJson(
				{ ok: false, error: 'too_large', message: 'Request body is too large.' },
				413
			)
		};
	}
	if (body.result !== 'ok') return { ok: false, response: invalidRequest() };
	try {
		const value: unknown = JSON.parse(body.buffer.toString('utf8'));
		if (typeof value !== 'object' || value === null || Array.isArray(value)) {
			return { ok: false, response: invalidRequest() };
		}
		return { ok: true, value };
	} catch {
		return { ok: false, response: invalidRequest() };
	}
}

function freshSessionRequired() {
	return mobileJson(
		{
			ok: false,
			error: 'fresh_session_required',
			message: 'Sign out and sign in again before making this account-security change.'
		},
		403
	);
}

function invalidRequest() {
	return mobileJson(
		{ ok: false, error: 'invalid_request', message: 'The account-security request is invalid.' },
		400
	);
}

function twoFactorStateConflict() {
	return mobileJson(
		{
			ok: false,
			error: 'two_factor_state_conflict',
			message: 'Refresh account security before changing authenticator setup.'
		},
		409
	);
}

function replacementSessionFailure() {
	return mobileJson(
		{
			ok: false,
			error: 'session_replacement_failed',
			message:
				'The security change completed, but the Android session could not be refreshed. Sign in again.'
		},
		500
	);
}

function localPasswordRequired() {
	return mobileJson(
		{
			ok: false,
			error: 'local_password_required',
			message: 'Recovery codes can only be replaced for a local password account.'
		},
		409
	);
}

function recoveryCodeGenerationFailure() {
	return mobileJson(
		{
			ok: false,
			error: 'recovery_code_generation_failed',
			message:
				'Recovery codes were replaced, but the new set could not be returned safely. Replace them again.'
		},
		500
	);
}

function betterAuthFailure(error: unknown, message: string) {
	if (error instanceof APIError && (error.statusCode === 401 || error.statusCode === 403)) {
		return freshSessionRequired();
	}
	return mobileJson(
		{ ok: false, error: 'account_security_failed', message },
		error instanceof APIError ? 400 : 500
	);
}

function mobileJson(
	body: Record<string, unknown>,
	status = 200,
	extraHeaders: Record<string, string> = {}
) {
	return json(body, {
		status,
		headers: {
			'Cache-Control': 'private, no-store',
			Vary: 'Authorization, X-Runway-Client',
			...extraHeaders
		}
	});
}
