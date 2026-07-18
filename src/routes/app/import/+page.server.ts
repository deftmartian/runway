import { fail, redirect } from '@sveltejs/kit';
import { env } from '$env/dynamic/private';
import {
	confirmActivityAsExtra,
	deleteActivityRecord,
	getActivityRecords,
	getImportWorkoutCandidates,
	linkActivityToWorkout,
	recordImportedActivity,
	unlinkActivityFromWorkout,
	updateActivityFeedback
} from '$lib/server/runway/repository';
import {
	getActivityImportGeneration,
	getAthleteProfile
} from '$lib/server/runway/repositories/profiles';
import { buildAndroidAssetLinks } from '$lib/server/runway/android-asset-links';
import { maxGpxImportBytes } from '$lib/import-limits';
import {
	createAndroidPairingRequest,
	listAndroidDevices,
	revokeAndroidDevice
} from '$lib/server/runway/android-devices';
import {
	androidPairingCreateRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import {
	activityIdSchema,
	activityLinkSchema,
	androidDeviceIdSchema,
	formDataToObject,
	formString
} from '$lib/server/runway/validation';
import { hashActivityFile, parseGpx } from '$lib/training/gpx';
import { formatConsequenceSummary } from '$lib/training/consequence-presentation';
import {
	disconnectImportSource,
	importTimeZoneRequiredMessage,
	isImportTimeZoneConfigured,
	listImportSources,
	saveNextcloudSource,
	syncNextcloudSource,
	testNextcloudSource
} from '$lib/server/runway/import-sources';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const androidApplicationId = buildAndroidAssetLinks(
		env['ANDROID_APPLICATION_ID'],
		env['ANDROID_CERTIFICATE_SHA256']
	)?.[0]?.target.package_name;
	const activityOffset = Math.max(
		0,
		Number.parseInt(event.url.searchParams.get('offset') ?? '0', 10) || 0
	);
	const [candidates, activities, sources, importTimeZoneConfigured, profile, androidDevices] =
		await Promise.all([
			getImportWorkoutCandidates(event.locals.user.id),
			getActivityRecords(event.locals.user.id, { limit: 50, offset: activityOffset }),
			listImportSources(event.locals.user.id),
			isImportTimeZoneConfigured(event.locals.user.id),
			getAthleteProfile(event.locals.user.id),
			listAndroidDevices(event.locals.user.id)
		]);
	return {
		candidates,
		activities,
		activityOffset,
		sources,
		androidDevices,
		androidApplicationId: androidApplicationId ?? null,
		importTimeZoneConfigured,
		routeDataMode: profile?.routeDataMode ?? 'private',
		shareNotice: shareNotice(event.url.searchParams.get('share'))
	};
};

function shareNotice(value: string | null): { message: string; failed: boolean } | null {
	switch (value) {
		case 'imported':
			return { message: 'GPX added to the activity inbox.', failed: false };
		case 'one-file':
			return { message: 'Share one GPX file at a time.', failed: true };
		case 'too-large':
			return { message: 'The shared GPX file is larger than 10 MB.', failed: true };
		case 'invalid':
			return { message: 'The shared file is not a valid GPX activity.', failed: true };
		case 'duplicate':
			return { message: 'That GPX file is already in runway.', failed: true };
		case 'deleted':
			return { message: 'That deleted GPX activity cannot be imported again.', failed: true };
		case 'future':
			return { message: 'Imported activities cannot be in the future.', failed: true };
		case 'time-zone-required':
			return { message: importTimeZoneRequiredMessage, failed: true };
		case 'failed':
			return { message: 'The shared GPX file could not be imported.', failed: true };
		default:
			return null;
	}
}

