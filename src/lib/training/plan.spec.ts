import { afterEach, describe, expect, expectTypeOf, test, vi } from 'vitest';
import { calculateConsequence } from './consequences';
import { formatPace } from './format';
import { parseGpx } from './gpx';
import {
	defaultHeartRateSettings,
	estimateMaxHeartRate,
	normalizeHeartRateSettings,
	summarizeHeartRateEffort
} from './heart-rate';
import { classifyRamp, generateTrainingPlan } from './plan';
import {
	feedbackSchema,
	goalSetupSchema,
	heartRateProfileSchema
} from '$lib/server/runway/validation';
import type { GoalPriority, TrainingIntake, WorkoutStatus, WorkoutType } from './types';

afterEach(() => vi.unstubAllEnvs());

const baseIntake: TrainingIntake = {
	raceDistance: 'half',
	targetDate: '2026-09-01',
	priority: 'finish_healthy',
	units: 'metric',
	currentWeeklyDistanceMeters: 12_000,
	currentRunsPerWeek: 3,
	longestRecentRunMeters: 10_000,
	experience: 'returning',
	availability: [1, 3, 5, 6],
	preferredLongRunDay: 6,
	injuryFlags: {
		recentInjury: true,
		currentPain: false,
		recurringPain: true,
		medicalRestriction: false,
		notes: ''
	},
	startDate: '2026-05-11'
};

