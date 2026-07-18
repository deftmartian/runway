import { maxGpxImportBytes } from '$lib/import-limits';

const databaseName = 'runway-device-folders';
const databaseVersion = 1;
const configStoreName = 'folders';
const seenStoreName = 'seen-files';
const seenUserIndexName = 'user-id';
const maxDirectoryEntries = 2_000;
const maxGpxCandidates = 500;
const activeScans = new Map<string, Promise<DeviceFolderScanResult>>();
const blockedUsers = new Set<string>();
let blockAllScans = false;

class DeviceFolderLimitError extends Error {}

type DirectoryPermissionHandle = FileSystemDirectoryHandle & {
	queryPermission?: (descriptor?: { mode?: 'read' | 'readwrite' }) => Promise<PermissionState>;
	requestPermission?: (descriptor?: { mode?: 'read' | 'readwrite' }) => Promise<PermissionState>;
};

type DirectoryPickerWindow = Window & {
	showDirectoryPicker?: (options?: {
		id?: string;
		mode?: 'read' | 'readwrite';
	}) => Promise<FileSystemDirectoryHandle>;
};

type StoredFolder = {
	userId: string;
	handle: FileSystemDirectoryHandle;
};

type SeenFile = {
	userId: string;
	digest: string;
};

type DeviceFileCandidate = {
	file: File;
	fingerprint: string;
	lastModified: number;
};

export type DeviceFolderConnectionState =
	| 'unsupported'
	| 'unlinked'
	| 'linked'
	| 'permission-required';

export type DeviceFolderScanResult =
	| { result: 'unsupported' }
	| { result: 'unlinked' }
	| { result: 'permission-required' }
	| { result: 'folder-missing' }
	| { result: 'folder-unavailable' }
	| { result: 'none' }
	| { result: 'imported' }
	| { result: 'duplicate' }
	| { result: 'deleted' }
	| { result: 'future' }
	| { result: 'time-zone-required' }
	| { result: 'invalid' }
	| { result: 'too-large' }
	| { result: 'too-many-files' }
	| { result: 'failed' };

export type DeviceFileMetadata = {
	fingerprint: string;
	lastModified: number;
};

export function supportsDeviceFolderImport(): boolean {
	return (
		typeof window !== 'undefined' &&
		typeof (window as DirectoryPickerWindow).showDirectoryPicker === 'function' &&
		'indexedDB' in window &&
		Boolean(globalThis.crypto?.subtle)
	);
}

export function isGpxFilename(name: string): boolean {
	return name.toLocaleLowerCase('en-US').endsWith('.gpx');
}

export function newestUnseenDeviceFile<T extends DeviceFileMetadata>(
	candidates: T[],
	seen: ReadonlySet<string>
): T | null {
	return (
		[...candidates]
			.filter((candidate) => !seen.has(candidate.fingerprint))
			.sort(
				(left, right) =>
					right.lastModified - left.lastModified ||
					left.fingerprint.localeCompare(right.fingerprint)
			)[0] ?? null
	);
}

export function isTerminalDeviceImportResult(result: DeviceFolderScanResult['result']): boolean {
	return ['imported', 'duplicate', 'deleted', 'future', 'invalid', 'too-large'].includes(result);
}

/**
 * Must be called directly from a user action. The picker grants read-only
 * access; runway never writes, renames, or deletes files in the chosen folder.
 */
export async function connectDeviceFolder(userId: string): Promise<DeviceFolderConnectionState> {
	if (!supportsDeviceFolderImport()) return 'unsupported';
	const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
	if (!picker) return 'unsupported';

	// Invoke the picker before any awaited work so transient user activation is
	// still present. IndexedDB receives only the resulting capability handle.
	const handle = await picker.call(window, { id: 'runway-gpx-import', mode: 'read' });
	const previous = await getStoredFolder(userId);
	if (!previous || !(await isSameDirectory(previous, handle))) {
		await clearSeenFiles(userId);
	}
	await storeFolder({ userId, handle });
	// A persisted storage bucket reduces the chance that Android evicts the
	// IndexedDB record containing the handle. It does not grant file access.
	void navigator.storage?.persist?.().catch(() => false);
	blockedUsers.delete(userId);
	return 'linked';
}

/**
 * Re-authorizes the already selected handle from a direct user gesture. This
 * avoids making the runner find and select the same folder again after the
 * browser downgrades a persisted handle from granted to prompt.
 */
