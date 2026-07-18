import { todayIsoInTimeZone } from '$lib/training/date';
import { getActivePlan } from './plan-queries';
import { getAthleteTimeZone } from './profiles';

export type TrainingDateContext = {
	timeZone: string | null;
	today: string | null;
};

export type TrainingReadContext = TrainingDateContext & {
	activePlan: NonNullable<Awaited<ReturnType<typeof getActivePlan>>> | null;
};

/**
 * Request loaders pass this value through their repository reads. It deliberately
 * has no module-level cache: account, plan, and date state never crosses requests.
 */
export async function getTrainingDateContext(userId: string): Promise<TrainingDateContext> {
	const timeZone = await getAthleteTimeZone(userId);
	return {
		timeZone,
		today: timeZone ? todayIsoInTimeZone(timeZone) : null
	};
}

export async function getTrainingReadContext(userId: string): Promise<TrainingReadContext> {
	const [activePlan, date] = await Promise.all([
		getActivePlan(userId),
		getTrainingDateContext(userId)
	]);
	return { ...date, activePlan: activePlan ?? null };
}
