import { createHash } from 'node:crypto';
import { SyntaxValidator } from 'fast-xml-validator';
import { XMLParser } from 'fast-xml-parser';
import type { ParsedGpxActivity } from './types';

type GpxPoint = {
	lat?: string | number;
	lon?: string | number;
	time?: string;
	ele?: string | number;
	extensions?: unknown;
};

const maxTrackPoints = 20_000;

const parser = new XMLParser({
	ignoreAttributes: false,
	attributeNamePrefix: '',
	parseTagValue: true,
	parseAttributeValue: true,
	trimValues: true,
	processEntities: false
});

export function hashActivityFile(input: string | Buffer, scope?: string): string {
	const hash = createHash('sha256');
	if (scope) hash.update('runway-activity-scope-v1\0').update(scope).update('\0');
	return hash.update(input).digest('hex');
}

export function parseGpx(input: string | Buffer): ParsedGpxActivity {
	const text = input.toString('utf8');
	assertTrackPointLimit(text);
	assertSafeValidXml(text);
	let parsed: Record<string, unknown>;
	try {
		parsed = parser.parse(text) as Record<string, unknown>;
	} catch {
		throw new Error('GPX file contains malformed XML.');
	}
	const gpxRoot = findGpxRoot(parsed);
	const segments = collectSegments(gpxRoot);
	const pointRecords = segments.flatMap((segment, segmentIndex) =>
		segment.map((point) => ({ point, segmentIndex }))
	);
	const points = pointRecords.map((record) => record.point);

	if (points.length < 2) {
		throw new Error('GPX file does not contain enough track points.');
	}

	let distanceMeters = 0;
	let maxHeartRate = 0;
	const heartRateSamples: { at: Date; bpm: number; segmentIndex: number }[] = [];
	const cadenceSamples: { at: Date; value: number; segmentIndex: number }[] = [];
	const speedSamples: { at: Date; value: number; segmentIndex: number }[] = [];
	let hasElevation = false;
	let previousTime: Date | undefined;

	for (const record of pointRecords) {
		const { point, segmentIndex } = record;
		validateCoordinate(point.lat, 'latitude');
		validateCoordinate(point.lon, 'longitude');
		const currentTime = parsePointTime(point);
		if (previousTime && currentTime.getTime() < previousTime.getTime()) {
			throw new Error('GPX track point timestamps must be chronological.');
		}
		previousTime = currentTime;
		if (point.ele !== undefined && point.ele !== null) hasElevation = true;

		const metrics = collectPointMetrics(point.extensions);
		if (metrics.heartRate !== undefined) {
			const heartRate = Math.round(metrics.heartRate);
			maxHeartRate = Math.max(maxHeartRate, heartRate);
			heartRateSamples.push({ at: currentTime, bpm: heartRate, segmentIndex });
		}
		if (metrics.cadence !== undefined) {
			cadenceSamples.push({ at: currentTime, value: metrics.cadence, segmentIndex });
		}
		if (metrics.speed !== undefined) {
			speedSamples.push({ at: currentTime, value: metrics.speed, segmentIndex });
		}
	}
	for (const segment of segments) {
		for (let index = 1; index < segment.length; index += 1) {
			const previous = segment[index - 1];
			const point = segment[index];
			if (previous && point) distanceMeters += haversineMeters(previous, point);
		}
	}

	const firstTime = parsePointTime(points[0]);
	const lastTime = parsePointTime(points.at(-1));
	const durationSeconds = Math.max(
		1,
		Math.round((lastTime.getTime() - firstTime.getTime()) / 1000)
	);
	if (durationSeconds > 24 * 60 * 60) {
		throw new Error('GPX activity duration is outside the supported running range.');
	}
	if (distanceMeters > 100_000) {
		throw new Error('GPX activity distance is outside the supported running range.');
	}

	const activity: ParsedGpxActivity = {
		startedAt: firstTime,
		durationSeconds,
		distanceMeters: Math.round(distanceMeters),
		pointCount: points.length,
		hasElevation,
		hasHeartRate: heartRateSamples.length > 0,
		hasCadence: cadenceSamples.length > 0,
		hasSpeed: speedSamples.length > 0
	};
	if (heartRateSamples.length > 0) {
		const averageHeartRate = Math.round(
			timeWeightedAverage(
				heartRateSamples.map((sample) => ({
					at: sample.at,
					value: sample.bpm,
					segmentIndex: sample.segmentIndex
				}))
			)
		);
		if (averageHeartRate < 30 || averageHeartRate > 240) {
			throw new Error('GPX heart-rate values are outside the supported range.');
		}
		activity.averageHeartRate = averageHeartRate;
		activity.maxHeartRate = maxHeartRate;
		activity.heartRateSamples = heartRateSamples;
	}
	if (cadenceSamples.length > 0)
		activity.averageCadence = Math.round(timeWeightedAverage(cadenceSamples));
	if (speedSamples.length > 0)
		activity.averageSpeedMetersPerSecond =
			Math.round(timeWeightedAverage(speedSamples) * 100) / 100;
	return activity;
}

