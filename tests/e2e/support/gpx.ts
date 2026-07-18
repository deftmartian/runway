import { request as httpRequest } from 'node:http';
import { randomBytes } from 'node:crypto';

export async function startHeldShareImport(url: URL, cookie: string, gpx: Buffer) {
	const boundary = `runway-test-${randomBytes(12).toString('hex')}`;
	const prefix = Buffer.from(
		`--${boundary}\r\nContent-Disposition: form-data; name="gpx"; filename="activity.gpx"\r\nContent-Type: application/gpx+xml\r\n\r\n`
	);
	const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
	let finish!: () => void;
	const response = new Promise<{ status: number; location: string }>((resolve, reject) => {
		const request = httpRequest(
			url,
			{
				method: 'POST',
				headers: {
					'content-type': `multipart/form-data; boundary=${boundary}`,
					'content-length': prefix.length + gpx.length + suffix.length,
					cookie,
					origin: url.origin
				}
			},
			(incoming) => {
				incoming.resume();
				incoming.once('end', () => {
					resolve({
						status: incoming.statusCode ?? 0,
						location: incoming.headers.location ?? ''
					});
				});
			}
		);
		request.once('error', reject);
		request.flushHeaders();
		request.write(prefix);
		finish = () => request.end(Buffer.concat([gpx, suffix]));
	});

	// Leave the multipart body incomplete long enough for the route to capture
	// the generation and block in formData(). The deletion request can then win.
	await new Promise((resolve) => setTimeout(resolve, 250));
	return { finish, response };
}

export async function startHeldDeviceFolderImport(
	url: URL,
	cookie: string,
	gpx: Buffer,
	activityGeneration: number,
	folderGeneration: number
) {
	const boundary = `runway-device-test-${randomBytes(12).toString('hex')}`;
	const prefix = Buffer.from(
		`--${boundary}\r\nContent-Disposition: form-data; name="activityGeneration"\r\n\r\n${activityGeneration}\r\n` +
			`--${boundary}\r\nContent-Disposition: form-data; name="folderGeneration"\r\n\r\n${folderGeneration}\r\n` +
			`--${boundary}\r\nContent-Disposition: form-data; name="gpx"; filename="activity.gpx"\r\nContent-Type: application/gpx+xml\r\n\r\n`
	);
	const suffix = Buffer.from(`\r\n--${boundary}--\r\n`);
	let finish!: () => void;
	const response = new Promise<{ status: number; body: unknown }>((resolve, reject) => {
		const request = httpRequest(
			url,
			{
				method: 'POST',
				headers: {
					'content-type': `multipart/form-data; boundary=${boundary}`,
					'content-length': prefix.length + gpx.length + suffix.length,
					cookie,
					origin: url.origin
				}
			},
			(incoming) => {
				const chunks: Buffer[] = [];
				incoming.on('data', (chunk: Buffer) => chunks.push(chunk));
				incoming.once('end', () => {
					try {
						const text = Buffer.concat(chunks).toString('utf8');
						resolve({
							status: incoming.statusCode ?? 0,
							body: text ? (JSON.parse(text) as unknown) : null
						});
					} catch (error) {
						reject(
							error instanceof Error
								? error
								: new Error('Device-folder import response was invalid.')
						);
					}
				});
			}
		);
		request.once('error', reject);
		request.flushHeaders();
		request.write(prefix);
		finish = () => request.end(Buffer.concat([gpx, suffix]));
	});

	await new Promise((resolve) => setTimeout(resolve, 250));
	return { finish, response };
}

export function startHeldAndroidImport(url: URL, headers: Record<string, string>, gpx: Buffer) {
	let finish!: () => void;
	const response = new Promise<{ status: number; body: unknown }>((resolve, reject) => {
		const request = httpRequest(
			url,
			{
				method: 'POST',
				headers: {
					...headers,
					'content-length': gpx.length,
					origin: url.origin
				}
			},
			(incoming) => {
				const chunks: Buffer[] = [];
				incoming.on('data', (chunk: Buffer) => chunks.push(chunk));
				incoming.once('end', () => {
					try {
						const text = Buffer.concat(chunks).toString('utf8');
						resolve({
							status: incoming.statusCode ?? 0,
							body: text ? (JSON.parse(text) as unknown) : null
						});
					} catch (error) {
						reject(
							error instanceof Error ? error : new Error('Android import response was invalid.')
						);
					}
				});
			}
		);
		request.once('error', reject);
		request.flushHeaders();
		request.write(gpx.subarray(0, 1));
		finish = () => request.end(gpx.subarray(1));
	});
	return { finish, response };
}

export function gpxForDistance(date: string, distanceMeters: number): Buffer {
	const latitude = 45;
	const startLongitude = -63;
	const longitudeDelta = distanceMeters / (111_320 * Math.cos((latitude * Math.PI) / 180));
	return Buffer.from(`<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="${latitude}" lon="${startLongitude}"><time>${date}T12:00:00Z</time></trkpt>
			<trkpt lat="${latitude}" lon="${startLongitude + longitudeDelta}"><time>${date}T12:30:00Z</time></trkpt>
		</trkseg></trk></gpx>`);
}

export function gpx(start: string): string {
	return `<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="45.0000" lon="-63.0000"><time>${start}</time></trkpt>
			<trkpt lat="45.0010" lon="-63.0010"><time>${new Date(new Date(start).getTime() + 60_000).toISOString()}</time></trkpt>
		</trkseg></trk></gpx>`;
}

export function longGpx(start: string): string {
	return `<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="45.0000" lon="-63.0000"><time>${start}</time></trkpt>
			<trkpt lat="45.2000" lon="-63.0000"><time>${new Date(new Date(start).getTime() + 7_200_000).toISOString()}</time></trkpt>
		</trkseg></trk></gpx>`;
}