describe('training plan generation', () => {
	test('uses sex-specific heart-rate estimates only as editable defaults', () => {
		expect.assertions(3);

		expect(estimateMaxHeartRate(40, 'female')).toBe(171);
		expect(estimateMaxHeartRate(40, 'male')).toBe(180);
		expect(estimateMaxHeartRate(40, 'not_specified')).toBe(180);
	});

	test('does not apply adult heart-rate estimates to children', () => {
		expect.assertions(2);
		expect(() => estimateMaxHeartRate(17, 'not_specified')).toThrow(/only for adults/i);
		expect(
			heartRateProfileSchema.safeParse({
				sexForEstimates: 'not_specified',
				ageYears: 17,
				heartRateSettingsSource: 'estimated',
				maxHeartRateBpm: 190,
				zone2FloorBpm: 110,
				zone3FloorBpm: 130,
				zone4FloorBpm: 150,
				zone5FloorBpm: 170
			}).success
		).toBe(false);
	});

	test('rejects corrupted stored heart-rate settings at the runtime boundary', () => {
		expect.assertions(5);
		const valid = defaultHeartRateSettings(35);
		expect(normalizeHeartRateSettings(valid)).not.toBeNull();
		expect(normalizeHeartRateSettings({ ...valid, source: 'device' } as never)).toBeNull();
		expect(normalizeHeartRateSettings({ ...valid, maxHeartRateBpm: 999 })).toBeNull();
		expect(
			normalizeHeartRateSettings({
				...valid,
				zones: valid.zones.map((zone, index) =>
					index === 2 ? { ...zone, floorBpm: valid.zones[1]?.floorBpm ?? 0 } : zone
				)
			})
		).toBeNull();
		expect(
			normalizeHeartRateSettings({
				...valid,
				zones: valid.zones.map((zone, index) =>
					index === 4 ? { ...zone, floorBpm: valid.maxHeartRateBpm + 1 } : zone
				)
			})
		).toBeNull();
	});

	test('builds a source-backed plan with rest days and taper', () => {
		expect.assertions(5);
		const plan = generateTrainingPlan(baseIntake);

		expect(plan.weeks.length).toBeGreaterThan(12);
		expect(plan.sourceRefs).toContain('mayo-running-injury-avoidance');
		expect(plan.weeks.some((week) => week.isTaper)).toBe(true);
		expect(plan.weeks[0]?.workouts.some((workout) => workout.type === 'rest')).toBe(true);
		expect(plan.summary.warnings.some((warning) => warning.includes('Injury recovery'))).toBe(true);
	});

	test('schedules the preferred long run on the requested weekday', () => {
		expect.assertions(3);
		const plan = generateTrainingPlan(baseIntake);
		const firstWeek = plan.weeks[0];
		const longRun = firstWeek?.workouts.find((workout) => workout.type === 'long');
		const sunday = firstWeek?.workouts.find((workout) => workout.scheduledDate === '2026-05-17');

		expect(longRun?.scheduledDate).toBe('2026-05-16');
		expect(sunday?.type).toBe('rest');
		expect(firstWeek?.workouts.filter((workout) => workout.type !== 'rest')).toHaveLength(3);
	});

	test('honors a two-run weekly baseline instead of silently adding frequency', () => {
		expect.assertions(4);
		const plan = generateTrainingPlan({
			...baseIntake,
			currentRunsPerWeek: 2,
			availability: [2, 6]
		});
		const firstWeekRuns = plan.weeks[0]?.workouts.filter((workout) => workout.type !== 'rest');

		expect(firstWeekRuns).toHaveLength(2);
		expect(firstWeekRuns?.some((workout) => workout.type === 'long')).toBe(true);
		expect(firstWeekRuns?.every((workout) => workout.targetDistanceMeters > 0)).toBe(true);
		expect(
			firstWeekRuns?.find((workout) => workout.type === 'long')?.targetDistanceMeters
		).toBeGreaterThanOrEqual(
			firstWeekRuns?.find((workout) => workout.type === 'easy')?.targetDistanceMeters ?? 0
		);
	});

	test('makes the concentration cost of a two-day half-marathon schedule explicit', () => {
		const plan = generateTrainingPlan({
			...baseIntake,
			raceDistance: 'half',
			currentRunsPerWeek: 2,
			availability: [2, 6]
		});

		expect(plan.weeks[0]?.workouts.filter((workout) => workout.type !== 'rest')).toHaveLength(2);
		expect(plan.risk).toBe('aggressive');
		expect(
			plan.summary.warnings.some((warning) => warning.includes('concentrate a high-volume goal'))
		).toBe(true);
	});

	test('starts new plans on the next full week instead of creating retroactive misses', () => {
		expect.assertions(3);
		vi.stubEnv('RUNWAY_FIXED_DATE', '2026-05-15');
		const intakeWithoutStart = structuredClone(baseIntake);
		delete intakeWithoutStart.startDate;
		const plan = generateTrainingPlan(intakeWithoutStart);

		expect(plan.startDate).toBe('2026-05-18');
		expect(plan.weeks[0]?.workouts[0]?.scheduledDate).toBe('2026-05-18');
		expect(
			plan.weeks
				.flatMap((week) => week.workouts)
				.every((workout) => workout.scheduledDate >= '2026-05-15')
		).toBe(true);
	});

	test('ends on the target date with one explicit race and no later workouts', () => {
		expect.assertions(7);
		const plan = generateTrainingPlan(baseIntake);
		const workouts = plan.weeks.flatMap((week) => week.workouts);
		const race = workouts.filter((workout) => workout.type === 'race');

		expect(race).toHaveLength(1);
		expect(race[0]?.scheduledDate).toBe(baseIntake.targetDate);
		expect(race[0]?.targetDistanceMeters).toBe(21_100);
		expect(workouts.every((workout) => workout.scheduledDate <= baseIntake.targetDate)).toBe(true);
		expect(plan.weeks.at(-1)?.eventDistanceMeters).toBe(21_100);
		expect(plan.weeks.at(-1)?.trainingTargetDistanceMeters).toBe(0);
		expect(plan.summary.peakMeters).toBe(
			Math.max(...plan.weeks.map((week) => week.trainingTargetDistanceMeters))
		);
	});

	test('supports the 52-week boundary without producing a 53rd week', () => {
		expect.assertions(2);
		const plan = generateTrainingPlan({
			...baseIntake,
			startDate: '2026-05-18',
			targetDate: '2027-05-14'
		});

		expect(plan.weeks).toHaveLength(52);
		expect(plan.weeks.at(-1)?.workouts.at(-1)?.scheduledDate).toBe('2027-05-14');
	});

	test('caps every post-cutback rebound from the actual adjacent week', () => {
		expect.assertions(3);
		const plan = generateTrainingPlan(baseIntake);
		const trainingMeters = plan.weeks.map((week) =>
			week.workouts
				.filter((workout) => workout.type !== 'race')
				.reduce((sum, workout) => sum + workout.targetDistanceMeters, 0)
		);
		const increases = trainingMeters.slice(1).map((current, index) => {
			const previous = trainingMeters[index] ?? 0;
			return previous > 0 ? ((current - previous) / previous) * 100 : 0;
		});

		expect(increases.every((increase) => increase <= 5.51)).toBe(true);
		expect(increases[3]).toBeGreaterThan(5.4);
		expect(plan.weeks[4]?.risk).toBe(classifyRamp(increases[3] ?? 0, true));
	});

	test('keeps the day after every long run as recovery and week totals consistent', () => {
		expect.assertions(2);
		const plan = generateTrainingPlan(baseIntake);
		const workouts = plan.weeks.flatMap((week) => week.workouts);
		const byDate = new Map(workouts.map((workout) => [workout.scheduledDate, workout]));
		const longRuns = workouts.filter((workout) => workout.type === 'long');

		expect(
			longRuns.every((run) => {
				const next = new Date(`${run.scheduledDate}T00:00:00Z`);
				next.setUTCDate(next.getUTCDate() + 1);
				return byDate.get(next.toISOString().slice(0, 10))?.type === 'rest';
			})
		).toBe(true);
		expect(
			plan.weeks.every(
				(week) =>
					week.targetDistanceMeters ===
					week.workouts.reduce((sum, workout) => sum + workout.targetDistanceMeters, 0)
			)
		).toBe(true);
	});

	test('rejects availability that cannot preserve recovery after the long run', () => {
		expect.assertions(1);
		expect(() =>
			generateTrainingPlan({
				...baseIntake,
				availability: [1, 2, 3],
				preferredLongRunDay: 2,
				currentRunsPerWeek: 3
			})
		).toThrow(/recovery day after the long run/i);
	});

	test('validates unique availability and preferred-day consistency at the form boundary', () => {
		expect.assertions(2);
		vi.stubEnv('RUNWAY_FIXED_DATE', '2026-05-15');
		const input = {
			raceDistance: 'half',
			targetDate: '2026-09-01',
			priority: 'finish_healthy',
			currentWeeklyDistanceKm: 12,
			currentRunsPerWeek: 3,
			longestRecentRunKm: 10,
			experience: 'returning',
			preferredLongRunDay: 6,
			availability: [1, 1, 3],
			recentInjury: false,
			currentPain: false,
			recurringPain: false,
			medicalRestriction: false,
			injuryNotes: ''
		};

		expect(goalSetupSchema.safeParse(input).success).toBe(false);
		expect(
			goalSetupSchema.safeParse({ ...input, availability: [1, 3, 5], preferredLongRunDay: 6 })
				.success
		).toBe(false);
	});

	test('can reach its own marathon long-run readiness floor from a strong base', () => {
		expect.assertions(2);
		const plan = generateTrainingPlan({
			...baseIntake,
			raceDistance: 'marathon',
			currentWeeklyDistanceMeters: 36_000,
			longestRecentRunMeters: 24_000,
			injuryFlags: {
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}
		});

		expect(plan.summary.longRunPeakMeters).toBeGreaterThanOrEqual(28_000);
		expect(plan.risk).toBe('conservative');
	});

	test('marks unrealistic ramps as unsafe', () => {
		expect.assertions(1);
		const plan = generateTrainingPlan({
			...baseIntake,
			targetDate: '2026-06-01',
			currentWeeklyDistanceMeters: 3_000,
			longestRecentRunMeters: 1_000
		});

		expect(plan.risk).toBe('unsafe');
	});

	test('reports the generated peak instead of the unsupported source target', () => {
		expect.assertions(3);
		const plan = generateTrainingPlan({
			...baseIntake,
			targetDate: '2026-07-06',
			currentWeeklyDistanceMeters: 20_000,
			longestRecentRunMeters: 12_000,
			injuryFlags: {
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}
		});
		const generatedPeak = Math.max(...plan.weeks.map((week) => week.targetDistanceMeters));

		expect(plan.risk).not.toBe('unsafe');
		expect(plan.summary.peakMeters).toBe(generatedPeak);
		expect(
			plan.summary.warnings.some((warning) => warning.includes('do not allow the usual peak'))
		).toBe(true);
	});

	test('rejects unsupported baselines instead of fabricating distance, frequency, or a long run', () => {
		expect.assertions(3);
		expect(() => generateTrainingPlan({ ...baseIntake, currentWeeklyDistanceMeters: 0 })).toThrow(
			/at least 3 km/i
		);
		expect(() => generateTrainingPlan({ ...baseIntake, currentRunsPerWeek: 1 })).toThrow(
			/2 to 5 runs/i
		);
		expect(() => generateTrainingPlan({ ...baseIntake, longestRecentRunMeters: 0 })).toThrow(
			/positive recent long-run/i
		);
	});

	test('rejects active pain and medical restrictions at the domain boundary', () => {
		expect.assertions(2);
		expect(() =>
			generateTrainingPlan({
				...baseIntake,
				injuryFlags: { ...baseIntake.injuryFlags, currentPain: true }
			})
		).toThrow(/pain is present now/i);
		expect(() =>
			generateTrainingPlan({
				...baseIntake,
				injuryFlags: { ...baseIntake.injuryFlags, medicalRestriction: true }
			})
		).toThrow(/clinician has limited running/i);
	});

	test('neutral free-text health context does not silently change the plan', () => {
		expect.assertions(3);
		const flags = {
			recentInjury: false,
			currentPain: false,
			recurringPain: false,
			medicalRestriction: false,
			notes: ''
		};
		const withoutNote = generateTrainingPlan({ ...baseIntake, injuryFlags: flags });
		const withNote = generateTrainingPlan({
			...baseIntake,
			injuryFlags: { ...flags, notes: 'Prefers soft trails when convenient.' }
		});

		expect(withNote.risk).toBe(withoutNote.risk);
		expect(withNote.summary.peakMeters).toBe(withoutNote.summary.peakMeters);
		expect(withNote.weeks.map((week) => week.targetDistanceMeters)).toEqual(
			withoutNote.weeks.map((week) => week.targetDistanceMeters)
		);
	});

	test('rejects duplicate and out-of-range availability in the generator itself', () => {
		expect.assertions(3);
		expect(() => generateTrainingPlan({ ...baseIntake, availability: [1, 1, 3, 6] })).toThrow(
			/unique weekdays/i
		);
		expect(() => generateTrainingPlan({ ...baseIntake, availability: [1, 3, 6, 7] })).toThrow(
			/unique weekdays/i
		);
		expect(() =>
			generateTrainingPlan({ ...baseIntake, currentRunsPerWeek: 4, availability: [1, 3, 6] })
		).toThrow(/enough unique days/i);
	});

	test('goal priority changes the generated ramp instead of acting as a dead control', () => {
		expect.assertions(2);
		const finishHealthy = generateTrainingPlan({
			...baseIntake,
			priority: 'finish_healthy',
			injuryFlags: {
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}
		});
		const consistency = generateTrainingPlan({
			...baseIntake,
			priority: 'consistency',
			injuryFlags: {
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}
		});

		expect(finishHealthy.summary.peakMeters).toBeLessThan(consistency.summary.peakMeters);
		expect(finishHealthy.weeks[1]?.targetDistanceMeters).toBeLessThan(
			consistency.weeks[1]?.targetDistanceMeters ?? 0
		);
	});

	test('rejects unsupported priority values at the request boundary', () => {
		expect.assertions(1);
		vi.stubEnv('RUNWAY_FIXED_DATE', '2026-05-15');
		const result = goalSetupSchema.safeParse({
			raceDistance: 'half',
			targetDate: '2026-09-01',
			priority: 'time',
			currentWeeklyDistanceKm: 12,
			currentRunsPerWeek: 3,
			longestRecentRunKm: 10,
			experience: 'returning',
			preferredLongRunDay: 6,
			availability: [1, 3, 6],
			recentInjury: false,
			currentPain: false,
			recurringPain: false,
			medicalRestriction: false,
			injuryNotes: ''
		});

		expect(result.success).toBe(false);
	});

	test('domain unions expose only implemented priority, workout, and status values', () => {
		expect(['finish_healthy', 'consistency']).not.toContain('time');
		expectTypeOf<'time'>().not.toExtend<GoalPriority>();
		expectTypeOf<'quality'>().not.toExtend<WorkoutType>();
		expectTypeOf<'cross_train'>().not.toExtend<WorkoutType>();
		expectTypeOf<'moved'>().not.toExtend<WorkoutStatus>();
	});

	test('supports common race distances instead of half marathon only', () => {
		expect.assertions(8);
		const distanceSourceRefs = {
			'5k': 'rei-5k-training',
			'10k': 'rei-10k-training',
			half: 'rei-half-marathon-training',
			marathon: 'rei-marathon-training'
		} as const;
		for (const distance of ['5k', '10k', 'half', 'marathon'] as const) {
			const plan = generateTrainingPlan({
				...baseIntake,
				raceDistance: distance,
				currentWeeklyDistanceMeters: distance === 'marathon' ? 36_000 : 14_000,
				longestRecentRunMeters: distance === 'marathon' ? 24_000 : 8_000,
				injuryFlags: {
					recentInjury: false,
					currentPain: false,
					recurringPain: false,
					medicalRestriction: false,
					notes: ''
				}
			});

			expect(plan.weeks.length).toBeGreaterThan(0);
			expect(plan.sourceRefs).toContain(distanceSourceRefs[distance]);
		}
	});
});

