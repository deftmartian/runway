import { z } from 'zod';

const healthConnectRecordId = z
	.string()
	.min(1)
	.max(256)
	.regex(/^[A-Za-z0-9._:-]+$/);
const healthConnectTimestamp = z.iso.datetime({ offset: true });
const healthConnectHeartRateSeries = z.object({
	version: z.literal(1),
	sourceSampleCount: z.number().int().min(1).max(100_000),
	points: z
		.array(
			z.object({
				elapsedSeconds: z.number().int().min(0).max(86_400),
				bpm: z.number().int().min(30).max(260)
			})
		)
		.min(1)
		.max(600)
});
const healthConnectRouteTrace = z.object({
	version: z.literal(1),
	sourcePointCount: z.number().int().min(2).max(100_000),
	points: z
		.array(
			z.object({
				latitudeE6: z.number().int().min(-90_000_000).max(90_000_000),
				longitudeE6: z.number().int().min(-180_000_000).max(180_000_000),
				elapsedSeconds: z.number().int().min(0).max(86_400),
				segmentIndex: z.number().int().min(0).max(10_000),
				speedMetersPerSecond: z.number().min(0).max(30).nullable()
			})
		)
		.min(2)
		.max(600)
});
export const healthConnectChangesSchema = z
	.object({
		version: z.literal(1),
		changes: z
			.array(
				z.discriminatedUnion('op', [
					z.object({
						op: z.literal('upsert'),
						recordId: healthConnectRecordId,
						originKey: healthConnectRecordId,
						originLabel: z.string().trim().min(1).max(100),
						startedAt: healthConnectTimestamp,
						durationSeconds: z.number().int().min(1).max(86_400),
						distanceMeters: z.number().int().min(1).max(500_000),
						averageHeartRate: z.number().int().min(30).max(240).optional(),
						maxHeartRate: z.number().int().min(30).max(260).optional(),
						averageCadence: z.number().int().min(1).max(300).optional(),
						elevationGainMeters: z.number().int().min(0).max(20_000).optional(),
						averageSpeedMetersPerSecond: z.number().min(0).max(30).optional(),
						heartRateSeries: healthConnectHeartRateSeries.optional(),
						routeTrace: healthConnectRouteTrace.optional()
					}),
					z.object({ op: z.literal('delete'), recordId: healthConnectRecordId })
				])
			)
			.min(1)
			.max(100)
	})
	.superRefine((value, context) => {
		for (const [index, change] of value.changes.entries()) {
			if (change.op !== 'upsert') continue;
			if (
				new Date(change.startedAt).getTime() + change.durationSeconds * 1_000 >
				Date.now() + 5 * 60 * 1_000
			)
				context.addIssue({
					code: 'custom',
					path: ['changes', index, 'startedAt'],
					message: 'Health Connect runs cannot be in the future.'
				});
			for (const [pointIndex, point] of (change.heartRateSeries?.points ?? []).entries()) {
				const previous = change.heartRateSeries?.points[pointIndex - 1];
				if (
					point.elapsedSeconds > change.durationSeconds ||
					(previous && point.elapsedSeconds <= previous.elapsedSeconds)
				) {
					context.addIssue({
						code: 'custom',
						path: ['changes', index, 'heartRateSeries', 'points', pointIndex, 'elapsedSeconds'],
						message: 'Health Connect heart-rate samples must be ordered within the activity.'
					});
				}
			}
			if (
				change.heartRateSeries &&
				change.heartRateSeries.sourceSampleCount < change.heartRateSeries.points.length
			) {
				context.addIssue({
					code: 'custom',
					path: ['changes', index, 'heartRateSeries', 'sourceSampleCount'],
					message: 'Health Connect heart-rate source count cannot be below retained samples.'
				});
			}
			for (const [pointIndex, point] of (change.routeTrace?.points ?? []).entries()) {
				const previous = change.routeTrace?.points[pointIndex - 1];
				if (
					point.elapsedSeconds > change.durationSeconds ||
					(previous &&
						(point.elapsedSeconds < previous.elapsedSeconds ||
							point.segmentIndex < previous.segmentIndex))
				) {
					context.addIssue({
						code: 'custom',
						path: ['changes', index, 'routeTrace', 'points', pointIndex, 'elapsedSeconds'],
						message:
							'Health Connect route points must be ordered within the activity and its segments.'
					});
				}
			}
			if (
				change.routeTrace &&
				change.routeTrace.sourcePointCount < change.routeTrace.points.length
			) {
				context.addIssue({
					code: 'custom',
					path: ['changes', index, 'routeTrace', 'sourcePointCount'],
					message: 'Health Connect route source count cannot be below retained points.'
				});
			}
		}
	});

