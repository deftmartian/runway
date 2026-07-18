import { afterEach, describe, expect, test, vi } from 'vitest';
import {
	enforceNextcloudVisibleGpxLimit,
	isNextcloudAuthenticationRejection,
	listNextcloudGpxFiles,
	maxNextcloudVisibleGpxFiles,
	parseNextcloudShareUrl
} from './nextcloud';
import { sealSecret } from './secrets';

afterEach(() => {
	vi.unstubAllGlobals();
	vi.unstubAllEnvs();
});

describe('parseNextcloudShareUrl', () => {
	test('requires an allowlist for non-loopback hosts', () => {
		expect.assertions(2);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', '');

		expect(() => parseNextcloudShareUrl('https://cloud.example.test/s/abcDEF123')).toThrow(
			/origin is not allowed/
		);
		expect(parseNextcloudShareUrl('http://127.0.0.1:4100/s/abcDEF123')).toEqual({
			shareHost: 'http://127.0.0.1:4100',
			shareToken: 'abcDEF123'
		});
	});

	test('extracts host and token from a public share URL', () => {
		expect.assertions(1);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');

		expect(parseNextcloudShareUrl('https://cloud.example.test/s/abcDEF123')).toEqual({
			shareHost: 'https://cloud.example.test',
			shareToken: 'abcDEF123'
		});
	});

	test('rejects URLs without a public share token', () => {
		expect.assertions(1);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');

		expect(() => parseNextcloudShareUrl('https://cloud.example.test/apps/files')).toThrow(
			/folder share token/
		);
	});

	test('rejects cleartext non-loopback share URLs', () => {
		expect.assertions(2);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', '');

		expect(() => parseNextcloudShareUrl('http://cloud.example.test/s/abcDEF123')).toThrow(/HTTPS/);
		expect(parseNextcloudShareUrl('http://127.0.0.1:4100/s/abcDEF123')).toEqual({
			shareHost: 'http://127.0.0.1:4100',
			shareToken: 'abcDEF123'
		});
	});

	test('enforces the allowed origin list when configured', () => {
		expect.assertions(3);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');

		expect(() => parseNextcloudShareUrl('https://127.0.0.1/s/abcDEF123')).toThrow(
			/origin is not allowed/
		);
		expect(() => parseNextcloudShareUrl('https://cloud.example.test:8443/s/abcDEF123')).toThrow(
			/origin is not allowed/
		);

		expect(parseNextcloudShareUrl('https://cloud.example.test/s/abcDEF123')).toEqual({
			shareHost: 'https://cloud.example.test',
			shareToken: 'abcDEF123'
		});
	});

	test('allows a nonstandard port only when the exact origin is configured', () => {
		expect.assertions(1);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test:8443');

		expect(parseNextcloudShareUrl('https://cloud.example.test:8443/s/abcDEF123')).toEqual({
			shareHost: 'https://cloud.example.test:8443',
			shareToken: 'abcDEF123'
		});
	});

	test('fails closed if any configured allowlist entry is invalid', () => {
		expect.assertions(1);
		vi.stubEnv(
			'NEXTCLOUD_ALLOWED_ORIGINS',
			'https://cloud.example.test,https://cloud.example.test/not-an-origin'
		);

		expect(() => parseNextcloudShareUrl('https://cloud.example.test/s/abcDEF123')).toThrow(
			/exact HTTPS origins/
		);
	});

	test('rejects invalid URLs and embedded credentials with safe errors', () => {
		expect.assertions(2);
		vi.stubEnv('NEXTCLOUD_ALLOWED_ORIGINS', 'https://cloud.example.test');

		expect(() => parseNextcloudShareUrl('not a URL')).toThrow('Nextcloud share URL is not valid.');
		expect(() =>
			parseNextcloudShareUrl('https://user:password@cloud.example.test/s/abcDEF123')
		).toThrow('Nextcloud share URL must not include credentials.');
	});
});

