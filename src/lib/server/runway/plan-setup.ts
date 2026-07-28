import {
	createGoalAndPlan,
	PlanCreationInputError
} from '$lib/server/runway/repositories/plan-lifecycle';
import { getActivePlan, getCurrentGoal } from '$lib/server/runway/repositories/plan-queries';
import { getAthleteProfile } from '$lib/server/runway/repositories/profiles';
import {
	goalSetupSchema,
	type GoalSetupFieldErrors,
	type GoalSetupFormValues
} from '$lib/server/runway/validation';
import { addDays, isValidTimeZone, todayIsoInTimeZone } from '$lib/training/date';
import type { PlanIntake, RaceDistance, StartMode } from '$lib/training/types';

type Experience = PlanIntake['experience'];
type GoalFieldErrors = GoalSetupFieldErrors & { healthFlags?: string };

export type GoalSetupResult =
	| { ok: true; planPending: boolean }
	| {
			ok: false;
			message: string;
			values: GoalSetupFormValues;
			fieldErrors: GoalFieldErrors;
	  };

export async function getGoalSetupView(userId: string) {
	const [activePlan, currentGoal, profile] = await Promise.all([
		getActivePlan(userId),
		getCurrentGoal(userId),
		getAthleteProfile(userId)
	]);
	const timeZone = profile?.timeZone ?? 'UTC';
	const today = todayIsoInTimeZone(timeZone);

	return {
		initialValues: currentGoal
			? valuesFromCurrentGoal(currentGoal, activePlan, profile)
			: emptyGoalValues(profile?.timeZone ?? ''),
		minimumTargetDate: addDays(today, 8 * 7),
		minimumCalibrationTargetDate: addDays(today, 10 * 7),
		minimumFoundationTargetDate: addDays(today, 17 * 7),
		maximumTargetDate: addDays(today, 52 * 7 - 1),
		activeGoal: currentGoal
			? {
					title: currentGoal.title,
					targetDate: currentGoal.targetDate,
					state: currentGoal.state,
					risk: activePlan?.plan.risk ?? null
				}
			: null
	};
}

export async function createPlanFromGoalSetup(
	userId: string,
	input: unknown,
	transportFieldErrors: GoalSetupFieldErrors = {}
): Promise<GoalSetupResult> {
	const parsed = goalSetupSchema.safeParse(input);
	if (!parsed.success) {
		const values = safeGoalSetupValues(input);
		const fieldErrors = { ...transportFieldErrors };
		for (const issue of parsed.error.issues) {
			const field = issue.path[0];
			if (typeof field !== 'string' || !(field in values)) continue;
			const key = field as keyof GoalSetupFormValues;
			fieldErrors[key] ??= issue.message;
		}
		return { ok: false, message: 'Review the fields marked below.', values, fieldErrors };
	}

	const values = parsed.data;
	const currentGoal = await getCurrentGoal(userId);
	const targetBounds =
		isValidTimeZone(values.timeZone) && values.startMode
			? targetDateBounds(values.timeZone, values.startMode)
			: null;
	const fieldErrors: GoalFieldErrors = {
		...validatePlanIntake(values, targetBounds),
		...transportFieldErrors
	};
	if (currentGoal && !values.confirmReplace) {
		fieldErrors.confirmReplace = 'Confirm that the current goal should be archived first.';
	}
	if (Object.keys(fieldErrors).length > 0) {
		return { ok: false, message: 'Review the fields marked below.', values, fieldErrors };
	}

	try {
		const created = await createGoalAndPlan(userId, planIntake(values), values.timeZone);
		return { ok: true, planPending: !created.plan };
	} catch (error) {
		if (!(error instanceof PlanCreationInputError)) throw error;
		return {
			ok: false,
			message: 'This setup cannot produce a plan yet.',
			values,
			fieldErrors: planCreationFieldErrors(error)
		};
	}
}

