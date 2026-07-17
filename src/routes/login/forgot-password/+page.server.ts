import { fail } from '@sveltejs/kit';
import { requestPasswordReset } from '$lib/server/runway/password-reset';
import { authEmailSchema, formString } from '$lib/server/runway/validation';
import type { Actions } from './$types';

const genericMessage = 'If that email belongs to a local account, a reset link will be sent.';

export const actions: Actions = {
	requestReset: async (event) => {
		const formData = await event.request.formData();
		const email = formString(formData, 'email').trim();
		const parsedEmail = authEmailSchema.safeParse(email);
		if (!parsedEmail.success) {
			return fail(400, {
				message: parsedEmail.error.issues[0]?.message ?? 'Enter a valid email address.'
			});
		}

		let result;
		try {
			result = await requestPasswordReset(
				parsedEmail.data,
				event.url.origin,
				event.getClientAddress()
			);
		} catch {
			return fail(502, {
				message: 'Password reset could not be started. Try again later or contact the operator.'
			});
		}

		if (result === 'rate_limited') {
			return fail(429, {
				message: 'Too many reset requests. Wait a few minutes before trying again.'
			});
		}

		if (result === 'email_not_configured') {
			return fail(503, {
				message: 'Password reset email is not available yet. Ask the workspace owner for help.'
			});
		}

		return { message: genericMessage };
	}
};
