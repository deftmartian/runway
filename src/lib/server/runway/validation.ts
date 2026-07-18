import { z } from 'zod';

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

export const goalSetupSchema = z
	.object({
		raceDistance: z.enum(['5k', '10k', 'half', 'marathon']),
		targetDate: z
			.string()
			.regex(/^\d{4}-\d{2}-\d{2}$/)
			.refine((value) => isValidIsoDate(value), 'Choose a real calendar date.'),
		priority: z.enum(['finish_healthy', 'consistency']).default('finish_healthy'),
		currentWeeklyDistanceKm: z.coerce.number().min(0).max(250),
		currentRunsPerWeek: z.coerce.number().int().min(2).max(5),
		longestRecentRunKm: z.coerce.number().min(0).max(80),
		experience: z.enum(['new', 'returning', 'comfortable']).default('returning'),
		preferredLongRunDay: z.coerce.number().int().min(0).max(6),
		availability: z.array(z.coerce.number().int().min(0).max(6)).min(2).max(7),
		recentInjury: z.coerce.boolean().default(false),
		currentPain: z.coerce.boolean().default(false),
		recurringPain: z.coerce.boolean().default(false),
		medicalRestriction: z.coerce.boolean().default(false),
		injuryNotes: z.string().max(240).default('')
	})
	.superRefine((value, context) => {
		const uniqueDays = new Set(value.availability);
		if (uniqueDays.size !== value.availability.length) {
			context.addIssue({
				code: 'custom',
				path: ['availability'],
				message: 'Choose each available run day only once.'
			});
		}
		if (!uniqueDays.has(value.preferredLongRunDay)) {
			context.addIssue({
				code: 'custom',
				path: ['preferredLongRunDay'],
				message: 'The preferred long-run day must be one of the available run days.'
			});
		}
		const frequencyCap = Math.min(
			value.currentRunsPerWeek,
			value.experience === 'new' ? 3 : 5,
			value.raceDistance === 'half' ? 4 : 5
		);
		const recoveryDay = (value.preferredLongRunDay + 1) % 7;
		const schedulableDays = [...uniqueDays].filter((day) => day !== recoveryDay);
		if (schedulableDays.length < frequencyCap) {
			context.addIssue({
				code: 'custom',
				path: ['availability'],
				message: 'Availability must leave a recovery day after the long run.'
			});
		}
	});

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
		completedDistanceKm: z.coerce.number().min(0).max(100).optional(),
		completedDurationMinutes: z.coerce.number().positive().max(600).optional(),
		feltHard: z.coerce.boolean().default(false),
		pain: z.coerce.boolean().default(false),
		choice: z.enum(['skip_continue', 'reduce_next']).default('skip_continue')
	})
	.superRefine((value, context) => {
		const completedDistance = value.completedDistanceKm ?? 0;
		if (value.status === 'skipped' && completedDistance > 0) {
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
		if (value.status === 'shortened' && completedDistance <= 0) {
			context.addIssue({
				code: 'custom',
				path: ['completedDistanceKm'],
				message: 'Shortened workouts need the completed distance.'
			});
		}
		if (
			value.status === 'done' &&
			value.completedDistanceKm !== undefined &&
			completedDistance <= 0
		) {
			context.addIssue({
				code: 'custom',
				path: ['completedDistanceKm'],
				message: 'Completed workouts need a positive distance.'
			});
		}
		if (value.status === 'skipped' && (value.completedDurationMinutes ?? 0) > 0) {
			context.addIssue({
				code: 'custom',
				path: ['completedDurationMinutes'],
				message: 'Skipped workouts cannot include a completed duration.'
			});
		}
	});

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
	intensity: z.string().trim().min(2).max(80),
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

function isValidIsoDate(value: string): boolean {
	const parsed = new Date(`${value}T00:00:00.000Z`);
	return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

function validateWorkoutPrescriptionFields(
	value: {
		type: 'easy' | 'long' | 'recovery' | 'rest';
		prescriptionKind: 'distance' | 'timed' | 'rest';
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
