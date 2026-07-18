export type AutoMatchActivity = {
	activityDate: string;
	distanceMeters: number;
	durationSeconds: number | null;
};

export type AutoMatchWorkout = {
	id: string;
	scheduledDate: string;
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
};

type RankedMatch = {
	workoutId: string;
	dateDifferenceDays: number;
	amountDifferenceRatio: number;
};

const dayMilliseconds = 24 * 60 * 60 * 1_000;

/**
 * Selects only a single conservative match. A candidate must be within the
 * same material threshold used to classify plan-versus-actual results, and a
 * tied best result is deliberately left for review.
 */
export function selectAutoWorkoutMatch(
	activity: AutoMatchActivity,
	candidates: readonly AutoMatchWorkout[]
): string | null {
	const ranked = candidates
		.flatMap((candidate): RankedMatch[] => {
			const amountDifferenceRatio = materialAmountDifference(activity, candidate);
			if (amountDifferenceRatio === null) return [];
			return [
				{
					workoutId: candidate.id,
					dateDifferenceDays: differenceInDays(activity.activityDate, candidate.scheduledDate),
					amountDifferenceRatio
				}
			];
		})
		.sort(
			(left, right) =>
				left.dateDifferenceDays - right.dateDifferenceDays ||
				left.amountDifferenceRatio - right.amountDifferenceRatio ||
				left.workoutId.localeCompare(right.workoutId)
		);

	const best = ranked[0];
	if (!best) return null;
	const second = ranked[1];
	if (
		second?.dateDifferenceDays === best.dateDifferenceDays &&
		Math.abs(second.amountDifferenceRatio - best.amountDifferenceRatio) < Number.EPSILON
	) {
		return null;
	}
	return best.workoutId;
}

function materialAmountDifference(
	activity: AutoMatchActivity,
	workout: AutoMatchWorkout
): number | null {
	if (workout.targetDistanceMeters > 0) {
		const difference = Math.abs(activity.distanceMeters - workout.targetDistanceMeters);
		const threshold = Math.max(500, workout.targetDistanceMeters * 0.15);
		return difference <= threshold ? difference / workout.targetDistanceMeters : null;
	}

	if (workout.targetDurationSeconds && activity.durationSeconds) {
		const difference = Math.abs(activity.durationSeconds - workout.targetDurationSeconds);
		const threshold = Math.max(300, workout.targetDurationSeconds * 0.15);
		return difference <= threshold ? difference / workout.targetDurationSeconds : null;
	}

	return null;
}

function differenceInDays(left: string, right: string): number {
	return Math.abs(
		(Date.parse(`${left}T00:00:00.000Z`) - Date.parse(`${right}T00:00:00.000Z`)) / dayMilliseconds
	);
}
