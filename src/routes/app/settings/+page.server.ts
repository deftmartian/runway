import { fail, redirect } from '@sveltejs/kit';
import { APIError } from 'better-auth/api';
import { parseSetCookieHeader, toCookieOptions } from 'better-auth/cookies';
import QRCode from 'qrcode';
import { auth } from '$lib/server/auth';
import { isFreshAuthSession } from '$lib/server/runway/auth-config';
import { readAuditRetentionPolicy } from '$lib/server/runway/audit-retention';
import {
	accountSecurityRateLimitBuckets,
	consumeSecurityRateLimit
} from '$lib/server/runway/security-rate-limit';
import { revokeTrustedDevices } from '$lib/server/runway/trusted-devices';
import { deleteActivityData } from '$lib/server/runway/repositories/activity-mutations';
import {
	getAthleteProfile,
	updateHealthContext,
	updateAthleteTimeZone,
	updateRouteDataMode,
	updateTrainingProfile
} from '$lib/server/runway/repositories/profiles';
import { defaultHeartRateSettings, zoneFloors } from '$lib/training/heart-rate';
import {
	formDataToObject,
	formString,
	heartRateProfileSchema,
	healthContextSchema,
	authPasswordSchema,
	totpCodeSchema
} from '$lib/server/runway/validation';
import type { Actions, PageServerLoad } from './$types';

export const load: PageServerLoad = async (event) => {
	if (!event.locals.user) throw redirect(302, '/login');
	const authUser = event.locals.user as typeof event.locals.user & {
		twoFactorEnabled?: boolean | null;
	};
	const profile = await getAthleteProfile(event.locals.user.id);
	const sexForEstimates = profile?.sexForEstimates ?? 'not_specified';
	const validAge =
		profile?.ageYears !== null &&
		profile?.ageYears !== undefined &&
		Number.isInteger(profile.ageYears) &&
		profile.ageYears >= 18 &&
		profile.ageYears <= 100
			? profile.ageYears
			: null;
	const heartRateSettings =
		profile?.heartRateSettings ??
		(validAge === null ? null : defaultHeartRateSettings(validAge, sexForEstimates));
	const floors = heartRateSettings ? zoneFloors(heartRateSettings) : null;
	const [passkeys, accounts] = await Promise.all([
		auth.api.listPasskeys({ headers: event.request.headers }),
		auth.api.listUserAccounts({ headers: event.request.headers })
	]);
	const hasCredentialAccount = accounts.some((account) => account.providerId === 'credential');
	return {
		user: {
			...event.locals.user,
			twoFactorEnabled: Boolean(authUser.twoFactorEnabled)
		},
		passkeys: passkeys.map((record) => ({
			id: record.id,
			name: record.name,
			deviceType: record.deviceType,
			backedUp: record.backedUp,
			createdAt: record.createdAt
		})),
		authCapabilities: {
			localPassword: hasCredentialAccount,
			oidc: accounts.some((account) => account.providerId === 'authentik')
		},
		auditRetention: readAuditRetentionPolicy(),
		profile: {
			timeZone: profile?.timeZone ?? null,
			routeDataMode: profile?.routeDataMode ?? 'private',
			sexForEstimates,
			ageYears: validAge,
			heartRateSettingsSource: heartRateSettings?.source ?? 'not_configured',
			maxHeartRateBpm: floors?.maxHeartRateBpm ?? null,
			zone2FloorBpm: floors?.zone2FloorBpm ?? null,
			zone3FloorBpm: floors?.zone3FloorBpm ?? null,
			zone4FloorBpm: floors?.zone4FloorBpm ?? null,
			zone5FloorBpm: floors?.zone5FloorBpm ?? null,
			injuryFlags: profile?.injuryFlags ?? {
				recentInjury: false,
				currentPain: false,
				recurringPain: false,
				medicalRestriction: false,
				notes: ''
			}
		}
	};
};

