import type {
	HeartRateActivitySummary,
	HeartRateSettings,
	HeartRateZone,
	HeartRateZoneKey,
	ParsedGpxActivity,
	SexForEstimates
} from './types';

const zoneKeys: HeartRateZoneKey[] = ['z1', 'z2', 'z3', 'z4', 'z5'];
const zoneLabels: Record<HeartRateZoneKey, string> = {
	z1: 'Recovery',
	z2: 'Easy',
	z3: 'Steady',
	z4: 'Hard',
	z5: 'Max'
};

export function estimateMaxHeartRate(
	ageYears: number,
	sexForEstimates: SexForEstimates = 'not_specified'
): number {
	if (!Number.isInteger(ageYears) || ageYears < 18 || ageYears > 100) {
		throw new Error('Age-based heart-rate estimates are available only for adults age 18 to 100.');
	}
	if (sexForEstimates === 'female') return Math.round(206 - 0.88 * ageYears);
	return Math.round(208 - 0.7 * ageYears);
}

export function defaultHeartRateSettings(
	ageYears: number,
	sexForEstimates: SexForEstimates = 'not_specified'
): HeartRateSettings {
	const maxHeartRateBpm = estimateMaxHeartRate(ageYears, sexForEstimates);
	return buildHeartRateSettings({
		maxHeartRateBpm,
		source: 'estimated',
		zone2FloorBpm: Math.round(maxHeartRateBpm * 0.6),
		zone3FloorBpm: Math.round(maxHeartRateBpm * 0.7),
		zone4FloorBpm: Math.round(maxHeartRateBpm * 0.8),
		zone5FloorBpm: Math.round(maxHeartRateBpm * 0.9)
	});
}

export function buildHeartRateSettings(input: {
	maxHeartRateBpm: number;
	source: HeartRateSettings['source'];
	zone2FloorBpm: number;
	zone3FloorBpm: number;
	zone4FloorBpm: number;
	zone5FloorBpm: number;
}): HeartRateSettings {
	return {
		maxHeartRateBpm: input.maxHeartRateBpm,
		source: input.source,
		zones: [
			zone('z1', 0, input.zone2FloorBpm - 1),
			zone('z2', input.zone2FloorBpm, input.zone3FloorBpm - 1),
			zone('z3', input.zone3FloorBpm, input.zone4FloorBpm - 1),
			zone('z4', input.zone4FloorBpm, input.zone5FloorBpm - 1),
			zone('z5', input.zone5FloorBpm)
		]
	};
}

export function normalizeHeartRateSettings(
	settings: HeartRateSettings | null | undefined
): HeartRateSettings | null {
	if (!settings || typeof settings !== 'object') return null;
	if (!Number.isInteger(settings.maxHeartRateBpm)) return null;
	if (settings.maxHeartRateBpm < 120 || settings.maxHeartRateBpm > 230) return null;
	if (settings.source !== 'estimated' && settings.source !== 'custom') return null;
	if (!Array.isArray(settings.zones)) return null;
	const zones = settings.zones.filter((candidate): candidate is HeartRateZone => isZone(candidate));
	if (zones.length !== 5) return null;
	if (zones.map((candidate) => candidate.key).join('|') !== zoneKeys.join('|')) return null;
	if (zones[0]?.floorBpm !== 0) return null;
	for (let index = 0; index < zones.length; index += 1) {
		const current = zones[index];
		const next = zones[index + 1];
		if (!current || current.floorBpm < 0) return null;
		if (next) {
			if (next.floorBpm <= current.floorBpm) return null;
			if (current.ceilingBpm !== next.floorBpm - 1) return null;
		} else if (current.ceilingBpm !== undefined) return null;
	}
	if ((zones.at(-1)?.floorBpm ?? Number.POSITIVE_INFINITY) > settings.maxHeartRateBpm) return null;
	return {
		maxHeartRateBpm: settings.maxHeartRateBpm,
		source: settings.source,
		zones: zones.map((candidate) => zone(candidate.key, candidate.floorBpm, candidate.ceilingBpm))
	};
}

