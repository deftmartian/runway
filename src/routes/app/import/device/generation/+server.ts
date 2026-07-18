import { json } from '@sveltejs/kit';
import { getBrowserFolderImportGenerations } from '$lib/server/runway/repositories/browser-folder-import';
import type { RequestHandler } from './$types';

const privateNoStoreHeaders = { 'cache-control': 'private, no-store' };

export const GET: RequestHandler = async (event) => {
	if (!event.locals.user) {
		return json({ result: 'signed-out' }, { status: 401, headers: privateNoStoreHeaders });
	}
	const generations = await getBrowserFolderImportGenerations(event.locals.user.id);
	return json(
		{ activityGeneration: generations.activity, folderGeneration: generations.folder },
		{ headers: privateNoStoreHeaders }
	);
};