function planIntake(values: GoalSetupFormValues): PlanIntake {
	const common = {
		priority: values.priority,
		units: 'metric' as const,
		experience: values.experience as Experience,
		availability: values.availability,
		injuryFlags: {
			recentInjury: values.recentInjury,
			currentPain: values.currentPain,
			recurringPain: values.recurringPain,
			medicalRestriction: values.medicalRestriction,
			notes: values.injuryNotes
		},
		startDate: nextPlanStartDate(values.timeZone)
	};

	switch (values.startMode) {
		case 'foundation_to_goal':
			return {
				...common,
				startMode: 'foundation_to_goal',
				goalKind: 'race',
				raceDistance: values.raceDistance as RaceDistance,
				targetDate: values.targetDate
			};
		case 'foundation_only':
			return {
				...common,
				startMode: 'foundation_only',
				goalKind: 'foundation',
				raceDistance: null
			};
		case 'calibration':
			return {
				...common,
				startMode: 'calibration',
				goalKind: values.goalKind,
				raceDistance: values.goalKind === 'race' ? (values.raceDistance as RaceDistance) : null,
				...(values.goalKind === 'race' ? { targetDate: values.targetDate } : {}),
				calibrationDurationSeconds: Math.round(Number(values.calibrationDurationMinutes) * 60)
			};
		case 'established':
			return {
				...common,
				startMode: 'established',
				goalKind: 'race',
				raceDistance: values.raceDistance as RaceDistance,
				targetDate: values.targetDate,
				currentWeeklyDistanceMeters: Math.round(Number(values.currentWeeklyDistanceKm) * 1_000),
				currentRunsPerWeek: Number(values.currentRunsPerWeek),
				longestRecentRunMeters: Math.round(Number(values.longestRecentRunKm) * 1_000),
				preferredLongRunDay: Number(values.preferredLongRunDay)
			};
		default:
			throw new Error('Choose a starting path before creating the plan.');
	}
}

function validatePlanIntake(
	values: GoalSetupFormValues,
	targetBounds: { minimum: string; maximum: string } | null
): GoalFieldErrors {
	const errors: GoalFieldErrors = {};
	const healthBlocked = values.currentPain || values.medicalRestriction;
	const raceGoal = values.goalKind === 'race';
	const requiredDays =
		values.startMode === 'foundation_to_goal' || values.startMode === 'foundation_only' ? 3 : 2;

	if (!isValidTimeZone(values.timeZone)) errors.timeZone = 'Select a valid IANA time zone.';
	if (!values.experience) errors.experience = 'Choose your current running experience.';
	if (raceGoal && !values.raceDistance) errors.raceDistance = 'Choose a race distance.';
	if (raceGoal && !values.startMode) errors.startMode = 'Choose how you are starting.';
	if (raceGoal && values.startMode && !values.targetDate) {
		errors.targetDate = 'Choose a target date.';
	} else if (
		raceGoal &&
		values.startMode &&
		targetBounds &&
		(values.targetDate < targetBounds.minimum || values.targetDate > targetBounds.maximum)
	) {
		errors.targetDate = `Choose a date from ${targetBounds.minimum} to ${targetBounds.maximum}.`;
	}
	if (values.startMode === 'foundation_only' && values.goalKind !== 'foundation') {
		errors.startMode = 'Foundation only uses the 30-minute continuous-running goal.';
	}
	if (values.startMode !== 'foundation_only' && values.goalKind === 'foundation') {
		errors.startMode = 'Choose Foundation only, or choose a race goal.';
	}
	if (new Set(values.availability).size < requiredDays) {
		errors.availability = `Choose at least ${requiredDays} available days.`;
	}
	if (requiresConcentratedScheduleAcceptance(values) && !values.confirmConcentratedSchedule) {
		errors.confirmConcentratedSchedule =
			'Confirm the two-day concentration before creating this plan.';
	}

	if (values.startMode === 'established' && !healthBlocked) {
		const weekly = strictNumber(values.currentWeeklyDistanceKm);
		const runs = strictNumber(values.currentRunsPerWeek);
		const longest = strictNumber(values.longestRecentRunKm);
		const preferred = strictNumber(values.preferredLongRunDay);
		if (weekly === null || weekly < 3 || weekly > 250) {
			errors.currentWeeklyDistanceKm = 'Enter a repeatable week of at least 3 km.';
		}
		if (runs === null || !Number.isInteger(runs) || runs < 2 || runs > 5) {
			errors.currentRunsPerWeek = 'Enter 2 to 5 current runs.';
		}
		if (longest === null || longest <= 0 || longest > 80) {
			errors.longestRecentRunKm = 'Enter a positive recent longest run.';
		}
		if (preferred === null || !values.availability.includes(preferred)) {
			errors.preferredLongRunDay = 'Choose an available long-run day.';
		}
		if (runs !== null && values.availability.length < runs) {
			errors.availability = 'Choose at least as many available days as current weekly runs.';
		}
	}

	if (values.startMode === 'calibration') {
		const duration = strictNumber(values.calibrationDurationMinutes);
		if (duration === null || !Number.isInteger(duration) || duration < 10 || duration > 30) {
			errors.calibrationDurationMinutes = 'Choose a whole duration from 10 to 30 minutes.';
		}
	}
	return errors;
}