export const actions: Actions = {
	createAndroidPairing: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const rateLimit = await consumeSecurityRateLimit(
			androidPairingCreateRateLimitBuckets(event.locals.user.id, event.getClientAddress())
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'android',
				message: 'Too many pairing codes were requested. Try again later.'
			});
		}
		const pairing = await createAndroidPairingRequest(event.locals.user.id);
		return {
			scope: 'android',
			message: 'Pairing code created.',
			pairingCode: pairing.code,
			pairingExpiresAt: pairing.expiresAt.toISOString()
		};
	},
	revokeAndroidDevice: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = androidDeviceIdSchema.safeParse(
			formDataToObject(await event.request.formData())
		);
		if (!parsed.success) {
			return fail(400, { scope: 'android', message: 'Choose an Android device.' });
		}
		const revoked = await revokeAndroidDevice(event.locals.user.id, parsed.data.deviceId);
		if (!revoked) {
			return fail(404, { scope: 'android', message: 'That Android device is not connected.' });
		}
		return { scope: 'android', message: 'Android device disconnected.' };
	},
	importGpx: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const importGeneration = await getActivityImportGeneration(event.locals.user.id);
		if (!(await isImportTimeZoneConfigured(event.locals.user.id))) {
			return fail(400, { message: importTimeZoneRequiredMessage });
		}
		const formData = await event.request.formData();
		const file = formData.get('file');
		if (!(file instanceof File)) {
			return fail(400, { message: 'Choose a GPX file.' });
		}
		if (file.size > maxGpxImportBytes) {
			return fail(400, { message: 'GPX file is too large for import.' });
		}
		const buffer = Buffer.from(await file.arrayBuffer());
		const matchMode = formString(formData, 'matchMode');
		const selectedWorkoutId = formString(formData, 'workoutId').trim();
		const matching:
			| { mode: 'unlinked' }
			| { mode: 'auto' }
			| { mode: 'workout'; workoutId: string }
			| null =
			matchMode === 'unlinked'
				? { mode: 'unlinked' }
				: matchMode === 'auto'
					? { mode: 'auto' }
					: matchMode === 'workout' && selectedWorkoutId
						? { mode: 'workout', workoutId: selectedWorkoutId }
						: null;
		if (!matching) {
			return fail(400, { message: 'Choose how this activity should match the plan.' });
		}
		let parsed;
		try {
			parsed = parseGpx(buffer);
		} catch {
			return fail(400, { message: 'The GPX file could not be parsed.' });
		}

		try {
			const activity = await recordImportedActivity(
				event.locals.user.id,
				hashActivityFile(buffer, event.locals.user.id),
				parsed,
				matching,
				importGeneration
			);
			const heartRateMessage = activity.heartRateSummary
				? ' Heart-rate zone time was included.'
				: parsed.hasHeartRate
					? ' Add age or custom zones in Settings to view the imported heart-rate samples.'
					: '';
			const consequenceMessage = activity.importConsequence
				? ` ${formatConsequenceSummary(activity.importConsequence)}`
				: '';
			const planMessage =
				matching.mode === 'unlinked'
					? ` Added to the activity inbox.${consequenceMessage}`
					: activity.workoutId
						? matching.mode === 'auto'
							? ` Auto-matched to a planned workout.${consequenceMessage}`
							: ` Matched to the selected planned workout.${consequenceMessage}`
						: ` No workout matched. Added to the activity inbox.${consequenceMessage}`;
			return {
				message: `Imported ${Math.round((parsed.distanceMeters / 1000) * 10) / 10} km.${heartRateMessage}${planMessage}`
			};
		} catch (error) {
			const message = error instanceof Error ? error.message : '';
			if (message === 'This activity file has already been imported.') {
				return fail(400, { message: 'This GPX file has already been imported.' });
			}
			if (message === 'This workout already has an imported activity.') {
				return fail(400, { message: 'That workout already has an imported activity.' });
			}
			if (message === 'Imported activities cannot be in the future.') {
				return fail(400, { message });
			}
			if (
				message === importTimeZoneRequiredMessage ||
				message === 'This deleted activity file cannot be imported again.' ||
				message === 'Import was cancelled because activity data was deleted.'
			) {
				return fail(400, { message });
			}
			return fail(400, { message: 'The GPX file could not be saved with that workout match.' });
		}
	},
	saveNextcloudSource: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		const label = formString(formData, 'label');
		const shareUrl = formString(formData, 'shareUrl');
		const sharePassword = formString(formData, 'sharePassword');

		try {
			await saveNextcloudSource(event.locals.user.id, { label, shareUrl, sharePassword });
			return { message: 'Nextcloud folder connected.' };
		} catch (error) {
			return fail(400, {
				message: nextcloudSourceError(error, 'Nextcloud folder could not be connected.')
			});
		}
	},
	testNextcloudSource: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const sourceId = formString(await event.request.formData(), 'sourceId');
		try {
			const result = await testNextcloudSource(event.locals.user.id, sourceId);
			return {
				message:
					result.count === 0
						? 'Connection works, but no GPX files are visible.'
						: `Connection works. ${result.count} GPX file${result.count === 1 ? '' : 's'} visible.`
			};
		} catch (error) {
			return fail(400, {
				message: nextcloudSourceError(error, 'Nextcloud folder could not be checked.')
			});
		}
	},
	syncNextcloudSource: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const sourceId = formString(await event.request.formData(), 'sourceId');
		let result;
		try {
			result = await syncNextcloudSource(event.locals.user.id, sourceId);
		} catch {
			return fail(400, { message: 'Nextcloud folder could not be synced.' });
		}
		if (result.status === 'failed') return fail(400, { message: result.message });
		return { message: result.message };
	},
	disconnectImportSource: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const sourceId = formString(await event.request.formData(), 'sourceId');
		try {
			await disconnectImportSource(event.locals.user.id, sourceId);
			return { message: 'Nextcloud folder disconnected.' };
		} catch {
			return fail(400, { message: 'Import source could not be disconnected.' });
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
				consequence
			};
		} catch (error) {
			return fail(400, { message: activityRecordError(error, 'Activity could not be linked.') });
		}
	},
	unlinkActivity: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			await unlinkActivityFromWorkout(event.locals.user.id, parsed.data.activityId);
			return { message: 'Activity unlinked from the workout.' };
		} catch (error) {
			return fail(400, { message: activityRecordError(error, 'Activity could not be unlinked.') });
		}
	},
	confirmActivityExtra: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			const consequence = await confirmActivityAsExtra(
				event.locals.user.id,
				parsed.data.activityId
			);
			return {
				message: consequence
					? formatConsequenceSummary(consequence)
					: 'Activity counted as extra training. Current plan unchanged.',
				consequence
			};
		} catch (error) {
			return fail(400, { message: activityRecordError(error, 'Activity could not be counted.') });
		}
	},
	updateActivityFeedback: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const object = formDataToObject(await event.request.formData());
		const parsed = activityIdSchema.safeParse(object);
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			const consequence = await updateActivityFeedback(
				event.locals.user.id,
				parsed.data.activityId,
				{ feltHard: object['feltHard'] === 'on', pain: object['pain'] === 'on' }
			);
			return { message: 'Activity feedback updated.', consequence };
		} catch (error) {
			return fail(400, {
				message: activityRecordError(error, 'Activity feedback could not be updated.')
			});
		}
	},
	deleteActivity: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = activityIdSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) return fail(400, { message: 'Choose an activity.' });
		try {
			await deleteActivityRecord(event.locals.user.id, parsed.data.activityId);
			return { message: 'Activity deleted.' };
		} catch (error) {
			return fail(400, { message: activityRecordError(error, 'Activity could not be deleted.') });
		}
	}
};

