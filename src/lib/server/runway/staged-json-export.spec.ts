import { mkdir, mkdtemp, rm, symlink, utimes } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, test, vi } from 'vitest';
import {
	reapStaleStagedExports,
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
});
