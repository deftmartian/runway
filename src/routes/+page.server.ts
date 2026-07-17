import { redirect } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import type { PageServerLoad } from './$types';

export const load: PageServerLoad = (event) => {
	if (event.locals.user) {
		throw redirect(302, '/app');
	}
	return {
		localSignupsEnabled: env['ALLOW_LOCAL_SIGNUPS'] === 'true'
	};
};