export const actions: Actions = {
	updateTimeZone: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const timeZone = formString(await event.request.formData(), 'timeZone');
		try {
			await updateAthleteTimeZone(event.locals.user.id, timeZone);
		} catch {
			return fail(400, {
				scope: 'timeZone',
				message: 'Enter a time zone such as America/Halifax.'
			});
		}
		return {
			scope: 'timeZone',
			message: 'Training time zone saved.'
		};
	},
	updateTrainingProfile: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = heartRateProfileSchema.safeParse(
			formDataToObject(await event.request.formData())
		);
		if (!parsed.success) {
			return fail(400, {
				scope: 'trainingProfile',
				message: parsed.error.issues[0]?.message ?? 'Training profile could not be saved.'
			});
		}
		await updateTrainingProfile(event.locals.user.id, parsed.data);
		return { scope: 'trainingProfile', message: 'Training profile saved.' };
	},
	updateHealthContext: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const parsed = healthContextSchema.safeParse(formDataToObject(await event.request.formData()));
		if (!parsed.success) {
			return fail(400, {
				scope: 'healthContext',
				message: parsed.error.issues[0]?.message ?? 'Health context could not be saved.'
			});
		}
		await updateHealthContext(event.locals.user.id, {
			recentInjury: parsed.data.recentInjury,
			currentPain: parsed.data.currentPain,
			recurringPain: parsed.data.recurringPain,
			medicalRestriction: parsed.data.medicalRestriction,
			notes: parsed.data.injuryNotes
		});
		return {
			scope: 'healthContext',
			message:
				parsed.data.recentInjury ||
				parsed.data.currentPain ||
				parsed.data.recurringPain ||
				parsed.data.medicalRestriction ||
				parsed.data.injuryNotes
					? 'Health context saved.'
					: 'Health context cleared.'
		};
	},
	updateRouteDataMode: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const routeDataMode = formString(await event.request.formData(), 'routeDataMode');
		if (routeDataMode !== 'discard' && routeDataMode !== 'private') {
			return fail(400, { scope: 'privacy', message: 'Choose how route points are handled.' });
		}
		const result = await updateRouteDataMode(event.locals.user.id, routeDataMode);
		const clearedMessage =
			result.clearedRouteCount > 0
				? ` Removed saved routes from ${result.clearedRouteCount} ${result.clearedRouteCount === 1 ? 'activity' : 'activities'}.`
				: '';
		return {
			scope: 'privacy',
			message:
				routeDataMode === 'private'
					? 'Route maps enabled for future GPX imports.'
					: `Route points will be discarded after import.${clearedMessage}`
		};
	},
	enableTwoFactor: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		const password = formString(formData, 'password');
		const invalidPassword = authPasswordSchema.safeParse(password);
		if (!invalidPassword.success) {
			return fail(400, { scope: 'twoFactor', message: 'Enter your current password.' });
		}
		const blocked = await accountSecurityRateLimit(event, 'enable-two-factor');
		if (blocked) return blocked;
		if (!(await hasCredentialAccount(event.request.headers))) {
			return fail(400, {
				scope: 'twoFactor',
				message: 'An authenticator app requires a local password account.'
			});
		}
		try {
			const result = await auth.api.enableTwoFactor({
				body: { password },
				headers: event.request.headers
			});
			await revokeTrustedDevices(event.locals.user.id);
			const totpQrCode = await QRCode.toDataURL(result.totpURI, {
				errorCorrectionLevel: 'M',
				margin: 2,
				width: 220
			});
			const totpManualKey = new URL(result.totpURI).searchParams.get('secret');
			if (!totpManualKey) throw new Error('Authenticator setup did not include a secret.');
			return {
				scope: 'twoFactor',
				message: 'Two-factor setup started.',
				totpQrCode,
				totpManualKey
			};
		} catch (error) {
			return authActionFailure(error, 'Could not enable two-factor authentication.', {
				scope: 'twoFactor'
			});
		}
	},
	verifySetupTotp: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		const code = formString(formData, 'code');
		const parsedCode = totpCodeSchema.safeParse(code);
		if (!parsedCode.success) {
			return fail(400, {
				scope: 'twoFactor',
				message: parsedCode.error.issues[0]?.message,
				setupPending: true
			});
		}
		const blocked = await accountSecurityRateLimit(event, 'verify-two-factor-setup', true);
		if (blocked) return blocked;
		if (!(await hasCredentialAccount(event.request.headers))) {
			return fail(400, {
				scope: 'twoFactor',
				message: 'An authenticator app requires a local password account.'
			});
		}
		try {
			await auth.api.verifyTOTP({
				body: { code: parsedCode.data },
				headers: event.request.headers
			});
			const recovery = await auth.api.viewBackupCodes({
				body: { userId: event.locals.user.id }
			});
			return {
				scope: 'twoFactor',
				message: 'Two-factor authentication enabled. Save your recovery codes now.',
				backupCodes: recovery.backupCodes
			};
		} catch (error) {
			return authActionFailure(error, 'Could not verify the authenticator code.', {
				scope: 'twoFactor',
				setupPending: true
			});
		}
	},
	disableTwoFactor: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		const formData = await event.request.formData();
		const password = formString(formData, 'password');
		const invalidPassword = authPasswordSchema.safeParse(password);
		if (!invalidPassword.success) {
			return fail(400, { scope: 'twoFactor', message: 'Enter your current password.' });
		}
		const blocked = await accountSecurityRateLimit(event, 'disable-two-factor');
		if (blocked) return blocked;
		if (!(await hasCredentialAccount(event.request.headers))) {
			return fail(400, {
				scope: 'twoFactor',
				message: 'This account does not have a local password.'
			});
		}
		try {
			const result = await auth.api.disableTwoFactor({
				body: { password },
				headers: event.request.headers,
				returnHeaders: true
			});
			applyAuthResponseCookies(event, result.headers);
			await revokeTrustedDevices(event.locals.user.id);
			return { scope: 'twoFactor', message: 'Two-factor authentication disabled.' };
		} catch (error) {
			return authActionFailure(error, 'Could not disable two-factor authentication.', {
				scope: 'twoFactor'
			});
		}
	},
	deletePasskey: async (event) => {
		if (!event.locals.user || !event.locals.session) throw redirect(302, '/login');
		if (!isFreshAuthSession(event.locals.session.createdAt)) {
			return fail(403, {
				scope: 'passkeys',
				message: 'Sign out and sign in again before removing a passkey.'
			});
		}
		const formData = await event.request.formData();
		const id = formString(formData, 'id');
		if (!id) return fail(400, { scope: 'passkeys', message: 'Choose a passkey to remove.' });
		const rateLimit = await consumeSecurityRateLimit(
			accountSecurityRateLimitBuckets(
				'delete-passkey',
				event.locals.user.id,
				event.getClientAddress()
			)
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'passkeys',
				message: 'Too many passkey changes. Try again later.'
			});
		}
		try {
			await auth.api.deletePasskey({ body: { id }, headers: event.request.headers });
			return { scope: 'passkeys', message: 'Passkey removed.' };
		} catch (error) {
			return authActionFailure(error, 'Could not remove the passkey.', {
				scope: 'passkeys'
			});
		}
	},
	deleteActivityData: async (event) => {
		if (!event.locals.user) throw redirect(302, '/login');
		let result: Awaited<ReturnType<typeof deleteActivityData>>;
		try {
			result = await deleteActivityData(event.locals.user.id);
		} catch {
			return fail(500, {
				scope: 'privacy',
				message: 'Imported GPX activities could not be deleted. Try again.'
			});
		}
		const activityMessage =
			result.count === 1
				? 'Deleted 1 imported GPX activity.'
				: `Deleted ${result.count} imported GPX activities.`;
		const sourceMessage =
			result.disconnectedImportSources === 0
				? ''
				: result.disconnectedImportSources === 1
					? ' Disconnected 1 import folder so it cannot sync the activity back.'
					: ` Disconnected ${result.disconnectedImportSources} import folders so they cannot sync the activities back.`;
		const androidMessage =
			result.disconnectedAndroidDevices === 0
				? ''
				: result.disconnectedAndroidDevices === 1
					? ' Disconnected 1 Android device so it cannot import the activity again.'
					: ` Disconnected ${result.disconnectedAndroidDevices} Android devices so they cannot import the activities again.`;
		return {
			scope: 'privacy',
			message: `${activityMessage}${sourceMessage}${androidMessage}`
		};
	},
	deleteAccount: async (event) => {
		if (!event.locals.user || !event.locals.session) throw redirect(302, '/login');
		if (!isFreshAuthSession(event.locals.session.createdAt)) {
			return fail(403, {
				scope: 'accountDeletion',
				message: 'Sign out and sign in again before deleting the account.'
			});
		}
		const rateLimit = await consumeSecurityRateLimit(
			accountSecurityRateLimitBuckets(
				'delete-account',
				event.locals.user.id,
				event.getClientAddress()
			)
		);
		if (!rateLimit.allowed) {
			event.setHeaders({ 'retry-after': String(rateLimit.retryAfterSeconds) });
			return fail(429, {
				scope: 'accountDeletion',
				message: 'Too many account-deletion attempts. Try again later.'
			});
		}
		const formData = await event.request.formData();
		if (formString(formData, 'confirmation') !== 'DELETE') {
			return fail(400, {
				scope: 'accountDeletion',
				message: 'Type DELETE exactly to confirm account deletion.'
			});
		}
		if (formString(formData, 'browserFolderDataCleared') !== 'yes') {
			return fail(400, {
				scope: 'accountDeletion',
				message: 'Browser folder access must be cleared before deleting the account.'
			});
		}

		let responseHeaders: Headers;
		try {
			const result = await auth.api.deleteUser({
				body: {},
				headers: event.request.headers,
				returnHeaders: true
			});
			responseHeaders = result.headers;
		} catch (error) {
			return authActionFailure(error, 'The account could not be deleted. Try again.', {
				scope: 'accountDeletion'
			});
		}
		applyAuthResponseCookies(event, responseHeaders);
		throw redirect(303, '/');
	}
};

