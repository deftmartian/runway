import { describe, expect, test } from 'vitest';
import { generateCalibrationPlan, generateFoundationPlan, generatePlan } from './plan';
import type {
	CalibrationIntake,
	FoundationOnlyIntake,
	FoundationToGoalIntake,
	TimedPrescription
} from './types';

const healthyFlags = {
	recentInjury: false,
	currentPain: false,
	recurringPain: false,
	medicalRestriction: false,
	notes: ''
};

const foundationOnly: FoundationOnlyIntake = {
	startMode: 'foundation_only',
	goalKind: 'foundation',
	raceDistance: null,
	priority: 'consistency',
	units: 'metric',
	experience: 'new',
	availability: [1, 3, 6],
	injuryFlags: healthyFlags,
	startDate: '2026-07-20'
};

describe('foundation planning', () => {
	test('reproduces nine weeks with three timed NHS sessions per week', () => {
		const plan = generateFoundationPlan(foundationOnly);
		const sessions = plan.weeks.flatMap((week) =>
			week.workouts.filter((workout) => workout.prescription.kind === 'timed')
		);

		expect(plan.phase).toBe('foundation');
		expect(plan.targetDate).toBe('2026-09-20');
		expect(plan.weeks).toHaveLength(9);
		expect(sessions).toHaveLength(27);
		expect(sessions.every((session) => session.targetDistanceMeters === 0)).toBe(true);
		expect(plan.sourceRefs).toContain('nhs-couch-to-5k');
	});

	test('stores internally consistent warmup, blocks, cooldown, and total duration', () => {
		const plan = generateFoundationPlan(foundationOnly);
		const prescriptions = plan.weeks.flatMap((week) =>
			week.workouts.flatMap((workout) =>
				workout.prescription.kind === 'timed' ? [workout.prescription] : []
			)
		);

		for (const prescription of prescriptions) {
			expect(prescription.totalDurationSeconds).toBe(prescriptionDuration(prescription));
		}
		expect(prescriptions.map((item) => item.totalDurationSeconds)).toEqual([
			...Array.from({ length: 3 }, () => 1_710),
			...Array.from({ length: 3 }, () => 1_740),
			...Array.from({ length: 3 }, () => 1_500),
			...Array.from({ length: 3 }, () => 1_890),
			1_860,
			1_860,
			1_800,
			2_040,
			1_980,
			2_100,
			...Array.from({ length: 3 }, () => 2_100),
			...Array.from({ length: 3 }, () => 2_280),
			...Array.from({ length: 3 }, () => 2_400)
		]);
		expect(prescriptions.slice(-3).map((item) => item.totalDurationSeconds)).toEqual([
			2_400, 2_400, 2_400
		]);
	});

	test('requires rest-day spacing between beginner sessions', () => {
		expect(() => generateFoundationPlan({ ...foundationOnly, availability: [1, 2, 3] })).toThrow(
			/rest day between beginner sessions/i
		);
		expect(() =>
			generateCalibrationPlan({
				startMode: 'calibration',
				goalKind: 'foundation',
				raceDistance: null,
				calibrationDurationSeconds: 1_200,
				priority: 'consistency',
				units: 'metric',
				experience: 'new',
				availability: [1, 2],
				injuryFlags: healthyFlags,
				startDate: '2026-07-20'
			})
		).toThrow(/rest day between beginner sessions/i);
	});

	test('retains a later race goal while generating only the foundation phase', () => {
		const intake: FoundationToGoalIntake = {
			...foundationOnly,
			startMode: 'foundation_to_goal',
			goalKind: 'race',
			raceDistance: '10k',
			targetDate: '2027-01-10'
		};
		const plan = generatePlan(intake);

		expect(plan.phase).toBe('foundation');
		expect(plan.targetDate).toBe('2026-09-20');
		expect(
			plan.weeks.flatMap((week) => week.workouts).some((workout) => workout.type === 'race')
		).toBe(false);
	});

	test('does not create a phase during current pain or a medical restriction', () => {
		expect(() =>
			generateFoundationPlan({
				...foundationOnly,
				injuryFlags: { ...healthyFlags, currentPain: true }
			})
		).toThrow(/pain is present now/i);
		expect(() =>
			generateFoundationPlan({
				...foundationOnly,
				injuryFlags: { ...healthyFlags, medicalRestriction: true }
			})
		).toThrow(/clinician has limited running/i);
	});
});

describe('calibration planning', () => {
	const calibration: CalibrationIntake = {
		startMode: 'calibration',
		goalKind: 'race',
		raceDistance: '5k',
		targetDate: '2026-12-01',
		calibrationDurationSeconds: 1_200,
		priority: 'finish_healthy',
		units: 'metric',
		experience: 'new',
		availability: [2, 5],
		injuryFlags: healthyFlags,
		startDate: '2026-07-20'
	};

	test('creates two identical sessions per week for two weeks without assumed distance', () => {
		const plan = generateCalibrationPlan(calibration);
		const sessions = plan.weeks.flatMap((week) =>
			week.workouts.filter((workout) => workout.prescription.kind === 'timed')
		);

		expect(plan.phase).toBe('calibration');
		expect(plan.weeks).toHaveLength(2);
		expect(sessions).toHaveLength(4);
		expect(sessions.map((session) => session.targetDurationSeconds)).toEqual([
			1_200, 1_200, 1_200, 1_200
		]);
		expect(sessions.every((session) => session.targetDistanceMeters === 0)).toBe(true);
		expect(
			sessions.every((session) => Number.isFinite(session.targetDurationSeconds ?? Number.NaN))
		).toBe(true);
	});

	test.each([599, 1_801, Number.NaN])('rejects an invalid duration of %s seconds', (duration) => {
		expect(() =>
			generateCalibrationPlan({ ...calibration, calibrationDurationSeconds: duration })
		).toThrow(/10 to 30 minutes/i);
	});
});

function prescriptionDuration(prescription: TimedPrescription): number {
	return (
		prescription.warmupSeconds +
		prescription.cooldownSeconds +
		prescription.blocks.reduce(
			(total, block) =>
				total +
				block.repetitions *
					block.segments.reduce((sum, segment) => sum + segment.durationSeconds, 0),
			0
		)
	);
}