function emptyGoalValues(timeZone: string): GoalSetupFormValues {
	return {
		goalKind: 'race',
		startMode: '',
		raceDistance: '',
		targetDate: '',
		priority: 'finish_healthy',
		currentWeeklyDistanceKm: '',
		currentRunsPerWeek: '',
		longestRecentRunKm: '',
		experience: '',
		calibrationDurationMinutes: '20',
		availability: [],
		preferredLongRunDay: '',
		timeZone,
		recentInjury: false,
		currentPain: false,
		recurringPain: false,
		medicalRestriction: false,
		injuryNotes: '',
		confirmConcentratedSchedule: false,
		confirmReplace: false
	};
}

function safeGoalSetupValues(input: unknown): GoalSetupFormValues {
	const record =
		typeof input === 'object' && input !== null ? (input as Record<string, unknown>) : {};
	const text = (key: string) => (typeof record[key] === 'string' ? record[key] : '');
	const flag = (key: string) => record[key] === true;
	const availability = Array.isArray(record['availability'])
		? record['availability'].filter(
				(value): value is number =>
					Number.isInteger(value) && Number(value) >= 0 && Number(value) <= 6
			)
		: [];
	return {
		goalKind: record['goalKind'] === 'foundation' ? 'foundation' : 'race',
		startMode: ['established', 'foundation_to_goal', 'foundation_only', 'calibration'].includes(
			text('startMode')
		)
			? (text('startMode') as GoalSetupFormValues['startMode'])
			: '',
		raceDistance: ['5k', '10k', 'half', 'marathon'].includes(text('raceDistance'))
			? (text('raceDistance') as GoalSetupFormValues['raceDistance'])
			: '',
		targetDate: text('targetDate').slice(0, 10),
		priority: record['priority'] === 'consistency' ? 'consistency' : 'finish_healthy',
		currentWeeklyDistanceKm: text('currentWeeklyDistanceKm').slice(0, 24),
		currentRunsPerWeek: text('currentRunsPerWeek').slice(0, 24),
		longestRecentRunKm: text('longestRecentRunKm').slice(0, 24),
		experience: ['new', 'returning', 'comfortable'].includes(text('experience'))
			? (text('experience') as GoalSetupFormValues['experience'])
			: '',
		calibrationDurationMinutes: text('calibrationDurationMinutes').slice(0, 24),
		availability: [...new Set(availability)].slice(0, 7),
		preferredLongRunDay: text('preferredLongRunDay').slice(0, 24),
		timeZone: text('timeZone').slice(0, 100),
		recentInjury: flag('recentInjury'),
		currentPain: flag('currentPain'),
		recurringPain: flag('recurringPain'),
		medicalRestriction: flag('medicalRestriction'),
		injuryNotes: text('injuryNotes').slice(0, 240),
		confirmConcentratedSchedule: flag('confirmConcentratedSchedule'),
		confirmReplace: flag('confirmReplace')
	};
}

