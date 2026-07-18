import { json } from '@sveltejs/kit';
import { revokeBrowserFolderImports } from '$lib/server/runway/repositories/browser-folder-import';
import type { RequestHandler } from './$types';

const privateNoStoreHeaders = { 'cache-control': 'private, no-store' };

export const DELETE: RequestHandler = async (event) => {
	if (!event.locals.user) {
		return json({ result: 'signed-out' }, { status: 401, headers: privateNoStoreHeaders });
	}
	const generation = await revokeBrowserFolderImports(event.locals.user.id);
	return json({ result: 'disconnected', generation }, { headers: privateNoStoreHeaders });
};
