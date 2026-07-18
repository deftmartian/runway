import { describe, expect, it } from 'vitest';
import { averagePaceFromPairedResults } from './history';

describe('weekly history pace', () => {
	it('uses only duration and distance recorded on the same result', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: null, durationSeconds: 30 * 60 },
				{ distanceMeters: 1_000, durationSeconds: 5 * 60 }
			])
		).toBe(5 * 60);
	});

	it('ignores distance-only, duration-only, zero, and invalid results', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: 5_000, durationSeconds: null },
				{ distanceMeters: null, durationSeconds: 1_800 },
				{ distanceMeters: 0, durationSeconds: 300 },
				{ distanceMeters: 1_000, durationSeconds: -1 }
			])
		).toBeNull();
	});

	it('weights valid results by their paired distance and duration totals', () => {
		expect(
			averagePaceFromPairedResults([
				{ distanceMeters: 1_000, durationSeconds: 300 },
				{ distanceMeters: 2_000, durationSeconds: 720 }
			])
		).toBe(340);
	});
});
