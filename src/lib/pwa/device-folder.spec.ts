import { afterEach, describe, expect, it, vi } from 'vitest';
import {
	classifyDirectoryReadFailure,
	getDeviceFolderSupportState,
	isGpxFilename,
	isTerminalDeviceImportResult,
	newestUnseenDeviceFile,
	requestDirectoryReadPermission
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
