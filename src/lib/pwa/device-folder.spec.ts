import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	classifyDirectoryReadFailure,
	deviceFolderFingerprintConcurrency,
	fingerprintDeviceFile,
	getDeviceFolderSupportState,
	isDeviceFolderControlMessage,
	isGpxFilename,
	isTerminalDeviceImportResult,
	mapWithBoundedConcurrency,
	maxDeviceFingerprintBytesPerFile,
	maxDeviceFingerprintBytesPerScan,
	maxDeviceFingerprintCandidatesPerScan,
	newestUnseenDeviceFile,
	requestDirectoryReadPermission,
	rotateDeviceCandidates
} from './device-folder';

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('device folder GPX selection', () => {
	it('accepts only GPX file names, case-insensitively', () => {
		expect(isGpxFilename('activity.gpx')).toBe(true);
		expect(isGpxFilename('ACTIVITY.GPX')).toBe(true);
		expect(isGpxFilename('activity.gpx.xml')).toBe(false);
		expect(isGpxFilename('activity.fit')).toBe(false);
	});

	it('accepts only bounded device-folder control messages', () => {
		expect(isDeviceFolderControlMessage({ type: 'clear-all' })).toBe(true);
		expect(isDeviceFolderControlMessage({ type: 'disconnected', userId: 'runner-1' })).toBe(true);
		expect(isDeviceFolderControlMessage({ type: 'connected', userId: '' })).toBe(false);
		expect(isDeviceFolderControlMessage({ type: 'disconnected', userId: 42 })).toBe(false);
		expect(isDeviceFolderControlMessage({ type: 'unknown', userId: 'runner-1' })).toBe(false);
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

	it('does not reuse a handled marker when bounded GPX content changes in place', async () => {
		const metadata = { type: 'application/gpx+xml', lastModified: 42 };
		const original = new File(['<gpx>A</gpx>'], 'activity.gpx', metadata);
		const replacement = new File(['<gpx>B</gpx>'], 'activity.gpx', metadata);

		expect(replacement.size).toBe(original.size);
		await expect(fingerprintDeviceFile(replacement, 'runner-1')).resolves.not.toBe(
			await fingerprintDeviceFile(original, 'runner-1')
		);
	});

	it('detects in-place changes at either edge of a large GPX', async () => {
		const originalBytes = new Uint8Array(20 * 1024);
		const prefixChangedBytes = originalBytes.slice();
		const suffixChangedBytes = originalBytes.slice();
		prefixChangedBytes[0] = 1;
		suffixChangedBytes[suffixChangedBytes.length - 1] = 1;
		const metadata = { type: 'application/gpx+xml', lastModified: 42 };
		const original = new File([originalBytes], 'activity.gpx', metadata);
		const originalFingerprint = await fingerprintDeviceFile(original, 'runner-1');

		await expect(
			fingerprintDeviceFile(new File([prefixChangedBytes], 'activity.gpx', metadata), 'runner-1')
		).resolves.not.toBe(originalFingerprint);
		await expect(
			fingerprintDeviceFile(new File([suffixChangedBytes], 'activity.gpx', metadata), 'runner-1')
		).resolves.not.toBe(originalFingerprint);
	});

	it('detects an in-place route change when the large GPX edges remain identical', async () => {
		const originalBytes = new Uint8Array(20 * 1024);
		const middleChangedBytes = originalBytes.slice();
		middleChangedBytes[Math.floor(middleChangedBytes.length / 2)] = 1;
		const metadata = { type: 'application/gpx+xml', lastModified: 42 };
		const original = new File([originalBytes], 'activity.gpx', metadata);
		const replacement = new File([middleChangedBytes], 'activity.gpx', metadata);

		expect(replacement.size).toBe(original.size);
		await expect(fingerprintDeviceFile(replacement, 'runner-1')).resolves.not.toBe(
			await fingerprintDeviceFile(original, 'runner-1')
		);
	});

	it('keeps browser-local content markers scoped to the signed-in account', async () => {
		const file = new File(['<gpx>A</gpx>'], 'activity.gpx', { lastModified: 42 });

		await expect(fingerprintDeviceFile(file, 'runner-2')).resolves.not.toBe(
			await fingerprintDeviceFile(file, 'runner-1')
		);
	});

	it('bounds content reads for every accepted-size candidate', async () => {
		const requestedRanges: { start: number; end: number }[] = [];
		const accepted = {
			name: 'accepted.gpx',
			size: 10 * 1024 * 1024,
			lastModified: 42,
			arrayBuffer: () => Promise.reject(new Error('full file must not be read')),
			slice: (start: number, end: number) => {
				requestedRanges.push({ start, end });
				return new Blob(['bounded']);
			}
		} as unknown as File;

		await expect(fingerprintDeviceFile(accepted, 'runner-1')).resolves.toMatch(/^[a-f0-9]{64}$/);
		expect(requestedRanges).toHaveLength(5);
		expect(requestedRanges[0]?.start).toBe(0);
		expect(requestedRanges.at(-1)?.end).toBe(accepted.size);
		expect(
			requestedRanges.some(
				(range) => range.start <= accepted.size / 2 && range.end > accepted.size / 2
			)
		).toBe(true);
		expect(
			requestedRanges.reduce((total, range) => total + range.end - range.start, 0)
		).toBeLessThanOrEqual(maxDeviceFingerprintBytesPerFile);
	});

	it('caps content reads across the maximum candidate scan', async () => {
		let requestedBytes = 0;
		let sliceCalls = 0;
		for (let index = 0; index < maxDeviceFingerprintCandidatesPerScan; index += 1) {
			const file = {
				name: `activity-${index}.gpx`,
				size: 10 * 1024 * 1024,
				lastModified: index,
				slice: (start: number, end: number) => {
					sliceCalls += 1;
					requestedBytes += end - start;
					return new Blob(['bounded']);
				}
			} as unknown as File;
			await fingerprintDeviceFile(file, 'runner-1');
		}

		expect(sliceCalls).toBe(maxDeviceFingerprintCandidatesPerScan * 5);
		expect(requestedBytes).toBe(maxDeviceFingerprintCandidatesPerScan * 8_000);
		expect(requestedBytes).toBeLessThanOrEqual(maxDeviceFingerprintBytesPerScan);
	});

	it('rotates a timed-out provider scan without dropping or duplicating candidates', () => {
		expect(rotateDeviceCandidates(['a', 'b', 'c', 'd'], 2)).toEqual(['c', 'd', 'a', 'b']);
		expect(rotateDeviceCandidates(['a', 'b', 'c', 'd'], 6)).toEqual(['c', 'd', 'a', 'b']);
		expect(rotateDeviceCandidates(['a', 'b', 'c', 'd'], -1)).toEqual(['d', 'a', 'b', 'c']);
		expect(rotateDeviceCandidates([], 3)).toEqual([]);
	});

	it('fingerprints candidates concurrently within the fixed provider bound', async () => {
		let active = 0;
		let peakActive = 0;
		const release: (() => void)[] = [];
		const values = Array.from({ length: 10 }, (_, index) => index);
		const resultPromise = mapWithBoundedConcurrency(
			values,
			deviceFolderFingerprintConcurrency,
			async (value) => {
				active += 1;
				peakActive = Math.max(peakActive, active);
				await new Promise<void>((resolve) => release.push(resolve));
				active -= 1;
				return value * 2;
			}
		);

		await vi.waitFor(() => {
			expect(active).toBe(deviceFolderFingerprintConcurrency);
		});
		while (release.length > 0 || active > 0) {
			const next = release.shift();
			next?.();
			await Promise.resolve();
		}

		await expect(resultPromise).resolves.toEqual(values.map((value) => value * 2));
		expect(peakActive).toBe(deviceFolderFingerprintConcurrency);
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
		for (const result of [
			'failed',
			'time-zone-required',
			'too-many-files',
			'timed-out',
			'cancelled'
		] as const) {
			expect(isTerminalDeviceImportResult(result)).toBe(false);
		}
	});

	it('restores an existing handle without reopening the directory picker', async () => {
		let requestCount = 0;
		const handle = {
			queryPermission: () => Promise.resolve('prompt' as PermissionState),
			requestPermission: () => {
				requestCount += 1;
				return Promise.resolve('granted' as PermissionState);
			}
		} as unknown as FileSystemDirectoryHandle;

		await expect(requestDirectoryReadPermission(handle)).resolves.toBe('granted');
		expect(requestCount).toBe(1);
	});

	it('does not prompt again while the saved handle remains granted', async () => {
		let requestCount = 0;
		const handle = {
			queryPermission: () => Promise.resolve('granted' as PermissionState),
			requestPermission: () => {
				requestCount += 1;
				return Promise.resolve('granted' as PermissionState);
			}
		} as unknown as FileSystemDirectoryHandle;

		await expect(requestDirectoryReadPermission(handle)).resolves.toBe('granted');
		expect(requestCount).toBe(0);
	});

	it('separates revoked, missing, and temporarily unavailable folders', () => {
		expect(classifyDirectoryReadFailure(new DOMException('', 'NotAllowedError'))).toBe(
			'permission-required'
		);
		expect(classifyDirectoryReadFailure(new DOMException('', 'NotFoundError'))).toBe(
			'folder-missing'
		);
		expect(classifyDirectoryReadFailure(new DOMException('', 'NotReadableError'))).toBe(
			'folder-unavailable'
		);
		expect(classifyDirectoryReadFailure(new Error('provider failed'))).toBe('failed');
	});

	it('reports an insecure origin separately from missing browser APIs', () => {
		vi.stubGlobal('window', { indexedDB: {} });
		vi.stubGlobal('isSecureContext', false);
		expect(getDeviceFolderSupportState()).toBe('https-required');

		vi.stubGlobal('isSecureContext', true);
		expect(getDeviceFolderSupportState()).toBe('unsupported');
	});
});
