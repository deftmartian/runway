import { maxGpxImportBytes } from '$lib/import-limits';

const databaseName = 'runway-device-folders';
const databaseVersion = 2;
const configStoreName = 'folders';
const seenStoreName = 'seen-files';
const scanStateStoreName = 'scan-state';
const seenUserIndexName = 'user-id';
const controlChannelName = 'runway-device-folder-control-v1';
export const deviceFolderControlEvent = 'runway:device-folder-control';
const maxDirectoryEntries = 2_000;
export const maxDeviceFingerprintCandidatesPerScan = 500;
const fingerprintWindowBytes = 1_600;
const fingerprintWindowDivisions = 4;
export const deviceFolderFingerprintConcurrency = 4;
export const deviceFolderScanBudgetMs = 20_000;
export const deviceFolderCandidateBudgetMs = 4_000;
export const maxDeviceFingerprintBytesPerFile = 8 * 1024;
export const maxDeviceFingerprintBytesPerScan =
	maxDeviceFingerprintCandidatesPerScan * maxDeviceFingerprintBytesPerFile;
type ActiveDeviceFolderScan = {
	controller: AbortController;
	listeners: Set<(progress: DeviceFolderScanProgress) => void>;
	progress: DeviceFolderScanProgress;
	promise: Promise<DeviceFolderScanResult>;
};

const activeScans = new Map<string, ActiveDeviceFolderScan>();
const candidateScanOffsets = new Map<string, number>();
const blockedUsers = new Set<string>();
let blockAllScans = false;
let capabilityRevision = 0;
let controlChannel: BroadcastChannel | null | undefined;

class DeviceFolderLimitError extends Error {}
class DeviceFolderScanTimeoutError extends Error {}
class DeviceFolderScanCancelledError extends Error {}

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

