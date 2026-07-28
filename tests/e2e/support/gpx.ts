import { request as httpRequest } from 'node:http';

export function startHeldAndroidImport(url: URL, headers: Record<string, string>, gpx: Buffer) {
	let finish!: () => void;
	const response = new Promise<{ status: number; body: unknown }>((resolve, reject) => {
		const request = httpRequest(
			url,
			{
				method: 'POST',
				headers: { ...headers, 'content-length': gpx.length, origin: url.origin }
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
