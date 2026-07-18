import { addDays, daysBetween, parseIsoDate, todayIso, weekStart } from './date';
import { sourceRefs } from './sources';
import type {
	CalibrationIntake,
	FoundationOnlyIntake,
	FoundationToGoalIntake,
	GeneratedCalibrationPlan,
	GeneratedDistancePlan,
	GeneratedFoundationPlan,
	GeneratedPlan,
	GeneratedWeek,
	GeneratedWorkout,
	InjuryFlags,
	PlanIntake,
	RaceDistance,
	RiskRating,
	RunWalkBlock,
	TrainingIntake
} from './types';

const maxPlanWeeks = 52;
const minimumRunMeters = 500;

const raceMeters: Record<RaceDistance, number> = {
	'5k': 5_000,
	'10k': 10_000,
	half: 21_100,
	marathon: 42_200
};

const peakWeeklyMeters: Record<RaceDistance, number> = {
	'5k': 14_000,
	'10k': 22_000,
	half: 34_000,
	marathon: 58_000
};

const peakLongRunMeters: Record<RaceDistance, number> = {
	'5k': 5_000,
	'10k': 9_000,
	half: 18_000,
	marathon: 32_000
};

const taperWeeks: Record<RaceDistance, number> = {
	'5k': 1,
	'10k': 1,
	half: 2,
	marathon: 3
};

const raceSourceRefs: Record<RaceDistance, string> = {
	'5k': sourceRefs.rei5kTraining,
	'10k': sourceRefs.rei10kTraining,
	half: sourceRefs.reiHalfMarathon,
	marathon: sourceRefs.reiMarathonTraining
};

export function getRaceMeters(distance: RaceDistance): number {
	return raceMeters[distance];
}

export function generatePlan(intake: PlanIntake): GeneratedPlan {
	if (intake.startMode === 'foundation_to_goal' || intake.startMode === 'foundation_only') {
		return generateFoundationPlan(intake);
	}
	if (intake.startMode === 'calibration') return generateCalibrationPlan(intake);
	return generateTrainingPlan(intake);
}

export function classifyRamp(
	requiredWeeklyIncreasePercent: number,
	hasInjuryRisk: boolean
): RiskRating {
	const injuryOffset = hasInjuryRisk ? 2 : 0;
	if (requiredWeeklyIncreasePercent <= 8 - injuryOffset) return 'conservative';
	if (requiredWeeklyIncreasePercent <= 12 - injuryOffset) return 'moderate';
	if (requiredWeeklyIncreasePercent <= 18 - injuryOffset) return 'aggressive';
	return 'unsafe';
}

