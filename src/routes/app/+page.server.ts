import { fail, redirect } from '@sveltejs/kit';
import {
	addFutureWorkout,
	applyFutureWorkoutEdit,
	previewFutureWorkoutAdd,
	previewFutureWorkoutEdit,
	previewFutureWorkoutRemoval,
	removeFutureWorkout,
	resetFutureWorkout,
	type FutureWorkoutAddInput,
	type FutureWorkoutEditInput,
	undoFutureWorkoutAdjustment
} from '$lib/server/runway/repositories/future-workouts';
import { getImportWorkoutCandidates } from '$lib/server/runway/repositories/activity-queries';
import {
	deleteActivityRecord,
	linkActivityToWorkout,
	unlinkActivityFromWorkout,
	updateActivityFeedback
} from '$lib/server/runway/repositories/activity-mutations';
import {
	confirmActivityAsExtra,
	recordManualRun
} from '$lib/server/runway/repositories/extra-activity-mutations';
import { getTrainingCalendar } from '$lib/server/runway/repositories/calendar';
import { getTrainingReadContext } from '$lib/server/runway/repositories/training-read-context';
import {
	applyConsequenceDecision,
	deleteWorkoutFeedback,
	recordWorkoutFeedback
} from '$lib/server/runway/repositories/workout-feedback';
import {
	activityIdSchema,
	activityLinkSchema,
	consequenceDecisionSchema,
	feedbackSchema,
	formDataToObject,
	formString,
	manualRunSchema,
	workoutAddSchema,
	workoutAdjustmentIdSchema,
	workoutEditSchema,
	workoutIdSchema
} from '$lib/server/runway/validation';
import { formatConsequenceSummary } from '$lib/training/consequence-presentation';
import type { RunWalkBlock, TimedIntervalStructure } from '$lib/training/types';
import { resizeTimedIntervalStructure } from '$lib/training/workout-edit';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const context = await getTrainingReadContext(event.locals.user.id);
	if (!context.timeZone) throw redirect(302, '/app/onboarding');
	const month = event.url.searchParams.get('month');
	const [calendar, activityCandidates] = await Promise.all([
		getTrainingCalendar(event.locals.user.id, { month, context }),
		getImportWorkoutCandidates(event.locals.user.id, context)
	]);
	return { ...calendar, activityCandidates };
};

