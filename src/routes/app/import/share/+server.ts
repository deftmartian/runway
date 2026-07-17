import { redirect } from '@sveltejs/kit';
import {
	importGpxIntoReviewInbox,
	maxGpxImportBytes,
	maxGpxMultipartOverheadBytes
} from '$lib/server/runway/gpx-review-import';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async (event) => {
	// Authenticate before reading a private route file into memory. A share made
	// while signed out cannot be recovered safely, so the user must share again.
	if (!event.locals.user) throw redirect(303, '/login?share=sign-in-required');

	const declaredLength = Number(event.request.headers.get('content-length'));
	if (
		Number.isFinite(declaredLength) &&
		declaredLength > maxGpxImportBytes + maxGpxMultipartOverheadBytes
	) {
		throw shareRedirect('too-large');
	}

	let formData: FormData;
	try {
		formData = await event.request.formData();
	} catch {
		throw shareRedirect('invalid');
	}

	const sharedFiles = formData.getAll('gpx');
	if (sharedFiles.length !== 1 || !(sharedFiles[0] instanceof File)) {
		throw shareRedirect('one-file');
	}
	const file = sharedFiles[0];
	if (file.size > maxGpxImportBytes) throw shareRedirect('too-large');

	// The buffer exists only for strict parsing and a user-scoped deduplication
	// hash. The shared importer stores aggregates, never the raw GPX or route.
	const buffer = Buffer.from(await file.arrayBuffer());
	const result = await importGpxIntoReviewInbox(event.locals.user.id, buffer);
	throw shareRedirect(result.result);
};

function shareRedirect(result: string) {
	return redirect(303, `/app/import?share=${result}`);
}