describe('training formatting', () => {
	test('carries rounded pace seconds into the next minute', () => {
		expect.assertions(1);
		expect(formatPace(599.8)).toBe('10:00/km');
	});
});

describe('consequence choices', () => {
	test('treats pain as a stronger signal than a normal skip', () => {
		expect.assertions(2);
		const consequence = calculateConsequence({
			status: 'shortened',
			choice: 'skip_continue',
			targetDistanceMeters: 6_000,
			completedDistanceMeters: 2_000,
			pain: true,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		});

		expect(consequence.risk).toBe('unsafe');
		expect(consequence.nextRunAdjustmentMeters).toBeLessThan(0);
	});

	test('does not describe a completed run as a skip', () => {
		expect.assertions(3);
		const consequence = calculateConsequence({
			status: 'done',
			choice: 'skip_continue',
			targetDistanceMeters: 6_000,
			completedDistanceMeters: 6_000,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		});

		expect(consequence.weeklyDistanceDeltaMeters).toBe(0);
		expect(consequence.nextRunAdjustmentMeters).toBe(0);
		expect(consequence.kind).toBe('completed_as_planned');
	});

	test('classifies distance results at the material threshold boundaries', () => {
		const base = {
			status: 'done' as const,
			choice: 'skip_continue' as const,
			targetDistanceMeters: 4_000,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 12_000
		};

		expect(calculateConsequence({ ...base, completedDistanceMeters: 3_400 }).deviation).toBe(
			'near_plan'
		);
		expect(calculateConsequence({ ...base, completedDistanceMeters: 3_399 }).deviation).toBe(
			'short'
		);
		expect(calculateConsequence({ ...base, completedDistanceMeters: 4_600 }).deviation).toBe(
			'near_plan'
		);
		expect(calculateConsequence({ ...base, completedDistanceMeters: 4_601 }).deviation).toBe(
			'over'
		);
	});

	test('classifies timed results using the larger five-minute threshold', () => {
		const base = {
			status: 'done' as const,
			choice: 'skip_continue' as const,
			targetDistanceMeters: 0,
			targetDurationSeconds: 1_200,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 0
		};

		expect(calculateConsequence({ ...base, completedDurationSeconds: 900 }).deviation).toBe(
			'near_plan'
		);
		expect(calculateConsequence({ ...base, completedDurationSeconds: 899 }).deviation).toBe(
			'short'
		);
		expect(calculateConsequence({ ...base, completedDurationSeconds: 1_501 }).deviation).toBe(
			'over'
		);
	});

	test('treats over-completing a workout as a load spike', () => {
		expect.assertions(3);
		const consequence = calculateConsequence({
			status: 'done',
			choice: 'skip_continue',
			targetDistanceMeters: 5_000,
			completedDistanceMeters: 12_000,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		});

		expect(consequence.weeklyDistanceDeltaMeters).toBe(7_000);
		expect(consequence.nextRunAdjustmentMeters).toBeLessThan(0);
		expect(consequence.kind).toBe('load_spike');
	});

	test('rates the same extra distance against the effective weekly target', () => {
		expect.assertions(4);
		const input = {
			status: 'done' as const,
			choice: 'skip_continue' as const,
			targetDistanceMeters: 5_000,
			completedDistanceMeters: 7_000,
			pain: false,
			feltHard: false
		};
		const smallWeek = calculateConsequence({ ...input, weekTargetDistanceMeters: 10_000 });
		const largeWeek = calculateConsequence({ ...input, weekTargetDistanceMeters: 40_000 });

		expect(smallWeek.weeklyDistanceDeltaMeters).toBe(2_000);
		expect(largeWeek.weeklyDistanceDeltaMeters).toBe(2_000);
		expect(smallWeek.risk).toBe('aggressive');
		expect(largeWeek.risk).toBe('moderate');
	});

	test('reserves unsafe extra-load feedback for a hard severe relative spike', () => {
		expect.assertions(2);
		const base = {
			status: 'done' as const,
			choice: 'skip_continue' as const,
			targetDistanceMeters: 5_000,
			completedDistanceMeters: 10_000,
			pain: false,
			weekTargetDistanceMeters: 20_000
		};

		expect(calculateConsequence({ ...base, feltHard: false }).risk).toBe('aggressive');
		expect(calculateConsequence({ ...base, feltHard: true }).risk).toBe('unsafe');
	});

	test('treats the dedicated shortened status as a shortfall, not a skip', () => {
		expect.assertions(3);
		const consequence = calculateConsequence({
			status: 'shortened',
			choice: 'skip_continue',
			targetDistanceMeters: 6_000,
			completedDistanceMeters: 3_000,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		});

		expect(consequence.weeklyDistanceDeltaMeters).toBe(-3_000);
		expect(consequence.kind).toBe('shortfall');
		expect(consequence.nextRunAdjustmentMeters).toBeLessThan(0);
	});

	test('turns repeated missed work into a real conservative adjustment', () => {
		expect.assertions(3);
		const consequence = calculateConsequence({
			status: 'skipped',
			choice: 'skip_continue',
			targetDistanceMeters: 6_000,
			completedDistanceMeters: 0,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000,
			recentMissedWorkouts: 1
		});

		expect(consequence.risk).toBe('moderate');
		expect(consequence.nextRunAdjustmentMeters).toBeLessThan(0);
		expect(consequence.kind).toBe('repeated_miss');
	});

	test('rejects contradictory shortened feedback', () => {
		expect.assertions(1);
		expect(() =>
			calculateConsequence({
				status: 'shortened',
				choice: 'skip_continue',
				targetDistanceMeters: 6_000,
				completedDistanceMeters: 6_000,
				pain: false,
				feltHard: false,
				weekTargetDistanceMeters: 18_000
			})
		).toThrow(/below the planned distance/i);
	});

	test('rejects zero-distance done feedback at the form boundary', () => {
		expect.assertions(1);
		expect(
			feedbackSchema.safeParse({
				workoutId: '550e8400-e29b-41d4-a716-446655440000',
				status: 'done',
				completedDistanceKm: 0,
				feltHard: false,
				pain: false,
				choice: 'skip_continue'
			}).success
		).toBe(false);
	});

	test('rejects completed feedback without the prescribed measurement', () => {
		const common = {
			status: 'done' as const,
			choice: 'skip_continue' as const,
			pain: false,
			feltHard: false,
			weekTargetDistanceMeters: 18_000
		};
		expect(() => calculateConsequence({ ...common, targetDistanceMeters: 6_000 })).toThrow(
			/recorded distance/i
		);
		expect(() =>
			calculateConsequence({
				...common,
				targetDistanceMeters: 0,
				targetDurationSeconds: 1_200
			})
		).toThrow(/recorded duration/i);
	});
});

