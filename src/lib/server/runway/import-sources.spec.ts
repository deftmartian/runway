import { afterEach, describe, expect, test, vi } from 'vitest';
import {
	enforceImportSourceLimit,
	importSourceLimitMessage,
	maxImportSourcesPerUser,
	normalizeNextcloudSourceInput,
	selectNewestRemoteCandidate,
	type KnownNextcloudSourceItem,
	verifyNextcloudShareCredentials
} from './import-sources';
import { parseNextcloudShareUrl, type NextcloudRemoteFile } from './nextcloud';

afterEach(() => {
	vi.unstubAllGlobals();
	vi.unstubAllEnvs();
});

describe('normalizeNextcloudSourceInput', () => {
	test('trims display fields without changing a whitespace-sensitive password', () => {
		expect.assertions(1);

		expect(
			normalizeNextcloudSourceInput({
				label: '  Morning exports  ',
				shareUrl: '  https://cloud.example.test/s/token  ',
				sharePassword: '  exact password\t'
			})
		).toEqual({
			label: 'Morning exports',
			shareUrl: 'https://cloud.example.test/s/token',
			sharePassword: '  exact password\t'
		});
	});

	test('bounds labels, URLs, and passwords before network or database work', () => {
		expect.assertions(3);
		const base = {
			label: 'Folder',
			shareUrl: 'https://cloud.example.test/s/token',
			sharePassword: 'password'
		};

		expect(() => normalizeNextcloudSourceInput({ ...base, label: 'x'.repeat(121) })).toThrow(
			/label is too long/
		);
		expect(() =>
			normalizeNextcloudSourceInput({ ...base, shareUrl: `https://${'x'.repeat(2_050)}` })
		).toThrow(/URL is too long/);
		expect(() =>
			normalizeNextcloudSourceInput({ ...base, sharePassword: 'x'.repeat(1_025) })
		).toThrow(/password is too long/);
	});
});

describe('import source product limit', () => {
	test('allows updating an existing source but rejects an eleventh source clearly', () => {
		expect(() => {
			enforceImportSourceLimit(true, maxImportSourcesPerUser);
		}).not.toThrow();
		expect(() => {
			enforceImportSourceLimit(false, maxImportSourcesPerUser - 1);
		}).not.toThrow();
		expect(() => {
			enforceImportSourceLimit(false, maxImportSourcesPerUser);
		}).toThrow(importSourceLimitMessage);
	});
});

describe('selectNewestRemoteCandidate', () => {
	const now = new Date('2026-07-15T20:00:00Z').getTime();
	const newest = remoteFile('newest.gpx', 'newest-v1', '2026-07-15T19:00:00Z', 200);
	const older = remoteFile('older.gpx', 'older-v1', '2026-07-14T19:00:00Z', 100);

	test('backfills the next older unknown file after the newest revision was imported', () => {
		expect.assertions(2);
		const result = selectNewestRemoteCandidate(
			[newest, older],
			[knownItem(newest, 'imported', new Date(now))],
			testRemoteKey,
			now
		);

		expect(result.file?.href).toBe(older.href);
		expect(result.alreadyHandled).toBe(false);
	});

	test('backfills past an unchanged failed revision', () => {
		expect.assertions(3);
		const result = selectNewestRemoteCandidate(
			[newest, older],
			[knownItem(newest, 'failed', new Date(now))],
			testRemoteKey,
			now
		);

		expect(result.file?.href).toBe(older.href);
		expect(result.alreadyHandled).toBe(false);
		expect(
			selectNewestRemoteCandidate(
				[{ ...newest, etag: null, contentLength: null, lastModifiedAt: null }, older],
				[
					knownItem(
						{ ...newest, etag: null, contentLength: null, lastModifiedAt: null },
						'failed',
						new Date(now)
					)
				],
				testRemoteKey,
				now
			).file?.href
		).toBe(older.href);
	});

	test('reimports the same path when its ETag changes', () => {
		expect.assertions(1);
		const priorRevision = { ...newest, etag: 'newest-v0' };

		expect(
			selectNewestRemoteCandidate(
				[newest, older],
				[knownItem(priorRevision, 'imported', new Date(now))],
				testRemoteKey,
				now
			).file?.href
		).toBe(newest.href);
	});

	test('uses content metadata when the server does not provide ETags', () => {
		expect.assertions(1);
		const noEtag = { ...newest, etag: null };
		const priorRevision = { ...noEtag, contentLength: 199 };

		expect(
			selectNewestRemoteCandidate(
				[noEtag],
				[knownItem(priorRevision, 'imported', new Date(now))],
				testRemoteKey,
				now
			).file?.href
		).toBe(noEtag.href);
	});

	test('reports handled only when every visible revision is already imported', () => {
		expect.assertions(1);

		expect(
			selectNewestRemoteCandidate(
				[newest, older],
				[knownItem(newest, 'imported', new Date(now)), knownItem(older, 'imported', new Date(now))],
				testRemoteKey,
				now
			)
		).toEqual({ file: null, alreadyHandled: true });
	});

	test('does not advance to another file while a fresh claim is active', () => {
		expect.assertions(1);

		expect(
			selectNewestRemoteCandidate(
				[newest, older],
				[knownItem(newest, 'importing', new Date(now - 60_000))],
				testRemoteKey,
				now
			)
		).toEqual({ file: null, alreadyHandled: true });
	});

	test('retries stale claims and changed prior failures', () => {
		expect.assertions(2);
		const stale = selectNewestRemoteCandidate(
			[newest],
			[knownItem(newest, 'importing', new Date(now - 31 * 60_000))],
			testRemoteKey,
			now
		);
		const failed = selectNewestRemoteCandidate(
			[newest],
			[knownItem({ ...newest, etag: 'newest-v0' }, 'failed', new Date(now))],
			testRemoteKey,
			now
		);

		expect(stale.file?.href).toBe(newest.href);
		expect(failed.file?.href).toBe(newest.href);
	});
});

