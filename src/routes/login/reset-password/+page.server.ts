import { fail, redirect } from '@sveltejs/kit';
import {
	isPasswordResetTokenUsable,
	resetPasswordWithToken
} from '$lib/server/runway/password-reset';
import {
	consumeSecurityRateLimit,
	passwordResetAttemptRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import { formString, newPasswordSchema } from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

const resetTokenCookie = 'runway_reset_token';
const resetErrorCookie = 'runway_reset_error';
const resetTokenPattern = /^[A-Za-z0-9_-]{43}$/;

export const load: PageServerLoad = async (event) => {
	const token = event.url.searchParams.get('token');
	if (token) {
		if (!resetTokenPattern.test(token)) {
			event.cookies.delete(resetTokenCookie, { path: '/login/reset-password' });
			event.cookies.set(resetErrorCookie, 'invalid', resetErrorCookieOptions(event.url));
			throw redirect(303, '/login/reset-password');
		}
		event.cookies.delete(resetErrorCookie, { path: '/login/reset-password' });
		event.cookies.set(resetTokenCookie, token, resetCookieOptions(event.url));
		throw redirect(303, '/login/reset-password');
	}

	if (event.cookies.get(resetErrorCookie)) {
		event.cookies.delete(resetErrorCookie, { path: '/login/reset-password' });
		return {
			hasToken: false,
			message: 'That reset link is invalid, expired, or already used.'
		};
	}

	const cookieToken = event.cookies.get(resetTokenCookie)?.trim();
	if (
		cookieToken &&
		(!resetTokenPattern.test(cookieToken) || !(await isPasswordResetTokenUsable(cookieToken)))
	) {
		event.cookies.delete(resetTokenCookie, { path: '/login/reset-password' });
		return {
			hasToken: false,
			message: 'That reset link is invalid, expired, or already used.'
		};
	}

	return {
		hasToken: Boolean(cookieToken)
	};
};

export const actions: Actions = {
	resetPassword: async (event) => {
		const formData = await event.request.formData();
		const token = event.cookies.get(resetTokenCookie)?.trim() || '';
		const password = formString(formData, 'password');
		const confirmPassword = formString(formData, 'confirmPassword');

		if (!resetTokenPattern.test(token)) {
			return fail(400, { message: 'The reset link is missing or invalid.' });
		}
		const parsedPassword = newPasswordSchema.safeParse(password);
		if (!parsedPassword.success) {
			return fail(400, {
				message: parsedPassword.error.issues[0]?.message ?? 'Choose a valid password.'
			});
		}
		if (password !== confirmPassword) {
			return fail(400, { message: 'The two passwords do not match.' });
		}
		const rateLimit = await consumeSecurityRateLimit(
			passwordResetAttemptRateLimitBuckets(event.getClientAddress())
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, { message: 'Too many reset attempts. Try again later.' });
		}

		const result = await resetPasswordWithToken(token, parsedPassword.data);
		if (result !== 'reset') {
			event.cookies.delete(resetTokenCookie, { path: '/login/reset-password' });
			return fail(400, { message: 'That reset link is invalid, expired, or already used.' });
		}

		event.cookies.delete(resetTokenCookie, { path: '/login/reset-password' });
		return { message: 'Password changed. Sign in with the new password.', resetComplete: true };
	}
};

function resetCookieOptions(url: URL) {
	return {
		httpOnly: true,
		maxAge: 30 * 60,
		path: '/login/reset-password',
		sameSite: 'lax' as const,
		secure: url.protocol === 'https:'
	};
}

function resetErrorCookieOptions(url: URL) {
	return {
		httpOnly: true,
		maxAge: 60,
		path: '/login/reset-password',
		sameSite: 'lax' as const,
		secure: url.protocol === 'https:'
	};
}
