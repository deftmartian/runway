import type { PageData } from './$types';

export type OnboardingValues = PageData['initialValues'];
export type OnboardingField = keyof OnboardingValues | 'healthFlags';
export type OnboardingFieldErrors = Partial<Record<OnboardingField, string>>;
export type StartMode = OnboardingValues['startMode'];
export type RaceStartMode = Exclude<StartMode, '' | 'foundation_only'>;

export type TargetWindows = {
	established: string;
	calibration: string;
	foundation: string;
	maximum: string;
};

export const steps = ['Goal', 'Starting point', 'Schedule', 'Review'] as const;

export const weekdays = [
	{ value: 1, short: 'Mon', label: 'Monday' },
	{ value: 2, short: 'Tue', label: 'Tuesday' },
	{ value: 3, short: 'Wed', label: 'Wednesday' },
	{ value: 4, short: 'Thu', label: 'Thursday' },
	{ value: 5, short: 'Fri', label: 'Friday' },
	{ value: 6, short: 'Sat', label: 'Saturday' },
	{ value: 0, short: 'Sun', label: 'Sunday' }
] as const;

export function healthBlocksScheduling(values: OnboardingValues) {
	return values.currentPain || values.medicalRestriction;
}

export function hasHealthCaution(values: OnboardingValues) {
	return values.recentInjury || values.recurringPain;
}

export function requiresConcentratedSchedule(values: OnboardingValues) {
	return (
		values.startMode === 'established' &&
		!healthBlocksScheduling(values) &&
		(values.raceDistance === 'half' || values.raceDistance === 'marathon') &&
		Number(values.currentRunsPerWeek) === 2
	);
}

export function minimumForStartMode(mode: StartMode, windows: TargetWindows) {
	return mode === 'foundation_to_goal'
		? windows.foundation
		: mode === 'calibration'
			? windows.calibration
			: windows.established;
}

export function startingPathName(mode: RaceStartMode) {
	return mode === 'foundation_to_goal'
		? 'Foundation first'
		: mode === 'calibration'
			? 'Short calibration'
			: 'Established week';
}

export function targetWindowHelp(mode: StartMode, timeZone: string) {
	const zone = timeZone || 'your time zone';
	return mode === 'foundation_to_goal'
		? `17–52 weeks ahead in ${zone}: nine foundation weeks, then at least eight race-plan weeks.`
		: mode === 'calibration'
			? `10–52 weeks ahead in ${zone}: two calibration weeks, then at least eight race-plan weeks.`
			: `8–52 weeks ahead in ${zone}.`;
}

export function modeLabel(values: OnboardingValues) {
	switch (values.startMode) {
		case 'foundation_to_goal':
			return 'Nine-week foundation, then confirm a race baseline';
		case 'foundation_only':
			return 'Nine-week foundation to 30 continuous minutes';
		case 'calibration':
			return `Two-week ${values.calibrationDurationMinutes || '—'} minute calibration`;
		case 'established':
			return 'Distance plan from an established week';
		default:
			return 'Starting path needed';
	}
}

export function selectedDayLabels(availability: number[]) {
	return weekdays.filter((day) => availability.includes(day.value)).map((day) => day.short);
}

export function distanceLabel(value: string) {
	return (
		({ '5k': '5K', '10k': '10K', half: 'Half marathon', marathon: 'Marathon' } as const)[
			value as '5k'
		] ?? 'Race goal'
	);
}