export async function restoreDeviceFolderPermission(
	userId: string
): Promise<DeviceFolderConnectionState> {
	if (!supportsDeviceFolderImport()) return 'unsupported';
	const handle = await getStoredFolder(userId);
	if (!handle) return 'unlinked';
	return (await requestDirectoryReadPermission(handle)) === 'granted'
		? 'linked'
		: 'permission-required';
}

export async function requestDirectoryReadPermission(
	handle: FileSystemDirectoryHandle
): Promise<PermissionState> {
	const permissionHandle = handle as DirectoryPermissionHandle;
	if ((await queryPermission(handle)) === 'granted') return 'granted';
	if (!permissionHandle.requestPermission) return 'denied';
	try {
		return await permissionHandle.requestPermission({ mode: 'read' });
	} catch {
		return 'denied';
	}
}

export async function disconnectDeviceFolder(userId: string): Promise<void> {
	blockedUsers.add(userId);
	await activeScans.get(userId)?.catch(() => undefined);
	const database = await openDatabase();
	try {
		const transaction = database.transaction([configStoreName, seenStoreName], 'readwrite');
		transaction.objectStore(configStoreName).delete(userId);
		deleteSeenFiles(transaction, userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

/** Remove every retained directory capability before a browser-profile handoff. */
export async function clearAllDeviceFolderData(): Promise<void> {
	if (typeof indexedDB === 'undefined') return;
	blockAllScans = true;
	try {
		await Promise.all([...activeScans.values()].map((scan) => scan.catch(() => undefined)));
		await new Promise<void>((resolve, reject) => {
			const request = indexedDB.deleteDatabase(databaseName);
			request.onsuccess = () => {
				resolve();
			};
			request.onerror = () => {
				reject(request.error ?? new Error('Device folder database could not be cleared.'));
			};
			request.onblocked = () => {
				reject(new Error('Device folder database cleanup was blocked by another tab.'));
			};
		});
	} catch (error) {
		blockAllScans = false;
		throw error;
	}
}

/**
 * Browser storage is origin-wide. Remove stale capabilities belonging to any
 * other runway account before the authenticated app can scan.
 */
export async function retainDeviceFolderForUser(userId: string): Promise<void> {
	if (typeof indexedDB === 'undefined') return;
	const database = await openDatabase();
	try {
		const transaction = database.transaction([configStoreName, seenStoreName], 'readwrite');
		deleteOtherUsers(transaction.objectStore(configStoreName), userId);
		deleteOtherUsers(transaction.objectStore(seenStoreName), userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

export async function getDeviceFolderConnectionState(
	userId: string
): Promise<DeviceFolderConnectionState> {
	if (!supportsDeviceFolderImport()) return 'unsupported';
	const handle = await getStoredFolder(userId);
	if (!handle) return 'unlinked';
	return (await queryPermission(handle)) === 'granted' ? 'linked' : 'permission-required';
}

export function scanDeviceFolder(userId: string): Promise<DeviceFolderScanResult> {
	if (blockAllScans || blockedUsers.has(userId)) return Promise.resolve({ result: 'unlinked' });
	const active = activeScans.get(userId);
	if (active) return active;
	const scan = scanDeviceFolderOnce(userId).finally(() => activeScans.delete(userId));
	activeScans.set(userId, scan);
	return scan;
}

async function scanDeviceFolderOnce(userId: string): Promise<DeviceFolderScanResult> {
	if (blockAllScans || blockedUsers.has(userId)) return { result: 'unlinked' };
	if (!supportsDeviceFolderImport()) return { result: 'unsupported' };
	const handle = await getStoredFolder(userId);
	if (!handle) return { result: 'unlinked' };
	if ((await queryPermission(handle)) !== 'granted') return { result: 'permission-required' };

	let candidates: DeviceFileCandidate[];
	try {
		candidates = await listGpxCandidates(handle, userId);
	} catch (error) {
		if (error instanceof DeviceFolderLimitError) return { result: 'too-many-files' };
		const firstFailure = classifyDirectoryReadFailure(error);
		if (firstFailure !== 'folder-unavailable') return { result: firstFailure };
		// Android document providers can still be waking up immediately after the
		// page resumes. Retry that transient state once before asking the runner to act.
		await new Promise((resolve) => setTimeout(resolve, 350));
		try {
			candidates = await listGpxCandidates(handle, userId);
		} catch (retryError) {
			if (retryError instanceof DeviceFolderLimitError) return { result: 'too-many-files' };
			return { result: classifyDirectoryReadFailure(retryError) };
		}
	}
	const seen = await getSeenDigests(userId);
	const candidate = newestUnseenDeviceFile(candidates, seen);
	if (!candidate) return { result: 'none' };
	if (blockAllScans || blockedUsers.has(userId)) return { result: 'unlinked' };

	let result: DeviceFolderScanResult;
	try {
		result = await uploadCandidate(candidate.file);
	} catch {
		return { result: 'failed' };
	}
	if (isTerminalDeviceImportResult(result.result)) {
		await markSeen(userId, candidate.fingerprint);
	}
	return result;
}

export function classifyDirectoryReadFailure(
	error: unknown
): 'permission-required' | 'folder-missing' | 'folder-unavailable' | 'failed' {
	if (!(error instanceof DOMException)) return 'failed';
	if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
		return 'permission-required';
	}
	if (error.name === 'NotFoundError') return 'folder-missing';
	if (
		error.name === 'NotReadableError' ||
		error.name === 'InvalidStateError' ||
		error.name === 'AbortError' ||
		error.name === 'UnknownError'
	) {
		return 'folder-unavailable';
	}
	return 'failed';
}

async function listGpxCandidates(
	handle: FileSystemDirectoryHandle,
	userId: string
): Promise<DeviceFileCandidate[]> {
	const files: File[] = [];
	let entryCount = 0;
	for await (const entry of handle.values()) {
		entryCount += 1;
		if (entryCount > maxDirectoryEntries) throw new DeviceFolderLimitError();
		if (entry.kind !== 'file' || !isGpxFilename(entry.name)) continue;
		if (files.length >= maxGpxCandidates) throw new DeviceFolderLimitError();
		files.push(await entry.getFile());
	}
	const candidates: DeviceFileCandidate[] = [];
	for (const file of files) {
		candidates.push({
			file,
			fingerprint: await fingerprintFile(file, userId),
			lastModified: file.lastModified
		});
	}
	return candidates;
}

async function fingerprintFile(file: File, userId: string): Promise<string> {
	const metadata = new TextEncoder().encode(
		JSON.stringify([userId, file.name, file.size, file.lastModified])
	);
	// Folder scans run whenever the app regains focus. Fingerprint metadata here
	// so hundreds of already-seen files do not require repeated content reads.
	// The selected file is still fully hashed and deduplicated on the server.
	const digest = await globalThis.crypto.subtle.digest('SHA-256', metadata);
	return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function uploadCandidate(file: File): Promise<DeviceFolderScanResult> {
	if (file.size > maxGpxImportBytes) return { result: 'too-large' };
	const formData = new FormData();
	// Do not transmit Gadgetbridge's original filename. The server only needs
	// bounded bytes for strict parsing and user-scoped content deduplication.
	formData.append(
		'gpx',
		new File([file], 'activity.gpx', {
			type: 'application/gpx+xml',
			lastModified: file.lastModified
		})
	);
	const response = await fetch('/app/import/device', {
		method: 'POST',
		body: formData,
		credentials: 'same-origin',
		signal: AbortSignal.timeout(30_000)
	});
	const body = (await response.json()) as { result?: unknown };
	const result = body.result;
	if (
		typeof result !== 'string' ||
		![
			'imported',
			'duplicate',
			'deleted',
			'future',
			'time-zone-required',
			'invalid',
			'too-large'
		].includes(result)
	) {
		return { result: 'failed' };
	}
	return { result } as DeviceFolderScanResult;
}

async function queryPermission(handle: FileSystemDirectoryHandle): Promise<PermissionState> {
	const permissionHandle = handle as DirectoryPermissionHandle;
	if (!permissionHandle.queryPermission) return 'granted';
	try {
		return await permissionHandle.queryPermission({ mode: 'read' });
	} catch {
		return 'denied';
	}
}

async function getStoredFolder(userId: string): Promise<FileSystemDirectoryHandle | null> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(configStoreName, 'readonly');
		const stored = await requestResult(transaction.objectStore(configStoreName).get(userId));
		await transactionComplete(transaction);
		return isStoredFolder(stored, userId) ? stored.handle : null;
	} finally {
		database.close();
	}
}

async function storeFolder(folder: StoredFolder): Promise<void> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(configStoreName, 'readwrite');
		transaction.objectStore(configStoreName).put(folder);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

async function clearSeenFiles(userId: string): Promise<void> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(seenStoreName, 'readwrite');
		deleteSeenFiles(transaction, userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

function deleteSeenFiles(transaction: IDBTransaction, userId: string) {
	const seenIndex = transaction.objectStore(seenStoreName).index(seenUserIndexName);
	const cursorRequest = seenIndex.openCursor(IDBKeyRange.only(userId));
	cursorRequest.onsuccess = () => {
		const cursor = cursorRequest.result;
		if (!cursor) return;
		cursor.delete();
		cursor.continue();
	};
}

function deleteOtherUsers(store: IDBObjectStore, currentUserId: string) {
	const cursorRequest = store.openCursor();
	cursorRequest.onsuccess = () => {
		const cursor = cursorRequest.result;
		if (!cursor) return;
		const key = cursor.primaryKey;
		const owner = Array.isArray(key) ? key[0] : key;
		if (owner !== currentUserId) {
			if (typeof owner === 'string') blockedUsers.add(owner);
			cursor.delete();
		}
		cursor.continue();
	};
}

async function isSameDirectory(
	left: FileSystemDirectoryHandle,
	right: FileSystemDirectoryHandle
): Promise<boolean> {
	try {
		return await left.isSameEntry(right);
	} catch {
		return false;
	}
}

async function getSeenDigests(userId: string): Promise<Set<string>> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(seenStoreName, 'readonly');
		const stored = await requestResult(
			transaction.objectStore(seenStoreName).index(seenUserIndexName).getAll(userId)
		);
		await transactionComplete(transaction);
		if (!Array.isArray(stored)) return new Set();
		return new Set(
			stored
				.filter((record): record is SeenFile => isSeenFile(record, userId))
				.map(({ digest }) => digest)
		);
	} finally {
		database.close();
	}
}

async function markSeen(userId: string, digest: string): Promise<void> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(seenStoreName, 'readwrite');
		transaction.objectStore(seenStoreName).put({ userId, digest } satisfies SeenFile);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

function openDatabase(): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const request = indexedDB.open(databaseName, databaseVersion);
		request.onupgradeneeded = () => {
			const database = request.result;
			if (!database.objectStoreNames.contains(configStoreName)) {
				database.createObjectStore(configStoreName, { keyPath: 'userId' });
			}
			if (!database.objectStoreNames.contains(seenStoreName)) {
				const store = database.createObjectStore(seenStoreName, {
					keyPath: ['userId', 'digest']
				});
				store.createIndex(seenUserIndexName, 'userId', { unique: false });
			}
		};
		request.onsuccess = () => {
			resolve(request.result);
		};
		request.onerror = () => {
			reject(request.error ?? new Error('Device folder database failed.'));
		};
		request.onblocked = () => {
			reject(new Error('Device folder database upgrade was blocked.'));
		};
	});
}