describe('Nextcloud password-protection probe', () => {
	test('does not treat an arbitrary WebDAV failure as proof that the share is protected', async () => {
		expect.assertions(2);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');
		const fetchMock = vi.fn(() => Promise.resolve(new Response('', { status: 500 })));
		vi.stubGlobal('fetch', fetchMock);

		await expect(saveSource('password')).rejects.toThrow('Nextcloud share could not be read.');
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});

	test('accepts only an explicit authentication rejection and preserves password whitespace', async () => {
		expect.assertions(3);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');
		const fetchMock = vi.fn((input: URL | RequestInfo, init?: RequestInit) => {
			void input;
			void init;
			return Promise.resolve(new Response('', { status: 401 }));
		});
		vi.stubGlobal('fetch', fetchMock);
		const password = '  exact password  ';

		await expect(saveSource(password)).rejects.toThrow('Nextcloud share password was rejected.');
		expect(fetchMock).toHaveBeenCalledTimes(2);
		const secondInit = fetchMock.mock.calls[1]?.[1];
		const authorization = new Headers(secondInit?.headers).get('authorization') ?? '';
		expect(Buffer.from(authorization.slice('Basic '.length), 'base64').toString('utf8')).toBe(
			`anonymous:${password}`
		);
	});

	test('rejects a share that lists successfully with the deliberately wrong password', async () => {
		expect.assertions(2);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');
		const fetchMock = vi.fn(() =>
			Promise.resolve(new Response(emptyWebDavFolder(), { status: 207 }))
		);
		vi.stubGlobal('fetch', fetchMock);

		await expect(saveSource('password')).rejects.toThrow(
			'Nextcloud share must require the password.'
		);
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});
});

function remoteFile(
	name: string,
	etag: string | null,
	modified: string,
	contentLength: number
): NextcloudRemoteFile {
	return {
		href: `/public.php/dav/files/token/${name}`,
		name,
		etag,
		contentLength,
		lastModifiedAt: new Date(modified)
	};
}

function knownItem(
	file: NextcloudRemoteFile,
	status: string,
	lastCheckedAt: Date
): KnownNextcloudSourceItem {
	return {
		remoteKey: testRemoteKey(file),
		etag: file.etag,
		contentLength: file.contentLength,
		lastModifiedAt: file.lastModifiedAt,
		status,
		lastCheckedAt
	};
}

function testRemoteKey(file: NextcloudRemoteFile): string {
	return file.href;
}

function saveSource(sharePassword: string) {
	return verifyNextcloudShareCredentials(
		parseNextcloudShareUrl('https://cloud.example.test/s/token'),
		sharePassword
	);
}

function emptyWebDavFolder(): string {
	return `<?xml version="1.0"?>
		<d:multistatus xmlns:d="DAV:">
			<d:response>
				<d:href>/public.php/dav/files/token/</d:href>
				<d:propstat>
					<d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop>
					<d:status>HTTP/1.1 200 OK</d:status>
				</d:propstat>
			</d:response>
		</d:multistatus>`;
}
