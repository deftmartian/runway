import { error, redirect } from '@sveltejs/kit';
import { getPlanDetail } from '$lib/server/runway/repository';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const detail = await getPlanDetail(event.locals.user.id, event.params.planId);
	if (!detail) throw error(404, 'Plan record not found.');
	return { detail };
};