export const authEmailSchema = z
	.string()
	.trim()
	.max(254, 'Email address must be no more than 254 characters.')
	.pipe(z.email('Enter a valid email address.'));
export const authPasswordSchema = z.string().min(1).max(128);
export const newPasswordSchema = z
	.string()
	.min(12, 'Password must be at least 12 characters.')
	.max(128, 'Password must be no more than 128 characters.');
export const totpCodeSchema = z.string().regex(/^\d{6}$/, 'Enter the 6-digit authenticator code.');
export const backupCodeSchema = z
	.string()
	.trim()
	.regex(/^[A-Za-z0-9]{5}-[A-Za-z0-9]{5}$/, 'Enter a backup code in the shown format.');

const optionalNumber = <T extends z.ZodType>(schema: T) =>
	z.preprocess((value) => (value === '' || value === null ? undefined : value), schema.optional());

const formCheckbox = z.preprocess(
	(value) => value === true || value === 'true' || value === 'on',
	z.boolean()
);

export const healthContextSchema = z.object({
	recentInjury: formCheckbox.default(false),
	currentPain: formCheckbox.default(false),
	recurringPain: formCheckbox.default(false),
	medicalRestriction: formCheckbox.default(false),
	injuryNotes: z.string().trim().max(240).default('')
});

const goalKindSchema = z.enum(['race', 'foundation']);
const startModeSchema = z.enum([
	'established',
	'foundation_to_goal',
	'foundation_only',
	'calibration'
]);
const raceDistanceSchema = z.enum(['5k', '10k', 'half', 'marathon']);
const goalPrioritySchema = z.enum(['finish_healthy', 'consistency']);
const experienceSchema = z.enum(['new', 'returning', 'comfortable']);
const shortFormNumber = z.string().max(24);

/**
 * The canonical transport shape for the onboarding action. Numeric values stay
 * as strings until start-mode-aware validation has decided whether they apply.
 * This schema is deliberately the action boundary, not a second planner model.
 */
export const goalSetupSchema = z
	.object({
		goalKind: goalKindSchema,
		startMode: z.union([startModeSchema, z.literal('')]),
		raceDistance: z.union([raceDistanceSchema, z.literal('')]),
		targetDate: z.string().max(10),
		priority: goalPrioritySchema,
		currentWeeklyDistanceKm: shortFormNumber,
		currentRunsPerWeek: shortFormNumber,
		longestRecentRunKm: shortFormNumber,
		experience: z.union([experienceSchema, z.literal('')]),
		calibrationDurationMinutes: shortFormNumber,
		availability: z.array(z.number().int().min(0).max(6)).max(7),
		preferredLongRunDay: shortFormNumber,
		timeZone: z.string().max(100),
		recentInjury: z.boolean(),
		currentPain: z.boolean(),
		recurringPain: z.boolean(),
		medicalRestriction: z.boolean(),
		injuryNotes: z.string().max(240),
		confirmConcentratedSchedule: z.boolean(),
		confirmReplace: z.boolean()
	})
	.superRefine((value, context) => {
		if (new Set(value.availability).size !== value.availability.length) {
			context.addIssue({
				code: 'custom',
				path: ['availability'],
				message: 'Choose each available run day only once.'
			});
		}
	});

