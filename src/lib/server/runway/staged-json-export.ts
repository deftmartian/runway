import { createReadStream } from 'node:fs';
import { chmod, lstat, mkdir, mkdtemp, open, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { isAbsolute, join, resolve } from 'node:path';
import { Readable } from 'node:stream';

export type JsonSink = {
	write(chunk: string): Promise<void>;
};

export type PageReader<Row> = (offset: number, limit: number) => Promise<readonly Row[]>;

export const stagedExportPageSize = 250;
export const staleStagedExportAgeMs = 24 * 60 * 60 * 1_000;
const stagedExportDirectoryPrefix = 'runway-training-export-';
const stagedExportFilename = 'training-data.json';
const mebibyte = 1024 * 1024;
export const defaultStagedExportMaxBytes = 256 * mebibyte;
export const defaultStagedExportMaxConcurrent = 1;
export const defaultStagedExportQuotaBytes = 256 * mebibyte;
export const stagedExportReaperIntervalMs = 5 * 60 * 1_000;
const largestStagedExportMaxBytes = 4 * 1024 * mebibyte;
const largestStagedExportQuotaBytes = 16 * 1024 * mebibyte;
const largestStagedExportConcurrency = 8;

export type StagedJsonExportOptions = {
	rootDirectory?: string;
	maxBytes?: number;
	maxConcurrent?: number;
	quotaBytes?: number;
};

type ResolvedStagedJsonExportOptions = Required<StagedJsonExportOptions>;

export class StagedExportCapacityError extends Error {
	constructor(message: string) {
		super(message);
		this.name = 'StagedExportCapacityError';
	}
}

function parseBoundedPositiveInteger(
	value: string | undefined,
	fallback: number,
	name: string,
	maximum: number
): number {
	if (value === undefined || value.trim() === '') return fallback;
	if (!/^\d+$/.test(value.trim())) {
		throw new Error(`${name} must be a positive integer.`);
	}
	const parsed = Number(value);
	if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > maximum) {
		throw new Error(`${name} must be between 1 and ${maximum}.`);
	}
	return parsed;
}

function resolveStagedJsonExportOptions(
	options: StagedJsonExportOptions = {}
): ResolvedStagedJsonExportOptions {
	const configuredRootDirectory =
		options.rootDirectory ??
		process.env['RUNWAY_EXPORT_STAGING_DIRECTORY']?.trim() ??
		join(tmpdir(), 'runway-exports');
	if (!configuredRootDirectory || !isAbsolute(configuredRootDirectory)) {
		throw new Error('RUNWAY_EXPORT_STAGING_DIRECTORY must be an absolute path.');
	}
	const rootDirectory = resolve(configuredRootDirectory);

	const maxBytes =
		options.maxBytes ??
		parseBoundedPositiveInteger(
			process.env['RUNWAY_EXPORT_MAX_BYTES'],
			defaultStagedExportMaxBytes,
			'RUNWAY_EXPORT_MAX_BYTES',
			largestStagedExportMaxBytes
		);
	const maxConcurrent =
		options.maxConcurrent ??
		parseBoundedPositiveInteger(
			process.env['RUNWAY_EXPORT_MAX_CONCURRENT'],
			defaultStagedExportMaxConcurrent,
			'RUNWAY_EXPORT_MAX_CONCURRENT',
			largestStagedExportConcurrency
		);
	const quotaBytes =
		options.quotaBytes ??
		parseBoundedPositiveInteger(
			process.env['RUNWAY_EXPORT_STAGING_QUOTA_BYTES'],
			defaultStagedExportQuotaBytes,
			'RUNWAY_EXPORT_STAGING_QUOTA_BYTES',
			largestStagedExportQuotaBytes
		);

	for (const [name, value, maximum] of [
		['maxBytes', maxBytes, largestStagedExportMaxBytes],
		['maxConcurrent', maxConcurrent, largestStagedExportConcurrency],
		['quotaBytes', quotaBytes, largestStagedExportQuotaBytes]
	] as const) {
		if (!Number.isSafeInteger(value) || value < 1 || value > maximum) {
			throw new Error(`Export ${name} must be between 1 and ${maximum}.`);
		}
	}
	if (quotaBytes < maxBytes) {
		throw new Error('Export staging quota must be at least the per-export byte limit.');
	}

	return { rootDirectory, maxBytes, maxConcurrent, quotaBytes };
}

