import { fail, redirect } from '@sveltejs/kit';
import { createPlanFromGoalSetup, getGoalSetupView } from '$lib/server/runway/plan-setup';
import { parseGoalSetupForm } from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	return getGoalSetupView(event.locals.user.id);
};

export const actions: Actions = {
	createPlan: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsedForm = parseGoalSetupForm(await event.request.formData());
		const result = await createPlanFromGoalSetup(
			event.locals.user.id,
			parsedForm.values,
			parsedForm.fieldErrors
		);
		if (!result.ok) {
			return fail(400, {
				message: result.message,
				values: result.values,
				fieldErrors: result.fieldErrors
			});
		}
		if (result.planPending) throw redirect(303, '/app/onboarding?pending=1');
		throw redirect(303, '/app');
	}
};