export type GoalSetupFormValues = z.infer<typeof goalSetupSchema>;
export type GoalSetupFieldErrors = Partial<Record<keyof GoalSetupFormValues, string>>;

export function parseGoalSetupForm(formData: FormData): {
	values: GoalSetupFormValues;
	fieldErrors: GoalSetupFieldErrors;
} {
	const raw = {
		goalKind: singleFormValue(formData, 'goalKind'),
		startMode: singleFormValue(formData, 'startMode'),
		raceDistance: singleFormValue(formData, 'raceDistance'),
		targetDate: singleFormValue(formData, 'targetDate'),
		priority: singleFormValue(formData, 'priority'),
		currentWeeklyDistanceKm: singleFormValue(formData, 'currentWeeklyDistanceKm'),
		currentRunsPerWeek: singleFormValue(formData, 'currentRunsPerWeek'),
		longestRecentRunKm: singleFormValue(formData, 'longestRecentRunKm'),
		experience: singleFormValue(formData, 'experience'),
		calibrationDurationMinutes: singleFormValue(formData, 'calibrationDurationMinutes'),
		availability: formData
			.getAll('availability')
			.map((value) =>
				typeof value === 'string' && /^[0-6]$/.test(value) ? Number(value) : Number.NaN
			),
		preferredLongRunDay: singleFormValue(formData, 'preferredLongRunDay'),
		timeZone: singleFormValue(formData, 'timeZone'),
		recentInjury: strictFormCheckbox(formData, 'recentInjury'),
		currentPain: strictFormCheckbox(formData, 'currentPain'),
		recurringPain: strictFormCheckbox(formData, 'recurringPain'),
		medicalRestriction: strictFormCheckbox(formData, 'medicalRestriction'),
		injuryNotes: singleFormValue(formData, 'injuryNotes'),
		confirmConcentratedSchedule: strictFormCheckbox(formData, 'confirmConcentratedSchedule'),
		confirmReplace: strictFormCheckbox(formData, 'confirmReplace')
	};
	const parsed = goalSetupSchema.safeParse(raw);
	if (parsed.success) return { values: parsed.data, fieldErrors: {} };

	const fieldErrors: GoalSetupFieldErrors = {};
	for (const issue of parsed.error.issues) {
		const field = issue.path[0];
		if (typeof field !== 'string' || !(field in safeGoalSetupValues(raw))) continue;
		const key = field as keyof GoalSetupFormValues;
		fieldErrors[key] ??= goalSetupIssueMessage(key, issue.message);
	}
	return { values: safeGoalSetupValues(raw), fieldErrors };
}

export const heartRateProfileSchema = z
	.object({
		sexForEstimates: z.enum(['female', 'male', 'not_specified']).default('not_specified'),
		ageYears: optionalNumber(z.coerce.number().int().min(18).max(100)),
		heartRateSettingsSource: z.enum(['estimated', 'custom']).default('custom'),
		maxHeartRateBpm: z.coerce.number().int().min(120).max(230),
		zone2FloorBpm: z.coerce.number().int().min(60).max(220),
		zone3FloorBpm: z.coerce.number().int().min(70).max(230),
		zone4FloorBpm: z.coerce.number().int().min(80).max(240),
		zone5FloorBpm: z.coerce.number().int().min(90).max(250)
	})
	.superRefine((value, context) => {
		const floors = [
			value.zone2FloorBpm,
			value.zone3FloorBpm,
			value.zone4FloorBpm,
			value.zone5FloorBpm
		];
		for (let index = 1; index < floors.length; index += 1) {
			const previous = floors[index - 1];
			const current = floors[index];
			if (current === undefined || previous === undefined || current <= previous) {
				context.addIssue({
					code: 'custom',
					path: ['zone2FloorBpm'],
					message: 'Heart-rate zone floors must increase from easy to max.'
				});
				return;
			}
		}
		if (value.zone5FloorBpm > value.maxHeartRateBpm) {
			context.addIssue({
				code: 'custom',
				path: ['zone5FloorBpm'],
				message: 'Zone 5 cannot start above max heart rate.'
			});
		}
	});