function requestResult(request: IDBRequest): Promise<unknown> {
	return new Promise((resolve, reject) => {
		request.onsuccess = () => {
			resolve(request.result as unknown);
		};
		request.onerror = () => {
			reject(request.error ?? new Error('Device folder request failed.'));
		};
	});
}

function transactionComplete(transaction: IDBTransaction): Promise<void> {
	return new Promise((resolve, reject) => {
		transaction.oncomplete = () => {
			resolve();
		};
		transaction.onabort = () => {
			reject(transaction.error ?? new Error('Device folder transaction was aborted.'));
		};
		transaction.onerror = () => {
			reject(transaction.error ?? new Error('Device folder transaction failed.'));
		};
	});
}

function isStoredFolder(value: unknown, userId: string): value is StoredFolder {
	if (!value || typeof value !== 'object' || !('userId' in value) || !('handle' in value)) {
		return false;
	}
	const handle = value.handle;
	if (!handle || typeof handle !== 'object' || !('kind' in handle)) return false;
	return value.userId === userId && handle.kind === 'directory';
}

function isSeenFile(value: unknown, userId: string): value is SeenFile {
	if (!value || typeof value !== 'object' || !('userId' in value) || !('digest' in value)) {
		return false;
	}
	return value.userId === userId && typeof value.digest === 'string';
}