export function generateTrainingPlan(intake: TrainingIntake): GeneratedDistancePlan {
	assertSupportedBaseline(intake);
	const startDate = intake.startDate ?? nextPlanWeekStart();
	const planDays = daysBetween(startDate, intake.targetDate) + 1;
	if (planDays < 1) throw new Error('The target date must be on or after the plan start date.');
	const availableWeeks = Math.max(1, Math.ceil(planDays / 7));
	if (availableWeeks > maxPlanWeeks) {
		throw new Error('Training plans cannot exceed 52 weeks.');
	}
	assertSchedulableIntake(intake);

	const taper = Math.min(taperWeeks[intake.raceDistance], Math.max(1, availableWeeks - 1));
	const buildWeeks = Math.max(1, availableWeeks - taper);
	const hasInjuryRisk = hasInjuryRiskFlags(intake.injuryFlags);
	const baselineMeters = intake.currentWeeklyDistanceMeters;
	const peakMeters = peakWeeklyMeters[intake.raceDistance];
	const longRunPeakMeters = peakLongRunMeters[intake.raceDistance];
	const initialLongRunMeters = Math.min(
		intake.longestRecentRunMeters,
		baselineMeters,
		longRunPeakMeters
	);
	const weeklyRampRequired = requiredBuildRamp(baselineMeters, peakMeters, buildWeeks);
	const longRampRequired = requiredBuildRamp(initialLongRunMeters, longRunPeakMeters, buildWeeks);
	const requiredWeeklyIncreasePercent = Math.max(weeklyRampRequired, longRampRequired);
	const shortPlanUnsafe = availableWeeks < 8 && baselineMeters < peakMeters * 0.55;
	const initialRisk = shortPlanUnsafe
		? 'unsafe'
		: classifyRamp(requiredWeeklyIncreasePercent, hasInjuryRisk);
	const warnings = getWarnings(intake, availableWeeks, requiredWeeklyIncreasePercent, initialRisk);
	const defaultWeeklyIncreasePercent = priorityRampCap(intake) * 100;
	const normalRamp = Math.min(requiredWeeklyIncreasePercent, defaultWeeklyIncreasePercent) / 100;
	const weeks: GeneratedWeek[] = [];
	const trainingDistances: number[] = [];

	let previousTrainingDistance = baselineMeters;
	let previousLongRun = initialLongRunMeters;
	let peakTrainingDistance = baselineMeters;
	let peakTrainingLongRun = initialLongRunMeters;

	for (let index = 0; index < availableWeeks; index += 1) {
		const weekNumber = index + 1;
		const isTaper = weekNumber > availableWeeks - taper;
		const isDownWeek = !isTaper && weekNumber % 4 === 0;
		const taperPosition = isTaper ? weekNumber - (availableWeeks - taper) : 0;
		const weekStartDate = addDays(startDate, index * 7);
		const nominalWeekEnd = addDays(weekStartDate, 6);
		const weekEndDate = nominalWeekEnd < intake.targetDate ? nominalWeekEnd : intake.targetDate;
		const isRaceWeek = intake.targetDate >= weekStartDate && intake.targetDate <= weekEndDate;

		const rampedDistance =
			weekNumber === 1 ? baselineMeters : Math.round(previousTrainingDistance * (1 + normalRamp));
		const buildDistance = Math.min(peakMeters, rampedDistance);
		const trainingBudget = isTaper
			? taperTarget(peakTrainingDistance, taperPosition, taper)
			: isDownWeek
				? Math.round(buildDistance * 0.85)
				: buildDistance;
		const rampedLongRun =
			weekNumber === 1 ? initialLongRunMeters : Math.round(previousLongRun * (1 + normalRamp));
		const desiredLongRun = isTaper
			? taperTarget(peakTrainingLongRun, taperPosition, taper)
			: Math.min(longRunPeakMeters, isDownWeek ? Math.round(rampedLongRun * 0.85) : rampedLongRun);

		const scheduled = createWeekWorkouts(
			intake,
			weekStartDate,
			weekEndDate,
			trainingBudget,
			desiredLongRun,
			isRaceWeek ? intake.targetDate : undefined
		);
		const actualTrainingDistance = scheduled.workouts
			.filter((workout) => workout.type !== 'race')
			.reduce((sum, workout) => sum + workout.targetDistanceMeters, 0);
		const targetDistanceMeters = scheduled.workouts.reduce(
			(sum, workout) => sum + workout.targetDistanceMeters,
			0
		);
		const eventDistanceMeters = scheduled.workouts
			.filter((workout) => workout.type === 'race')
			.reduce((sum, workout) => sum + workout.targetDistanceMeters, 0);
		const weeklyIncrease = percentIncrease(previousTrainingDistance, actualTrainingDistance);
		const longIncrease = scheduled.longRunMeters
			? percentIncrease(previousLongRun, scheduled.longRunMeters)
			: 0;
		const weekRisk = classifyRamp(Math.max(weeklyIncrease, longIncrease), hasInjuryRisk);

		weeks.push({
			weekNumber,
			startDate: weekStartDate,
			trainingTargetDistanceMeters: actualTrainingDistance,
			eventDistanceMeters,
			targetDistanceMeters,
			targetDurationSeconds: scheduled.workouts.reduce(
				(sum, workout) => sum + (workout.targetDurationSeconds ?? 0),
				0
			),
			longRunMeters: scheduled.longRunMeters,
			risk: weekRisk,
			isDownWeek,
			isTaper,
			workouts: scheduled.workouts
		});
		trainingDistances.push(actualTrainingDistance);

		previousTrainingDistance = actualTrainingDistance;
		if (scheduled.longRunMeters > 0) previousLongRun = scheduled.longRunMeters;
		if (!isTaper) {
			peakTrainingDistance = Math.max(peakTrainingDistance, actualTrainingDistance);
			peakTrainingLongRun = Math.max(peakTrainingLongRun, scheduled.longRunMeters);
		}
	}

	const generatedPeakMeters = Math.max(...trainingDistances);
	const generatedLongRunPeakMeters = Math.max(...weeks.map((week) => week.longRunMeters));
	if (generatedPeakMeters < peakMeters * 0.95) {
		warnings.push(
			'The available weeks do not allow the usual peak distance for this goal. Move the target date or choose a shorter distance.'
		);
	}
	const readinessShortfall =
		generatedLongRunPeakMeters < getLongRunReadinessFloor(intake.raceDistance);
	if (readinessShortfall) {
		warnings.push(
			'The longest planned run is low for this race distance. Keep the goal completion-oriented or move the date.'
		);
	}
	const marathonBaseShortfall = hasMarathonBaseShortfall(intake, baselineMeters);
	if (marathonBaseShortfall) {
		warnings.push(
			'Marathon goals need a stronger recent base than shorter races. Build consistency first or move the date.'
		);
	}
	const concentratedSchedule =
		(intake.raceDistance === 'half' || intake.raceDistance === 'marathon') &&
		plannedRunCount(intake) < 3;
	if (concentratedSchedule) {
		warnings.push(
			'Two run days concentrate a high-volume goal into unusually large sessions. Add a third available day, choose a shorter goal, or explicitly accept the higher load concentration.'
		);
	}
	const localRisk = highestRisk(weeks.map((week) => week.risk));
	const baselineRisk = marathonBaseShortfall
		? elevateRisk(highestRisk([initialRisk, localRisk]), 'unsafe')
		: readinessShortfall
			? elevateRisk(highestRisk([initialRisk, localRisk]), 'aggressive')
			: highestRisk([initialRisk, localRisk]);
	const risk = concentratedSchedule ? elevateRisk(baselineRisk, 'aggressive') : baselineRisk;

	return {
		phase: 'distance',
		startMode: 'established',
		startDate,
		targetDate: intake.targetDate,
		weeks,
		risk,
		summary: {
			kind: 'distance',
			baselineMeters,
			peakMeters: generatedPeakMeters,
			requiredWeeklyIncreasePercent: Math.round(requiredWeeklyIncreasePercent * 10) / 10,
			defaultWeeklyIncreasePercent: Math.round(defaultWeeklyIncreasePercent * 10) / 10,
			longRunPeakMeters: generatedLongRunPeakMeters,
			warnings
		},
		sourceRefs: [
			sourceRefs.mayoInjuryAvoidance,
			sourceRefs.mayoTaper,
			raceSourceRefs[intake.raceDistance],
			sourceRefs.rrcaRunnerGuidance,
			sourceRefs.niamsSportsInjury
		]
	};
}

