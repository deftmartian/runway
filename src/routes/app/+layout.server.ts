import { requireUser } from '$lib/server/runway/auth';
import { hasPlanHistory } from '$lib/server/runway/repositories/plan-queries';
import type { LayoutServerLoad } from './$types';

export const load: LayoutServerLoad = async (event) => {
	const user = requireUser(event);
	return { user, setupComplete: await hasPlanHistory(user.id) };
};