export function validationStep(
	values: OnboardingValues,
	windows: TargetWindows,
	hasActiveGoal: boolean
): number | null {
	const healthBlocked = healthBlocksScheduling(values);
	const minimumTargetDate = minimumForStartMode(values.startMode, windows);

	if (values.goalKind === 'race' && !values.startMode) return 0;
	if (
		values.goalKind === 'race' &&
		(!values.raceDistance ||
			!values.targetDate ||
			values.targetDate < minimumTargetDate ||
			values.targetDate > windows.maximum)
	) {
		return 0;
	}
	if (!values.experience) return 1;
	if (
		values.startMode === 'established' &&
		!healthBlocked &&
		(!numberInRange(values.currentWeeklyDistanceKm, 3, 250) ||
			!integerInRange(values.currentRunsPerWeek, 2, 5) ||
			!numberInRange(values.longestRecentRunKm, Number.EPSILON, 80))
	) {
		return 1;
	}
	if (
		values.startMode === 'calibration' &&
		!integerInRange(values.calibrationDurationMinutes, 10, 30)
	) {
		return 1;
	}
	const requiredDays =
		values.startMode === 'foundation_to_goal' || values.startMode === 'foundation_only' ? 3 : 2;
	if (new Set(values.availability).size < requiredDays || !values.timeZone) return 2;
	if (
		values.startMode === 'established' &&
		(!values.preferredLongRunDay ||
			!values.availability.includes(Number(values.preferredLongRunDay)) ||
			values.availability.length < Number(values.currentRunsPerWeek))
	) {
		return 2;
	}
	if (requiresConcentratedSchedule(values) && !values.confirmConcentratedSchedule) return 3;
	if (hasActiveGoal && !values.confirmReplace) return 3;
	return null;
}

export function validationMessage(
	targetStep: number,
	values: OnboardingValues,
	windows: TargetWindows
) {
	const minimumTargetDate = minimumForStartMode(values.startMode, windows);
	if (
		targetStep === 0 &&
		values.goalKind === 'race' &&
		values.targetDate &&
		(values.targetDate < minimumTargetDate || values.targetDate > windows.maximum)
	) {
		return values.startMode === 'foundation_to_goal'
			? 'Foundation first needs a race date 17 to 52 weeks away.'
			: values.startMode === 'calibration'
				? 'Calibration needs a race date 10 to 52 weeks away.'
				: 'Choose a race date 8 to 52 weeks away.';
	}
	if (
		targetStep === 2 &&
		values.startMode === 'established' &&
		values.availability.length < Number(values.currentRunsPerWeek)
	) {
		return 'Choose at least as many available days as current weekly runs.';
	}
	if (
		targetStep === 3 &&
		requiresConcentratedSchedule(values) &&
		!values.confirmConcentratedSchedule
	) {
		return 'Confirm the two-day concentration before creating this plan.';
	}
	return 'Complete this step before continuing.';
}

export function errorStep(errors: OnboardingFieldErrors): number | null {
	if (
		errors.goalKind ||
		errors.startMode ||
		errors.raceDistance ||
		errors.targetDate ||
		errors.priority
	) {
		return 0;
	}
	if (
		errors.currentWeeklyDistanceKm ||
		errors.currentRunsPerWeek ||
		errors.longestRecentRunKm ||
		errors.experience ||
		errors.calibrationDurationMinutes ||
		errors.injuryNotes ||
		errors.healthFlags
	) {
		return 1;
	}
	if (errors.availability || errors.preferredLongRunDay || errors.timeZone) return 2;
	if (errors.confirmConcentratedSchedule || errors.confirmReplace) return 3;
	return null;
}

export function targetWindowsForTimeZone(timeZone: string, now = new Date()): TargetWindows | null {
	try {
		const parts = new Intl.DateTimeFormat('en-US', {
			timeZone,
			year: 'numeric',
			month: '2-digit',
			day: '2-digit'
		}).formatToParts(now);
		const year = parts.find((part) => part.type === 'year')?.value;
		const month = parts.find((part) => part.type === 'month')?.value;
		const day = parts.find((part) => part.type === 'day')?.value;
		if (!year || !month || !day) return null;
		const today = `${year}-${month}-${day}`;
		return {
			established: shiftIsoDate(today, 8 * 7),
			calibration: shiftIsoDate(today, 10 * 7),
			foundation: shiftIsoDate(today, 17 * 7),
			maximum: shiftIsoDate(today, 52 * 7 - 1)
		};
	} catch {
		return null;
	}
}

function shiftIsoDate(value: string, days: number) {
	const date = new Date(`${value}T00:00:00.000Z`);
	date.setUTCDate(date.getUTCDate() + days);
	return date.toISOString().slice(0, 10);
}

function numberInRange(value: string | number, minimum: number, maximum: number) {
	const rawValue = String(value).trim();
	if (!rawValue) return false;
	const parsed = Number(rawValue);
	return Number.isFinite(parsed) && parsed >= minimum && parsed <= maximum;
}

function integerInRange(value: string | number, minimum: number, maximum: number) {
	return numberInRange(value, minimum, maximum) && Number.isInteger(Number(value));
}