function valuesFromCurrentGoal(
	currentGoal: NonNullable<Awaited<ReturnType<typeof getCurrentGoal>>>,
	activePlan: Awaited<ReturnType<typeof getActivePlan>>,
	profile: Awaited<ReturnType<typeof getAthleteProfile>>
): GoalSetupFormValues {
	return {
		goalKind: currentGoal.kind,
		startMode: currentGoal.startMode,
		raceDistance: currentGoal.distance ?? '',
		targetDate: currentGoal.kind === 'race' ? currentGoal.targetDate : '',
		priority: currentGoal.priority,
		currentWeeklyDistanceKm: profile
			? formatDistanceInput(profile.currentWeeklyDistanceMeters)
			: '',
		currentRunsPerWeek: profile ? String(profile.currentRunsPerWeek) : '',
		longestRecentRunKm: profile ? formatDistanceInput(profile.longestRecentRunMeters) : '',
		experience: isExperience(profile?.experience) ? profile.experience : '',
		calibrationDurationMinutes:
			activePlan?.plan.summary.kind === 'calibration'
				? String(activePlan.plan.summary.sessionDurationSeconds / 60)
				: '20',
		availability: profile?.availability ?? [],
		preferredLongRunDay:
			profile?.preferredLongRunDay === null || profile?.preferredLongRunDay === undefined
				? ''
				: String(profile.preferredLongRunDay),
		timeZone: profile?.timeZone ?? '',
		recentInjury: profile?.injuryFlags.recentInjury ?? false,
		currentPain: profile?.injuryFlags.currentPain ?? false,
		recurringPain: profile?.injuryFlags.recurringPain ?? false,
		medicalRestriction: profile?.injuryFlags.medicalRestriction ?? false,
		injuryNotes: profile?.injuryFlags.notes ?? '',
		confirmConcentratedSchedule: false,
		confirmReplace: false
	};
}

function requiresConcentratedScheduleAcceptance(values: GoalSetupFormValues): boolean {
	return (
		values.startMode === 'established' &&
		!values.currentPain &&
		!values.medicalRestriction &&
		(values.raceDistance === 'half' || values.raceDistance === 'marathon') &&
		strictNumber(values.currentRunsPerWeek) === 2
	);
}

function planCreationFieldErrors(error: PlanCreationInputError): GoalFieldErrors {
	switch (error.field) {
		case 'timeZone':
			return { timeZone: error.message };
		case 'targetDate':
			return { targetDate: error.message };
		case 'availability':
			return { availability: error.message };
		case 'baseline':
			return { currentWeeklyDistanceKm: error.message };
		case 'health':
			return { healthFlags: error.message };
		case 'calibrationDuration':
			return { calibrationDurationMinutes: error.message };
	}
}

function strictNumber(value: string): number | null {
	if (!value.trim()) return null;
	const parsed = Number(value);
	return Number.isFinite(parsed) ? parsed : null;
}

function targetDateBounds(timeZone: string, startMode: StartMode) {
	const today = todayIsoInTimeZone(timeZone);
	const minimumWeeks =
		startMode === 'foundation_to_goal' ? 17 : startMode === 'calibration' ? 10 : 8;
	return { minimum: addDays(today, minimumWeeks * 7), maximum: addDays(today, 52 * 7 - 1) };
}

function nextPlanStartDate(timeZone: string): string {
	const today = todayIsoInTimeZone(timeZone);
	const weekday = new Date(`${today}T00:00:00.000Z`).getUTCDay();
	const daysUntilMonday = weekday === 1 ? 0 : (8 - weekday) % 7;
	return addDays(today, daysUntilMonday);
}

function formatDistanceInput(meters: number): string {
	return String(Math.round((meters / 1_000) * 10) / 10);
}

function isExperience(value: string | undefined): value is Experience {
	return value === 'new' || value === 'returning' || value === 'comfortable';
}
