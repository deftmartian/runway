import { createHmac } from 'node:crypto';
import { sql } from 'drizzle-orm';
import { env } from '$env/dynamic/private';
import { db } from '$lib/server/db';
import { passwordResetRateLimit } from '$lib/server/db/schema';

type RateLimitBucket = {
	name: string;
	subject: string;
	max: number;
	windowMs: number;
};

export type SecurityRateLimitResult = {
	allowed: boolean;
	retryAfterSeconds: number;
};

const tenMinutes = 10 * 60 * 1_000;

export function signInRateLimitBuckets(email: string, clientAddress: string): RateLimitBucket[] {
	const normalizedEmail = email.trim().toLowerCase();
	return [
		{ name: 'sign-in:ip', subject: clientAddress, max: 25, windowMs: tenMinutes },
		{ name: 'sign-in:account', subject: normalizedEmail, max: 10, windowMs: tenMinutes }
	];
}

export function signUpRateLimitBuckets(email: string, clientAddress: string): RateLimitBucket[] {
	const normalizedEmail = email.trim().toLowerCase();
	return [
		{ name: 'sign-up:ip', subject: clientAddress, max: 10, windowMs: tenMinutes },
		{ name: 'sign-up:email', subject: normalizedEmail, max: 3, windowMs: tenMinutes }
	];
}

export function oidcSignInRateLimitBuckets(clientAddress: string): RateLimitBucket[] {
	return [{ name: 'oidc-sign-in:ip', subject: clientAddress, max: 20, windowMs: tenMinutes }];
}

export function passkeyAuthenticationRateLimitBuckets(
	action: 'options' | 'verify',
	clientAddress: string
): RateLimitBucket[] {
	return [
		{ name: 'passkey-authentication:ip', subject: clientAddress, max: 40, windowMs: tenMinutes },
		{
			name: `passkey-authentication:${action}:ip`,
			subject: clientAddress,
			max: action === 'options' ? 30 : 15,
			windowMs: tenMinutes
		}
	];
}

export function passkeyRegistrationRateLimitBuckets(
	userId: string,
	clientAddress: string
): RateLimitBucket[] {
	return [
		{ name: 'passkey-registration:ip', subject: clientAddress, max: 30, windowMs: tenMinutes },
		{ name: 'passkey-registration:user', subject: userId, max: 20, windowMs: tenMinutes }
	];
}

export function twoFactorRateLimitBuckets(
	method: 'totp' | 'backup',
	challenge: string,
	clientAddress: string
): RateLimitBucket[] {
	return [
		{ name: `two-factor:${method}:ip`, subject: clientAddress, max: 20, windowMs: tenMinutes },
		{ name: `two-factor:${method}:challenge`, subject: challenge, max: 5, windowMs: tenMinutes }
	];
}

export function accountSecurityRateLimitBuckets(
	action:
		| 'enable-two-factor'
		| 'verify-two-factor-setup'
		| 'disable-two-factor'
		| 'delete-passkey'
		| 'export-data',
	userId: string,
	clientAddress: string
): RateLimitBucket[] {
	return [
		{ name: `${action}:ip`, subject: clientAddress, max: 20, windowMs: tenMinutes },
		{ name: `${action}:user`, subject: userId, max: 5, windowMs: tenMinutes }
	];
}

export function passwordResetRequestRateLimitBuckets(
	email: string,
	clientAddress: string
): RateLimitBucket[] {
	const normalizedEmail = email.trim().toLowerCase();
	return [
		{ name: 'password-reset-request:ip', subject: clientAddress, max: 5, windowMs: tenMinutes },
		{ name: 'password-reset-request:email', subject: normalizedEmail, max: 5, windowMs: tenMinutes }
	];
}

export function passwordResetAttemptRateLimitBuckets(clientAddress: string): RateLimitBucket[] {
	return [
		{ name: 'password-reset-attempt:ip', subject: clientAddress, max: 20, windowMs: tenMinutes }
	];
}

export function androidPairingCreateRateLimitBuckets(
	userId: string,
	clientAddress: string
): RateLimitBucket[] {
	return [
		{ name: 'android-pairing-create:ip', subject: clientAddress, max: 10, windowMs: tenMinutes },
		{ name: 'android-pairing-create:user', subject: userId, max: 5, windowMs: tenMinutes }
	];
}

