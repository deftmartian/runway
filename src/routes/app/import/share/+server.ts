import { redirect } from '@sveltejs/kit';
import {
	importGpxIntoReviewInbox,
	maxGpxImportBytes,
	maxGpxMultipartOverheadBytes
} from '$lib/server/runway/gpx-review-import';
import { getActivityImportGeneration } from '$lib/server/runway/repositories/profiles';
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
	// Authenticate before reading a private route file into memory. A share made
	// while signed out cannot be recovered safely, so the user must share again.
	if (!event.locals.user) throw redirect(303, '/login?share=sign-in-required');
	const userId = event.locals.user.id;
	const rateLimit = await consumeSecurityRateLimit(
		gpxImportRateLimitBuckets(userId, event.getClientAddress())
	);
	if (!rateLimit.allowed) return shareRateLimited(rateLimit.retryAfterSeconds);
	try {
		return await withUserImportOperationLease(userId, 'share-target-gpx', () =>
			handleSharedGpx(event.request, userId)
		);
	} catch (error) {
		if (error instanceof ImportOperationBusyError) {
			return shareRateLimited(error.retryAfterSeconds);
		}
		throw error;
	}
};

async function handleSharedGpx(request: Request, userId: string): Promise<never> {
	// Capture this before reading the multipart body. A privacy deletion that
	// races a large or slow share must invalidate the in-flight import.
	const importGeneration = await getActivityImportGeneration(userId);

	const declaredLength = Number(request.headers.get('content-length'));
	if (
		Number.isFinite(declaredLength) &&
		declaredLength > maxGpxImportBytes + maxGpxMultipartOverheadBytes
	) {
		throw shareRedirect('too-large');
	}

	let formData: FormData;
	try {
		formData = await request.formData();
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
	// hash. Raw bytes are discarded; bounded traces follow the saved privacy mode.
	const buffer = Buffer.from(await file.arrayBuffer());
	const result = await importGpxIntoReviewInbox(userId, buffer, importGeneration);
	throw shareRedirect(result.result);
}

function shareRedirect(result: string) {
	return redirect(303, `/app/import?share=${result}`);
}

function shareRateLimited(retryAfterSeconds: number) {
	return new Response(null, {
		status: 303,
		headers: {
			Location: '/app/import?share=busy',
			'Retry-After': String(retryAfterSeconds)
		}
	});
}
