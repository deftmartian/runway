import { z } from 'zod';
import {
	addFutureWorkout,
	applyFutureWorkoutEdit,
	previewFutureWorkoutAdd,
	previewFutureWorkoutEdit,
	previewFutureWorkoutRemoval,
	removeFutureWorkout,
	resetFutureWorkout,
	undoFutureWorkoutAdjustment
} from './repositories/future-workouts';
import {
	deleteActivityRecord,
	linkActivityToWorkout,
	unlinkActivityFromWorkout,
	updateActivityFeedback
} from './repositories/activity-mutations';
import { confirmActivityAsExtra, recordManualRun } from './repositories/extra-activity-mutations';
import {
	archiveActivePlan,
	completeActivePlan,
	confirmPhaseBaseline,
	continueBeginnerPhase
} from './repositories/plan-lifecycle';
import {
	updateAthleteTimeZone,
	updateHealthContext,
	updateRouteDataMode,
	updateTrainingProfile
} from './repositories/profiles';
import {
	applyConsequenceDecision,
	deleteWorkoutFeedback,
	recordWorkoutFeedback
} from './repositories/workout-feedback';
import { createPlanFromGoalSetup } from './plan-setup';
import {
	activityIdSchema,
	activityLinkSchema,
	consequenceDecisionSchema,
	feedbackSchema,
	goalSetupSchema,
	healthContextSchema,
	heartRateProfileSchema,
	manualRunSchema,
	workoutAdjustmentIdSchema,
	workoutIdSchema
} from './validation';
import { formatConsequenceSummary } from '$lib/training/consequence-presentation';
import {
	disconnectImportSource,
	saveNextcloudSource,
	syncNextcloudSource,
	testNextcloudSource
} from './import-sources';
import { revokeAndroidDevice } from './android-devices';
import { deleteActivityData } from './repositories/activity-mutations';
import { resolveHealthConnectDuplicate, resolveHealthConnectRecord } from './health-connect';

export const mobileActionNames = [
	'create_plan',
	'record_feedback',
	'delete_feedback',
	'record_manual_run',
	'link_activity',
	'unlink_activity',
	'confirm_activity_extra',
	'update_activity_feedback',
	'delete_activity',
	'resolve_health_connect_record',
	'resolve_health_connect_duplicate',
	'apply_plan_decision',
	'preview_workout_edit',
	'apply_workout_edit',
	'preview_workout_add',
	'apply_workout_add',
	'preview_workout_removal',
	'remove_workout',
	'reset_workout',
	'undo_workout_adjustment',
	'complete_plan',
	'confirm_phase_baseline',
	'continue_beginner_phase',
	'archive_plan',
	'update_time_zone',
	'update_route_data_mode',
	'update_health_context',
	'update_training_profile',
	'connect_nextcloud',
	'test_nextcloud',
	'sync_nextcloud',
	'disconnect_nextcloud',
	'revoke_android_device',
	'delete_imported_activity_data'
] as const;

export type MobileActionName = (typeof mobileActionNames)[number];

export type MobileActionResponse = {
	status: number;
	body: Record<string, unknown>;
};