describe('gpx parsing', () => {
	test('extracts aggregate activity data without needing raw route output', () => {
		expect.assertions(7);
		const parsed = parseGpx(`<?xml version="1.0"?>
			<gpx>
				<trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><ele>10</ele><time>2026-05-14T12:00:00Z</time><extensions><gpxtpx:hr>140</gpxtpx:hr><gpxtpx:cad>82</gpxtpx:cad></extensions></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><ele>11</ele><time>2026-05-14T12:01:00Z</time><extensions><gpxtpx:hr>142</gpxtpx:hr><gpxtpx:cad>84</gpxtpx:cad></extensions></trkpt>
				</trkseg></trk>
			</gpx>`);

		expect(parsed.pointCount).toBe(2);
		expect(parsed.distanceMeters).toBeGreaterThan(100);
		expect(parsed.durationSeconds).toBe(60);
		expect(parsed.averageHeartRate).toBe(141);
		expect(parsed.maxHeartRate).toBe(142);
		expect(parsed.heartRateSamples).toHaveLength(2);
		expect(parsed.hasCadence).toBe(true);
	});

	test('reports exact time in heart-rate zones without turning it into an unsupported effort label', () => {
		expect.assertions(4);
		const parsed = parseGpx(`<?xml version="1.0"?>
			<gpx>
				<trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time><extensions><gpxtpx:hr>168</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-14T12:15:00Z</time><extensions><gpxtpx:hr>170</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0020" lon="-63.0020"><time>2026-05-14T12:30:00Z</time><extensions><gpxtpx:hr>172</gpxtpx:hr></extensions></trkpt>
				</trkseg></trk>
			</gpx>`);
		const summary = summarizeHeartRateEffort(parsed, defaultHeartRateSettings(35));

		expect(summary?.effort).toBe('unknown');
		expect(summary?.highSeconds).toBe(1_800);
		expect(
			Object.values(summary?.secondsByZone ?? {}).reduce((sum, seconds) => sum + seconds, 0)
		).toBe(1_800);
		expect(summary?.settingsSource).toBe('estimated');
	});

	test('does not bridge separate GPX track segments with artificial distance', () => {
		expect.assertions(2);
		const parsed = parseGpx(`<?xml version="1.0"?>
			<gpx><trk>
				<trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0000"><time>2026-05-14T12:05:00Z</time></trkpt>
				</trkseg>
				<trkseg>
					<trkpt lat="46.0000" lon="-64.0000"><time>2026-05-14T12:10:00Z</time></trkpt>
					<trkpt lat="46.0010" lon="-64.0000"><time>2026-05-14T12:15:00Z</time></trkpt>
				</trkseg>
			</trk></gpx>`);

		expect(parsed.distanceMeters).toBeGreaterThan(200);
		expect(parsed.distanceMeters).toBeLessThan(230);
	});

	test('does not weight metrics or zone time across segment gaps', () => {
		expect.assertions(6);
		const parsed = parseGpx(`<?xml version="1.0"?>
			<gpx><trk>
				<trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time><extensions><hr>100</hr><cad>80</cad><speed>2</speed></extensions></trkpt>
					<trkpt lat="45.0010" lon="-63.0000"><time>2026-05-14T12:01:00Z</time><extensions><hr>100</hr><cad>80</cad><speed>2</speed></extensions></trkpt>
				</trkseg>
				<trkseg>
					<trkpt lat="45.0020" lon="-63.0000"><time>2026-05-14T13:00:00Z</time><extensions><hr>200</hr><cad>100</cad><speed>4</speed></extensions></trkpt>
					<trkpt lat="45.0030" lon="-63.0000"><time>2026-05-14T13:10:00Z</time><extensions><hr>200</hr><cad>100</cad><speed>4</speed></extensions></trkpt>
				</trkseg>
			</trk></gpx>`);
		const summary = summarizeHeartRateEffort(parsed, defaultHeartRateSettings(35));

		expect(parsed.durationSeconds).toBe(4_200);
		expect(parsed.averageHeartRate).toBe(191);
		expect(parsed.averageCadence).toBe(98);
		expect(parsed.averageSpeedMetersPerSecond).toBe(3.82);
		expect(summary?.highSeconds).toBe(600);
		expect(
			Object.values(summary?.secondsByZone ?? {}).reduce((sum, seconds) => sum + seconds, 0)
		).toBe(660);
	});

	test('rejects invalid GPX extension metrics before persistence', () => {
		expect.assertions(4);
		const gpxWithMetric = (metric: string) => `<?xml version="1.0"?><gpx><trk><trkseg>
			<trkpt lat="45" lon="-63"><time>2026-05-14T12:00:00Z</time><extensions>${metric}</extensions></trkpt>
			<trkpt lat="45.001" lon="-63"><time>2026-05-14T12:01:00Z</time></trkpt>
		</trkseg></trk></gpx>`;

		expect(() => parseGpx(gpxWithMetric('<hr>NaN</hr>'))).toThrow(/invalid numeric/i);
		expect(() => parseGpx(gpxWithMetric('<hr>300</hr>'))).toThrow(/heart-rate values/i);
		expect(() => parseGpx(gpxWithMetric('<cad>-1</cad>'))).toThrow(/cadence values/i);
		expect(() => parseGpx(gpxWithMetric('<speed>-1</speed>'))).toThrow(/speed values/i);
	});

	test('rejects antipodal coordinates as out of range instead of returning NaN', () => {
		expect.assertions(1);
		expect(() =>
			parseGpx(`<?xml version="1.0"?><gpx><trk><trkseg>
				<trkpt lat="0" lon="0"><time>2026-05-14T12:00:00Z</time></trkpt>
				<trkpt lat="0" lon="180"><time>2026-05-14T12:01:00Z</time></trkpt>
			</trkseg></trk></gpx>`)
		).toThrow(/distance is outside/i);
	});

	test('rejects malformed XML and entity declarations before parsing', () => {
		expect.assertions(2);
		expect(() =>
			parseGpx(
				'<gpx><trk><trkseg><trkpt lat="45" lon="-63"><time>2026-05-14T12:00:00Z</time></trkpt>'
			)
		).toThrow(/malformed XML/i);
		expect(() =>
			parseGpx(`<!DOCTYPE gpx [<!ENTITY x "45">]><gpx><trk><trkseg>
				<trkpt lat="&x;" lon="-63"><time>2026-05-14T12:00:00Z</time></trkpt>
				<trkpt lat="45.1" lon="-63"><time>2026-05-14T12:01:00Z</time></trkpt>
			</trkseg></trk></gpx>`)
		).toThrow(/entity declarations/i);
	});

	test('rejects out-of-range coordinates', () => {
		expect.assertions(1);
		expect(() =>
			parseGpx(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="145.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-14T12:01:00Z</time></trkpt>
				</trkseg></trk></gpx>`)
		).toThrow(/invalid coordinates/i);
	});

	test('rejects non-chronological timestamps', () => {
		expect.assertions(1);
		expect(() =>
			parseGpx(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:01:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-14T12:00:00Z</time></trkpt>
				</trkseg></trk></gpx>`)
		).toThrow(/chronological/i);
	});

	test('rejects files with too many track points before doing full import work', () => {
		expect.assertions(1);
		const points = Array.from(
			{ length: 20_001 },
			(_, index) =>
				`<trkpt lat="45" lon="-63"><time>2026-05-14T12:${String(index % 60).padStart(2, '0')}:00Z</time></trkpt>`
		).join('');

		expect(() =>
			parseGpx(`<?xml version="1.0"?><gpx><trk><trkseg>${points}</trkseg></trk></gpx>`)
		).toThrow(/too many track points/i);
	});
});
