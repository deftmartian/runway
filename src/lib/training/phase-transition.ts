import type { PhaseBaseline, PhaseTransitionOption, PlanPhase } from './types';

export type BaselineObservation = {
	distanceMeters: number | null;
	durationSeconds: number | null;
	completed: boolean;
};

export function derivePhaseBaseline(
	observations: BaselineObservation[],
	weeksObserved: number
): PhaseBaseline {
	if (!Number.isFinite(weeksObserved) || weeksObserved <= 0) {
		throw new Error('Observed week count must be positive.');
	}
	const completed = observations.filter((record) => record.completed);
	const totalDistanceMeters = completed.reduce(
		(sum, record) => sum + nonnegative(record.distanceMeters),
		0
	);
	const totalDurationSeconds = completed.reduce(
		(sum, record) => sum + nonnegative(record.durationSeconds),
		0
	);
	const longestActivityMeters = completed.reduce(
		(longest, record) => Math.max(longest, nonnegative(record.distanceMeters)),
		0
	);

	return {
		activityCount: completed.length,
		totalDurationSeconds,
		totalDistanceMeters,
		longestActivityMeters,
		weeklyDistanceMeters: Math.round(totalDistanceMeters / weeksObserved),
		runsPerWeek: Math.round((completed.length / weeksObserved) * 10) / 10
	};
}

export function canUseDistancePlannerBaseline(baseline: PhaseBaseline): boolean {
	return (
		baseline.weeklyDistanceMeters >= 3_000 &&
		baseline.runsPerWeek >= 2 &&
		baseline.longestActivityMeters > 0
	);
}

export function phaseTransitionOptions(
	phase: Exclude<PlanPhase, 'distance'>,
	goalKind: 'race' | 'foundation',
	baseline: PhaseBaseline,
	raceRampSupported: boolean
): { recommended: PhaseTransitionOption; options: PhaseTransitionOption[] } {
	const continuation = phase === 'foundation' ? 'another_foundation_week' : 'continue_calibration';
	if (goalKind === 'foundation') {
		return { recommended: continuation, options: [continuation] };
	}
	if (canUseDistancePlannerBaseline(baseline) && raceRampSupported) {
		return {
			recommended: 'confirm_race_baseline',
			options: ['confirm_race_baseline', continuation, 'later_date', 'shorter_goal']
		};
	}
	return {
		recommended: continuation,
		options: [continuation, 'later_date', 'shorter_goal']
	};
}

function nonnegative(value: number | null): number {
	return value !== null && Number.isFinite(value) && value > 0 ? value : 0;
}
