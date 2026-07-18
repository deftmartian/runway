import { hashActivityFile, parseGpx } from '$lib/training/gpx';
export { maxGpxImportBytes, maxGpxMultipartOverheadBytes } from '$lib/import-limits';
import { maxGpxImportBytes } from '$lib/import-limits';
import { importTimeZoneRequiredMessage } from './import-sources';
import { recordImportedActivity } from './repository';

export type GpxReviewImportResult =
	| { result: 'imported' }
	| { result: 'duplicate' }
	| { result: 'deleted' }
	| { result: 'future' }
	| { result: 'time-zone-required' }
	| { result: 'invalid' }
	| { result: 'too-large' }
	| { result: 'failed' };

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
