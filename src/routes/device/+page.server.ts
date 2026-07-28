import { error, fail, redirect } from '@sveltejs/kit';
import { APIError } from 'better-auth/api';
import { auth } from '$lib/server/auth';
import { safeAuthReturnTo } from '$lib/server/runway/auth-return';
import { formString } from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	const returnTo = safeAuthReturnTo(
		`/device?user_code=${event.url.searchParams.get('user_code') ?? ''}`
	);
	if (returnTo === '/app') throw error(400, 'This Android sign-in request is not valid.');
	if (!event.locals.user) {
		throw redirect(302, `/login?returnTo=${encodeURIComponent(returnTo)}`);
	}
	const userCode = new URL(returnTo, event.url.origin).searchParams.get('user_code');
	if (!userCode) throw error(400, 'This Android sign-in request is not valid.');
	try {
		const result = await auth.api.deviceVerify({
			query: { user_code: userCode },
			headers: event.request.headers
		});
		return {
			userCode,
			status: result.status,
			accountName: event.locals.user.name || event.locals.user.email
		};
	} catch (cause) {
		if (cause instanceof APIError) {
			throw error(cause.statusCode || 400, 'This Android sign-in request expired or is invalid.');
		}
		throw cause;
	}
};

export const actions: Actions = {
	approve: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const userCode = normalizedCode(formString(await event.request.formData(), 'userCode'));
		if (!userCode) return fail(400, { message: 'This Android sign-in request is invalid.' });
		try {
			await auth.api.deviceApprove({
				body: { userCode },
				headers: event.request.headers
			});
			return { approved: true, message: 'Android signed in. You can return to runway.' };
		} catch (cause) {
			return deviceActionFailure(cause);
		}
	},
	deny: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const userCode = normalizedCode(formString(await event.request.formData(), 'userCode'));
		if (!userCode) return fail(400, { message: 'This Android sign-in request is invalid.' });
		try {
			await auth.api.deviceDeny({
				body: { userCode },
				headers: event.request.headers
			});
			return { denied: true, message: 'Android sign-in denied.' };
		} catch (cause) {
			return deviceActionFailure(cause);
		}
	}
};

function normalizedCode(input: string): string | null {
	const value = input.replaceAll('-', '').trim().toUpperCase();
	return /^[A-HJ-NP-Z2-9]{8}$/.test(value) ? value : null;
}

function deviceActionFailure(cause: unknown) {
	if (cause instanceof APIError) {
		return fail(cause.statusCode || 400, {
			message: 'This Android sign-in request expired or was already handled.'
		});
	}
	return fail(500, { message: 'Android sign-in could not be updated.' });
}
