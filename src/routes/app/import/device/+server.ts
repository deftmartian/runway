import { json } from '@sveltejs/kit';
import {
	importBrowserFolderGpxIntoReviewInbox,
	maxGpxImportBytes,
	maxGpxMultipartOverheadBytes,
	type BrowserFolderGpxReviewImportResult
} from '$lib/server/runway/gpx-review-import';
import {
	ImportOperationBusyError,
	withUserImportOperationLease
} from '$lib/server/runway/import-operation-lease';
import {
	consumeSecurityRateLimit,
	gpxImportRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

export const POST: RequestHandler = async (event) => {
	if (!event.locals.user) return json({ result: 'signed-out' }, { status: 401 });
	const userId = event.locals.user.id;
	const rateLimit = await consumeSecurityRateLimit(
		gpxImportRateLimitBuckets(userId, event.getClientAddress())
	);
	if (!rateLimit.allowed) return rateLimitedResponse(rateLimit.retryAfterSeconds);
	try {
		return await withUserImportOperationLease(userId, 'browser-folder-gpx', () =>
			handleDeviceFolderUpload(event.request, userId)
		);
	} catch (error) {
		if (error instanceof ImportOperationBusyError) {
			return rateLimitedResponse(error.retryAfterSeconds);
		}
		throw error;
	}
};

async function handleDeviceFolderUpload(request: Request, userId: string) {
	const declaredLength = Number(request.headers.get('content-length'));
	if (
		Number.isFinite(declaredLength) &&
		declaredLength > maxGpxImportBytes + maxGpxMultipartOverheadBytes
	) {
		return resultResponse({ result: 'too-large' });
	}

	let formData: FormData;
	try {
		formData = await request.formData();
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
	const activityGeneration = formGeneration(formData, 'activityGeneration');
	const folderGeneration = formGeneration(formData, 'folderGeneration');
	if (activityGeneration === null || folderGeneration === null) {
		return json({ result: 'generation-required' }, { status: 400 });
	}

	const result = await importBrowserFolderGpxIntoReviewInbox(
		userId,
		Buffer.from(await files[0].arrayBuffer()),
		activityGeneration,
		folderGeneration
	);
	return resultResponse(result);
}

function formGeneration(formData: FormData, name: string): number | null {
	const values = formData.getAll(name);
	const value = values[0];
	const generation = Number(value);
	return values.length === 1 &&
		typeof value === 'string' &&
		/^\d+$/.test(value) &&
		Number.isSafeInteger(generation) &&
		generation >= 0
		? generation
		: null;
}

function resultResponse(result: BrowserFolderGpxReviewImportResult) {
	const status =
		result.result === 'imported'
			? 201
			: result.result === 'duplicate' || result.result === 'deleted'
				? 200
				: result.result === 'disconnected'
					? 409
					: result.result === 'too-large'
						? 413
						: result.result === 'time-zone-required'
							? 409
							: result.result === 'failed'
								? 500
								: 422;
	return json(result, { status });
}

function rateLimitedResponse(retryAfterSeconds: number) {
	return json(
		{ result: 'rate-limited' },
		{ status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } }
	);
}