async function ensureStagingRoot(rootDirectory: string): Promise<void> {
	await mkdir(rootDirectory, { recursive: true, mode: 0o700 });
	const rootStat = await lstat(rootDirectory);
	if (!rootStat.isDirectory() || rootStat.isSymbolicLink()) {
		throw new Error('Export staging path must be a real directory.');
	}
	if (typeof process.geteuid === 'function' && rootStat.uid !== process.geteuid()) {
		throw new Error('Export staging directory must be owned by the application user.');
	}
	if ((rootStat.mode & 0o077) !== 0) {
		throw new Error('Export staging directory must not be accessible by group or other users.');
	}
}

export async function reapStaleStagedExports(
	rootDirectory = tmpdir(),
	now = new Date(),
	protectedDirectories: ReadonlySet<string> = new Set()
): Promise<number> {
	const entries = await readdir(rootDirectory, { withFileTypes: true }).catch(() => []);
	let removed = 0;
	for (const entry of entries) {
		if (!entry.name.startsWith(stagedExportDirectoryPrefix) || !entry.isDirectory()) continue;
		const directoryPath = join(rootDirectory, entry.name);
		if (protectedDirectories.has(directoryPath)) continue;
		const directoryStat = await lstat(directoryPath).catch(() => null);
		if (
			!directoryStat ||
			!directoryStat.isDirectory() ||
			directoryStat.isSymbolicLink() ||
			now.getTime() - directoryStat.mtimeMs < staleStagedExportAgeMs
		) {
			continue;
		}
		try {
			await rm(directoryPath, { recursive: true, force: true });
			removed += 1;
		} catch {
			// A concurrent request or the operating system may still own the directory.
		}
	}
	return removed;
}

async function measureOrphanedStagedExportBytes(
	rootDirectory: string,
	activeDirectories: ReadonlySet<string>
): Promise<number> {
	const entries = await readdir(rootDirectory, { withFileTypes: true }).catch(() => []);
	let total = 0;
	for (const entry of entries) {
		if (!entry.name.startsWith(stagedExportDirectoryPrefix) || !entry.isDirectory()) continue;
		const directoryPath = join(rootDirectory, entry.name);
		if (activeDirectories.has(directoryPath)) continue;
		const directoryStat = await lstat(directoryPath).catch(() => null);
		if (!directoryStat?.isDirectory() || directoryStat.isSymbolicLink()) continue;
		const fileStat = await lstat(join(directoryPath, stagedExportFilename)).catch(() => null);
		if (!fileStat?.isFile() || fileStat.isSymbolicLink()) continue;
		total += fileStat.size;
	}
	return total;
}

type StagingReservation = {
	attach: (directoryPath: string) => void;
	release: () => void;
};

class StagingCapacity {
	readonly #activeDirectories = new Set<string>();
	#activeReservations = 0;
	#reservedBytes = 0;
	#mutation = Promise.resolve();