function collectSegments(root: unknown): GpxPoint[][] {
	const segments: GpxPoint[][] = [];
	collect(root);
	return segments;

	function collect(value: unknown): void {
		if (Array.isArray(value)) {
			for (const item of value) collect(item);
			return;
		}
		if (!value || typeof value !== 'object') return;

		for (const [key, childValue] of Object.entries(value)) {
			if (localName(key) === 'trkseg') {
				for (const segmentValue of asArray(childValue)) {
					const segment = directTrackPoints(segmentValue);
					if (segment.length > 0) segments.push(segment);
				}
				continue;
			}
			collect(childValue);
		}
	}
}

function directTrackPoints(segment: unknown): GpxPoint[] {
	if (!segment || typeof segment !== 'object') return [];
	const entry = Object.entries(segment).find(([key]) => localName(key) === 'trkpt');
	if (!entry) return [];
	return asArray(entry[1]).filter(isGpxPoint);
}

function findGpxRoot(parsed: Record<string, unknown>): unknown {
	const entry = Object.entries(parsed).find(([key]) => localName(key) === 'gpx');
	if (!entry) throw new Error('File is not a GPX document.');
	return entry[1];
}

function localName(name: string): string {
	return name.toLowerCase().replace(/^.*:/, '');
}

function assertSafeValidXml(text: string): void {
	if (/<!\s*DOCTYPE\b/i.test(text) || /<!\s*ENTITY\b/i.test(text)) {
		throw new Error('GPX files with document type or entity declarations are not supported.');
	}
	let validation: ReturnType<typeof SyntaxValidator.validate>;
	try {
		validation = SyntaxValidator.validate(text, { allowBooleanAttributes: false });
	} catch {
		throw new Error('GPX file contains malformed XML.');
	}
	if (validation !== true) throw new Error('GPX file contains malformed XML.');
}

function assertTrackPointLimit(text: string): void {
	let count = 0;
	const pattern = /<\s*(?:[A-Za-z0-9_-]+:)?trkpt\b/gi;
	while (pattern.exec(text)) {
		count += 1;
		if (count > maxTrackPoints) {
			throw new Error('GPX file has too many track points for import.');
		}
	}
}

function walk(value: unknown, visitor: (value: unknown, key: string) => void, key = ''): void {
	visitor(value, key);
	if (Array.isArray(value)) {
		for (const item of value) walk(item, visitor, key);
		return;
	}
	if (value && typeof value === 'object') {
		for (const [childKey, childValue] of Object.entries(value)) {
			walk(childValue, visitor, childKey);
		}
	}
}

function asArray(value: unknown): unknown[] {
	return Array.isArray(value) ? value : [value];
}