const uuid = z.uuid();
const emptyBody = z.strictObject({});
const timeZoneBody = z.strictObject({ timeZone: z.string().trim().min(1).max(100) });
const routeModeBody = z.strictObject({ routeDataMode: z.enum(['discard', 'private']) });
const nextcloudSourceBody = z.strictObject({
	label: z.string().trim().max(120),
	shareUrl: z.string().trim().min(1).max(2_048),
	sharePassword: z.string().max(1_024)
});
const sourceIdBody = z.strictObject({ sourceId: uuid });
const androidDeviceIdBody = z.strictObject({ deviceId: uuid });
const deleteImportedActivityDataBody = z.strictObject({
	confirmation: z.literal('DELETE IMPORTED ACTIVITY DATA')
});
const activityFeedbackBody = z.strictObject({
	activityId: uuid,
	feltHard: z.boolean(),
	pain: z.boolean()
});
const healthConnectRecordDecisionBody = z.strictObject({
	mappingId: uuid,
	decision: z.enum(['accept_correction', 'keep_current', 'delete_from_runway', 'retain_in_runway'])
});
const healthConnectDuplicateDecisionBody = z.strictObject({
	mappingId: uuid,
	decision: z.enum(['keep_health_connect', 'use_existing'])
});
const timedSegment = z.strictObject({
	kind: z.enum(['run', 'walk']),
	durationSeconds: z.number().int().min(1).max(7_200)
});
const timedBlock = z.strictObject({
	repetitions: z.number().int().min(1).max(100),
	segments: z.array(timedSegment).min(1).max(20)
});
const timedStructure = z.strictObject({
	warmupSeconds: z.number().int().min(0).max(21_600),
	cooldownSeconds: z.number().int().min(0).max(21_600),
	blocks: z.array(timedBlock).max(20)
});
const workoutMutationFields = {
	scheduledDate: z.iso.date(),
	type: z.enum(['easy', 'long', 'recovery', 'rest']),
	prescriptionKind: z.enum(['distance', 'timed', 'rest']),
	targetDistanceMeters: z.number().int().min(0).max(100_000),
	targetDurationSeconds: z.number().int().min(600).max(21_600).nullable(),
	intervalStructure: timedStructure.nullable(),
	intensity: z.enum(['easy', 'rest']),
	purpose: z.string().trim().min(2).max(120),
	userReason: z.string().trim().max(500).optional(),
	rebalance: z.boolean(),
	confirmRisk: z.boolean()
};
const workoutMutation = z.strictObject(workoutMutationFields).superRefine(validateWorkoutMutation);
const workoutEditMutation = z
	.strictObject({ workoutId: uuid, ...workoutMutationFields })
	.superRefine(validateWorkoutMutation);

export function isMobileActionName(input: string): input is MobileActionName {
	return (mobileActionNames as readonly string[]).includes(input);
}