function nextcloudSourceError(error: unknown, fallback: string): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		importTimeZoneRequiredMessage,
		'Enter the Nextcloud share URL.',
		'Enter the share password.',
		'Nextcloud folder label is too long.',
		'Nextcloud share URL is too long.',
		'Nextcloud share password is too long.',
		'Nextcloud share URL is not valid.',
		'Nextcloud share URL must not include credentials.',
		'Nextcloud share URL must use HTTPS.',
		'Nextcloud share origin is not allowed.',
		'Nextcloud share URL must include a folder share token.',
		'Nextcloud share could not be reached.',
		'Nextcloud source credentials could not be opened.',
		'Nextcloud share password was rejected.',
		'Nextcloud share must require the password.',
		'Nextcloud share folder was not found.',
		'Nextcloud share could not be read.',
		'Nextcloud share returned an invalid WebDAV response.',
		'Nextcloud response is too large.',
		'That import source is not connected.'
	]);
	return knownMessages.has(message) ? message : fallback;
}

function activityRecordError(error: unknown, fallback: string): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		'Activity not found.',
		'Activity is already linked.',
		'Linked activities already count against the plan.',
		'This activity has already been counted as extra.',
		'Workout is not available for linking.',
		'Workout is outside the activity match window.',
		'That workout already has an activity.',
		'Activity is not linked.'
	]);
	return knownMessages.has(message) ? message : fallback;
}
