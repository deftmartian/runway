import { redirect } from '@sveltejs/kit';
import { getHistory } from '$lib/server/runway/repositories/history';
import { getPlanTrace, getPlanWeeks } from '$lib/server/runway/repositories/plan-queries';
import { getTrainingReadContext } from '$lib/server/runway/repositories/training-read-context';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const context = await getTrainingReadContext(event.locals.user.id);
	if (!context.timeZone) throw redirect(302, '/app/onboarding');
	const active = context.activePlan;
	const [weeks, history, planTrace] = await Promise.all([
		active ? getPlanWeeks(event.locals.user.id, active.plan.id) : Promise.resolve(null),
		getHistory(event.locals.user.id, context),
		active ? getPlanTrace(event.locals.user.id, active.plan.id) : Promise.resolve([])
	]);
	return { active, detail: weeks ? { weeks } : null, history, planTrace };
};
