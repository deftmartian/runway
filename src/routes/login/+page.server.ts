import { fail, redirect } from '@sveltejs/kit';
import { APIError } from 'better-auth/api';
import { env } from '$env/dynamic/private';
import { auth } from '$lib/server/auth';
import {
	consumeSecurityRateLimit,
	oidcSignInRateLimitBuckets,
	signInRateLimitBuckets,
	signUpRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import {
	authEmailSchema,
	authPasswordSchema,
	formString,
	newPasswordSchema
} from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = (event) => {
	if (event.locals.user) {
		throw redirect(302, '/app');
	}

	return {
		oidcConfigured: Boolean(
			env['OIDC_ISSUER'] && env['OIDC_CLIENT_ID'] && env['OIDC_CLIENT_SECRET']
		),
		localAuthEnabled: env['LOCAL_AUTH_ENABLED'] !== 'false',
		localSignupsEnabled: env['ALLOW_LOCAL_SIGNUPS'] === 'true',
		shareSignInRequired: event.url.searchParams.get('share') === 'sign-in-required'
	};
};

export const actions: Actions = {
	signInEmail: async (event) => {
		const formData = await event.request.formData();
		const email = formString(formData, 'email');
		const password = formString(formData, 'password');
		const parsedEmail = authEmailSchema.safeParse(email);
		const parsedPassword = authPasswordSchema.safeParse(password);
		if (!parsedEmail.success || !parsedPassword.success) {
			return fail(400, { scope: 'signInEmail', message: 'Email or password is not correct.' });
		}
		const rateLimit = await consumeSecurityRateLimit(
			signInRateLimitBuckets(parsedEmail.data, event.getClientAddress())
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'signInEmail',
				message: 'Too many sign-in attempts. Try again later.'
			});
		}

		let result: unknown;
		try {
			result = await auth.api.signInEmail({
				body: { email: parsedEmail.data, password: parsedPassword.data, callbackURL: '/app' },
				headers: event.request.headers
			});
		} catch (error) {
			if (isRedirect(error)) throw error;
			return authFailure(error, 'signInEmail', 'Email or password is not correct.');
		}

		if (requiresTwoFactor(result)) {
			throw redirect(302, '/login/two-factor');
		}

		throw redirect(302, '/app');
	},
	signUpEmail: async (event) => {
		const formData = await event.request.formData();
		const email = formString(formData, 'email');
		const password = formString(formData, 'password');
		const submittedName = formString(formData, 'name');
		const parsedEmail = authEmailSchema.safeParse(email);
		const parsedPassword = newPasswordSchema.safeParse(password);
		if (!parsedEmail.success) {
			return fail(400, {
				scope: 'signUpEmail',
				message: parsedEmail.error.issues[0]?.message ?? 'Enter a valid email.'
			});
		}
		if (!parsedPassword.success) {
			return fail(400, {
				scope: 'signUpEmail',
				message: parsedPassword.error.issues[0]?.message ?? 'Choose a valid password.'
			});
		}
		const name = submittedName.trim() || parsedEmail.data;
		if (name.length > 100) {
			return fail(400, {
				scope: 'signUpEmail',
				message: 'Name must be no more than 100 characters.'
			});
		}
		const rateLimit = await consumeSecurityRateLimit(
			signUpRateLimitBuckets(parsedEmail.data, event.getClientAddress())
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'signUpEmail',
				message: 'Too many account-creation attempts. Try again later.'
			});
		}

		try {
			await auth.api.signUpEmail({
				body: {
					email: parsedEmail.data,
					password: parsedPassword.data,
					name,
					callbackURL: '/app'
				},
				headers: event.request.headers
			});
		} catch (error) {
			return authFailure(error, 'signUpEmail', 'Account could not be created.');
		}

		throw redirect(302, '/app');
	},
	signInOidc: async (event) => {
		const rateLimit = await consumeSecurityRateLimit(
			oidcSignInRateLimitBuckets(event.getClientAddress())
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'signInOidc',
				message: 'Too many sign-in attempts. Try again later.'
			});
		}
		try {
			const result = await auth.api.signInWithOAuth2({
				body: { providerId: 'authentik', callbackURL: '/app' },
				headers: event.request.headers
			});
			if ('url' in result && result.url) {
				throw redirect(302, result.url);
			}
		} catch (error) {
			if (isRedirect(error)) throw error;
			return authFailure(error, 'signInOidc', 'OIDC sign in is not available.');
		}

		return fail(400, {
			scope: 'signInOidc',
			message: 'OIDC sign in did not return a redirect URL.'
		});
	}
};

function authFailure(
	error: unknown,
	scope: 'signInEmail' | 'signUpEmail' | 'signInOidc',
	fallback: string
) {
	if (error instanceof APIError) {
		const message = normalizeAuthError(error, fallback);
		return fail(error.statusCode || 400, { scope, message });
	}
	return fail(500, { scope, message: fallback });
}

function normalizeAuthError(error: APIError, fallback: string): string {
	const raw = `${error.message ?? ''} ${error.body?.code ?? ''}`.toLowerCase();
	if (raw.includes('invalid') || raw.includes('password') || raw.includes('credential')) {
		return fallback;
	}
	return fallback;
}

function isRedirect(error: unknown): boolean {
	return (
		typeof error === 'object' &&
		error !== null &&
		'status' in error &&
		'location' in error &&
		Number(error.status) >= 300 &&
		Number(error.status) < 400
	);
}

function requiresTwoFactor(result: unknown): boolean {
	return (
		typeof result === 'object' &&
		result !== null &&
		'twoFactorRedirect' in result &&
		result.twoFactorRedirect === true
	);
}
