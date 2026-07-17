import { describe, expect, it } from 'vitest';
import {
	isGpxFilename,
	isTerminalDeviceImportResult,
	newestUnseenDeviceFile
} from './device-folder';

describe('device folder GPX selection', () => {
	it('accepts only GPX file names, case-insensitively', () => {
		expect(isGpxFilename('activity.gpx')).toBe(true);
		expect(isGpxFilename('ACTIVITY.GPX')).toBe(true);
		expect(isGpxFilename('activity.gpx.xml')).toBe(false);
		expect(isGpxFilename('activity.fit')).toBe(false);
	});

	it('chooses the newest unseen file with a deterministic tie break', () => {
		const files = [
			{ fingerprint: 'older', lastModified: 1 },
			{ fingerprint: 'same-b', lastModified: 3 },
			{ fingerprint: 'same-a', lastModified: 3 }
		];
		expect(newestUnseenDeviceFile(files, new Set(['same-a']))?.fingerprint).toBe('same-b');
		expect(newestUnseenDeviceFile(files, new Set(['same-a', 'same-b']))?.fingerprint).toBe('older');
	});

	it('marks only stable outcomes as locally handled', () => {
		for (const result of [
			'imported',
			'duplicate',
			'deleted',
			'future',
			'invalid',
			'too-large'
		] as const) {
			expect(isTerminalDeviceImportResult(result)).toBe(true);
		}
		for (const result of ['failed', 'time-zone-required', 'too-many-files'] as const) {
			expect(isTerminalDeviceImportResult(result)).toBe(false);
		}
	});
});