export async function runMobileAction(
	action: MobileActionName,
	userId: string,
	body: unknown
): Promise<MobileActionResponse> {
	try {
		switch (action) {
			case 'create_plan': {
				const input = parse(goalSetupSchema, body);
				if (!input.ok) return input.response;
				const result = await createPlanFromGoalSetup(userId, input.data);
				return result.ok
					? success(
							result.planPending
								? 'Goal saved. The plan is paused until the health restriction is cleared.'
								: 'Plan created.',
							{ planPending: result.planPending }
						)
					: {
							status: 400,
							body: {
								ok: false,
								error: 'validation',
								message: result.message,
								fieldErrors: result.fieldErrors
							}
						};
			}
			case 'record_feedback': {
				const input = parse(feedbackSchema, body);
				if (!input.ok) return input.response;
				const completedDistanceMeters =
					input.data.status === 'skipped' || input.data.completedDistanceKm === undefined
						? undefined
						: Math.round(input.data.completedDistanceKm * 1_000);
				const consequence = await recordWorkoutFeedback(userId, {
					workoutId: input.data.workoutId,
					status: input.data.status,
					feltHard: input.data.feltHard,
					pain: input.data.pain,
					choice: input.data.choice,
					...(completedDistanceMeters === undefined ? {} : { completedDistanceMeters }),
					...(input.data.completedDurationMinutes === undefined
						? {}
						: {
								completedDurationSeconds: Math.round(input.data.completedDurationMinutes * 60)
							})
				});
				return success('Result saved.', { consequence });
			}
			case 'delete_feedback': {
				const input = parse(workoutIdSchema, body);
				if (!input.ok) return input.response;
				await deleteWorkoutFeedback(userId, input.data.workoutId);
				return success('Saved result removed.');
			}
			case 'record_manual_run': {
				const input = parse(manualRunSchema, body);
				if (!input.ok) return input.response;
				const result = await recordManualRun(userId, {
					occurredDate: input.data.occurredDate,
					distanceMeters: Math.round((input.data.distanceKm ?? 0) * 1_000),
					feltHard: input.data.feltHard,
					pain: input.data.pain,
					...(input.data.durationMinutes === undefined
						? {}
						: { durationSeconds: Math.round(input.data.durationMinutes * 60) })
				});
				return success(
					result.consequence
						? `Run recorded. ${formatConsequenceSummary(result.consequence)}`
						: 'Run recorded.',
					{ activityId: result.activity.id, consequence: result.consequence }
				);
			}
			case 'link_activity': {
				const input = parse(activityLinkSchema, body);
				if (!input.ok) return input.response;
				const consequence = await linkActivityToWorkout(userId, input.data);
				return success(formatConsequenceSummary(consequence), { consequence });
			}
			case 'unlink_activity': {
				const input = parse(activityIdSchema, body);
				if (!input.ok) return input.response;
				const consequence = await unlinkActivityFromWorkout(userId, input.data.activityId);
				return success('Activity unlinked from the workout.', { consequence });
			}
			case 'confirm_activity_extra': {
				const input = parse(activityIdSchema, body);
				if (!input.ok) return input.response;
				const consequence = await confirmActivityAsExtra(userId, input.data.activityId);
				return success(
					consequence
						? formatConsequenceSummary(consequence)
						: 'Activity counted as historical training. The current plan is unchanged.',
					{ consequence }
				);
			}
			case 'update_activity_feedback': {
				const input = parse(activityFeedbackBody, body);
				if (!input.ok) return input.response;
				const consequence = await updateActivityFeedback(userId, input.data.activityId, {
					feltHard: input.data.feltHard,
					pain: input.data.pain
				});
				return success('Activity feedback updated.', { consequence });
			}
			case 'delete_activity': {
				const input = parse(activityIdSchema, body);
				if (!input.ok) return input.response;
				await deleteActivityRecord(userId, input.data.activityId);
				return success('Activity deleted.');
			}
			case 'resolve_health_connect_record': {
				const input = parse(healthConnectRecordDecisionBody, body);
				if (!input.ok) return input.response;
				await resolveHealthConnectRecord(userId, input.data.mappingId, input.data.decision);
				return success(
					input.data.decision === 'accept_correction'
						? 'Health Connect correction applied.'
						: input.data.decision === 'delete_from_runway'
							? 'Activity removed from runway.'
							: 'Health Connect source decision saved.'
				);
			}
			case 'resolve_health_connect_duplicate': {
				const input = parse(healthConnectDuplicateDecisionBody, body);
				if (!input.ok) return input.response;
				await resolveHealthConnectDuplicate(userId, input.data.mappingId, input.data.decision);
				return success(
					input.data.decision === 'keep_health_connect'
						? 'Health Connect record kept.'
						: 'Existing activity kept.'
				);
			}
			case 'apply_plan_decision': {
				const input = parse(consequenceDecisionSchema, body);
				if (!input.ok) return input.response;
				const consequence = await applyConsequenceDecision(userId, input.data);
				return success(formatConsequenceSummary(consequence), { consequence });
			}
			case 'preview_workout_edit':
			case 'apply_workout_edit': {
				const input = parse(workoutEditMutation, body);
				if (!input.ok) return input.response;
				const { userReason, ...requiredInput } = input.data;
				const mutationInput = {
					...requiredInput,
					...(userReason === undefined ? {} : { userReason })
				};
				const result =
					action === 'preview_workout_edit'
						? await previewFutureWorkoutEdit(userId, mutationInput)
						: await applyFutureWorkoutEdit(userId, mutationInput);
				return success(
					action === 'preview_workout_edit'
						? 'Review the workout and its effect on the plan.'
						: 'Workout updated.',
					action === 'preview_workout_edit' ? { preview: result } : { result }
				);
			}
			case 'preview_workout_add':
			case 'apply_workout_add': {
				const input = parse(workoutMutation, body);
				if (!input.ok) return input.response;
				const { userReason, ...requiredInput } = input.data;
				const mutationInput = {
					...requiredInput,
					...(userReason === undefined ? {} : { userReason })
				};
				const result =
					action === 'preview_workout_add'
						? await previewFutureWorkoutAdd(userId, mutationInput)
						: await addFutureWorkout(userId, mutationInput);
				return success(
					action === 'preview_workout_add'
						? 'Review the workout and its effect on the plan.'
						: 'Workout added.',
					action === 'preview_workout_add' ? { preview: result } : { result }
				);
			}
			case 'preview_workout_removal':
			case 'remove_workout':
			case 'reset_workout': {
				const input = parse(workoutIdSchema, body);
				if (!input.ok) return input.response;
				const result =
					action === 'preview_workout_removal'
						? await previewFutureWorkoutRemoval(userId, input.data.workoutId)
						: action === 'remove_workout'
							? await removeFutureWorkout(userId, input.data.workoutId)
							: await resetFutureWorkout(userId, input.data.workoutId);
				const message =
					action === 'preview_workout_removal'
						? 'Review the weekly load after removing this workout.'
						: action === 'remove_workout'
							? 'Workout removed. You can undo the change.'
							: 'Generated recommendation restored.';
				return success(
					message,
					action === 'preview_workout_removal' ? { preview: result } : { result }
				);
			}
			case 'undo_workout_adjustment': {
				const input = parse(workoutAdjustmentIdSchema, body);
				if (!input.ok) return input.response;
				const result = await undoFutureWorkoutAdjustment(userId, input.data.adjustmentId);
				return success('Workout change undone.', { result });
			}
			case 'complete_plan': {
				const input = parse(emptyBody, body);
				if (!input.ok) return input.response;
				const result = await completeActivePlan(userId);
				return result ? success('Plan completed.', { result }) : notFound('No active plan.');
			}
			case 'confirm_phase_baseline': {
				const input = parse(emptyBody, body);
				if (!input.ok) return input.response;
				const result = await confirmPhaseBaseline(userId);
				return success('Baseline confirmed. The race plan is ready.', { result });
			}
			case 'continue_beginner_phase': {
				const input = parse(emptyBody, body);
				if (!input.ok) return input.response;
				const result = await continueBeginnerPhase(userId);
				return success(
					result.continued
						? 'The beginner phase was extended.'
						: 'The current phase is still active.',
					{ result }
				);
			}
			case 'archive_plan': {
				const input = parse(z.strictObject({ confirmation: z.literal('ARCHIVE') }), body);
				if (!input.ok) return input.response;
				const result = await archiveActivePlan(userId, 'abandoned');
				return result ? success('Plan archived.', { result }) : notFound('No active plan.');
			}
			case 'update_time_zone': {
				const input = parse(timeZoneBody, body);
				if (!input.ok) return input.response;
				const result = await updateAthleteTimeZone(userId, input.data.timeZone);
				return success('Training time zone saved.', { result });
			}
			case 'update_route_data_mode': {
				const input = parse(routeModeBody, body);
				if (!input.ok) return input.response;
				const result = await updateRouteDataMode(userId, input.data.routeDataMode);
				return success(
					result.routeDataMode === 'private'
						? 'Route maps enabled for future imports.'
						: 'Route points will be discarded after import.',
					{ result }
				);
			}
			case 'update_health_context': {
				const input = parse(healthContextSchema, body);
				if (!input.ok) return input.response;
				const result = await updateHealthContext(userId, {
					recentInjury: input.data.recentInjury,
					currentPain: input.data.currentPain,
					recurringPain: input.data.recurringPain,
					medicalRestriction: input.data.medicalRestriction,
					notes: input.data.injuryNotes
				});
				return success('Health context saved.', { result });
			}
			case 'update_training_profile': {
				const input = parse(heartRateProfileSchema, body);
				if (!input.ok) return input.response;
				const result = await updateTrainingProfile(userId, input.data);
				return success('Training profile saved.', { result });
			}
			case 'connect_nextcloud': {
				const input = parse(nextcloudSourceBody, body);
				if (!input.ok) return input.response;
				try {
					const result = await saveNextcloudSource(userId, input.data);
					return success('Nextcloud folder connected.', {
						source: {
							id: result.id,
							label: result.label,
							enabled: result.enabled
						}
					});
				} catch (error) {
					return nextcloudFailure(error, 'Nextcloud folder could not be connected.');
				}
			}
			case 'test_nextcloud': {
				const input = parse(sourceIdBody, body);
				if (!input.ok) return input.response;
				try {
					const result = await testNextcloudSource(userId, input.data.sourceId);
					return success(
						result.count === 0
							? 'Connection works, but no GPX files are visible.'
							: `Connection works. ${result.count} GPX file${result.count === 1 ? '' : 's'} visible.`
					);
				} catch (error) {
					return nextcloudFailure(error, 'Nextcloud folder could not be checked.');
				}
			}
			case 'sync_nextcloud': {
				const input = parse(sourceIdBody, body);
				if (!input.ok) return input.response;
				try {
					const result = await syncNextcloudSource(userId, input.data.sourceId);
					return result.status === 'failed'
						? { status: 400, body: { ok: false, error: 'rejected', message: result.message } }
						: success(result.message, { result });
				} catch (error) {
					return nextcloudFailure(error, 'Nextcloud folder could not be synced.');
				}
			}
			case 'disconnect_nextcloud': {
				const input = parse(sourceIdBody, body);
				if (!input.ok) return input.response;
				try {
					await disconnectImportSource(userId, input.data.sourceId);
					return success('Nextcloud folder disconnected.');
				} catch (error) {
					return nextcloudFailure(error, 'Nextcloud folder could not be disconnected.');
				}
			}
			case 'revoke_android_device': {
				const input = parse(androidDeviceIdBody, body);
				if (!input.ok) return input.response;
				const revoked = await revokeAndroidDevice(userId, input.data.deviceId);
				return revoked
					? success('Import device revoked.')
					: notFound('That import device is no longer active.');
			}
			case 'delete_imported_activity_data': {
				const input = parse(deleteImportedActivityDataBody, body);
				if (!input.ok) return input.response;
				const result = await deleteActivityData(userId);
				return success(
					'Imported activity data deleted. This phone was disconnected from imports.',
					{
						deleted: result
					}
				);
			}
		}
	} catch (error) {
		const message = safeDomainMessage(error);
		if (!message) throw error;
		return { status: 400, body: { ok: false, error: 'rejected', message } };
	}
}