	async reap(rootDirectory: string, now = new Date()): Promise<number> {
		let unlock = (): void => undefined;
		const previousMutation = this.#mutation;
		this.#mutation = new Promise<void>((resolve) => {
			unlock = resolve;
		});
		await previousMutation;
		try {
			return await reapStaleStagedExports(rootDirectory, now, this.#activeDirectories);
		} finally {
			unlock();
		}
	}

	async reserve(options: ResolvedStagedJsonExportOptions): Promise<StagingReservation> {
		let unlock = (): void => undefined;
		const previousMutation = this.#mutation;
		this.#mutation = new Promise<void>((resolve) => {
			unlock = resolve;
		});
		await previousMutation;

		try {
			await reapStaleStagedExports(options.rootDirectory, new Date(), this.#activeDirectories);
			if (this.#activeReservations >= options.maxConcurrent) {
				throw new StagedExportCapacityError(
					'Another training-data export is already being prepared. Try again after it finishes.'
				);
			}
			const orphanedBytes = await measureOrphanedStagedExportBytes(
				options.rootDirectory,
				this.#activeDirectories
			);
			if (orphanedBytes + this.#reservedBytes + options.maxBytes > options.quotaBytes) {
				throw new StagedExportCapacityError(
					'The training-data export staging quota is currently unavailable. Try again later.'
				);
			}
			this.#activeReservations += 1;
			this.#reservedBytes += options.maxBytes;
		} finally {
			unlock();
		}

		let directoryPath: string | null = null;
		let released = false;
		return {
			attach: (path) => {
				if (released || directoryPath) throw new Error('Invalid export staging reservation state.');
				directoryPath = path;
				this.#activeDirectories.add(path);
			},
			release: () => {
				if (released) return;
				released = true;
				if (directoryPath) this.#activeDirectories.delete(directoryPath);
				this.#activeReservations -= 1;
				this.#reservedBytes -= options.maxBytes;
			}
		};
	}
}

const stagingCapacityByRoot = new Map<string, StagingCapacity>();

function stagingCapacity(rootDirectory: string): StagingCapacity {
	let capacity = stagingCapacityByRoot.get(rootDirectory);
	if (!capacity) {
		capacity = new StagingCapacity();
		stagingCapacityByRoot.set(rootDirectory, capacity);
	}
	return capacity;
}

declare global {
	var runwayStagedExportReaperStarted: boolean | undefined;
}

export async function reapConfiguredStagedExports(
	options?: StagedJsonExportOptions,
	now = new Date()
): Promise<number> {
	const resolvedOptions = resolveStagedJsonExportOptions(options);
	await ensureStagingRoot(resolvedOptions.rootDirectory);
	return stagingCapacity(resolvedOptions.rootDirectory).reap(resolvedOptions.rootDirectory, now);
}

export function startStagedExportReaper(): void {
	if (globalThis.runwayStagedExportReaperStarted) return;
	globalThis.runwayStagedExportReaperStarted = true;
	const run = async () => {
		try {
			await reapConfiguredStagedExports();
		} catch {
			console.error('Stale training-data export cleanup failed; the app will retry.');
		}
	};
	void run();
	setInterval(() => void run(), stagedExportReaperIntervalMs).unref();
}

export async function writePagedJsonArray<Row>(
	sink: JsonSink,
	readPage: PageReader<Row>,
	pageSize = stagedExportPageSize
): Promise<number> {
	if (!Number.isSafeInteger(pageSize) || pageSize < 1) {
		throw new Error('Export page size must be a positive safe integer.');
	}

	await sink.write('[');
	let offset = 0;
	let first = true;

	for (;;) {
		const rows = await readPage(offset, pageSize);
		if (rows.length > pageSize) {
			throw new Error('Export page reader returned more rows than requested.');
		}
		for (const row of rows) {
			const encoded = JSON.stringify(row);
			if (encoded === undefined) throw new Error('Export rows must be JSON serializable.');
			await sink.write(first ? encoded : `,${encoded}`);
			first = false;
		}
		offset += rows.length;
		if (rows.length < pageSize) break;
	}

	await sink.write(']');
	return offset;
}

export class StagedJsonArtifact {
	readonly byteLength: number;
	readonly #directoryPath: string;
	readonly #filePath: string;
	readonly #releaseCapacity: () => void;
	#cleanupPromise: Promise<void> | null = null;
	#opened = false;