type FoundationIntake = FoundationToGoalIntake | FoundationOnlyIntake;

const foundationSessions: readonly (readonly RunWalkBlock[][])[] = [
	Array.from({ length: 3 }, () => [
		{ repetitions: 7, segments: [run(60), walk(90)] },
		{ repetitions: 1, segments: [run(60)] }
	]),
	Array.from({ length: 3 }, () => [
		{ repetitions: 5, segments: [run(90), walk(120)] },
		{ repetitions: 1, segments: [run(90)] }
	]),
	Array.from({ length: 3 }, () => [
		{
			repetitions: 1,
			segments: [run(90), walk(90), run(180), walk(180), run(90), walk(90), run(180)]
		}
	]),
	Array.from({ length: 3 }, () => [
		{
			repetitions: 1,
			segments: [run(180), walk(90), run(300), walk(150), run(180), walk(90), run(300)]
		}
	]),
	[
		[
			{
				repetitions: 1,
				segments: [run(300), walk(180), run(300), walk(180), run(300)]
			}
		],
		[{ repetitions: 1, segments: [run(480), walk(300), run(480)] }],
		[{ repetitions: 1, segments: [run(1_200)] }]
	],
	[
		[
			{
				repetitions: 1,
				segments: [run(300), walk(180), run(480), walk(180), run(300)]
			}
		],
		[{ repetitions: 1, segments: [run(600), walk(180), run(600)] }],
		[{ repetitions: 1, segments: [run(1_500)] }]
	],
	Array.from({ length: 3 }, () => [{ repetitions: 1, segments: [run(1_500)] }]),
	Array.from({ length: 3 }, () => [{ repetitions: 1, segments: [run(1_680)] }]),
	Array.from({ length: 3 }, () => [{ repetitions: 1, segments: [run(1_800)] }])
];