function validateWorkoutMutation(
	value: z.infer<z.ZodObject<typeof workoutMutationFields>>,
	context: z.RefinementCtx
) {
	const structureSeconds =
		(value.intervalStructure?.warmupSeconds ?? 0) +
		(value.intervalStructure?.cooldownSeconds ?? 0) +
		(value.intervalStructure?.blocks.reduce(
			(total, block) =>
				total +
				block.repetitions *
					block.segments.reduce((subtotal, segment) => subtotal + segment.durationSeconds, 0),
			0
		) ?? 0);
	const valid =
		(value.prescriptionKind === 'rest' &&
			value.type === 'rest' &&
			value.intensity === 'rest' &&
			value.targetDistanceMeters === 0 &&
			value.targetDurationSeconds === null &&
			value.intervalStructure === null) ||
		(value.prescriptionKind === 'distance' &&
			value.type !== 'rest' &&
			value.intensity === 'easy' &&
			value.targetDistanceMeters > 0 &&
			value.targetDurationSeconds === null &&
			value.intervalStructure === null) ||
		(value.prescriptionKind === 'timed' &&
			value.type !== 'rest' &&
			value.intensity === 'easy' &&
			value.targetDistanceMeters === 0 &&
			value.targetDurationSeconds !== null &&
			value.intervalStructure !== null &&
			structureSeconds === value.targetDurationSeconds);
	if (!valid) {
		context.addIssue({
			code: 'custom',
			message: 'Workout type, load, and interval details do not agree.'
		});
	}
}