export const actions: Actions = {
	previewWorkoutEdit: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = await parseWorkoutEditRequest(event.request, true);
		if (!parsed.success) return fail(400, parsed.failure);
		try {
			const preview = await previewFutureWorkoutEdit(event.locals.user.id, parsed.input);
			return {
				message: 'Review the proposed workout and its plan effect.',
				preview,
				editValues: parsed.values,
				scope: { action: 'previewWorkoutEdit' as const, workoutId: parsed.input.workoutId }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'previewWorkoutEdit' as const, workoutId: parsed.input.workoutId }
			});
		}
	},
	applyWorkoutEdit: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = await parseWorkoutEditRequest(event.request, true);
		if (!parsed.success) return fail(400, parsed.failure);
		try {
			await applyFutureWorkoutEdit(event.locals.user.id, parsed.input);
			return {
				message: 'Workout updated. The generated recommendation remains in the ledger.',
				scope: { action: 'applyWorkoutEdit' as const, workoutId: parsed.input.workoutId }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'applyWorkoutEdit' as const, workoutId: parsed.input.workoutId }
			});
		}
	},
	previewWorkoutAdd: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = await parseWorkoutEditRequest(event.request, false);
		if (!parsed.success) return fail(400, parsed.failure);
		try {
			const preview = await previewFutureWorkoutAdd(event.locals.user.id, parsed.input);
			return {
				message: 'Review the new workout and its plan effect.',
				preview,
				editValues: parsed.values,
				scope: { action: 'previewWorkoutAdd' as const, date: parsed.input.scheduledDate }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'previewWorkoutAdd' as const, date: parsed.input.scheduledDate }
			});
		}
	},
	applyWorkoutAdd: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = await parseWorkoutEditRequest(event.request, false);
		if (!parsed.success) return fail(400, parsed.failure);
		try {
			await addFutureWorkout(event.locals.user.id, parsed.input);
			return {
				message: 'Workout added. It can be undone without deleting its ledger entry.',
				scope: { action: 'applyWorkoutAdd' as const, date: parsed.input.scheduledDate }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'applyWorkoutAdd' as const, date: parsed.input.scheduledDate }
			});
		}
	},
	previewWorkoutRemoval: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = workoutIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose a future workout.' });
		try {
			const preview = await previewFutureWorkoutRemoval(
				event.locals.user.id,
				parsed.data.workoutId
			);
			return {
				message: 'Review the weekly load after removing this workout.',
				preview,
				scope: { action: 'previewWorkoutRemoval' as const, workoutId: parsed.data.workoutId }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'previewWorkoutRemoval' as const, workoutId: parsed.data.workoutId }
			});
		}
	},
	removeWorkout: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = workoutIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose a future workout.' });
		try {
			await removeFutureWorkout(event.locals.user.id, parsed.data.workoutId);
			return {
				message: 'Workout removed. Its recommendation and undo path remain in the ledger.',
				scope: { action: 'removeWorkout' as const, workoutId: parsed.data.workoutId }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'removeWorkout' as const, workoutId: parsed.data.workoutId }
			});
		}
	},
	resetWorkout: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = workoutIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an edited workout.' });
		try {
			await resetFutureWorkout(event.locals.user.id, parsed.data.workoutId);
			return {
				message:
					'Generated recommendation restored; later non-manual ledger entries were preserved.',
				scope: { action: 'resetWorkout' as const, workoutId: parsed.data.workoutId }
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: { action: 'resetWorkout' as const, workoutId: parsed.data.workoutId }
			});
		}
	},
	undoWorkoutAdjustment: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = workoutAdjustmentIdSchema.safeParse(
			formDataToObject(await event.request.formData())
		);
		if (!parsed.success) return fail(400, { message: 'Choose a reversible workout change.' });
		try {
			await undoFutureWorkoutAdjustment(event.locals.user.id, parsed.data.adjustmentId);
			return {
				message:
					'Manual workout change undone; later feedback and activity changes were preserved.',
				scope: {
					action: 'undoWorkoutAdjustment' as const,
					adjustmentId: parsed.data.adjustmentId
				}
			};
		} catch (error) {
			return fail(400, {
				message: workoutEditError(error),
				scope: {
					action: 'undoWorkoutAdjustment' as const,
					adjustmentId: parsed.data.adjustmentId
				}
			});
		}
	},
	applyPlanDecision: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = consequenceDecisionSchema.safeParse(
			formDataToObject(await event.request.formData())
		);
		if (!parsed.success) return fail(400, { message: 'Choose a valid plan option.' });
		const scope = {
			action: 'applyPlanDecision' as const,
			sourceId: parsed.data.sourceId
		};
		try {
			const consequence = await applyConsequenceDecision(event.locals.user.id, parsed.data);
			return {
				message: formatConsequenceSummary(consequence),
				consequence,
				scope
			};
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Plan option could not be applied.'),
				scope
			});
		}
	},
	recordFeedback: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const object = formDataToObject(await event.request.formData());
		const completedDistanceKm = object['completedDistanceKm'];
		const completedDurationMinutes = object['completedDurationMinutes'];
		const feedbackScope = {
			action: 'recordFeedback' as const,
			workoutId: typeof object['workoutId'] === 'string' ? object['workoutId'] : ''
		};
		const parsed = feedbackSchema.safeParse({
			...object,
			feltHard: object['feltHard'] === 'on',
			pain: object['pain'] === 'on',
			completedDistanceKm: completedDistanceKm === '' ? undefined : completedDistanceKm,
			completedDurationMinutes:
				completedDurationMinutes === '' ? undefined : completedDurationMinutes
		});

		if (!parsed.success) {
			return fail(400, {
				message: parsed.error.issues[0]?.message ?? 'Feedback could not be saved.',
				scope: feedbackScope
			});
		}

		const completedDistanceMeters =
			parsed.data.status === 'skipped'
				? 0
				: parsed.data.completedDistanceKm === undefined
					? undefined
					: Math.round(parsed.data.completedDistanceKm * 1_000);

		let consequence;
		try {
			consequence = await recordWorkoutFeedback(event.locals.user.id, {
				workoutId: parsed.data.workoutId,
				status: parsed.data.status,
				feltHard: parsed.data.feltHard,
				pain: parsed.data.pain,
				choice: parsed.data.choice,
				...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
				...(parsed.data.completedDurationMinutes === undefined
					? {}
					: { completedDurationSeconds: Math.round(parsed.data.completedDurationMinutes * 60) })
			});
		} catch (error) {
			return fail(400, {
				message: feedbackErrorMessage(error),
				scope: feedbackScope
			});
		}

		return { message: 'Feedback saved.', consequence, scope: feedbackScope };
	},
	deleteFeedback: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const workoutId = formString(await event.request.formData(), 'workoutId').trim();
		const scope = { action: 'deleteFeedback' as const, workoutId };
		if (!workoutId) return fail(400, { message: 'Choose a saved workout result.', scope });
		try {
			await deleteWorkoutFeedback(event.locals.user.id, workoutId);
			return { message: 'Saved result removed. Record the run again when ready.', scope };
		} catch (error) {
			const message = error instanceof Error ? error.message : '';
			if (
				message === 'Workout feedback not found.' ||
				message === 'Unlink or delete the recorded activity instead of deleting its feedback.'
			) {
				return fail(400, { message, scope });
			}
			return fail(400, { message: 'Saved result could not be removed.', scope });
		}
	},
	recordManualRun: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const object = formDataToObject(await event.request.formData());
		const durationMinutes = object['durationMinutes'];
		const manualScope = {
			action: 'recordManualRun' as const,
			date: typeof object['occurredDate'] === 'string' ? object['occurredDate'] : ''
		};
		const parsed = manualRunSchema.safeParse({
			...object,
			feltHard: object['feltHard'] === 'on',
			pain: object['pain'] === 'on',
			durationMinutes: durationMinutes === '' ? undefined : durationMinutes
		});

		if (!parsed.success) {
			return fail(400, {
				message: parsed.error.issues[0]?.message ?? 'Run could not be recorded.',
				scope: manualScope
			});
		}

		try {
			const result = await recordManualRun(event.locals.user.id, {
				occurredDate: parsed.data.occurredDate,
				distanceMeters: Math.round(parsed.data.distanceKm * 1_000),
				feltHard: parsed.data.feltHard,
				pain: parsed.data.pain,
				...(parsed.data.durationMinutes === undefined
					? {}
					: { durationSeconds: Math.round(parsed.data.durationMinutes * 60) })
			});
			const consequenceMessage = result.consequence
				? ` ${formatConsequenceSummary(result.consequence)}`
				: '';
			return {
				message: `Run recorded.${consequenceMessage}`,
				consequence: result.consequence,
				scope: manualScope
			};
		} catch (error) {
			return fail(400, { message: manualRunErrorMessage(error), scope: manualScope });
		}
	},
	linkActivity: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityLinkSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) {
			return fail(400, { message: 'Choose an activity and planned workout.' });
		}
		try {
			const consequence = await linkActivityToWorkout(event.locals.user.id, parsed.data);
			return {
				message: formatConsequenceSummary(consequence),
				consequence,
				scope: { action: 'linkActivity' as const, activityId: parsed.data.activityId }
			};
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity could not be linked.'),
				scope: { action: 'linkActivity' as const, activityId: parsed.data.activityId }
			});
		}
	},
	unlinkActivity: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			await unlinkActivityFromWorkout(event.locals.user.id, parsed.data.activityId);
			return {
				message: 'Activity unlinked from the workout.',
				scope: { action: 'unlinkActivity' as const, activityId: parsed.data.activityId }
			};
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity could not be unlinked.'),
				scope: { action: 'unlinkActivity' as const, activityId: parsed.data.activityId }
			});
		}
	},
	confirmActivityExtra: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		const scope = { action: 'confirmActivityExtra' as const, activityId: parsed.data.activityId };
		try {
			const consequence = await confirmActivityAsExtra(
				event.locals.user.id,
				parsed.data.activityId
			);
			return {
				message: consequence
					? formatConsequenceSummary(consequence)
					: 'Activity counted as historical training. Current plan unchanged.',
				consequence,
				scope
			};
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity could not be counted.'),
				scope
			});
		}
	},
	updateActivityFeedback: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const object = formDataToObject(await event.request.formData());
		const parsed = activityIdSchema.safeParse(object);
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		const scope = { action: 'updateActivityFeedback' as const, activityId: parsed.data.activityId };
		try {
			const consequence = await updateActivityFeedback(
				event.locals.user.id,
				parsed.data.activityId,
				{ feltHard: object['feltHard'] === 'on', pain: object['pain'] === 'on' }
			);
			return { message: 'Activity feedback updated.', consequence, scope };
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity feedback could not be updated.'),
				scope
			});
		}
	},
	deleteActivity: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			await deleteActivityRecord(event.locals.user.id, parsed.data.activityId);
			return {
				message: 'Activity deleted.',
				scope: { action: 'deleteActivity' as const, activityId: parsed.data.activityId }
			};
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity could not be deleted.'),
				scope: { action: 'deleteActivity' as const, activityId: parsed.data.activityId }
			});
		}
	}
};

