import { hashActivityFile, parseGpx } from '$lib/training/gpx';
export { maxGpxImportBytes, maxGpxMultipartOverheadBytes } from '$lib/import-limits';
import { maxGpxImportBytes } from '$lib/import-limits';
import { importTimeZoneRequiredMessage } from './import-sources';
import { finalizeAndroidImportRequest, type AuthenticatedAndroidDevice } from './android-devices';
import {
	recordImportedActivity,
	recordImportedActivityInTransaction
} from './repositories/activity-mutations';
import { recordBrowserFolderImportedActivity } from './repositories/browser-folder-import';

export type GpxReviewImportResult =
	| { result: 'imported' }
	| { result: 'duplicate' }
	| { result: 'deleted' }
	| { result: 'future' }
	| { result: 'time-zone-required' }
	| { result: 'invalid' }
	| { result: 'too-large' }
	| { result: 'failed' };

export type BrowserFolderGpxReviewImportResult = GpxReviewImportResult | { result: 'disconnected' };

export type AndroidGpxReviewFinalization =
	| { state: 'completed'; result: GpxReviewImportResult }
	| { state: 'revoked' }
	| { state: 'lease-lost' }
	| { state: 'retryable'; reason: 'time-zone-required' | 'server-error' };

/**
 * Parse one bounded GPX in memory and store a review-only activity. Raw GPX
 * bytes are discarded; bounded route and heart-rate traces follow the user's
 * saved privacy setting. Browser ingestion never auto-matches or adjusts a plan.
 */
export async function importGpxIntoReviewInbox(
	userId: string,
	buffer: Buffer,
	expectedImportGeneration: number
): Promise<GpxReviewImportResult> {
	if (buffer.length === 0) return { result: 'invalid' };
	if (buffer.length > maxGpxImportBytes) return { result: 'too-large' };

	let parsed;
	try {
		parsed = parseGpx(buffer);
	} catch {
		return { result: 'invalid' };
	}

	try {
		await recordImportedActivity(
			userId,
			hashActivityFile(buffer, userId),
			parsed,
			{ mode: 'unlinked' },
			expectedImportGeneration
		);
		return { result: 'imported' };
	} catch (error) {
		const message = error instanceof Error ? error.message : '';
		if (message === 'This activity file has already been imported.') {
			return { result: 'duplicate' };
		}
		if (message === 'This deleted activity file cannot be imported again.') {
			return { result: 'deleted' };
		}
		if (message === 'Import was cancelled because activity data was deleted.') {
			return { result: 'deleted' };
		}
		if (message === 'Imported activities cannot be in the future.') {
			return { result: 'future' };
		}
		if (message === importTimeZoneRequiredMessage) {
			return { result: 'time-zone-required' };
		}
		return { result: 'failed' };
	}
}

export async function importBrowserFolderGpxIntoReviewInbox(
	userId: string,
	buffer: Buffer,
	expectedActivityGeneration: number,
	expectedFolderGeneration: number
): Promise<BrowserFolderGpxReviewImportResult> {
	if (buffer.length === 0) return { result: 'invalid' };
	if (buffer.length > maxGpxImportBytes) return { result: 'too-large' };

	let parsed;
	try {
		parsed = parseGpx(buffer);
	} catch {
		return { result: 'invalid' };
	}

	try {
		await recordBrowserFolderImportedActivity(
			userId,
			hashActivityFile(buffer, userId),
			parsed,
			expectedActivityGeneration,
			expectedFolderGeneration
		);
		return { result: 'imported' };
	} catch (error) {
		if (
			error instanceof Error &&
			error.message === 'Import was cancelled because the browser folder was disconnected.'
		) {
			return { result: 'disconnected' };
		}
		return mapReviewImportError(error);
	}
}

export async function finalizeAndroidGpxIntoReviewInbox(
	device: AuthenticatedAndroidDevice,
	receiptId: string,
	claimUpdatedAt: Date,
	expectedImportGeneration: number,
	buffer: Buffer
): Promise<AndroidGpxReviewFinalization> {
	if (buffer.length === 0 || buffer.length > maxGpxImportBytes) {
		return finalizeAndroidReviewOutcome(
			device,
			receiptId,
			claimUpdatedAt,
			expectedImportGeneration,
			{ result: buffer.length === 0 ? 'invalid' : 'too-large' }
		);
	}

	let parsed;
	try {
		parsed = parseGpx(buffer);
	} catch {
		return finalizeAndroidReviewOutcome(
			device,
			receiptId,
			claimUpdatedAt,
			expectedImportGeneration,
			{ result: 'invalid' }
		);
	}

	try {
		const finalized = await finalizeAndroidImportRequest(
			device,
			receiptId,
			claimUpdatedAt,
			expectedImportGeneration,
			async (tx) => {
				try {
					await recordImportedActivityInTransaction(
						tx,
						device.userId,
						hashActivityFile(buffer, device.userId),
						parsed,
						{ mode: 'unlinked' },
						expectedImportGeneration
					);
					return {
						result: 'imported' as const,
						reason: null,
						value: { result: 'imported' as const }
					};
				} catch (error) {
					const mapped = mapReviewImportError(error);
					if (mapped.result === 'time-zone-required' || mapped.result === 'failed') throw error;
					return {
						result:
							mapped.result === 'duplicate' || mapped.result === 'deleted'
								? 'duplicate'
								: 'quarantined',
						reason:
							mapped.result === 'deleted'
								? 'deleted'
								: mapped.result === 'duplicate'
									? null
									: mapped.result,
						value: mapped
					};
				}
			}
		);
		return finalized.state === 'completed'
			? { state: 'completed', result: finalized.value }
			: finalized;
	} catch (error) {
		const mapped = mapReviewImportError(error);
		return {
			state: 'retryable',
			reason: mapped.result === 'time-zone-required' ? 'time-zone-required' : 'server-error'
		};
	}
}

export async function finalizeAndroidReviewOutcome(
	device: AuthenticatedAndroidDevice,
	receiptId: string,
	claimUpdatedAt: Date,
	expectedImportGeneration: number,
	result: Exclude<GpxReviewImportResult, { result: 'imported' }>,
	receiptReason?: string
): Promise<AndroidGpxReviewFinalization> {
	const outcome =
		result.result === 'duplicate' || result.result === 'deleted'
			? ('duplicate' as const)
			: ('quarantined' as const);
	const reason =
		receiptReason ??
		(result.result === 'duplicate'
			? null
			: result.result === 'deleted'
				? 'deleted'
				: result.result);
	const finalized = await finalizeAndroidImportRequest(
		device,
		receiptId,
		claimUpdatedAt,
		expectedImportGeneration,
		() => Promise.resolve({ result: outcome, reason, value: result })
	);
	return finalized.state === 'completed'
		? { state: 'completed', result: finalized.value }
		: finalized;
}

function mapReviewImportError(error: unknown): GpxReviewImportResult {
	const message = error instanceof Error ? error.message : '';
	if (message === 'This activity file has already been imported.') return { result: 'duplicate' };
	if (message === 'This deleted activity file cannot be imported again.')
		return { result: 'deleted' };
	if (message === 'Import was cancelled because activity data was deleted.')
		return { result: 'deleted' };
	if (message === 'Imported activities cannot be in the future.') return { result: 'future' };
	if (message === importTimeZoneRequiredMessage) return { result: 'time-zone-required' };
	return { result: 'failed' };
}