export function summarizeHeartRateEffort(
	parsed: ParsedGpxActivity,
	settings: HeartRateSettings | null | undefined
): HeartRateActivitySummary | null {
	const normalized = normalizeHeartRateSettings(settings);
	if (!normalized || !parsed.heartRateSamples || parsed.heartRateSamples.length === 0) {
		return null;
	}

	const secondsByZone = emptySecondsByZone();
	const samples = parsed.heartRateSamples;
	const activityStartMs = parsed.startedAt.getTime();
	const activityEndMs = activityStartMs + parsed.durationSeconds * 1_000;

	for (let index = 0; index < samples.length; index += 1) {
		const sample = samples[index];
		if (!sample) continue;
		const next = samples[index + 1];
		if (next?.segmentIndex !== sample.segmentIndex) continue;
		const intervalStartMs = Math.max(activityStartMs, sample.at.getTime());
		const intervalEndMs = Math.min(activityEndMs, next.at.getTime());
		const seconds = Math.max(0, Math.round((intervalEndMs - intervalStartMs) / 1_000));
		if (seconds <= 0) continue;
		secondsByZone[zoneForBpm(sample.bpm, normalized.zones).key] += seconds;
	}

	const highSeconds = secondsByZone.z4 + secondsByZone.z5;
	const totalSeconds = Object.values(secondsByZone).reduce((sum, seconds) => sum + seconds, 0);
	const highShare = totalSeconds > 0 ? Math.round((highSeconds / totalSeconds) * 100) / 100 : 0;

	return {
		// Zone occupancy is descriptive. There is no source-backed threshold here that is
		// safe to turn into a hard-workout label or an automatic plan adjustment.
		effort: 'unknown',
		highSeconds,
		highShare,
		secondsByZone,
		settingsSource: normalized.source
	};
}

export function zoneFloors(settings: HeartRateSettings | null | undefined): {
	maxHeartRateBpm: number;
	zone2FloorBpm: number;
	zone3FloorBpm: number;
	zone4FloorBpm: number;
	zone5FloorBpm: number;
} | null {
	const normalized = normalizeHeartRateSettings(settings);
	if (!normalized) return null;
	return {
		maxHeartRateBpm: normalized.maxHeartRateBpm,
		zone2FloorBpm: normalized.zones.find((zone) => zone.key === 'z2')?.floorBpm ?? 0,
		zone3FloorBpm: normalized.zones.find((zone) => zone.key === 'z3')?.floorBpm ?? 0,
		zone4FloorBpm: normalized.zones.find((zone) => zone.key === 'z4')?.floorBpm ?? 0,
		zone5FloorBpm: normalized.zones.find((zone) => zone.key === 'z5')?.floorBpm ?? 0
	};
}

function zone(key: HeartRateZoneKey, floorBpm: number, ceilingBpm?: number): HeartRateZone {
	return {
		key,
		label: zoneLabels[key],
		floorBpm,
		...(ceilingBpm === undefined ? {} : { ceilingBpm })
	};
}

function isZone(value: unknown): value is HeartRateZone {
	if (!value || typeof value !== 'object') return false;
	const candidate = value as Partial<HeartRateZone>;
	return (
		typeof candidate.key === 'string' &&
		zoneKeys.includes(candidate.key) &&
		typeof candidate.floorBpm === 'number' &&
		Number.isInteger(candidate.floorBpm) &&
		(candidate.ceilingBpm === undefined ||
			(typeof candidate.ceilingBpm === 'number' &&
				Number.isInteger(candidate.ceilingBpm) &&
				candidate.ceilingBpm >= candidate.floorBpm))
	);
}

function emptySecondsByZone(): Record<HeartRateZoneKey, number> {
	return { z1: 0, z2: 0, z3: 0, z4: 0, z5: 0 };
}

function zoneForBpm(bpm: number, zones: HeartRateZone[]): HeartRateZone {
	const fallback = zones[0];
	if (!fallback) throw new Error('Heart-rate zones are not configured.');
	return (
		zones
			.slice()
			.reverse()
			.find((zone) => bpm >= zone.floorBpm) ?? fallback
	);
}