export function generateFoundationPlan(intake: FoundationIntake): GeneratedFoundationPlan {
	assertPhaseCanStart(intake.injuryFlags);
	const startDate = intake.startDate ?? nextPlanWeekStart();
	const sessionDays = pickPhaseDays(intake.availability, 3);
	const weeks = foundationSessions.map((sessions, index) => {
		const weekStartDate = addDays(startDate, index * 7);
		const workouts = phaseWeekWorkouts(weekStartDate, sessionDays, (sessionIndex, date) => {
			const blocks = structuredClone(sessions[sessionIndex] ?? sessions.at(-1) ?? []);
			const totalDurationSeconds = 600 + blocksDuration(blocks);
			return {
				scheduledDate: date,
				type: 'easy',
				targetDistanceMeters: 0,
				targetDurationSeconds: totalDurationSeconds,
				prescription: {
					kind: 'timed',
					totalDurationSeconds,
					warmupSeconds: 300,
					cooldownSeconds: 300,
					blocks
				},
				intensity: 'easy',
				purpose: `Foundation run/walk ${sessionIndex + 1}`,
				reason: `NHS Couch to 5K week ${index + 1}. Keep each running interval comfortable.`,
				sourceRefs: [sourceRefs.nhsCouchTo5k, sourceRefs.mayoBeginnerRunWalk]
			} satisfies GeneratedWorkout;
		});
		return timedWeek(index + 1, weekStartDate, workouts);
	});
	const targetDate = addDays(startDate, 62);

	return {
		phase: 'foundation',
		startMode: intake.startMode,
		startDate,
		targetDate,
		weeks,
		risk: 'conservative',
		summary: {
			kind: 'foundation',
			programWeeks: 9,
			sessionsPerWeek: 3,
			continuousRunTargetSeconds: 1_800,
			warnings: [
				'Completion provides observed training data for confirmation; it does not create a race baseline automatically.',
				...timedPhaseHealthWarnings(intake.injuryFlags)
			]
		},
		sourceRefs: [sourceRefs.nhsCouchTo5k, sourceRefs.mayoBeginnerRunWalk]
	};
}

export function generateCalibrationPlan(intake: CalibrationIntake): GeneratedCalibrationPlan {
	assertPhaseCanStart(intake.injuryFlags);
	if (
		!Number.isInteger(intake.calibrationDurationSeconds) ||
		intake.calibrationDurationSeconds < 600 ||
		intake.calibrationDurationSeconds > 1_800
	) {
		throw new Error(
			'Calibration duration must be a whole number of seconds from 10 to 30 minutes.'
		);
	}
	const startDate = intake.startDate ?? nextPlanWeekStart();
	const sessionDays = pickPhaseDays(intake.availability, 2);
	const blocks = calibrationBlocks(intake.calibrationDurationSeconds - 240);
	const weeks = Array.from({ length: 2 }, (_, index) => {
		const weekStartDate = addDays(startDate, index * 7);
		const workouts = phaseWeekWorkouts(weekStartDate, sessionDays, (sessionIndex, date) => ({
			scheduledDate: date,
			type: 'easy',
			targetDistanceMeters: 0,
			targetDurationSeconds: intake.calibrationDurationSeconds,
			prescription: {
				kind: 'timed',
				totalDurationSeconds: intake.calibrationDurationSeconds,
				warmupSeconds: 120,
				cooldownSeconds: 120,
				blocks: structuredClone(blocks)
			},
			intensity: 'easy',
			purpose: `Calibration run/walk ${sessionIndex + 1}`,
			reason: 'Repeat the same comfortable time. Distance is observed, not prescribed.',
			sourceRefs: [sourceRefs.mayoBeginnerRunWalk]
		}));
		return timedWeek(index + 1, weekStartDate, workouts);
	});

	return {
		phase: 'calibration',
		startMode: 'calibration',
		startDate,
		targetDate: addDays(startDate, 13),
		weeks,
		risk: 'conservative',
		summary: {
			kind: 'calibration',
			programWeeks: 2,
			sessionsPerWeek: 2,
			sessionDurationSeconds: intake.calibrationDurationSeconds,
			warnings: [
				'Distance remains observational until the runner confirms the completed activities as a baseline.',
				...timedPhaseHealthWarnings(intake.injuryFlags)
			]
		},
		sourceRefs: [sourceRefs.mayoBeginnerRunWalk]
	};
}

