import { requireUser } from '$lib/server/runway/auth';
import type { LayoutServerLoad } from './$types';

export const load: LayoutServerLoad = (event) => {
	const user = requireUser(event);
	return { user };
};