export const feedbackSchema = z
	.object({
		workoutId: z.uuid(),
		status: z.enum(['done', 'skipped', 'shortened']),
		completedDistanceKm: z.coerce.number().positive().max(100).optional(),
		completedDurationMinutes: z.coerce.number().positive().max(600).optional(),
		feltHard: z.coerce.boolean().default(false),
		pain: z.coerce.boolean().default(false),
		choice: z.enum(['skip_continue', 'reduce_next']).default('skip_continue')
	})
	.superRefine((value, context) => {
		if (value.status === 'skipped' && value.completedDistanceKm !== undefined) {
			context.addIssue({
				code: 'custom',
				path: ['completedDistanceKm'],
				message: 'Skipped workouts cannot include completed distance.'
			});
		}
		if (value.status === 'skipped' && value.completedDurationMinutes !== undefined) {
			context.addIssue({
				code: 'custom',
				path: ['completedDurationMinutes'],
				message: 'Skipped workouts cannot include completed duration.'
			});
		}
	});

/**
 * Form parsing cannot know a workout's prescription. Apply this after loading
 * the workout so direct requests cannot record a timed result without time,
 * or a distance result without distance.
 */
export function feedbackMeasurementError(input: {
	status: 'done' | 'skipped' | 'shortened';
	targetDurationSeconds: number | null;
	completedDistanceMeters?: number;
	completedDurationSeconds?: number;
}): string | null {
	if (input.status === 'skipped') {
		return input.completedDistanceMeters === undefined &&
			input.completedDurationSeconds === undefined
			? null
			: 'Skipped workouts cannot include a recorded distance or duration.';
	}
	const timed = (input.targetDurationSeconds ?? 0) > 0;
	const completed = timed ? input.completedDurationSeconds : input.completedDistanceMeters;
	if (!Number.isFinite(completed) || (completed ?? 0) <= 0) {
		return timed
			? 'Timed workouts need the completed duration.'
			: 'Distance workouts need the completed distance.';
	}
	return null;
}

export const manualRunSchema = z.object({
	occurredDate: z
		.string()
		.regex(/^\d{4}-\d{2}-\d{2}$/)
		.refine((value) => isValidIsoDate(value), 'Choose a real calendar date.'),
	distanceKm: z.coerce.number().min(0.1).max(100),
	durationMinutes: optionalNumber(z.coerce.number().min(1).max(600)),
	feltHard: z.coerce.boolean().default(false),
	pain: z.coerce.boolean().default(false)
});

export const activityLinkSchema = z.object({
	activityId: z.uuid(),
	workoutId: z.uuid()
});

export const activityIdSchema = z.object({
	activityId: z.uuid()
});

export const androidDeviceIdSchema = z.object({
	deviceId: z.uuid()
});

export const consequenceDecisionSchema = z.object({
	source: z.enum(['feedback', 'activity']),
	sourceId: z.uuid(),
	decision: z.enum([
		'keep_plan',
		'reduce_next',
		'next_rest',
		'repeat_prescription',
		'rebalance_week'
	]),
	confirmRisk: z.coerce.boolean().default(false)
});

