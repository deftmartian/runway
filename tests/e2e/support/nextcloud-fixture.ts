import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { gpx } from './gpx';

export async function startNextcloudShareFixture(options?: {
	requirePassword?: boolean;
	malformedNewest?: boolean;
	holdNewestDownload?: boolean;
}): Promise<{
	url: string;
	password: string;
	newerDownloads: () => number;
	olderDownloads: () => number;
	renamedDownloads: () => number;
	exposeRenamedDuplicate: () => void;
	replaceNewer: () => void;
	newestDownloadStarted: () => Promise<void>;
	releaseNewestDownload: () => void;
	close: () => Promise<void>;
}> {
	const requirePassword = options?.requirePassword ?? true;
	const token = 'testToken123';
	const password = 'correct share password';
	const authorization = `Basic ${Buffer.from(`anonymous:${password}`).toString('base64')}`;
	let newerDownloads = 0;
	let olderDownloads = 0;
	let renamedDownloads = 0;
	let renamedDuplicateVisible = false;
	let newerRevision = 1;
	let malformedNewest = options?.malformedNewest ?? false;
	let signalNewestDownload!: () => void;
	const newestDownloadStarted = new Promise<void>((resolve) => {
		signalNewestDownload = resolve;
	});
	let releaseNewestDownload!: () => void;
	const newestDownloadReleased = new Promise<void>((resolve) => {
		releaseNewestDownload = resolve;
	});
	const server: Server = createServer((request, response) => {
		if (requirePassword && request.headers.authorization !== authorization) {
			response.writeHead(401);
			response.end('unauthorized');
			return;
		}

		if (request.method === 'PROPFIND') {
			sendXml(response, webdavListing(token, renamedDuplicateVisible, newerRevision));
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/renamed.gpx')) {
			renamedDownloads += 1;
			sendXml(response, gpx('2026-05-14T12:00:00Z'));
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/newer.gpx')) {
			newerDownloads += 1;
			signalNewestDownload();
			const finish = () => {
				sendXml(
					response,
					malformedNewest
						? '<gpx><metadata /></gpx>'
						: gpx(newerRevision === 1 ? '2026-05-14T12:00:00Z' : '2026-05-12T18:00:00Z')
				);
			};
			if (options?.holdNewestDownload) {
				void newestDownloadReleased.then(finish);
			} else {
				finish();
			}
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/older.gpx')) {
			olderDownloads += 1;
			sendXml(response, gpx('2026-05-13T12:00:00Z'));
			return;
		}

		response.writeHead(404);
		response.end('not found');
	});

	await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
	const address = server.address() as AddressInfo;
	return {
		url: `http://127.0.0.1:${address.port}/s/${token}`,
		password,
		newerDownloads: () => newerDownloads,
		olderDownloads: () => olderDownloads,
		renamedDownloads: () => renamedDownloads,
		exposeRenamedDuplicate: () => {
			renamedDuplicateVisible = true;
		},
		replaceNewer: () => {
			newerRevision = 2;
			malformedNewest = false;
		},
		newestDownloadStarted: () => newestDownloadStarted,
		releaseNewestDownload,
		close: () =>
			new Promise((resolve) => {
				server.close(() => {
					resolve();
				});
			})
	};
}

export function sendXml(response: import('node:http').ServerResponse, body: string): void {
	response.writeHead(207, { 'content-type': 'application/xml' });
	response.end(body);
}

export function webdavListing(
	token: string,
	includeRenamedDuplicate: boolean,
	newerRevision: number
): string {
	return `<?xml version="1.0"?>
		<d:multistatus xmlns:d="DAV:">
			<d:response>
				<d:href>/public.php/dav/files/${token}/</d:href>
				<d:propstat><d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop></d:propstat>
			</d:response>
			${
				includeRenamedDuplicate
					? `<d:response>
				<d:href>/public.php/dav/files/${token}/renamed.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Fri, 15 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"renamed"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>`
					: ''
			}
			<d:response>
				<d:href>/public.php/dav/files/${token}/older.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Wed, 13 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"older"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
			<d:response>
				<d:href>/public.php/dav/files/${token}/newer.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>${newerRevision === 1 ? 'Thu, 14 May 2026 12:00:00 GMT' : 'Fri, 15 May 2026 13:00:00 GMT'}</d:getlastmodified><d:getetag>"newer-v${newerRevision}"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
		</d:multistatus>`;
}