type DeviceFolderScanState = {
	userId: string;
	settledSignature: string;
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

type DeviceFolderControlMessage =
	| { type: 'connected'; userId: string }
	| { type: 'disconnected'; userId: string }
	| { type: 'clear-all' };

export type DeviceFolderConnectionState =
	| 'https-required'
	| 'unsupported'
	| 'unlinked'
	| 'linked'
	| 'permission-required';

export type DeviceFolderSupportState = 'supported' | 'https-required' | 'unsupported';

export type DeviceFolderScanResult = (
	| { result: 'https-required' }
	| { result: 'unsupported' }
	| { result: 'unlinked' }
	| { result: 'permission-required' }
	| { result: 'folder-missing' }
	| { result: 'folder-unavailable' }
	| { result: 'none' }
	| { result: 'imported' }
	| { result: 'duplicate' }
	| { result: 'deleted' }
	| { result: 'disconnected' }
	| { result: 'rate-limited' }
	| { result: 'future' }
	| { result: 'time-zone-required' }
	| { result: 'invalid' }
	| { result: 'too-large' }
	| { result: 'too-many-files' }
	| { result: 'timed-out' }
	| { result: 'cancelled' }
	| { result: 'failed' }
) & {
	/** Number of other unseen GPX files left after a terminal result. */
	remaining?: number;
	retryAfterSeconds?: number;
	checkedCandidates?: number;
	totalCandidates?: number;
	scanIncomplete?: boolean;
};

export type DeviceFolderScanProgress = {
	phase: 'enumerating' | 'fingerprinting' | 'uploading';
	completed: number;
	total: number | null;
};

export type DeviceFolderScanOptions = {
	onProgress?: (progress: DeviceFolderScanProgress) => void;
	mode?: 'automatic' | 'manual';
};

type DeviceFolderScanContext = {
	signal: AbortSignal;
	deadline: number;
	automatic: boolean;
	progress: DeviceFolderScanProgress;
	report: (progress: DeviceFolderScanProgress) => void;
};

export type DeviceFileMetadata = {
	fingerprint: string;
	lastModified: number;
};

export function supportsDeviceFolderImport(): boolean {
	return getDeviceFolderSupportState() === 'supported';
}

export function getDeviceFolderSupportState(): DeviceFolderSupportState {
	if (typeof window === 'undefined') return 'unsupported';
	if ('isSecureContext' in globalThis && !globalThis.isSecureContext) return 'https-required';
	return typeof (window as DirectoryPickerWindow).showDirectoryPicker === 'function' &&
		'indexedDB' in window &&
		Boolean(globalThis.crypto?.subtle)
		? 'supported'
		: 'unsupported';
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

export function automaticDeviceFolderScanDelayMs(result: DeviceFolderScanResult): number {
	if (result.result === 'rate-limited') {
		return Math.max(60_000, (result.retryAfterSeconds ?? 60) * 1_000);
	}
	if (result.remaining && result.remaining > 0) return 5_000;
	if (isTerminalDeviceImportResult(result.result)) return 60_000;
	if (result.result === 'none') return 5 * 60_000;
	if (result.result === 'permission-required' || result.result === 'unlinked') return 15 * 60_000;
	if (result.result === 'unsupported' || result.result === 'https-required') return 60 * 60_000;
	return 60_000;
}

/**
 * Must be called directly from a user action. The picker grants read-only
 * access; runway never writes, renames, or deletes files in the chosen folder.
 */
export async function connectDeviceFolder(userId: string): Promise<DeviceFolderConnectionState> {
	const support = getDeviceFolderSupportState();
	if (support !== 'supported') return support;
	const picker = (window as DirectoryPickerWindow).showDirectoryPicker;
	if (!picker) return 'unsupported';

	// Invoke the picker before any awaited work so transient user activation is
	// still present. IndexedDB receives only the resulting capability handle.
	const handle = await picker.call(window, { id: 'runway-gpx-import', mode: 'read' });
	const previous = await getStoredFolder(userId);
	if (!previous || !(await isSameDirectory(previous, handle))) {
		await clearSeenFiles(userId);
	}
	await clearDeviceFolderScanState(userId);
	await storeFolder({ userId, handle });
	// A persisted storage bucket reduces the chance that Android evicts the
	// IndexedDB record containing the handle. It does not grant file access.
	void navigator.storage?.persist?.().catch(() => false);
	signalDeviceFolderControl({ type: 'connected', userId });
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
	const support = getDeviceFolderSupportState();
	if (support !== 'supported') return support;
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
	signalDeviceFolderControl({ type: 'disconnected', userId });
	await revokeServerBrowserFolderGeneration();
	await activeScans.get(userId)?.promise.catch(() => undefined);
	const database = await openDatabase();
	try {
		const transaction = database.transaction(
			[configStoreName, seenStoreName, scanStateStoreName],
			'readwrite'
		);
		transaction.objectStore(configStoreName).delete(userId);
		transaction.objectStore(scanStateStoreName).delete(userId);
		deleteSeenFiles(transaction, userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

/** Remove every retained directory capability before a browser-profile handoff. */
export async function clearAllDeviceFolderData(): Promise<void> {
	if (typeof indexedDB === 'undefined') return;
	signalDeviceFolderControl({ type: 'clear-all' });
	try {
		await Promise.all([...activeScans.values()].map((scan) => scan.promise.catch(() => undefined)));
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
	ensureDeviceFolderControlChannel();
	blockAllScans = false;
	blockedUsers.delete(userId);
	const database = await openDatabase();
	try {
		const transaction = database.transaction(
			[configStoreName, seenStoreName, scanStateStoreName],
			'readwrite'
		);
		deleteOtherUsers(transaction.objectStore(configStoreName), userId);
		deleteOtherUsers(transaction.objectStore(seenStoreName), userId);
		deleteOtherUsers(transaction.objectStore(scanStateStoreName), userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

export async function getDeviceFolderConnectionState(
	userId: string
): Promise<DeviceFolderConnectionState> {
	const support = getDeviceFolderSupportState();
	if (support !== 'supported') return support;
	const handle = await getStoredFolder(userId);
	if (!handle) return 'unlinked';
	return (await queryPermission(handle)) === 'granted' ? 'linked' : 'permission-required';
}

export function scanDeviceFolder(
	userId: string,
	options: DeviceFolderScanOptions = {}
): Promise<DeviceFolderScanResult> {
	ensureDeviceFolderControlChannel();
	if (blockAllScans || blockedUsers.has(userId)) return Promise.resolve({ result: 'unlinked' });
	const active = activeScans.get(userId);
	if (active) return observeActiveScan(active, options.onProgress);

	const controller = new AbortController();
	const listeners = new Set<(progress: DeviceFolderScanProgress) => void>();
	const entry: ActiveDeviceFolderScan = {
		controller,
		listeners,
		progress: { phase: 'enumerating', completed: 0, total: null },
		promise: Promise.resolve({ result: 'failed' } as DeviceFolderScanResult)
	};
	const context: DeviceFolderScanContext = {
		signal: controller.signal,
		deadline: monotonicNow() + deviceFolderScanBudgetMs,
		automatic: options.mode === 'automatic',
		progress: entry.progress,
		report: (progress) => {
			context.progress = progress;
			entry.progress = progress;
			for (const listener of listeners) listener(progress);
		}
	};
	entry.promise = scanDeviceFolderOnce(userId, context).finally(() => {
		if (activeScans.get(userId) === entry) activeScans.delete(userId);
	});
	activeScans.set(userId, entry);
	return observeActiveScan(entry, options.onProgress);
}

export function cancelDeviceFolderScan(userId: string): void {
	activeScans.get(userId)?.controller.abort();
}

function observeActiveScan(
	active: ActiveDeviceFolderScan,
	listener?: (progress: DeviceFolderScanProgress) => void
): Promise<DeviceFolderScanResult> {
	if (!listener) return active.promise;
	active.listeners.add(listener);
	listener(active.progress);
	return active.promise.finally(() => active.listeners.delete(listener));
}

async function scanDeviceFolderOnce(
	userId: string,
	context: DeviceFolderScanContext
): Promise<DeviceFolderScanResult> {
	if (blockAllScans || blockedUsers.has(userId)) return { result: 'unlinked' };
	const support = getDeviceFolderSupportState();
	if (support !== 'supported') return { result: support };
	const handle = await getStoredFolder(userId);
	if (!handle) return { result: 'unlinked' };
	if ((await queryPermission(handle)) !== 'granted') return { result: 'permission-required' };
	const scanRevision = capabilityRevision;
	const generations = await fetchImportGenerations();
	if (generations === null) return { result: 'failed' };

	let candidateScan: DeviceCandidateScan;
	try {
		candidateScan = await listGpxCandidates(handle, userId, context);
	} catch (error) {
		if (error instanceof DeviceFolderLimitError) return { result: 'too-many-files' };
		if (error instanceof DeviceFolderScanTimeoutError) {
			return scanTimeoutResult(context.progress);
		}
		if (error instanceof DeviceFolderScanCancelledError) {
			return blockAllScans || blockedUsers.has(userId)
				? { result: 'unlinked' }
				: { result: 'cancelled' };
		}
		const firstFailure = classifyDirectoryReadFailure(error);
		if (firstFailure !== 'folder-unavailable') return { result: firstFailure };
		// Android document providers can still be waking up immediately after the
		// page resumes. Retry that transient state once before asking the runner to act.
		await waitForOperation(new Promise((resolve) => setTimeout(resolve, 350)), context);
		try {
			candidateScan = await listGpxCandidates(handle, userId, context);
		} catch (retryError) {
			if (retryError instanceof DeviceFolderLimitError) return { result: 'too-many-files' };
			if (retryError instanceof DeviceFolderScanTimeoutError) {
				return scanTimeoutResult(context.progress);
			}
			if (retryError instanceof DeviceFolderScanCancelledError) {
				return blockAllScans || blockedUsers.has(userId)
					? { result: 'unlinked' }
					: { result: 'cancelled' };
			}
			return { result: classifyDirectoryReadFailure(retryError) };
		}
	}
	const { candidates, skippedCandidates, directorySignature } = candidateScan;
	const seen = await getSeenDigests(userId);
	const candidate = newestUnseenDeviceFile(candidates, seen);
	if (!candidate) {
		if (skippedCandidates === 0) {
			await storeSettledDirectorySignature(userId, directorySignature);
		}
		return skippedCandidates > 0
			? {
					result: 'timed-out',
					checkedCandidates: candidateScan.checkedCandidates,
					totalCandidates: candidateScan.totalCandidates
				}
			: { result: 'none' };
	}
	const remaining = Math.max(
		0,
		candidates.filter((item) => !seen.has(item.fingerprint)).length - 1
	);
	if (blockAllScans || blockedUsers.has(userId)) return { result: 'unlinked' };
	const capabilityState = await revalidateDeviceFolderCapability(userId, handle, scanRevision);
	if (capabilityState !== 'linked') return { result: capabilityState };
	context.report({ phase: 'uploading', completed: candidates.length, total: candidates.length });

	let result: DeviceFolderScanResult;
	try {
		result = await uploadCandidate(candidate.file, generations, context.signal);
	} catch (error) {
		if (context.signal.aborted || error instanceof DeviceFolderScanCancelledError) {
			return blockAllScans || blockedUsers.has(userId)
				? { result: 'unlinked' }
				: { result: 'cancelled' };
		}
		return { result: 'failed' };
	}
	if (isTerminalDeviceImportResult(result.result)) {
		await markSeen(userId, candidate.fingerprint);
		if (remaining === 0 && skippedCandidates === 0) {
			await storeSettledDirectorySignature(userId, directorySignature);
		}
		return {
			...result,
			remaining,
			...(skippedCandidates > 0
				? {
						scanIncomplete: true,
						checkedCandidates: candidateScan.checkedCandidates,
						totalCandidates: candidateScan.totalCandidates
					}
				: {})
		};
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
	userId: string,
	context: DeviceFolderScanContext
): Promise<DeviceCandidateScan> {
	const fileHandles: FileSystemFileHandle[] = [];
	let entryCount = 0;
	const iterator = handle.values()[Symbol.asyncIterator]();
	while (true) {
		const next = await waitForOperation(iterator.next(), context);
		if (next.done) break;
		const entry = next.value;
		assertScanActive(context);
		entryCount += 1;
		context.report({ phase: 'enumerating', completed: entryCount, total: null });
		if (entryCount > maxDirectoryEntries) throw new DeviceFolderLimitError();
		if (entry.kind !== 'file' || !isGpxFilename(entry.name)) continue;
		if (fileHandles.length >= maxDeviceFingerprintCandidatesPerScan) {
			throw new DeviceFolderLimitError();
		}
		fileHandles.push(entry);
	}
	const directorySignature = await directoryListingSignature(
		fileHandles.map(({ name }) => name),
		context
	);
	if (context.automatic && directorySignature === (await getSettledDirectorySignature(userId))) {
		return {
			candidates: [],
			skippedCandidates: 0,
			checkedCandidates: 0,
			totalCandidates: fileHandles.length,
			directorySignature
		};
	}
	context.report({ phase: 'fingerprinting', completed: 0, total: fileHandles.length });

	const startOffset = normalizedCandidateOffset(
		candidateScanOffsets.get(userId) ?? 0,
		fileHandles.length
	);
	const orderedHandles = rotateDeviceCandidates(fileHandles, startOffset);
	let providerAttempts = 0;
	let completed = 0;
	let skippedCandidates = 0;
	const outcomes = await mapWithBoundedConcurrency(
		orderedHandles,
		deviceFolderFingerprintConcurrency,
		async (fileHandle) => {
			if (monotonicNow() < context.deadline) providerAttempts += 1;
			const candidateContext: DeviceFolderScanContext = {
				...context,
				deadline: Math.min(context.deadline, monotonicNow() + deviceFolderCandidateBudgetMs)
			};
			try {
				const file = await waitForOperation(fileHandle.getFile(), candidateContext);
				const fingerprint = await fingerprintDeviceFile(file, userId, candidateContext);
				return { file, fingerprint, lastModified: file.lastModified };
			} catch (error) {
				if (error instanceof DeviceFolderScanCancelledError) throw error;
				if (error instanceof DeviceFolderScanTimeoutError || isSkippableCandidateFailure(error)) {
					skippedCandidates += 1;
					return null;
				}
				throw error;
			} finally {
				completed += 1;
				context.report({
					phase: 'fingerprinting',
					completed,
					total: orderedHandles.length
				});
			}
		}
	);
	const candidates = outcomes.filter(
		(candidate): candidate is DeviceFileCandidate => candidate !== null
	);
	if (skippedCandidates === 0) {
		candidateScanOffsets.set(userId, 0);
	} else {
		// Resume after the provider entries that received time in this pass. Slow or
		// stale entries cannot silently keep later GPX files out of every scan.
		candidateScanOffsets.set(
			userId,
			normalizedCandidateOffset(startOffset + Math.max(1, providerAttempts), fileHandles.length)
		);
	}
	return {
		candidates,
		skippedCandidates,
		checkedCandidates: providerAttempts,
		totalCandidates: orderedHandles.length,
		directorySignature
	};
}

type DeviceCandidateScan = {
	candidates: DeviceFileCandidate[];
	skippedCandidates: number;
	checkedCandidates: number;
	totalCandidates: number;
	directorySignature: string;
};

export async function directoryListingSignature(
	names: string[],
	context?: DeviceFolderScanContext
): Promise<string> {
	const encoded = new TextEncoder().encode(
		JSON.stringify(['runway-device-folder-listing-v1', ...[...names].sort()])
	);
	const digest = await waitForOptionalScanOperation(
		globalThis.crypto.subtle.digest('SHA-256', encoded),
		context
	);
	return hexDigest(digest);
}

function isSkippableCandidateFailure(error: unknown): boolean {
	return (
		error instanceof DOMException &&
		[
			'NotFoundError',
			'NotReadableError',
			'InvalidStateError',
			'AbortError',
			'UnknownError'
		].includes(error.name)
	);
}

export async function fingerprintDeviceFile(
	file: File,
	userId: string,
	context?: DeviceFolderScanContext
): Promise<string> {
	// Gadgetbridge can replace an export without changing its name, byte length,
	// or modified time. Include deterministic, stratified content windows in the
	// browser-local marker so common in-place replacements are offered for
	// authoritative server-side deduplication. Never read more than 8 KiB per
	// candidate: a full folder scan is capped at about 4 MiB rather than gigabytes.
	const contentSample = await readFingerprintSample(file, context);
	const contentDigest = await waitForOptionalScanOperation(
		globalThis.crypto.subtle.digest('SHA-256', contentSample),
		context
	);
	const identity = new TextEncoder().encode(
		JSON.stringify([
			userId,
			file.name,
			file.size,
			file.lastModified,
			file.size <= maxDeviceFingerprintBytesPerFile ? 'complete-v1' : 'stratified-sample-v1',
			hexDigest(contentDigest)
		])
	);
	const digest = await waitForOptionalScanOperation(
		globalThis.crypto.subtle.digest('SHA-256', identity),
		context
	);
	return hexDigest(digest);
}

async function readFingerprintSample(
	file: File,
	context?: DeviceFolderScanContext
): Promise<Uint8Array<ArrayBuffer>> {
	const ranges =
		file.size <= maxDeviceFingerprintBytesPerFile
			? [{ start: 0, end: file.size }]
			: fingerprintSampleRanges(file.size);
	const chunks: Uint8Array<ArrayBuffer>[] = [];
	let totalBytes = 0;
	for (const range of ranges) {
		const chunk = new Uint8Array(
			await waitForOptionalScanOperation(file.slice(range.start, range.end).arrayBuffer(), context)
		);
		chunks.push(chunk);
		totalBytes += chunk.byteLength;
	}
	const sample = new Uint8Array(totalBytes);
	let offset = 0;
	for (const chunk of chunks) {
		sample.set(chunk, offset);
		offset += chunk.byteLength;
	}
	return sample;
}

function fingerprintSampleRanges(fileSize: number): { start: number; end: number }[] {
	const lastStart = fileSize - fingerprintWindowBytes;
	return Array.from({ length: fingerprintWindowDivisions + 1 }, (_, index) => {
		const start = Math.round((lastStart * index) / fingerprintWindowDivisions);
		return { start, end: start + fingerprintWindowBytes };
	});
}

export function rotateDeviceCandidates<T>(values: T[], offset: number): T[] {
	if (values.length === 0) return [];
	const normalized = normalizedCandidateOffset(offset, values.length);
	return [...values.slice(normalized), ...values.slice(0, normalized)];
}

export async function mapWithBoundedConcurrency<T, R>(
	values: T[],
	concurrency: number,
	worker: (value: T, index: number) => Promise<R>
): Promise<R[]> {
	if (!Number.isSafeInteger(concurrency) || concurrency < 1) {
		throw new Error('Device-folder concurrency must be a positive integer.');
	}
	const results = new Array<R>(values.length);
	let nextIndex = 0;
	const workers = Array.from({ length: Math.min(concurrency, values.length) }, async () => {
		while (true) {
			const index = nextIndex;
			if (index >= values.length) return;
			nextIndex += 1;
			const value = values[index] as T;
			results[index] = await worker(value, index);
		}
	});
	await Promise.all(workers);
	return results;
}

function normalizedCandidateOffset(offset: number, length: number): number {
	if (length === 0) return 0;
	return ((Math.trunc(offset) % length) + length) % length;
}

function scanTimeoutResult(progress: DeviceFolderScanProgress): DeviceFolderScanResult {
	return {
		result: 'timed-out',
		checkedCandidates: progress.phase === 'enumerating' ? 0 : progress.completed,
		...(progress.total === null ? {} : { totalCandidates: progress.total })
	};
}

function monotonicNow(): number {
	return globalThis.performance?.now?.() ?? Date.now();
}

function assertScanActive(context: DeviceFolderScanContext): void {
	if (context.signal.aborted) throw new DeviceFolderScanCancelledError();
	if (monotonicNow() >= context.deadline) throw new DeviceFolderScanTimeoutError();
}

function waitForOptionalScanOperation<T>(
	operation: Promise<T>,
	context?: DeviceFolderScanContext
): Promise<T> {
	return context ? waitForOperation(operation, context) : operation;
}

function waitForOperation<T>(operation: Promise<T>, context: DeviceFolderScanContext): Promise<T> {
	assertScanActive(context);
	const remainingMs = Math.max(1, context.deadline - monotonicNow());
	return new Promise<T>((resolve, reject) => {
		let settled = false;
		const finish = (callback: () => void) => {
			if (settled) return;
			settled = true;
			clearTimeout(timeout);
			context.signal.removeEventListener('abort', handleAbort);
			callback();
		};
		const handleAbort = () => {
			finish(() => {
				reject(new DeviceFolderScanCancelledError());
			});
		};
		const timeout = setTimeout(() => {
			finish(() => {
				reject(new DeviceFolderScanTimeoutError());
			});
		}, remainingMs);
		context.signal.addEventListener('abort', handleAbort, { once: true });
		operation.then(
			(value) => {
				finish(() => {
					resolve(value);
				});
			},
			(error: unknown) => {
				const reason =
					error instanceof Error
						? error
						: new Error('The device-folder operation failed.', { cause: error });
				finish(() => {
					reject(reason);
				});
			}
		);
	});
}

function combinedAbortSignal(signal: AbortSignal, timeoutMs: number): AbortSignal {
	return AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]);
}

function hexDigest(digest: ArrayBuffer): string {
	return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

async function uploadCandidate(
	file: File,
	generations: { activity: number; folder: number },
	signal: AbortSignal
): Promise<DeviceFolderScanResult> {
	if (file.size > maxGpxImportBytes) return { result: 'too-large' };
	const formData = new FormData();
	formData.append('activityGeneration', String(generations.activity));
	formData.append('folderGeneration', String(generations.folder));
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
		signal: combinedAbortSignal(signal, 30_000)
	});
	const body = (await response.json()) as { result?: unknown };
	const result = body.result;
	if (response.status === 429 && result === 'rate-limited') {
		const retryAfterSeconds = Number(response.headers.get('retry-after'));
		return {
			result: 'rate-limited',
			...(Number.isSafeInteger(retryAfterSeconds) && retryAfterSeconds > 0
				? { retryAfterSeconds }
				: {})
		};
	}
	if (
		typeof result !== 'string' ||
		![
			'imported',
			'duplicate',
			'deleted',
			'disconnected',
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

async function fetchImportGenerations(): Promise<{ activity: number; folder: number } | null> {
	try {
		const response = await fetch('/app/import/device/generation', {
			method: 'GET',
			headers: { accept: 'application/json' },
			credentials: 'same-origin',
			cache: 'no-store',
			signal: AbortSignal.timeout(10_000)
		});
		if (!response.ok) return null;
		const body = (await response.json()) as {
			activityGeneration?: unknown;
			folderGeneration?: unknown;
		};
		return typeof body.activityGeneration === 'number' &&
			Number.isSafeInteger(body.activityGeneration) &&
			body.activityGeneration >= 0 &&
			typeof body.folderGeneration === 'number' &&
			Number.isSafeInteger(body.folderGeneration) &&
			body.folderGeneration >= 0
			? { activity: body.activityGeneration, folder: body.folderGeneration }
			: null;
	} catch {
		return null;
	}
}

async function revokeServerBrowserFolderGeneration(): Promise<void> {
	const response = await fetch('/app/import/device/connection', {
		method: 'DELETE',
		credentials: 'same-origin',
		headers: { accept: 'application/json' },
		cache: 'no-store',
		signal: AbortSignal.timeout(10_000)
	});
	if (!response.ok) throw new Error('Browser folder disconnection could not be recorded.');
}

async function revalidateDeviceFolderCapability(
	userId: string,
	handle: FileSystemDirectoryHandle,
	scanRevision: number
): Promise<'linked' | 'unlinked' | 'permission-required' | 'failed'> {
	if (blockAllScans || blockedUsers.has(userId) || capabilityRevision !== scanRevision) {
		return 'unlinked';
	}
	try {
		const stored = await getStoredFolder(userId);
		if (!stored || !(await isSameDirectory(stored, handle))) return 'unlinked';
		if ((await queryPermission(stored)) !== 'granted') return 'permission-required';
	} catch {
		return 'failed';
	}
	return blockAllScans || blockedUsers.has(userId) || capabilityRevision !== scanRevision
		? 'unlinked'
		: 'linked';
}

function ensureDeviceFolderControlChannel(): BroadcastChannel | null {
	if (controlChannel !== undefined) return controlChannel;
	if (typeof window === 'undefined' || typeof BroadcastChannel === 'undefined') {
		controlChannel = null;
		return null;
	}
	try {
		controlChannel = new BroadcastChannel(controlChannelName);
		controlChannel.addEventListener('message', (event: MessageEvent<unknown>) => {
			if (isDeviceFolderControlMessage(event.data)) applyDeviceFolderControl(event.data);
		});
	} catch {
		controlChannel = null;
	}
	return controlChannel;
}

function signalDeviceFolderControl(message: DeviceFolderControlMessage): void {
	applyDeviceFolderControl(message);
	try {
		ensureDeviceFolderControlChannel()?.postMessage(message);
	} catch {
		// IndexedDB capability revalidation remains the cross-tab backstop when
		// BroadcastChannel is unavailable or a browser profile is shutting down.
	}
}

function applyDeviceFolderControl(message: DeviceFolderControlMessage): void {
	capabilityRevision += 1;
	globalThis.dispatchEvent?.(new CustomEvent(deviceFolderControlEvent, { detail: message }));
	if (message.type === 'clear-all') {
		blockAllScans = true;
		for (const active of activeScans.values()) active.controller.abort();
		return;
	}
	if (message.type === 'disconnected') {
		blockedUsers.add(message.userId);
		activeScans.get(message.userId)?.controller.abort();
		return;
	}
	blockAllScans = false;
	blockedUsers.delete(message.userId);
}

export function isDeviceFolderControlMessage(value: unknown): value is DeviceFolderControlMessage {
	if (!value || typeof value !== 'object' || !('type' in value)) return false;
	if (value.type === 'clear-all') return true;
	return (
		(value.type === 'connected' || value.type === 'disconnected') &&
		'userId' in value &&
		typeof value.userId === 'string' &&
		value.userId.length > 0 &&
		value.userId.length <= 200
	);
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

async function clearDeviceFolderScanState(userId: string): Promise<void> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(scanStateStoreName, 'readwrite');
		transaction.objectStore(scanStateStoreName).delete(userId);
		await transactionComplete(transaction);
	} finally {
		database.close();
	}
}

async function getSettledDirectorySignature(userId: string): Promise<string | null> {
	const database = await openDatabase();
	try {
		const transaction = database.transaction(scanStateStoreName, 'readonly');
		const stored = await requestResult(transaction.objectStore(scanStateStoreName).get(userId));
		await transactionComplete(transaction);
		return isDeviceFolderScanState(stored, userId) ? stored.settledSignature : null;
	} finally {
		database.close();
	}
}

async function storeSettledDirectorySignature(userId: string, settledSignature: string) {
	if (blockAllScans || blockedUsers.has(userId)) return;
	const database = await openDatabase();
	try {
		const transaction = database.transaction(scanStateStoreName, 'readwrite');
		transaction
			.objectStore(scanStateStoreName)
			.put({ userId, settledSignature } satisfies DeviceFolderScanState);
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
			if (!database.objectStoreNames.contains(scanStateStoreName)) {
				database.createObjectStore(scanStateStoreName, { keyPath: 'userId' });
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

function isDeviceFolderScanState(value: unknown, userId: string): value is DeviceFolderScanState {
	return (
		value !== null &&
		typeof value === 'object' &&
		'userId' in value &&
		'settledSignature' in value &&
		value.userId === userId &&
		typeof value.settledSignature === 'string'
	);
}
