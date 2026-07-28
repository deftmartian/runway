import { fail, redirect } from '@sveltejs/kit';
import { APIError } from 'better-auth/api';
import { auth } from '$lib/server/auth';
import { safeAuthReturnTo } from '$lib/server/runway/auth-return';
import {
	consumeSecurityRateLimit,
	twoFactorChallengeFromHeaders,
	twoFactorRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import { backupCodeSchema, formString, totpCodeSchema } from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = (event) => ({
	returnTo: safeAuthReturnTo(event.url.searchParams.get('returnTo'))
});

export const actions: Actions = {
	verifyTotp: async (event) => {
		const formData = await event.request.formData();
		const returnTo = safeAuthReturnTo(formString(formData, 'returnTo'));
		const code = formString(formData, 'code');
		const parsedCode = totpCodeSchema.safeParse(code);
		if (!parsedCode.success) {
			return fail(400, { scope: 'verifyTotp', message: parsedCode.error.issues[0]?.message });
		}
		const rateLimit = await consumeSecurityRateLimit(
			twoFactorRateLimitBuckets(
				'totp',
				twoFactorChallengeFromHeaders(event.request.headers),
				event.getClientAddress()
			)
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'verifyTotp',
				message: 'Too many verification attempts. Try again later.'
			});
		}
		try {
			await auth.api.verifyTOTP({
				body: { code: parsedCode.data, trustDevice: formData.has('trustDevice') },
				headers: event.request.headers
			});
		} catch (error) {
			return twoFactorFailure(error, 'verifyTotp', 'Two-factor verification failed.');
		}
		throw redirect(302, returnTo);
	},
	verifyBackupCode: async (event) => {
		const formData = await event.request.formData();
		const returnTo = safeAuthReturnTo(formString(formData, 'returnTo'));
		const code = formString(formData, 'code');
		const parsedCode = backupCodeSchema.safeParse(code);
		if (!parsedCode.success) {
			return fail(400, {
				scope: 'verifyBackupCode',
				message: parsedCode.error.issues[0]?.message
			});
		}
		const rateLimit = await consumeSecurityRateLimit(
			twoFactorRateLimitBuckets(
				'backup',
				twoFactorChallengeFromHeaders(event.request.headers),
				event.getClientAddress()
			)
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'verifyBackupCode',
				message: 'Too many verification attempts. Try again later.'
			});
		}
		try {
			await auth.api.verifyBackupCode({
				body: { code: parsedCode.data, trustDevice: formData.has('trustDevice') },
				headers: event.request.headers
			});
		} catch (error) {
			return twoFactorFailure(error, 'verifyBackupCode', 'Backup code verification failed.');
		}
		throw redirect(302, returnTo);
	}
};

function twoFactorFailure(
	error: unknown,
	scope: 'verifyTotp' | 'verifyBackupCode',
	fallback: string
) {
	if (error instanceof APIError) {
		return fail(error.statusCode || 400, { scope, message: fallback });
	}
	return fail(500, { scope, message: fallback });
}
