import type { ActivityRouteTrace, HeartRateSeries, ParsedGpxActivity } from './types';

export const maxRetainedTracePoints = 600;

export function buildActivityRouteTrace(parsed: ParsedGpxActivity): ActivityRouteTrace | null {
	if (parsed.routePoints.length < 2) return null;
	const points = retainRepresentativePoints(parsed.routePoints, maxRetainedTracePoints);
	const startedAtMs = parsed.startedAt.getTime();
	return {
		version: 1,
		sourcePointCount: parsed.routePoints.length,
		points: points.map((point) => ({
			latitudeE6: Math.round(point.latitude * 1_000_000),
			longitudeE6: Math.round(point.longitude * 1_000_000),
			elapsedSeconds: Math.max(0, Math.round((point.at.getTime() - startedAtMs) / 1_000)),
			segmentIndex: point.segmentIndex,
			speedMetersPerSecond:
				point.speedMetersPerSecond === null
					? null
					: Math.round(point.speedMetersPerSecond * 100) / 100
		}))
	};
}

export function buildHeartRateSeries(parsed: ParsedGpxActivity): HeartRateSeries | null {
	const samples = parsed.heartRateSamples;
	if (!samples || samples.length === 0) return null;
	const peakIndex = samples.reduce(
		(best, sample, index) => (sample.bpm > (samples[best]?.bpm ?? 0) ? index : best),
		0
	);
	const retained = retainRepresentativePoints(samples, maxRetainedTracePoints, [peakIndex]);
	const startedAtMs = parsed.startedAt.getTime();
	return {
		version: 1,
		sourceSampleCount: samples.length,
		points: retained.map((sample) => ({
			elapsedSeconds: Math.max(0, Math.round((sample.at.getTime() - startedAtMs) / 1_000)),
			bpm: sample.bpm
		}))
	};
}

function retainRepresentativePoints<T>(
	values: T[],
	limit: number,
	requiredIndexes: number[] = []
): T[] {
	if (values.length <= limit) return values.slice();
	const indexes = new Set<number>([0, values.length - 1, ...requiredIndexes]);
	const available = Math.max(0, limit - indexes.size);
	for (let slot = 1; slot <= available; slot += 1) {
		indexes.add(Math.round((slot * (values.length - 1)) / (available + 1)));
	}
	return [...indexes]
		.sort((left, right) => left - right)
		.slice(0, limit)
		.flatMap((index) => {
			const value = values[index];
			return value === undefined ? [] : [value];
		});
}
