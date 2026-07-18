import { fail, redirect } from '@sveltejs/kit';
import {
	archiveActivePlan,
	completeActivePlan,
	confirmPhaseBaseline,
	continueBeginnerPhase,
	getPhaseCompletionReview
} from '$lib/server/runway/repositories/plan-lifecycle';
import { listPlanHistory } from '$lib/server/runway/repositories/plan-queries';
import { getAthleteTimeZone } from '$lib/server/runway/repositories/profiles';
import type { Actions, PageServerLoad } from './$types';

const PAGE_SIZE = 20;

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	if (!(await getAthleteTimeZone(event.locals.user.id))) throw redirect(302, '/app/onboarding');
	const offset = validOffset(event.url.searchParams.get('offset'));
	const [history, current, phaseReview] = await Promise.all([
		listPlanHistory(event.locals.user.id, { limit: PAGE_SIZE, offset }),
		offset === 0
			? Promise.resolve(null)
			: listPlanHistory(event.locals.user.id, { limit: 1, offset: 0 }),
		offset === 0 ? getPhaseCompletionReview(event.locals.user.id) : Promise.resolve(null)
	]);
	const firstItem = (current ?? history).items[0] ?? null;

	return {
		history,
		activeItem: firstItem?.plan.status === 'active' ? firstItem : null,
		phaseReview,
		offset,
		pageSize: PAGE_SIZE
	};
};

export const actions: Actions = {
	completePlan: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		if (formData.get('confirmLifecycle') !== 'on') {
			return fail(400, { error: 'Confirm that this plan reached its end before closing it.' });
		}

		try {
			const result = await completeActivePlan(event.locals.user.id);
			if (!result) return fail(404, { error: 'There is no active plan to complete.' });
			return { message: 'Plan completed. Its workouts and recorded runs remain in History.' };
		} catch (error) {
			return fail(400, { error: lifecycleError(error, 'The plan could not be completed.') });
		}
	},
	confirmPhaseBaseline: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		if (formData.get('confirmBaseline') !== 'on') {
			return fail(400, { error: 'Confirm the recorded values before building the race phase.' });
		}
		try {
			await confirmPhaseBaseline(event.locals.user.id);
			return { message: 'Recorded baseline confirmed. The race phase is now active.' };
		} catch (error) {
			return fail(400, { error: lifecycleError(error, 'The race phase could not be created.') });
		}
	},
	continuePhase: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		if (formData.get('confirmContinuation') !== 'on') {
			return fail(400, { error: 'Confirm that you want to repeat the latest beginner week.' });
		}
		try {
			await continueBeginnerPhase(event.locals.user.id);
			return {
				message: 'One more beginner week was added. The recorded baseline was not changed.'
			};
		} catch (error) {
			return fail(400, {
				error: lifecycleError(error, 'The beginner phase could not be continued.')
			});
		}
	},
	archivePlan: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		if (formData.get('confirmLifecycle') !== 'on') {
			return fail(400, { error: 'Confirm that you want to stop this plan.' });
		}

		try {
			const result = await archiveActivePlan(event.locals.user.id, 'abandoned');
			if (!result) return fail(404, { error: 'There is no active plan to stop.' });
			return { message: 'Plan stopped. Its workouts and recorded runs remain in History.' };
		} catch (error) {
			return fail(400, { error: lifecycleError(error, 'The plan could not be stopped.') });
		}
	}
};

function validOffset(value: string | null): number {
	const offset = Number(value ?? 0);
	return Number.isSafeInteger(offset) && offset >= 0 ? offset : 0;
}

function lifecycleError(error: unknown, fallback: string): string {
	const message = error instanceof Error ? error.message : '';
	return message === 'The active plan cannot be completed before its target date.' ||
		message === 'Confirm the recorded baseline before starting the retained race goal.' ||
		message === 'The recorded work does not support this race ramp yet.'
		? message
		: fallback;
}