describe('listNextcloudGpxFiles', () => {
	test('applies an explicit visible-folder bound before marker queries', () => {
		expect(
			enforceNextcloudVisibleGpxLimit(
				Array.from({ length: maxNextcloudVisibleGpxFiles }, (_, id) => id)
			)
		).toHaveLength(maxNextcloudVisibleGpxFiles);
		expect(() =>
			enforceNextcloudVisibleGpxLimit(
				Array.from({ length: maxNextcloudVisibleGpxFiles + 1 }, (_, id) => id)
			)
		).toThrow(`at most ${maxNextcloudVisibleGpxFiles} GPX files`);
	});

	test('ignores WebDAV hrefs outside the public share folder', async () => {
		expect.assertions(2);
		vi.stubGlobal(
			'fetch',
			vi.fn(() => Promise.resolve(new Response(webdavListingWithHostileHrefs(), { status: 207 })))
		);

		const files = await listNextcloudGpxFiles({
			shareHost: 'https://cloud.example.test',
			shareTokenSecret: await sealSecret('abcDEF123'),
			sharePasswordSecret: await sealSecret('test share password')
		});

		expect(files).toHaveLength(1);
		expect(files[0]?.href).toBe('/public.php/dav/files/abcDEF123/good.gpx');
	});

	test('does not follow WebDAV redirects', async () => {
		expect.assertions(3);
		vi.stubGlobal(
			'fetch',
			vi.fn((input: URL | RequestInfo, init?: RequestInit) => {
				const requestUrl =
					input instanceof Request ? input.url : input instanceof URL ? input.href : input;
				expect(requestUrl).toBe('https://cloud.example.test/public.php/dav/files/abcDEF123/');
				expect(init?.redirect).toBe('manual');
				return Promise.resolve(
					new Response('', {
						status: 302,
						headers: { Location: 'http://127.0.0.1:4100/internal.gpx' }
					})
				);
			})
		);

		await expect(
			listNextcloudGpxFiles({
				shareHost: 'https://cloud.example.test',
				shareTokenSecret: await sealSecret('abcDEF123'),
				sharePasswordSecret: await sealSecret('test share password')
			})
		).rejects.toThrow(/could not be read/);
	});

	test('classifies only explicit HTTP authentication rejection as a wrong password', async () => {
		expect.assertions(2);
		vi.stubGlobal(
			'fetch',
			vi.fn(() => Promise.resolve(new Response('', { status: 401 })))
		);

		let rejection: unknown;
		try {
			await listNextcloudGpxFiles(await credentials());
		} catch (error) {
			rejection = error;
		}

		expect(rejection).toBeInstanceOf(Error);
		expect(isNextcloudAuthenticationRejection(rejection)).toBe(true);
	});

	test('rejects WebDAV documents with a document type declaration', async () => {
		expect.assertions(1);
		vi.stubGlobal(
			'fetch',
			vi.fn(() =>
				Promise.resolve(
					new Response(
						'<?xml version="1.0"?><!DOCTYPE multistatus [<!ENTITY x "value">]><multistatus />',
						{ status: 207 }
					)
				)
			)
		);

		await expect(listNextcloudGpxFiles(await credentials())).rejects.toThrow(
			/invalid WebDAV response/
		);
	});

	test('rejects a successful response that does not describe the requested share folder', async () => {
		expect.assertions(1);
		vi.stubGlobal(
			'fetch',
			vi.fn(() =>
				Promise.resolve(
					new Response(
						'<d:multistatus xmlns:d="DAV:"><d:response><d:href>/unrelated/</d:href><d:propstat><d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop></d:propstat></d:response></d:multistatus>',
						{ status: 207 }
					)
				)
			)
		);

		await expect(listNextcloudGpxFiles(await credentials())).rejects.toThrow(
			/invalid WebDAV response/
		);
	});

	test('returns a safe error when stored credentials cannot be opened with the configured key', async () => {
		expect.assertions(2);
		vi.stubEnv('IMPORT_SECRET_KEY', 'first-test-secret-with-at-least-thirty-two-characters');
		const stored = await credentials();
		vi.stubEnv('IMPORT_SECRET_KEY', 'second-test-secret-with-at-least-thirty-two-characters');
		const fetchMock = vi.fn();
		vi.stubGlobal('fetch', fetchMock);

		await expect(listNextcloudGpxFiles(stored)).rejects.toThrow(
			'Nextcloud source credentials could not be opened.'
		);
		expect(fetchMock).not.toHaveBeenCalled();
	});
});

async function credentials() {
	return {
		shareHost: 'https://cloud.example.test',
		shareTokenSecret: await sealSecret('abcDEF123'),
		sharePasswordSecret: await sealSecret('test share password')
	};
}

function webdavListingWithHostileHrefs(): string {
	return `<?xml version="1.0"?>
			<d:multistatus xmlns:d="DAV:">
				<d:response>
					<d:href>/public.php/dav/files/abcDEF123/</d:href>
					<d:propstat><d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
				</d:response>
				<d:response>
				<d:href>/public.php/dav/files/abcDEF123/good.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Thu, 14 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"good"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
			<d:response>
				<d:href>https://evil.example.test/route.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Fri, 15 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"evil"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
				<d:response>
					<d:href>/status.php/route.gpx</d:href>
					<d:propstat><d:prop><d:getlastmodified>Fri, 15 May 2026 13:00:00 GMT</d:getlastmodified><d:getetag>"internal"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
				</d:response>
				<d:response>
					<d:href>/public.php/dav/files/abcDEF123evil/route.gpx</d:href>
					<d:propstat><d:prop><d:getlastmodified>Fri, 15 May 2026 14:00:00 GMT</d:getlastmodified><d:getetag>"prefix"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
				</d:response>
			</d:multistatus>`;
}