export function androidPairingExchangeRateLimitBuckets(clientAddress: string): RateLimitBucket[] {
	return [
		{ name: 'android-pairing-exchange:ip', subject: clientAddress, max: 20, windowMs: tenMinutes }
	];
}

export function androidApiPreAuthRateLimitBuckets(
	clientAddress: string,
	action: 'status' | 'import'
): RateLimitBucket[] {
	return [{ name: `android-${action}:ip`, subject: clientAddress, max: 120, windowMs: tenMinutes }];
}

export function androidApiDeviceRateLimitBuckets(
	deviceId: string,
	action: 'status' | 'import'
): RateLimitBucket[] {
	return [
		{
			name: `android-${action}:device`,
			subject: deviceId,
			max: action === 'import' ? 30 : 120,
			windowMs: tenMinutes
		}
	];
}

export async function consumeSecurityRateLimit(
	buckets: RateLimitBucket[]
): Promise<SecurityRateLimitResult> {
	if (buckets.length === 0) return { allowed: true, retryAfterSeconds: 0 };
	const now = new Date();
	const results = await db.transaction(async (tx) => {
		await tx
			.delete(passwordResetRateLimit)
			.where(sql`${passwordResetRateLimit.resetAt} <= now() - interval '1 day'`);

		const rows: { count: number; max: number; resetAt: Date }[] = [];
		const orderedBuckets = [...buckets].sort(
			(left, right) =>
				left.name.localeCompare(right.name) || left.subject.localeCompare(right.subject)
		);
		for (const bucket of orderedBuckets) {
			const keyHash = hashRateLimitKey(bucket.name, bucket.subject || 'unavailable');
			const resetAt = new Date(now.getTime() + bucket.windowMs);
			const [updated] = await tx
				.insert(passwordResetRateLimit)
				.values({ keyHash, count: 1, resetAt, updatedAt: now })
				.onConflictDoUpdate({
					target: passwordResetRateLimit.keyHash,
					set: {
						count: sql<number>`case when ${passwordResetRateLimit.resetAt} <= now() then 1 else ${passwordResetRateLimit.count} + 1 end`,
						resetAt: sql<Date>`case when ${passwordResetRateLimit.resetAt} <= now() then now() + (${bucket.windowMs} * interval '1 millisecond') else ${passwordResetRateLimit.resetAt} end`,
						updatedAt: now
					}
				})
				.returning({
					count: passwordResetRateLimit.count,
					resetAt: passwordResetRateLimit.resetAt
				});
			if (updated) rows.push({ ...updated, max: bucket.max });
		}
		return rows;
	});

	const blockedRows = results.filter((row) => row.count > row.max);
	if (blockedRows.length === 0) return { allowed: true, retryAfterSeconds: 0 };
	const retryAfterSeconds = Math.max(
		1,
		...blockedRows.map((row) => Math.ceil((row.resetAt.getTime() - Date.now()) / 1_000))
	);
	return { allowed: false, retryAfterSeconds };
}

export function twoFactorChallengeFromHeaders(headers: Headers): string {
	const cookieHeader = headers.get('cookie') ?? '';
	for (const part of cookieHeader.split(';')) {
		const separator = part.indexOf('=');
		if (separator < 1) continue;
		const name = part.slice(0, separator).trim();
		if (name === 'two_factor' || name.endsWith('.two_factor')) {
			return part.slice(separator + 1).trim() || 'missing';
		}
	}
	return 'missing';
}

function hashRateLimitKey(name: string, subject: string): string {
	return createHmac('sha256', securityRateLimitSecret())
		.update('runway-security-rate-limit-v2')
		.update('\0')
		.update(name)
		.update('\0')
		.update(subject)
		.digest('hex');
}

function securityRateLimitSecret(): string {
	const secret =
		env['AUTH_RATE_LIMIT_SECRET'] ||
		env['PASSWORD_RESET_RATE_LIMIT_SECRET'] ||
		env['BETTER_AUTH_SECRET'] ||
		(env['NODE_ENV'] === 'production' ? undefined : 'runway-dev-rate-limit-secret');
	if (!secret) throw new Error('BETTER_AUTH_SECRET is required for authentication rate limiting.');
	return secret;
}
