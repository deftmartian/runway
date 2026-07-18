import { fail, redirect } from '@sveltejs/kit';
import { createGoalAndPlan } from '$lib/server/runway/repositories/plan-lifecycle';
import { getActivePlan, getCurrentGoal } from '$lib/server/runway/repositories/plan-queries';
import { getAthleteProfile } from '$lib/server/runway/repositories/profiles';
import { formString } from '$lib/server/runway/validation';
import { addDays, isValidTimeZone, todayIsoInTimeZone } from '$lib/training/date';
import type {
	GoalKind,
	GoalPriority,
	PlanIntake,
	RaceDistance,
	StartMode
} from '$lib/training/types';
import type { Actions, PageServerLoad } from './$types';

type Experience = PlanIntake['experience'];
type ExperienceField = Experience | '';
type StartModeField = StartMode | '';

type GoalFormValues = {
	goalKind: GoalKind;
	startMode: StartModeField;
	raceDistance: RaceDistance | '';
	targetDate: string;
	priority: GoalPriority;
	currentWeeklyDistanceKm: string;
	currentRunsPerWeek: string;
	longestRecentRunKm: string;
	experience: ExperienceField;
	calibrationDurationMinutes: string;
	availability: number[];
	preferredLongRunDay: string;
	timeZone: string;
	recentInjury: boolean;
	currentPain: boolean;
	recurringPain: boolean;
	medicalRestriction: boolean;
	injuryNotes: string;
	confirmReplace: boolean;
};

type GoalFieldErrors = Partial<Record<keyof GoalFormValues | 'healthFlags', string>>;

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const [activePlan, currentGoal, profile] = await Promise.all([
		getActivePlan(event.locals.user.id),
		getCurrentGoal(event.locals.user.id),
		getAthleteProfile(event.locals.user.id)
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
};

export const actions: Actions = {
	createPlan: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		const values = goalFormValues(formData);
		const currentGoal = await getCurrentGoal(event.locals.user.id);
		const targetBounds =
			isValidTimeZone(values.timeZone) && values.startMode
				? targetDateBounds(values.timeZone, values.startMode)
				: null;
		const fieldErrors = validatePlanIntake(values, targetBounds);

		if (currentGoal && !values.confirmReplace) {
			fieldErrors.confirmReplace = 'Confirm that the current goal should be archived first.';
		}
		if (Object.keys(fieldErrors).length > 0) {
			return fail(400, { message: 'Review the fields marked below.', values, fieldErrors });
		}

		const intake = planIntake(values);
		let hasPlan: boolean;
		try {
			const created = await createGoalAndPlan(event.locals.user.id, intake, values.timeZone);
			hasPlan = Boolean(created.plan);
		} catch (error) {
			const message = error instanceof Error ? error.message : '';
			return fail(400, {
				message: 'This setup cannot produce a plan yet.',
				values,
				fieldErrors: {
					...(message.includes('Unsafe')
						? { targetDate: 'Move the target date later or choose a shorter goal.' }
						: { availability: message || 'Review the selected schedule.' })
				} satisfies GoalFieldErrors
			});
		}
		if (!hasPlan) throw redirect(303, '/app/onboarding?pending=1');
		throw redirect(303, '/app');
	}
};

function planIntake(values: GoalFormValues): PlanIntake {
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
	values: GoalFormValues,
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
	if (values.injuryNotes.length > 240)
		errors.injuryNotes = 'Keep health context to 240 characters.';

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

function emptyGoalValues(timeZone: string): GoalFormValues {
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
		confirmReplace: false
	};
}

function valuesFromCurrentGoal(
	currentGoal: NonNullable<Awaited<ReturnType<typeof getCurrentGoal>>>,
	activePlan: Awaited<ReturnType<typeof getActivePlan>>,
	profile: Awaited<ReturnType<typeof getAthleteProfile>>
): GoalFormValues {
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
		confirmReplace: false
	};
}

function goalFormValues(formData: FormData): GoalFormValues {
	return {
		goalKind: enumValue(formData, 'goalKind', ['race', 'foundation']) || 'race',
		startMode: enumValue(formData, 'startMode', [
			'established',
			'foundation_to_goal',
			'foundation_only',
			'calibration'
		]),
		raceDistance: enumValue(formData, 'raceDistance', ['5k', '10k', 'half', 'marathon']),
		targetDate: formString(formData, 'targetDate'),
		priority:
			enumValue(formData, 'priority', ['finish_healthy', 'consistency']) || 'finish_healthy',
		currentWeeklyDistanceKm: formString(formData, 'currentWeeklyDistanceKm'),
		currentRunsPerWeek: formString(formData, 'currentRunsPerWeek'),
		longestRecentRunKm: formString(formData, 'longestRecentRunKm'),
		experience: enumValue(formData, 'experience', ['new', 'returning', 'comfortable']),
		calibrationDurationMinutes: formString(formData, 'calibrationDurationMinutes', '20'),
		availability: Array.from(
			new Set(
				formData
					.getAll('availability')
					.map(Number)
					.filter((value) => Number.isInteger(value) && value >= 0 && value <= 6)
			)
		),
		preferredLongRunDay: formString(formData, 'preferredLongRunDay'),
		timeZone: formString(formData, 'timeZone'),
		recentInjury: formData.get('recentInjury') === 'on',
		currentPain: formData.get('currentPain') === 'on',
		recurringPain: formData.get('recurringPain') === 'on',
		medicalRestriction: formData.get('medicalRestriction') === 'on',
		injuryNotes: formString(formData, 'injuryNotes'),
		confirmReplace: formData.get('confirmReplace') === 'on'
	};
}

function enumValue<const T extends string>(formData: FormData, key: string, values: T[]): T | '' {
	const value = formString(formData, key);
	return values.includes(value as T) ? (value as T) : '';
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