function phaseWeekWorkouts(
	weekStartDate: string,
	sessionDays: number[],
	createSession: (sessionIndex: number, date: string) => GeneratedWorkout
): GeneratedWorkout[] {
	let sessionIndex = 0;
	return Array.from({ length: 7 }, (_, offset) => {
		const date = addDays(weekStartDate, offset);
		const weekday = parseIsoDate(date).getUTCDay();
		if (!sessionDays.includes(weekday)) return restWorkout(date);
		return createSession(sessionIndex++, date);
	});
}

function timedWeek(
	weekNumber: number,
	startDate: string,
	workouts: GeneratedWorkout[]
): GeneratedWeek {
	return {
		weekNumber,
		startDate,
		trainingTargetDistanceMeters: 0,
		eventDistanceMeters: 0,
		targetDistanceMeters: 0,
		targetDurationSeconds: workouts.reduce(
			(sum, workout) => sum + (workout.targetDurationSeconds ?? 0),
			0
		),
		longRunMeters: 0,
		risk: 'conservative',
		isDownWeek: false,
		isTaper: false,
		workouts
	};
}

function pickPhaseDays(availability: number[], count: number): number[] {
	if (availability.some((day) => !Number.isInteger(day) || day < 0 || day > 6)) {
		throw new Error('Available run days must be unique weekdays from 0 through 6.');
	}
	const unique = [...new Set(availability)].sort((a, b) => a - b);
	if (unique.length !== availability.length) {
		throw new Error('Available run days must be unique weekdays from 0 through 6.');
	}
	if (unique.length < count) throw new Error(`Choose at least ${count} available run days.`);
	const combinations = chooseDays(unique, count);
	combinations.sort(
		(a, b) => minimumCircularSpacing(b) - minimumCircularSpacing(a) || compareDays(a, b)
	);
	const picked = combinations[0];
	if (!picked || minimumCircularSpacing(picked) < 2) {
		throw new Error('Choose available days that leave a rest day between beginner sessions.');
	}
	return picked;
}

function chooseDays(days: number[], count: number, start = 0, picked: number[] = []): number[][] {
	if (picked.length === count) return [[...picked]];
	const combinations: number[][] = [];
	for (let index = start; index <= days.length - (count - picked.length); index += 1) {
		const day = days[index];
		if (day === undefined) continue;
		picked.push(day);
		combinations.push(...chooseDays(days, count, index + 1, picked));
		picked.pop();
	}
	return combinations;
}

function minimumCircularSpacing(days: number[]): number {
	return Math.min(
		...days.flatMap((day, index) =>
			days
				.slice(index + 1)
				.map((other) => Math.min(Math.abs(day - other), 7 - Math.abs(day - other)))
		)
	);
}

function compareDays(left: number[], right: number[]): number {
	for (let index = 0; index < Math.min(left.length, right.length); index += 1) {
		const difference = (left[index] ?? 0) - (right[index] ?? 0);
		if (difference !== 0) return difference;
	}
	return left.length - right.length;
}

function calibrationBlocks(activeSeconds: number): RunWalkBlock[] {
	const repetitions = Math.floor(activeSeconds / 150);
	const remainder = activeSeconds - repetitions * 150;
	const blocks: RunWalkBlock[] = [];
	if (repetitions > 0) {
		blocks.push({ repetitions, segments: [run(60), walk(90)] });
	}
	if (remainder > 0) {
		blocks.push({
			repetitions: 1,
			segments: remainder <= 60 ? [run(remainder)] : [run(60), walk(remainder - 60)]
		});
	}
	return blocks;
}

function blocksDuration(blocks: RunWalkBlock[]): number {
	return blocks.reduce(
		(total, block) =>
			total +
			block.repetitions * block.segments.reduce((sum, segment) => sum + segment.durationSeconds, 0),
		0
	);
}

function run(durationSeconds: number) {
	return { kind: 'run' as const, durationSeconds };
}

function walk(durationSeconds: number) {
	return { kind: 'walk' as const, durationSeconds };
}

function assertPhaseCanStart(flags: TrainingIntake['injuryFlags']): void {
	if (flags.currentPain) throw new Error('A workout phase cannot start while pain is present now.');
	if (flags.medicalRestriction) {
		throw new Error('A workout phase cannot start while a clinician has limited running.');
	}
}

function timedPhaseHealthWarnings(flags: InjuryFlags): string[] {
	if (!flags.recentInjury && !flags.recurringPain) return [];
	return [
		'Recent injury or recurring pain is noted with this plan. It does not change the timed prescription or assess whether running is appropriate.'
	];
}

