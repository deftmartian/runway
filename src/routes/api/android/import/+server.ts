import { createHash } from 'node:crypto';
import { json } from '@sveltejs/kit';
import {
	abandonAndroidImportRequest,
	authenticateAndroidDevice,
	claimAndroidImportRequest,
	completeAndroidImportRequest,
	type AndroidImportResult
} from '$lib/server/runway/android-devices';
import { readBoundedRequestBody } from '$lib/server/runway/bounded-request-body';
import {
	importGpxIntoReviewInbox,
	maxGpxImportBytes,
	type GpxReviewImportResult
} from '$lib/server/runway/gpx-review-import';
import { getActivityImportGeneration } from '$lib/server/runway/repository';
import {
	androidApiDeviceRateLimitBuckets,
	androidApiPreAuthRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import type { RequestHandler } from './$types';

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const sha256Pattern = /^[0-9a-f]{64}$/;

export const POST: RequestHandler = async (event) => {
	if (event.request.headers.get('x-runway-client') !== 'runway-android/1') {
		return json({ result: 'unsupported-client' }, { status: 400 });
	}
	const preAuthLimit = await consumeSecurityRateLimit(
		androidApiPreAuthRateLimitBuckets(event.getClientAddress(), 'import')
	);
	if (!preAuthLimit.allowed) return rateLimited(preAuthLimit.retryAfterSeconds);

	const device = await authenticateAndroidDevice(event.request.headers.get('authorization'));
	if (!device) return json({ result: 'unauthorized' }, { status: 401 });
	const deviceLimit = await consumeSecurityRateLimit(
		androidApiDeviceRateLimitBuckets(device.id, 'import')
	);
	if (!deviceLimit.allowed) return rateLimited(deviceLimit.retryAfterSeconds);

	if (event.request.headers.get('content-encoding')) {
		return json({ result: 'unsupported' }, { status: 415 });
	}
	const contentType = event.request.headers.get('content-type')?.toLowerCase() ?? '';
	if (
		!['application/gpx+xml', 'application/x-gpx+xml'].some(
			(allowed) => contentType === allowed || contentType.startsWith(`${allowed};`)
		)
	) {
		return json({ result: 'unsupported' }, { status: 415 });
	}
	const requestId = event.request.headers.get('x-runway-request-id')?.trim() ?? '';
	const contentDigest =
		event.request.headers.get('x-runway-content-sha256')?.trim().toLowerCase() ?? '';
	if (!uuidPattern.test(requestId) || !sha256Pattern.test(contentDigest)) {
		return json({ result: 'invalid-headers' }, { status: 400 });
	}

	const claim = await claimAndroidImportRequest(device, requestId, contentDigest);
	if (claim.state === 'revoked') {
		return json({ result: 'unauthorized' }, { status: 401 });
	}
	if (claim.state === 'conflict') {
		return json({ result: 'request-conflict', requestId }, { status: 409 });
	}
	if (claim.state === 'processing') {
		return json(
			{ result: 'retryable', requestId },
			{ status: 409, headers: { 'Retry-After': '5' } }
		);
	}
	if (claim.state === 'completed') {
		return androidImportResponse(requestId, claim.result, claim.reason, true);
	}

	const importGeneration = await getActivityImportGeneration(device.userId);
	const body = await readBoundedRequestBody(event.request, maxGpxImportBytes);
	if (body.result !== 'ok') {
		const reason = body.result === 'too-large' ? 'too-large' : 'invalid';
		if (!(await completeAndroidImportRequest(device, claim.receiptId, 'quarantined', reason))) {
			return revokedDuringImport(requestId);
		}
		return androidImportResponse(requestId, 'quarantined', reason, false);
	}
	const actualDigest = createHash('sha256').update(body.buffer).digest('hex');
	if (actualDigest !== contentDigest) {
		if (
			!(await completeAndroidImportRequest(
				device,
				claim.receiptId,
				'quarantined',
				'digest-mismatch'
			))
		) {
			return revokedDuringImport(requestId);
		}
		return androidImportResponse(requestId, 'quarantined', 'digest-mismatch', false);
	}

	const imported = await importGpxIntoReviewInbox(device.userId, body.buffer, importGeneration);
	const mapped = mapImportResult(imported);
	if (mapped.result === 'retryable') {
		await abandonAndroidImportRequest(device, claim.receiptId);
		return json(
			{ result: 'retryable', requestId, reason: mapped.reason },
			{
				status: mapped.reason === 'time-zone-required' ? 409 : 503,
				headers: { 'Retry-After': '60' }
			}
		);
	}
	if (
		!(await completeAndroidImportRequest(device, claim.receiptId, mapped.result, mapped.reason))
	) {
		return revokedDuringImport(requestId);
	}
	return androidImportResponse(requestId, mapped.result, mapped.reason, false);
};

function mapImportResult(
	result: GpxReviewImportResult
):
	| { result: AndroidImportResult; reason: string | null }
	| { result: 'retryable'; reason: 'time-zone-required' | 'server-error' } {
	switch (result.result) {
		case 'imported':
			return { result: 'imported', reason: null };
		case 'duplicate':
			return { result: 'duplicate', reason: null };
		case 'deleted':
			return { result: 'duplicate', reason: 'deleted' };
		case 'future':
		case 'invalid':
		case 'too-large':
			return { result: 'quarantined', reason: result.result };
		case 'time-zone-required':
			return { result: 'retryable', reason: 'time-zone-required' };
		case 'failed':
			return { result: 'retryable', reason: 'server-error' };
	}
}

function androidImportResponse(
	requestId: string,
	result: AndroidImportResult,
	reason: string | null,
	replayed: boolean
) {
	const status = result === 'imported' ? 201 : result === 'duplicate' ? 200 : 422;
	return json({ result, requestId, reason, replayed }, { status });
}

function rateLimited(retryAfterSeconds: number) {
	return json(
		{ result: 'rate-limited' },
		{ status: 429, headers: { 'Retry-After': String(retryAfterSeconds) } }
	);
}

function revokedDuringImport(requestId: string) {
	return json(
		{ result: 'retryable', requestId, reason: 'device-revoked' },
		{ status: 409, headers: { 'Retry-After': '5' } }
	);
}