const workoutPrescriptionFields = {
	scheduledDate: z
		.string()
		.regex(/^\d{4}-\d{2}-\d{2}$/)
		.refine((value) => isValidIsoDate(value), 'Choose a real calendar date.'),
	type: z.enum(['easy', 'long', 'recovery', 'rest']),
	prescriptionKind: z.enum(['distance', 'timed', 'rest']),
	distanceKm: optionalNumber(z.coerce.number().min(0.1).max(100)),
	durationMinutes: optionalNumber(z.coerce.number().int().min(10).max(360)),
	intervalStructureJson: z.string().max(20_000).default(''),
	replaceIntervals: z.boolean().default(false),
	runMinutes: optionalNumber(z.coerce.number().min(0.25).max(120)),
	walkMinutes: optionalNumber(z.coerce.number().min(0.25).max(120)),
	repetitions: optionalNumber(z.coerce.number().int().min(1).max(100)),
	intensity: z.enum(['easy', 'rest']),
	purpose: z.string().trim().min(2).max(120),
	userReason: z.string().trim().max(500).default(''),
	rebalance: z.boolean().default(false),
	confirmRisk: z.boolean().default(false)
};

export const workoutEditSchema = z
	.object({ workoutId: z.uuid(), ...workoutPrescriptionFields })
	.superRefine(validateWorkoutPrescriptionFields);

export const workoutAddSchema = z
	.object(workoutPrescriptionFields)
	.superRefine(validateWorkoutPrescriptionFields);

export const workoutIdSchema = z.object({ workoutId: z.uuid() });

export const workoutAdjustmentIdSchema = z.object({ adjustmentId: z.uuid() });

export function formDataToObject(
	formData: FormData
): Record<string, FormDataEntryValue | FormDataEntryValue[]> {
	const object: Record<string, FormDataEntryValue | FormDataEntryValue[]> = {};
	for (const [key, value] of formData.entries()) {
		const existing = object[key];
		if (existing === undefined) {
			object[key] = value;
			continue;
		}
		object[key] = Array.isArray(existing) ? [...existing, value] : [existing, value];
	}
	return object;
}

export function formString(formData: FormData, key: string, fallback = ''): string {
	const value = formData.get(key);
	return typeof value === 'string' ? value : fallback;
}

function singleFormValue(formData: FormData, key: string): string | undefined {
	const values = formData.getAll(key);
	if (values.length === 0) return '';
	return values.length === 1 && typeof values[0] === 'string' ? values[0] : undefined;
}

function strictFormCheckbox(formData: FormData, key: string): boolean | undefined {
	const values = formData.getAll(key);
	if (values.length === 0) return false;
	return values.length === 1 && values[0] === 'on' ? true : undefined;
}

function safeGoalSetupValues(raw: Record<keyof GoalSetupFormValues, unknown>): GoalSetupFormValues {
	return {
		goalKind: safeEnumValue(raw.goalKind, ['race', 'foundation'], 'race'),
		startMode: safeEnumValue(
			raw.startMode,
			['', 'established', 'foundation_to_goal', 'foundation_only', 'calibration'],
			''
		),
		raceDistance: safeEnumValue(raw.raceDistance, ['', '5k', '10k', 'half', 'marathon'], ''),
		targetDate: safeShortString(raw.targetDate, 10),
		priority: safeEnumValue(raw.priority, ['finish_healthy', 'consistency'], 'finish_healthy'),
		currentWeeklyDistanceKm: safeShortString(raw.currentWeeklyDistanceKm, 24),
		currentRunsPerWeek: safeShortString(raw.currentRunsPerWeek, 24),
		longestRecentRunKm: safeShortString(raw.longestRecentRunKm, 24),
		experience: safeEnumValue(raw.experience, ['', 'new', 'returning', 'comfortable'], ''),
		calibrationDurationMinutes: safeShortString(raw.calibrationDurationMinutes, 24),
		availability: Array.from(
			new Set(
				Array.isArray(raw.availability)
					? raw.availability.filter(
							(value): value is number =>
								typeof value === 'number' && Number.isInteger(value) && value >= 0 && value <= 6
						)
					: []
			)
		),
		preferredLongRunDay: safeShortString(raw.preferredLongRunDay, 24),
		timeZone: safeShortString(raw.timeZone, 100),
		recentInjury: raw.recentInjury === true,
		currentPain: raw.currentPain === true,
		recurringPain: raw.recurringPain === true,
		medicalRestriction: raw.medicalRestriction === true,
		injuryNotes: safeShortString(raw.injuryNotes, 240),
		confirmConcentratedSchedule: raw.confirmConcentratedSchedule === true,
		confirmReplace: raw.confirmReplace === true
	};
}

