import { describe, expect, test } from 'vitest';
import {
	buildActivityRouteTrace,
	buildHeartRateSeries,
	maxRetainedTracePoints
} from './activity-trace';
import { parseGpx } from './gpx';

describe('private activity traces', () => {
	test('derives relative-speed route points and retains heart-rate timing', () => {
		const parsed = parseGpx(`<?xml version="1.0"?>
			<gpx><trk><trkseg>
				<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time><extensions><hr>120</hr></extensions></trkpt>
				<trkpt lat="45.0010" lon="-63.0000"><time>2026-05-14T12:01:00Z</time><extensions><hr>145</hr></extensions></trkpt>
				<trkpt lat="45.0030" lon="-63.0000"><time>2026-05-14T12:02:00Z</time><extensions><hr>170</hr></extensions></trkpt>
			</trkseg></trk></gpx>`);

		const route = buildActivityRouteTrace(parsed);
		const heartRate = buildHeartRateSeries(parsed);

		expect(route?.points).toHaveLength(3);
		expect(route?.points[0]?.speedMetersPerSecond).toBeGreaterThan(1);
		expect(route?.points[1]?.speedMetersPerSecond).toBeGreaterThan(
			route?.points[0]?.speedMetersPerSecond ?? Number.POSITIVE_INFINITY
		);
		expect(route?.points.at(-1)?.speedMetersPerSecond).toBeNull();
		expect(heartRate?.points).toEqual([
			{ elapsedSeconds: 0, bpm: 120 },
			{ elapsedSeconds: 60, bpm: 145 },
			{ elapsedSeconds: 120, bpm: 170 }
		]);
	});

	test('bounds retained data while preserving endpoints and the measured peak', () => {
		const points = Array.from({ length: 1_200 }, (_, index) => {
			const heartRate = index === 617 ? 199 : 120 + (index % 20);
			return `<trkpt lat="${45 + index / 1_000_000}" lon="-63"><time>${new Date(
				Date.parse('2026-05-14T12:00:00Z') + index * 1_000
			).toISOString()}</time><extensions><hr>${heartRate}</hr></extensions></trkpt>`;
		}).join('');
		const parsed = parseGpx(
			`<?xml version="1.0"?><gpx><trk><trkseg>${points}</trkseg></trk></gpx>`
		);

		const route = buildActivityRouteTrace(parsed);
		const heartRate = buildHeartRateSeries(parsed);

		expect(route?.points.length).toBeLessThanOrEqual(maxRetainedTracePoints);
		expect(route?.points[0]?.elapsedSeconds).toBe(0);
		expect(route?.points.at(-1)?.elapsedSeconds).toBe(1_199);
		expect(heartRate?.points.length).toBeLessThanOrEqual(maxRetainedTracePoints);
		expect(heartRate?.points.some((point) => point.bpm === 199)).toBe(true);
	});
});
