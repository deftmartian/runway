import { describe, expect, it } from 'vitest';
import { effectiveWeekRisk, groupRowsByPlan } from './plan-queries';

describe('plan history row grouping', () => {
	it('keeps each plan row set separate while preserving query order', () => {
		const grouped = groupRowsByPlan([
			{ planId: 'plan-a', id: 'a-1' },
			{ planId: 'plan-b', id: 'b-1' },
			{ planId: 'plan-a', id: 'a-2' }
		]);

		expect(grouped.get('plan-a')).toEqual([
			{ planId: 'plan-a', id: 'a-1' },
			{ planId: 'plan-a', id: 'a-2' }
		]);
		expect(grouped.get('plan-b')).toEqual([{ planId: 'plan-b', id: 'b-1' }]);
		expect(grouped.has('plan-c')).toBe(false);
	});
});

describe('effective week assessment', () => {
	const common = {
		storedRisk: 'conservative' as const,
		currentDistanceMeters: 0,
		currentDurationSeconds: 6_300,
		currentHasMixedLoad: false,
		previousDistanceMeters: 0,
		previousDurationSeconds: 5_040,
		previousHasMixedLoad: false,
		hasInjuryRisk: false
	};

	it('does not apply distance-ramp thresholds to a sourced timed foundation plan', () => {
		expect(effectiveWeekRisk({ ...common, planKind: 'foundation' })).toBe('conservative');
		expect(effectiveWeekRisk({ ...common, planKind: 'calibration' })).toBe('conservative');
	});

	it('preserves the stored assessment when a distance plan has incomparable mixed load', () => {
		expect(
			effectiveWeekRisk({
				...common,
				planKind: 'distance',
				currentDistanceMeters: 5_000,
				currentHasMixedLoad: true
			})
		).toBe('conservative');
	});

	it('continues to assess comparable distance-plan ramps', () => {
		expect(
			effectiveWeekRisk({
				...common,
				planKind: 'distance',
				currentDistanceMeters: 6_000,
				currentDurationSeconds: 0,
				previousDistanceMeters: 5_000,
				previousDurationSeconds: 0
			})
		).toBe('unsafe');
	});
});