function feedbackErrorMessage(error: unknown): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		'Workout not found.',
		'Workout is scheduled for the future.',
		'Rest days do not accept workout feedback. Record an unplanned run instead.',
		'Feedback has already been recorded for this workout.',
		"This goal is outside runway's plan-generation limits. Choose a later date or a shorter distance.",
		'Training plans cannot exceed 52 weeks.'
	]);
	return knownMessages.has(message) ? message : 'Feedback could not be saved.';
}

function manualRunErrorMessage(error: unknown): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		'Manual runs cannot be recorded in the future.',
		'Manual run could not be recorded.'
	]);
	return knownMessages.has(message) ? message : 'Run could not be recorded.';
}

function activityRecordError(error: unknown, fallback: string): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		'Activity not found.',
		'Activity is already linked.',
		'Linked activities already count against the plan.',
		'This activity has already been counted as extra.',
		'Activity is no longer available to count.',
		'Workout is not available for linking.',
		'Workout is outside the activity match window.',
		'Activity date is outside the active plan weeks.',
		'That workout already has an activity.',
		'Activity is not linked.',
		'Activity is no longer available for linking.',
		'Workout is no longer available for linking.',
		'Linked workout not found.',
		'Activity is no longer linked to this workout.'
	]);
	return knownMessages.has(message) ? message : fallback;
}