function requiredBuildRamp(startMeters: number, goalMeters: number, buildWeeks: number): number {
	if (buildWeeks <= 1 || startMeters >= goalMeters) return 0;
	const transitions = buildWeeks - 1;
	const downWeeks = Math.floor(buildWeeks / 4);
	const cutbackFactor = 0.85 ** downWeeks;
	return (Math.pow(goalMeters / startMeters / cutbackFactor, 1 / transitions) - 1) * 100;
}

function getWarnings(
	intake: TrainingIntake,
	availableWeeks: number,
	requiredWeeklyIncreasePercent: number,
	risk: RiskRating
): string[] {
	const warnings: string[] = [];
	if (availableWeeks < 8) {
		warnings.push(
			'The target date is less than eight weeks away. A conservative plan may require a later date.'
		);
	}
	if (intake.longestRecentRunMeters > intake.currentWeeklyDistanceMeters) {
		warnings.push(
			'The longest recent run exceeds the reported weekly distance. Weekly distance is used as the baseline.'
		);
	}
	if (risk === 'aggressive' || risk === 'unsafe') {
		warnings.push(
			"The required weekly increase is above runway's default. Move the target date later or choose a shorter distance."
		);
	}
	if (hasInjuryRiskFlags(intake.injuryFlags)) {
		warnings.push(
			'Injury recovery or recurring pain is included in the ramp assessment. Get qualified guidance if pain persists, worsens, or changes how you move.'
		);
	}
	if (requiredWeeklyIncreasePercent > 10) {
		warnings.push(
			"Weekly distance growth above 10% is outside runway's default, not a normal target."
		);
	}
	if (
		intake.experience === 'new' &&
		intake.currentRunsPerWeek > experienceRunCap(intake.experience)
	) {
		warnings.push('New runners are capped at three run days per week until consistency is built.');
	}
	if (
		intake.raceDistance === 'half' &&
		intake.currentRunsPerWeek > raceRunCap(intake.raceDistance)
	) {
		warnings.push('Half-marathon plans are capped at four run days per week in this planner.');
	}
	return warnings;
}

function priorityRampCap(intake: TrainingIntake): number {
	const baseCap = intake.priority === 'finish_healthy' ? 0.075 : 0.1;
	const injuryReduction = hasInjuryRiskFlags(intake.injuryFlags) ? 0.02 : 0;
	return Math.max(0.04, baseCap - injuryReduction);
}

function taperTarget(peakMeters: number, taperPosition: number, taperLength: number): number {
	const remainingVolume =
		taperLength === 1 ? 0.5 : taperPosition === 1 ? 0.6 : taperPosition === 2 ? 0.45 : 0.4;
	return Math.round(peakMeters * remainingVolume);
}

function createWeekWorkouts(
	intake: TrainingIntake,
	weekStartDate: string,
	weekEndDate: string,
	targetDistanceMeters: number,
	desiredLongRunMeters: number,
	raceDate?: string
): { workouts: GeneratedWorkout[]; longRunMeters: number } {
	const runDays = pickRunDays(intake);
	const longRunDay = intake.preferredLongRunDay;
	const scheduledDates: { date: string; weekday: number }[] = [];
	for (let date = weekStartDate; date <= weekEndDate; date = addDays(date, 1)) {
		scheduledDates.push({ date, weekday: parseIsoDate(date).getUTCDay() });
	}

	const trainingDates = scheduledDates.filter(
		(candidate) =>
			runDays.includes(candidate.weekday) &&
			candidate.date !== raceDate &&
			(!raceDate || candidate.date < addDays(raceDate, -1))
	);
	const allocation = raceDate
		? allocateEvenly(targetDistanceMeters, trainingDates.length)
		: allocateRunDistances(targetDistanceMeters, desiredLongRunMeters, trainingDates, longRunDay);
	const workouts: GeneratedWorkout[] = [];

	for (const candidate of scheduledDates) {
		if (candidate.date === raceDate) {
			workouts.push(raceWorkout(intake, candidate.date));
			continue;
		}
		const trainingIndex = trainingDates.findIndex((item) => item.date === candidate.date);
		if (trainingIndex < 0) {
			workouts.push(restWorkout(candidate.date));
			continue;
		}
		const distance = allocation.distances[trainingIndex] ?? 0;
		const isLongRun = !raceDate && candidate.weekday === longRunDay;
		workouts.push({
			scheduledDate: candidate.date,
			type: isLongRun ? 'long' : 'easy',
			targetDistanceMeters: distance,
			prescription: { kind: 'distance', distanceMeters: distance },
			intensity: 'easy',
			purpose: isLongRun ? 'Long run' : raceDate ? 'Race-week easy run' : 'Easy run',
			reason: isLongRun
				? 'Builds endurance below race effort.'
				: raceDate
					? 'Keeps an easy run in race week without using the day before the goal.'
					: 'Adds easy weekly distance.',
			sourceRefs: isLongRun
				? [raceSourceRefs[intake.raceDistance], sourceRefs.mayoInjuryAvoidance]
				: [sourceRefs.mayoInjuryAvoidance, sourceRefs.rrcaRunnerGuidance]
		});
	}

	return { workouts, longRunMeters: raceDate ? 0 : allocation.longRunMeters };
}

