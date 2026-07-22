import { chmod, mkdir, mkdtemp, readdir, rm, symlink, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, test, vi } from 'vitest';
import {
	reapConfiguredStagedExports,
	reapStaleStagedExports,
	StagedExportCapacityError,
	StagedJsonArtifact,
	stageJsonArtifact,
	stageThenRecordSuccess,
	staleStagedExportAgeMs,
	writePagedJsonArray,
	type JsonSink
} from './staged-json-export';

function memorySink(): { chunks: string[]; sink: JsonSink } {
	const chunks: string[] = [];
	return {
		chunks,
		sink: {
			write: (chunk) => {
				chunks.push(chunk);
				return Promise.resolve();
			}
		}
	};
}

describe('staged JSON export', () => {
	test('serializes a large collection in bounded pages without truncation', async () => {
		expect.assertions(5);
		const totalRows = 10_003;
		const rows = Array.from({ length: totalRows }, (_, id) => ({ id, note: `run-${id}` }));
		const requestedPages: { offset: number; limit: number }[] = [];
		const { chunks, sink } = memorySink();

		const written = await writePagedJsonArray(
			sink,
			(offset, limit) => {
				requestedPages.push({ offset, limit });
				return Promise.resolve(rows.slice(offset, offset + limit));
			},
			250
		);

		expect(written).toBe(totalRows);
		expect(Math.max(...requestedPages.map(({ limit }) => limit))).toBe(250);
		expect(requestedPages).toHaveLength(41);
		const parsed = JSON.parse(chunks.join('')) as typeof rows;
		expect(parsed).toHaveLength(totalRows);
		expect(parsed.at(-1)).toEqual({ id: 10_002, note: 'run-10002' });
	});

	test('does not record success when staging fails', async () => {
		expect.assertions(2);
		const recordSuccess = vi.fn();
		await expect(
			stageThenRecordSuccess(() => Promise.reject(new Error('disk full')), recordSuccess)
		).rejects.toThrow('disk full');
		expect(recordSuccess).not.toHaveBeenCalled();
	});

	test('removes a completed artifact when recording success fails', async () => {
		expect.assertions(2);
		const artifact = await stageJsonArtifact((sink) => sink.write('{"ok":true}'));
		const cleanup = vi.spyOn(artifact, 'cleanup');

		await expect(
			stageThenRecordSuccess(
				() => Promise.resolve(artifact),
				() => Promise.reject(new Error('audit unavailable'))
			)
		).rejects.toThrow('audit unavailable');
		expect(cleanup).toHaveBeenCalledOnce();
	});

	test('streams the staged file and removes it after consumption', async () => {
		expect.assertions(2);
		const artifact = await stageJsonArtifact(async (sink) => {
			await sink.write('{"private":');
			await sink.write('"data"');
			await sink.write('}');
		});
		const response = new Response(artifact.openBody());
		await expect(response.json()).resolves.toEqual({ private: 'data' });
		await expect(artifact.cleanup()).resolves.toBeUndefined();
	});

	test('cleans the staged artifact promptly when the client cancels', async () => {
		expect.assertions(1);
		const artifact = await stageJsonArtifact((sink) => sink.write('[]'));
		const cleanup = vi.spyOn(artifact, 'cleanup');
		await artifact.openBody().cancel('client disconnected');
		expect(cleanup).toHaveBeenCalledOnce();
	});

	test('cleans the staging directory when the file cannot be read', async () => {
		expect.assertions(2);
		const directoryPath = await mkdtemp(join(tmpdir(), 'runway-training-export-error-'));
		const artifact = new StagedJsonArtifact(directoryPath, join(directoryPath, 'missing.json'), 1);
		const cleanup = vi.spyOn(artifact, 'cleanup');
		await expect(new Response(artifact.openBody()).text()).rejects.toThrow();
		expect(cleanup).toHaveBeenCalledOnce();
	});

	test('enforces the configured byte limit using encoded bytes and removes the partial file', async () => {
		expect.assertions(3);
		const root = await mkdtemp(join(tmpdir(), 'runway-export-byte-limit-test-'));
		try {
			await expect(
				stageJsonArtifact((sink) => sink.write('ééé'), {
					rootDirectory: root,
					maxBytes: 5,
					maxConcurrent: 1,
					quotaBytes: 5
				})
			).rejects.toBeInstanceOf(StagedExportCapacityError);
			await expect(readdir(root)).resolves.toEqual([]);
			await expect(
				stageJsonArtifact((sink) => sink.write('éé'), {
					rootDirectory: root,
					maxBytes: 5,
					maxConcurrent: 1,
					quotaBytes: 5
				}).then((artifact) => artifact.cleanup())
			).resolves.toBeUndefined();
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});

	test('holds a concurrency reservation until the staged response is cleaned', async () => {
		expect.assertions(3);
		const root = await mkdtemp(join(tmpdir(), 'runway-export-concurrency-test-'));
		const options = {
			rootDirectory: root,
			maxBytes: 64,
			maxConcurrent: 1,
			quotaBytes: 64
		};
		try {
			const first = await stageJsonArtifact((sink) => sink.write('[]'), options);
			await expect(stageJsonArtifact((sink) => sink.write('[]'), options)).rejects.toThrow(
				'Another training-data export is already being prepared'
			);
			await expect(first.openBody().cancel('client disconnected')).resolves.toBeUndefined();
			const next = await stageJsonArtifact((sink) => sink.write('[]'), options);
			await expect(next.cleanup()).resolves.toBeUndefined();
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});

	test('reserves capacity around recent crash artifacts instead of overcommitting the quota', async () => {
		expect.assertions(2);
		const root = await mkdtemp(join(tmpdir(), 'runway-export-quota-test-'));
		try {
			const orphan = join(root, 'runway-training-export-recent-crash');
			await mkdir(orphan);
			await writeFile(join(orphan, 'training-data.json'), '123456', { mode: 0o600 });

			await expect(
				stageJsonArtifact((sink) => sink.write('[]'), {
					rootDirectory: root,
					maxBytes: 10,
					maxConcurrent: 2,
					quotaBytes: 15
				})
			).rejects.toThrow('staging quota is currently unavailable');
			await expect(readdir(orphan)).resolves.toEqual(['training-data.json']);
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});

	test('rejects an invalid staging configuration before creating an artifact', async () => {
		expect.assertions(1);
		await expect(
			stageJsonArtifact((sink) => sink.write('[]'), {
				rootDirectory: 'relative/export-path',
				maxBytes: 10,
				maxConcurrent: 1,
				quotaBytes: 10
			})
		).rejects.toThrow('must be an absolute path');
	});

	test('refuses a staging root exposed to other operating-system users', async () => {
		expect.assertions(1);
		const root = await mkdtemp(join(tmpdir(), 'runway-export-permission-test-'));
		try {
			await chmod(root, 0o755);
			await expect(
				stageJsonArtifact((sink) => sink.write('[]'), {
					rootDirectory: root,
					maxBytes: 10,
					maxConcurrent: 1,
					quotaBytes: 10
				})
			).rejects.toThrow('must not be accessible by group or other users');
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});

	test('reaps only old runway export directories', async () => {
		expect.assertions(5);
		const root = await mkdtemp(join(tmpdir(), 'runway-export-reaper-test-'));
		try {
			const oldExport = join(root, 'runway-training-export-old');
			const activeExport = join(root, 'runway-training-export-active');
			const unrelated = join(root, 'other-export-old');
			const symlinkPath = join(root, 'runway-training-export-link');
			await Promise.all([mkdir(oldExport), mkdir(activeExport), mkdir(unrelated)]);
			await symlink(unrelated, symlinkPath, 'dir');
			const now = new Date('2026-07-18T18:00:00.000Z');
			const oldTime = new Date(now.getTime() - staleStagedExportAgeMs - 1);
			await Promise.all([utimes(oldExport, oldTime, oldTime), utimes(unrelated, oldTime, oldTime)]);

			await expect(reapStaleStagedExports(root, now)).resolves.toBe(1);
			await expect(mkdir(oldExport)).resolves.toBeUndefined();
			await expect(mkdir(activeExport)).rejects.toThrow();
			await expect(mkdir(unrelated)).rejects.toThrow();
			await expect(mkdir(symlinkPath)).rejects.toThrow();
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});

	test('reaps the configured app staging root without waiting for another export', async () => {
		const root = await mkdtemp(join(tmpdir(), 'runway-export-configured-reaper-test-'));
		try {
			const oldExport = join(root, 'runway-training-export-process-crash');
			await mkdir(oldExport);
			const now = new Date('2026-07-22T03:00:00.000Z');
			const oldTime = new Date(now.getTime() - staleStagedExportAgeMs - 1);
			await utimes(oldExport, oldTime, oldTime);

			await expect(
				reapConfiguredStagedExports(
					{ rootDirectory: root, maxBytes: 64, maxConcurrent: 1, quotaBytes: 64 },
					now
				)
			).resolves.toBe(1);
			await expect(readdir(root)).resolves.toEqual([]);
		} finally {
			await rm(root, { recursive: true, force: true });
		}
	});
});