type WorkoutFormValues = {
	workoutId?: string;
	scheduledDate: string;
	type: string;
	prescriptionKind: string;
	distanceKm: string;
	durationMinutes: string;
	intervalStructureJson: string;
	replaceIntervals: boolean;
	runMinutes: string;
	walkMinutes: string;
	repetitions: string;
	intensity: string;
	purpose: string;
	userReason: string;
	rebalance: boolean;
	confirmRisk: boolean;
};

type WorkoutParseFailure = {
	message: string;
	scope:
		| { action: 'previewWorkoutEdit'; workoutId: string }
		| { action: 'previewWorkoutAdd'; date: string };
};

async function parseWorkoutEditRequest(
	request: Request,
	withId: true
): Promise<
	| { success: true; input: FutureWorkoutEditInput; values: WorkoutFormValues }
	| { success: false; failure: WorkoutParseFailure }
>;
async function parseWorkoutEditRequest(
	request: Request,
	withId: false
): Promise<
	| { success: true; input: FutureWorkoutAddInput; values: WorkoutFormValues }
	| { success: false; failure: WorkoutParseFailure }
>;
async function parseWorkoutEditRequest(request: Request, withId: boolean) {
	const object = formDataToObject(await request.formData());
	const values = workoutFormValues(object);
	const normalized = {
		...(withId ? { workoutId: values.workoutId } : {}),
		scheduledDate: values.scheduledDate,
		type: values.type,
		prescriptionKind: values.prescriptionKind,
		distanceKm: optionalFormNumber(values.distanceKm),
		durationMinutes: optionalFormNumber(values.durationMinutes),
		intervalStructureJson: values.intervalStructureJson,
		replaceIntervals: values.replaceIntervals,
		runMinutes: optionalFormNumber(values.runMinutes),
		walkMinutes: optionalFormNumber(values.walkMinutes),
		repetitions: optionalFormNumber(values.repetitions),
		intensity: values.intensity,
		purpose: values.purpose,
		userReason: values.userReason,
		rebalance: values.rebalance,
		confirmRisk: values.confirmRisk
	};
	const parsed = withId
		? workoutEditSchema.safeParse(normalized)
		: workoutAddSchema.safeParse(normalized);
	const failureScope = withId
		? { action: 'previewWorkoutEdit' as const, workoutId: values.workoutId ?? '' }
		: { action: 'previewWorkoutAdd' as const, date: values.scheduledDate };
	if (!parsed.success) {
		return {
			success: false as const,
			failure: {
				message: parsed.error.issues[0]?.message ?? 'Review the workout fields.',
				scope: failureScope
			}
		};
	}
	let intervalStructure: TimedIntervalStructure | null = null;
	if (parsed.data.prescriptionKind === 'timed') {
		try {
			const totalDurationSeconds = Math.round((parsed.data.durationMinutes ?? 0) * 60);
			const previousStructure = parsed.data.intervalStructureJson
				? (JSON.parse(parsed.data.intervalStructureJson) as TimedIntervalStructure)
				: null;
			const blocks: RunWalkBlock[] = parsed.data.replaceIntervals
				? [
						{
							repetitions: parsed.data.repetitions ?? 1,
							segments: [
								{
									kind: 'run' as const,
									durationSeconds: Math.round((parsed.data.runMinutes ?? 0) * 60)
								},
								...(parsed.data.walkMinutes
									? [
											{
												kind: 'walk' as const,
												durationSeconds: Math.round(parsed.data.walkMinutes * 60)
											}
										]
									: [])
							]
						}
					]
				: (previousStructure?.blocks ?? []);
			if (parsed.data.replaceIntervals) {
				const blockSeconds = blocks.reduce(
					(total, block) =>
						total +
						block.repetitions *
							block.segments.reduce((sum, segment) => sum + segment.durationSeconds, 0),
					0
				);
				const remaining = Math.max(0, totalDurationSeconds - blockSeconds);
				intervalStructure = {
					warmupSeconds: Math.floor(remaining / 2),
					cooldownSeconds: remaining - Math.floor(remaining / 2),
					blocks
				};
			} else {
				intervalStructure = resizeTimedIntervalStructure(previousStructure, totalDurationSeconds);
			}
		} catch {
			return {
				success: false as const,
				failure: { message: 'Run/walk interval data is invalid.', scope: failureScope }
			};
		}
	}
	const input = {
		...(withId && 'workoutId' in parsed.data ? { workoutId: parsed.data.workoutId } : {}),
		scheduledDate: parsed.data.scheduledDate,
		type: parsed.data.type,
		prescriptionKind: parsed.data.prescriptionKind,
		targetDistanceMeters:
			parsed.data.prescriptionKind === 'distance'
				? Math.round((parsed.data.distanceKm ?? 0) * 1_000)
				: 0,
		targetDurationSeconds:
			parsed.data.prescriptionKind === 'timed'
				? Math.round((parsed.data.durationMinutes ?? 0) * 60)
				: null,
		intervalStructure,
		intensity: parsed.data.intensity,
		purpose: parsed.data.purpose,
		...(parsed.data.userReason ? { userReason: parsed.data.userReason } : {}),
		rebalance: parsed.data.rebalance,
		confirmRisk: parsed.data.confirmRisk
	};
	return { success: true as const, input, values };
}