function allocateRunDistances(
	totalMeters: number,
	desiredLongRunMeters: number,
	dates: { weekday: number }[],
	longRunDay: number
): { distances: number[]; longRunMeters: number } {
	if (dates.length === 0) return { distances: [], longRunMeters: 0 };
	const longIndex = dates.findIndex((candidate) => candidate.weekday === longRunDay);
	if (longIndex < 0) return allocateEvenly(totalMeters, dates.length);
	const minimumLongest = Math.ceil(totalMeters / dates.length);
	const maximumLong = Math.max(
		minimumLongest,
		totalMeters - minimumRunMeters * Math.max(0, dates.length - 1)
	);
	const longRunMeters = Math.min(
		maximumLong,
		Math.max(minimumLongest, Math.round(desiredLongRunMeters))
	);
	const easyAllocation = allocateEvenly(totalMeters - longRunMeters, dates.length - 1).distances;
	const distances: number[] = [];
	let easyIndex = 0;
	for (let index = 0; index < dates.length; index += 1) {
		if (index === longIndex) distances.push(longRunMeters);
		else distances.push(easyAllocation[easyIndex++] ?? 0);
	}
	return { distances, longRunMeters };
}

function allocateEvenly(
	totalMeters: number,
	count: number
): { distances: number[]; longRunMeters: number } {
	if (count <= 0) return { distances: [], longRunMeters: 0 };
	const base = Math.floor(totalMeters / count);
	let remainder = totalMeters - base * count;
	const distances = Array.from({ length: count }, () => {
		const distance = base + (remainder > 0 ? 1 : 0);
		if (remainder > 0) remainder -= 1;
		return distance;
	});
	return { distances, longRunMeters: 0 };
}

export function hasInjuryRiskFlags(flags: InjuryFlags): boolean {
	return flags.recentInjury || flags.currentPain || flags.recurringPain || flags.medicalRestriction;
}

function getLongRunReadinessFloor(distance: RaceDistance): number {
	const floors: Record<RaceDistance, number> = {
		'5k': 4_000,
		'10k': 8_000,
		half: 16_000,
		marathon: 28_000
	};
	return floors[distance];
}

function hasMarathonBaseShortfall(intake: TrainingIntake, baselineMeters: number): boolean {
	return (
		intake.raceDistance === 'marathon' &&
		(baselineMeters < 32_000 || intake.longestRecentRunMeters < 20_000)
	);
}

function assertSchedulableIntake(intake: TrainingIntake): void {
	if (intake.availability.some((day) => !Number.isInteger(day) || day < 0 || day > 6)) {
		throw new Error('Available run days must be unique weekdays from 0 through 6.');
	}
	if (new Set(intake.availability).size !== intake.availability.length) {
		throw new Error('Available run days must be unique weekdays from 0 through 6.');
	}
	const validDays = uniqueAvailableDays(intake.availability);
	if (validDays.length < 2) throw new Error('Choose at least two unique available run days.');
	if (!validDays.includes(intake.preferredLongRunDay)) {
		throw new Error('The preferred long-run day must be one of the available run days.');
	}
	const runCount = plannedRunCount(intake);
	if (validDays.length < runCount) {
		throw new Error('Availability must include enough unique days for the planned run frequency.');
	}
	const recoveryDay = (intake.preferredLongRunDay + 1) % 7;
	if (validDays.filter((day) => day !== recoveryDay).length < runCount) {
		throw new Error('Availability must leave a recovery day after the long run.');
	}
}