function parse<Schema extends z.ZodType>(
	schema: Schema,
	body: unknown
): { ok: true; data: z.infer<Schema> } | { ok: false; response: MobileActionResponse } {
	const parsed = schema.safeParse(body);
	if (parsed.success) return { ok: true, data: parsed.data };
	return {
		ok: false,
		response: {
			status: 400,
			body: {
				ok: false,
				error: 'validation',
				message: parsed.error.issues[0]?.message ?? 'Review the submitted fields.'
			}
		}
	};
}

function success(message: string, result: Record<string, unknown> = {}): MobileActionResponse {
	return { status: 200, body: { ok: true, message, ...result } };
}

function notFound(message: string): MobileActionResponse {
	return { status: 404, body: { ok: false, error: 'not_found', message } };
}

function nextcloudFailure(error: unknown, fallback: string): MobileActionResponse {
	const message = error instanceof Error ? error.message : '';
	const safe =
		message.length <= 300 &&
		/^(Enter the Nextcloud|Enter the share|Nextcloud folder|Nextcloud share|That import source|Import source|Set training time zone|You can connect up to|Another import)/.test(
			message
		)
			? message
			: fallback;
	return { status: 400, body: { ok: false, error: 'rejected', message: safe } };
}

function safeDomainMessage(error: unknown): string | null {
	const message = error instanceof Error ? error.message : '';
	if (!message || message.length > 300) return null;
	const safePatterns = [
		/^(Activity|Workout|Future workout|Rest days|Feedback|Manual run|Manual runs|Linked|Unlink|That workout|This activity|This goal|Training plans|Race events|No active plan|No reversible|The active plan|The beginner phase|There is no completed|The recorded work|The current phase|The proposed workout|Review and confirm|Generated recommendation|Workout dates|Only workouts|Reset or undo|Set training time zone|Choose a valid|Unknown plan lifecycle)/,
		/^(Distance|Timed|Rest|Run) prescriptions/,
		/^(Health Connect record is not available|Source deletion is not pending|Correction is not pending|Unlink this accepted activity before applying a correction|Stop counting this activity as extra before applying a correction|Duplicate candidate is not available)$/
	];
	return safePatterns.some((pattern) => pattern.test(message)) ? message : null;
}