function workoutFormValues(
	object: Record<string, FormDataEntryValue | FormDataEntryValue[]>
): WorkoutFormValues {
	const value = (key: string) => (typeof object[key] === 'string' ? object[key] : '');
	return {
		...(value('workoutId') ? { workoutId: value('workoutId') } : {}),
		scheduledDate: value('scheduledDate'),
		type: value('type'),
		prescriptionKind: value('prescriptionKind'),
		distanceKm: value('distanceKm'),
		durationMinutes: value('durationMinutes'),
		intervalStructureJson: value('intervalStructureJson'),
		replaceIntervals: object['replaceIntervals'] === 'on',
		runMinutes: value('runMinutes'),
		walkMinutes: value('walkMinutes'),
		repetitions: value('repetitions'),
		intensity: value('intensity'),
		purpose: value('purpose'),
		userReason: value('userReason'),
		rebalance: object['rebalance'] === 'on',
		confirmRisk: object['confirmRisk'] === 'on'
	};
}

function optionalFormNumber(value: string): string | undefined {
	return value === '' ? undefined : value;
}

function workoutEditError(error: unknown): string {
	const message = error instanceof Error ? error.message : '';
	const safePatterns = [
		/^(Future workout|Workout|Race events|Completed and past|Reset or undo|No active plan|The selected date|Review and confirm|No reversible)/,
		/^Rest prescriptions/,
		/^Run prescriptions/,
		/^Distance prescriptions/,
		/^Timed prescriptions/
	];
	return safePatterns.some((pattern) => pattern.test(message))
		? message
		: 'Workout change could not be completed.';
}
