import { createReadStream } from 'node:fs';
import { chmod, lstat, mkdtemp, open, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Readable } from 'node:stream';

export type JsonSink = {
	write(chunk: string): Promise<void>;
};

export type PageReader<Row> = (offset: number, limit: number) => Promise<readonly Row[]>;

export const stagedExportPageSize = 250;
export const staleStagedExportAgeMs = 24 * 60 * 60 * 1_000;
const stagedExportDirectoryPrefix = 'runway-training-export-';

export async function reapStaleStagedExports(
	rootDirectory = tmpdir(),
	now = new Date()
): Promise<number> {
	const entries = await readdir(rootDirectory, { withFileTypes: true }).catch(() => []);
	let removed = 0;
	for (const entry of entries) {
		if (!entry.name.startsWith(stagedExportDirectoryPrefix) || !entry.isDirectory()) continue;
		const directoryPath = join(rootDirectory, entry.name);
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
	#cleaned = false;
	#opened = false;

	constructor(directoryPath: string, filePath: string, byteLength: number) {
		this.#directoryPath = directoryPath;
		this.#filePath = filePath;
		this.byteLength = byteLength;
	}

	openBody(): ReadableStream<Uint8Array> {
		if (this.#cleaned) throw new Error('The staged export has already been removed.');
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
		if (this.#cleaned) return;
		this.#cleaned = true;
		await rm(this.#directoryPath, { recursive: true, force: true });
	}
}

export async function stageJsonArtifact(
	writeJson: (sink: JsonSink) => Promise<void>
): Promise<StagedJsonArtifact> {
	await reapStaleStagedExports();
	const directoryPath = await mkdtemp(join(tmpdir(), stagedExportDirectoryPrefix));
	await chmod(directoryPath, 0o700);
	const filePath = join(directoryPath, 'training-data.json');
	let fileHandle: Awaited<ReturnType<typeof open>> | null = null;

	try {
		fileHandle = await open(filePath, 'wx', 0o600);
		const writableFile = fileHandle;
		const sink: JsonSink = {
			write: async (chunk) => {
				await writableFile.writeFile(chunk, { encoding: 'utf8' });
			}
		};
		await writeJson(sink);
		await fileHandle.sync();
		await fileHandle.close();
		fileHandle = null;
		const fileStat = await stat(filePath);
		return new StagedJsonArtifact(directoryPath, filePath, fileStat.size);
	} catch (cause) {
		await fileHandle?.close().catch(() => undefined);
		await rm(directoryPath, { recursive: true, force: true }).catch(() => undefined);
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
