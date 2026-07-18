import { redirect } from '@sveltejs/kit';
import { getHistory } from '$lib/server/runway/repository';
import {
	getActivePlan,
	getPlanTrace,
	getPlanWeeks
} from '$lib/server/runway/repositories/plan-queries';
import { getAthleteTimeZone } from '$lib/server/runway/repositories/profiles';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	if (!(await getAthleteTimeZone(event.locals.user.id))) throw redirect(302, '/app/onboarding');
	const active = await getActivePlan(event.locals.user.id);
	const detail = active
		? { weeks: await getPlanWeeks(event.locals.user.id, active.plan.id) }
		: null;
	const [history, planTrace] = await Promise.all([
		getHistory(event.locals.user.id),
		active ? getPlanTrace(event.locals.user.id, active.plan.id) : Promise.resolve([])
	]);
	return { active, detail, history, planTrace };
};