function isGpxPoint(value: unknown): value is GpxPoint {
	return Boolean(value && typeof value === 'object' && 'lat' in value && 'lon' in value);
}

function parsePointTime(point: GpxPoint | undefined): Date {
	const parsed = new Date(point?.time ?? '');
	if (Number.isNaN(parsed.getTime())) {
		throw new Error('GPX track point is missing a valid timestamp.');
	}
	return parsed;
}

function haversineMeters(a: GpxPoint, b: GpxPoint): number {
	const latA = validateCoordinate(a.lat, 'latitude');
	const latB = validateCoordinate(b.lat, 'latitude');
	const lonA = validateCoordinate(a.lon, 'longitude');
	const lonB = validateCoordinate(b.lon, 'longitude');
	const lat1 = toRadians(latA);
	const lat2 = toRadians(latB);
	const deltaLat = toRadians(latB - latA);
	const deltaLon = toRadians(lonB - lonA);
	const sinLat = Math.sin(deltaLat / 2);
	const sinLon = Math.sin(deltaLon / 2);
	const h = Math.min(
		1,
		Math.max(0, sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon)
	);
	return 6_371_000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
}

function collectPointMetrics(value: unknown): {
	heartRate?: number;
	cadence?: number;
	speed?: number;
} {
	const metrics: { heartRate?: number; cadence?: number; speed?: number } = {};
	walk(value, (child, key) => {
		const normalizedKey = key.toLowerCase().replace(/^[a-z0-9]+:/, '');
		const isHeartRate = normalizedKey === 'hr' || normalizedKey === 'heartrate';
		const isCadence = normalizedKey === 'cad' || normalizedKey === 'cadence';
		const isSpeed = normalizedKey === 'speed';
		if (!isHeartRate && !isCadence && !isSpeed) return;
		const numeric =
			typeof child === 'number' || typeof child === 'string' ? Number(child) : Number.NaN;
		if (!Number.isFinite(numeric)) {
			throw new Error('GPX extension metrics contain an invalid numeric value.');
		}
		if (isHeartRate) {
			if (numeric < 30 || numeric > 260) {
				throw new Error('GPX heart-rate values are outside the supported range.');
			}
			metrics.heartRate = numeric;
		}
		if (isCadence) {
			if (numeric < 0) throw new Error('GPX cadence values cannot be negative.');
			metrics.cadence = numeric;
		}
		if (isSpeed) {
			if (numeric < 0) throw new Error('GPX speed values cannot be negative.');
			metrics.speed = numeric;
		}
	});
	return metrics;
}

function validateCoordinate(
	value: string | number | undefined,
	kind: 'latitude' | 'longitude'
): number {
	const number = Number(value);
	if (!Number.isFinite(number)) throw new Error('GPX track point has invalid coordinates.');
	const min = kind === 'latitude' ? -90 : -180;
	const max = kind === 'latitude' ? 90 : 180;
	if (number < min || number > max) throw new Error('GPX track point has invalid coordinates.');
	return number;
}

function toRadians(value: number): number {
	return (value * Math.PI) / 180;
}

function timeWeightedAverage(samples: { at: Date; value: number; segmentIndex: number }[]): number {
	if (samples.length === 0) return 0;
	if (samples.length === 1) return samples[0]?.value ?? 0;
	let weightedTotal = 0;
	let totalSeconds = 0;
	for (let index = 0; index < samples.length - 1; index += 1) {
		const current = samples[index];
		const next = samples[index + 1];
		if (!current || !next) continue;
		if (current.segmentIndex !== next.segmentIndex) continue;
		const seconds = Math.max(0, (next.at.getTime() - current.at.getTime()) / 1_000);
		if (seconds === 0) continue;
		weightedTotal += ((current.value + next.value) / 2) * seconds;
		totalSeconds += seconds;
	}
	return totalSeconds > 0 ? weightedTotal / totalSeconds : (samples[0]?.value ?? 0);
}
