import { json } from '@sveltejs/kit';
import {
	importGpxIntoReviewInbox,
	maxGpxImportBytes,
	maxGpxMultipartOverheadBytes,
	type GpxReviewImportResult
} from '$lib/server/runway/gpx-review-import';
import { getActivityImportGeneration } from '$lib/server/runway/repository';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async (event) => {
	if (!event.locals.user) return json({ result: 'signed-out' }, { status: 401 });
	const importGeneration = await getActivityImportGeneration(event.locals.user.id);

	const declaredLength = Number(event.request.headers.get('content-length'));
	if (
		Number.isFinite(declaredLength) &&
		declaredLength > maxGpxImportBytes + maxGpxMultipartOverheadBytes
	) {
		return resultResponse({ result: 'too-large' });
	}

	let formData: FormData;
	try {
		formData = await event.request.formData();
	} catch {
		return resultResponse({ result: 'invalid' });
	}

	const files = formData.getAll('gpx');
	if (files.length !== 1 || !(files[0] instanceof File)) {
		return json({ result: 'one-file' }, { status: 400 });
	}
	if (files[0].size > maxGpxImportBytes) {
		return resultResponse({ result: 'too-large' });
	}

	const result = await importGpxIntoReviewInbox(
		event.locals.user.id,
		Buffer.from(await files[0].arrayBuffer()),
		importGeneration
	);
	return resultResponse(result);
};

function resultResponse(result: GpxReviewImportResult) {
	const status =
		result.result === 'imported'
			? 201
			: result.result === 'duplicate' || result.result === 'deleted'
				? 200
				: result.result === 'too-large'
					? 413
					: result.result === 'time-zone-required'
						? 409
						: result.result === 'failed'
							? 500
							: 422;
	return json(result, { status });
}