function safeEnumValue<const T extends string>(
	value: unknown,
	allowed: readonly T[],
	fallback: T
): T {
	return typeof value === 'string' && allowed.includes(value as T) ? (value as T) : fallback;
}

function safeShortString(value: unknown, maximum: number): string {
	return typeof value === 'string' ? value.slice(0, maximum) : '';
}

function goalSetupIssueMessage(field: keyof GoalSetupFormValues, fallback: string): string {
	switch (field) {
		case 'goalKind':
			return 'Choose a supported goal.';
		case 'startMode':
			return 'Choose a supported starting path.';
		case 'raceDistance':
			return 'Choose a supported race distance.';
		case 'priority':
			return 'Choose a supported planning priority.';
		case 'experience':
			return 'Choose a supported running experience.';
		case 'availability':
			return fallback.includes('only once')
				? fallback
				: 'Choose valid available days without duplicates.';
		case 'injuryNotes':
			return 'Keep health context to 240 characters.';
		case 'timeZone':
			return 'Select a valid IANA time zone.';
		default:
			return 'Review this field.';
	}
}

function isValidIsoDate(value: string): boolean {
	const parsed = new Date(`${value}T00:00:00.000Z`);
	return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function validateWorkoutPrescriptionFields(
	value: {
		type: 'easy' | 'long' | 'recovery' | 'rest';
		prescriptionKind: 'distance' | 'timed' | 'rest';
		intensity: 'easy' | 'rest';
		distanceKm?: number | undefined;
		durationMinutes?: number | undefined;
		intervalStructureJson: string;
		replaceIntervals: boolean;
		runMinutes?: number | undefined;
		walkMinutes?: number | undefined;
		repetitions?: number | undefined;
	},
	context: z.RefinementCtx
) {
	if (value.prescriptionKind === 'rest' && value.type !== 'rest') {
		context.addIssue({
			code: 'custom',
			path: ['type'],
			message: 'Rest needs the rest workout type.'
		});
	}
	if (value.prescriptionKind === 'rest' && value.intensity !== 'rest') {
		context.addIssue({ code: 'custom', path: ['intensity'], message: 'Rest needs rest effort.' });
	}
	if (value.prescriptionKind !== 'rest' && value.intensity !== 'easy') {
		context.addIssue({ code: 'custom', path: ['intensity'], message: 'Runs use easy effort.' });
	}
	if (value.prescriptionKind !== 'rest' && value.type === 'rest') {
		context.addIssue({ code: 'custom', path: ['type'], message: 'Runs need a run workout type.' });
	}
	if (value.prescriptionKind === 'distance' && value.distanceKm === undefined) {
		context.addIssue({
			code: 'custom',
			path: ['distanceKm'],
			message: 'Enter the planned distance.'
		});
	}
	if (value.prescriptionKind === 'timed') {
		if (value.durationMinutes === undefined) {
			context.addIssue({
				code: 'custom',
				path: ['durationMinutes'],
				message: 'Enter the planned duration.'
			});
		}
		if (
			value.replaceIntervals &&
			(value.runMinutes === undefined || value.repetitions === undefined)
		) {
			context.addIssue({
				code: 'custom',
				path: ['runMinutes'],
				message: 'Enter a run interval and repeat count.'
			});
		}
		if (!value.replaceIntervals && !value.intervalStructureJson) {
			context.addIssue({
				code: 'custom',
				path: ['intervalStructureJson'],
				message: 'Timed workouts need run/walk intervals.'
			});
		}
	}
}