function pickRunDays(intake: TrainingIntake): number[] {
	const available = uniqueAvailableDays(intake.availability).filter(
		(day) => day !== (intake.preferredLongRunDay + 1) % 7
	);
	const count = plannedRunCount(intake);
	const picked = [intake.preferredLongRunDay];
	const candidates = available.filter((day) => day !== intake.preferredLongRunDay);
	while (picked.length < count && candidates.length > 0) {
		candidates.sort((a, b) => spacingScore(b, picked) - spacingScore(a, picked) || a - b);
		const next = candidates.shift();
		if (next !== undefined) picked.push(next);
	}
	return picked.sort((a, b) => a - b);
}

function uniqueAvailableDays(availability: number[]): number[] {
	return [
		...new Set(availability.filter((day) => Number.isInteger(day) && day >= 0 && day <= 6))
	].sort((a, b) => a - b);
}

function plannedRunCount(intake: TrainingIntake): number {
	return Math.min(
		intake.currentRunsPerWeek,
		experienceRunCap(intake.experience),
		raceRunCap(intake.raceDistance)
	);
}

function assertSupportedBaseline(intake: TrainingIntake): void {
	if (
		!Number.isFinite(intake.currentWeeklyDistanceMeters) ||
		intake.currentWeeklyDistanceMeters < 3_000
	) {
		throw new Error('The planner requires a current weekly baseline of at least 3 km.');
	}
	if (
		!Number.isInteger(intake.currentRunsPerWeek) ||
		intake.currentRunsPerWeek < 2 ||
		intake.currentRunsPerWeek > 5
	) {
		throw new Error('The planner requires a current baseline of 2 to 5 runs per week.');
	}
	if (!Number.isFinite(intake.longestRecentRunMeters) || intake.longestRecentRunMeters <= 0) {
		throw new Error('The planner requires a positive recent long-run distance.');
	}
	if (intake.injuryFlags.currentPain) {
		throw new Error('A running ramp cannot be created while pain is present now.');
	}
	if (intake.injuryFlags.medicalRestriction) {
		throw new Error('A running ramp cannot be created while a clinician has limited running.');
	}
}

function spacingScore(day: number, picked: number[]): number {
	return Math.min(
		...picked.map((other) => Math.min(Math.abs(day - other), 7 - Math.abs(day - other)))
	);
}

function experienceRunCap(experience: TrainingIntake['experience']): number {
	return experience === 'new' ? 3 : 5;
}

function raceRunCap(distance: RaceDistance): number {
	return distance === 'half' ? 4 : 5;
}

function percentIncrease(previous: number, current: number): number {
	return previous > 0 ? ((current - previous) / previous) * 100 : current > 0 ? 100 : 0;
}

function elevateRisk(current: RiskRating, minimum: RiskRating): RiskRating {
	const order: RiskRating[] = ['conservative', 'moderate', 'aggressive', 'unsafe'];
	return order.indexOf(current) >= order.indexOf(minimum) ? current : minimum;
}

function highestRisk(risks: RiskRating[]): RiskRating {
	return risks.reduce<RiskRating>((highest, risk) => elevateRisk(highest, risk), 'conservative');
}

function nextPlanWeekStart(): string {
	const today = todayIso();
	const currentWeekStart = weekStart();
	return currentWeekStart < today ? addDays(currentWeekStart, 7) : today;
}

function raceWorkout(intake: TrainingIntake, scheduledDate: string): GeneratedWorkout {
	return {
		scheduledDate,
		type: 'race',
		targetDistanceMeters: raceMeters[intake.raceDistance],
		prescription: { kind: 'distance', distanceMeters: raceMeters[intake.raceDistance] },
		intensity: 'race',
		purpose: 'Goal event',
		reason: 'This is the plan endpoint. Do not add missed taper distance before the event.',
		sourceRefs: [raceSourceRefs[intake.raceDistance], sourceRefs.mayoTaper]
	};
}

function restWorkout(scheduledDate: string): GeneratedWorkout {
	return {
		scheduledDate,
		type: 'rest',
		targetDistanceMeters: 0,
		prescription: { kind: 'rest' },
		intensity: 'rest',
		purpose: 'Rest day',
		reason: 'Recovery is part of the plan, especially around long or hard work.',
		sourceRefs: [sourceRefs.rrcaRunnerGuidance]
	};
}