function authActionFailure(
	error: unknown,
	fallback: string,
	details: Record<string, unknown> = {}
) {
	if (error instanceof APIError) {
		return fail(error.statusCode || 400, { ...details, message: fallback });
	}
	return fail(500, { ...details, message: fallback });
}

async function hasCredentialAccount(headers: Headers): Promise<boolean> {
	const accounts = await auth.api.listUserAccounts({ headers });
	return accounts.some((account) => account.providerId === 'credential');
}

function applyAuthResponseCookies(
	event: Parameters<NonNullable<Actions['disableTwoFactor']>>[0],
	headers: Headers
) {
	const setCookie = headers.get('set-cookie');
	if (!setCookie) return;
	for (const [name, attributes] of parseSetCookieHeader(setCookie)) {
		event.cookies.set(name, attributes.value, {
			...toCookieOptions(attributes),
			path: attributes.path || '/'
		});
	}
}

async function accountSecurityRateLimit(
	event: Parameters<NonNullable<Actions['enableTwoFactor']>>[0],
	action: 'enable-two-factor' | 'verify-two-factor-setup' | 'disable-two-factor',
	setupPending = false
) {
	if (!event.locals.user) throw redirect(302, '/login');
	const result = await consumeSecurityRateLimit(
		accountSecurityRateLimitBuckets(action, event.locals.user.id, event.getClientAddress())
	);
	if (result.allowed) return null;
	event.setHeaders({ 'retry-after': String(result.retryAfterSeconds) });
	return fail(429, {
		scope: 'twoFactor',
		...(setupPending ? { setupPending: true } : {}),
		message: 'Too many security-setting attempts. Try again later.'
	});
}