	constructor(
		directoryPath: string,
		filePath: string,
		byteLength: number,
		releaseCapacity: () => void = () => undefined
	) {
		this.#directoryPath = directoryPath;
		this.#filePath = filePath;
		this.byteLength = byteLength;
		this.#releaseCapacity = releaseCapacity;
	}

	openBody(): ReadableStream<Uint8Array> {
		if (this.#cleanupPromise) throw new Error('The staged export has already been removed.');
		if (this.#opened) throw new Error('The staged export can only be opened once.');
		this.#opened = true;

		const fileStream = createReadStream(this.#filePath, { highWaterMark: 64 * 1024 });
		const reader = (Readable.toWeb(fileStream) as ReadableStream<Uint8Array>).getReader();
		const cleanup = () => this.cleanup();

		return new ReadableStream<Uint8Array>({
			async pull(controller) {
				try {
					const next = await reader.read();
					if (next.done) {
						controller.close();
						await cleanup();
						return;
					}
					controller.enqueue(next.value);
				} catch (cause) {
					controller.error(cause);
					await cleanup();
				}
			},
			async cancel(reason) {
				try {
					await reader.cancel(reason);
				} finally {
					await cleanup();
				}
			}
		});
	}

	async cleanup(): Promise<void> {
		if (!this.#cleanupPromise) {
			this.#cleanupPromise = (async () => {
				try {
					await rm(this.#directoryPath, { recursive: true, force: true });
				} finally {
					this.#releaseCapacity();
				}
			})();
		}
		await this.#cleanupPromise;
	}
}

export async function stageJsonArtifact(
	writeJson: (sink: JsonSink) => Promise<void>,
	options?: StagedJsonExportOptions
): Promise<StagedJsonArtifact> {
	const resolvedOptions = resolveStagedJsonExportOptions(options);
	await ensureStagingRoot(resolvedOptions.rootDirectory);
	const reservation = await stagingCapacity(resolvedOptions.rootDirectory).reserve(resolvedOptions);
	let directoryPath: string;
	try {
		directoryPath = await mkdtemp(join(resolvedOptions.rootDirectory, stagedExportDirectoryPrefix));
		reservation.attach(directoryPath);
	} catch (cause) {
		reservation.release();
		throw cause;
	}
	const filePath = join(directoryPath, stagedExportFilename);
	let fileHandle: Awaited<ReturnType<typeof open>> | null = null;
	let bytesWritten = 0;

	try {
		await chmod(directoryPath, 0o700);
		fileHandle = await open(filePath, 'wx', 0o600);
		const writableFile = fileHandle;
		const sink: JsonSink = {
			write: async (chunk) => {
				const nextBytes = Buffer.byteLength(chunk, 'utf8');
				if (bytesWritten + nextBytes > resolvedOptions.maxBytes) {
					throw new StagedExportCapacityError(
						`Training-data export exceeds the configured ${resolvedOptions.maxBytes}-byte limit.`
					);
				}
				await writableFile.writeFile(chunk, { encoding: 'utf8' });
				bytesWritten += nextBytes;
			}
		};
		await writeJson(sink);
		await fileHandle.sync();
		await fileHandle.close();
		fileHandle = null;
		const fileStat = await stat(filePath);
		return new StagedJsonArtifact(directoryPath, filePath, fileStat.size, reservation.release);
	} catch (cause) {
		await fileHandle?.close().catch(() => undefined);
		await rm(directoryPath, { recursive: true, force: true }).catch(() => undefined);
		reservation.release();
		throw cause;
	}
}

export async function stageThenRecordSuccess(
	stage: () => Promise<StagedJsonArtifact>,
	recordSuccess: (artifact: StagedJsonArtifact) => Promise<void>
): Promise<StagedJsonArtifact> {
	const artifact = await stage();
	try {
		await recordSuccess(artifact);
		return artifact;
	} catch (cause) {
		await artifact.cleanup();
		throw cause;
	}
}
