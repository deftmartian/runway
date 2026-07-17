import { createHash, randomBytes } from 'node:crypto';
import { hashPassword } from 'better-auth/crypto';
import { and, eq, gt, isNull, like, sql } from 'drizzle-orm';
import { env } from '$env/dynamic/private';
import { db } from '$lib/server/db';
import {
	account,
	auditEvent,
	passwordResetToken,
	session,
	user,
	verification
} from '$lib/server/db/schema';
import { isEmailConfigured, sendPasswordResetEmail } from '$lib/server/runway/email';
import {
	consumeSecurityRateLimit,
	passwordResetRequestRateLimitBuckets
} from '$lib/server/runway/security-rate-limit';

const tokenBytes = 32;
const tokenExpiryMinutes = 30;
const tokenHashDomain = 'runway-password-reset-v1';
const minimumResetResponseMs = 350;

export type PasswordResetRequestResult =
	| 'sent_or_unknown'
	| 'email_not_configured'
	| 'rate_limited';
export type PasswordResetResult = 'reset' | 'invalid_or_expired';

export async function requestPasswordReset(
	email: string,
	requestOrigin: string,
	clientAddress: string
) {
	const startedAt = Date.now();
	if (!(await allowPasswordResetRequest(email, clientAddress))) {
		return 'rate_limited' satisfies PasswordResetRequestResult;
	}
	if (!isEmailConfigured()) {
		await settleMinimumResetResponseTime(startedAt);
		return 'email_not_configured' satisfies PasswordResetRequestResult;
	}

	const record = await findCredentialUser(email);
	if (!record) {
		await settleMinimumResetResponseTime(startedAt);
		return 'sent_or_unknown' satisfies PasswordResetRequestResult;
	}

	const token = randomBytes(tokenBytes).toString('base64url');
	const tokenHash = hashResetToken(token);
	const expiresAt = new Date(Date.now() + tokenExpiryMinutes * 60 * 1000);
	const resetUrl = buildResetUrl(token, requestOrigin);

	await db.transaction(async (tx) => {
		const now = new Date();
		await tx
			.update(passwordResetToken)
			.set({ usedAt: now })
			.where(
				and(
					eq(passwordResetToken.userId, record.userId),
					isNull(passwordResetToken.usedAt),
					gt(passwordResetToken.expiresAt, now)
				)
			);

		await tx.insert(passwordResetToken).values({
			userId: record.userId,
			tokenHash,
			expiresAt
		});

		await tx.insert(auditEvent).values({
			userId: record.userId,
			eventType: 'password_reset.requested',
			detail: { delivery: 'email' }
		});
	});

	void deliverPasswordResetEmail(record.userId, tokenHash, record.email, resetUrl);

	await settleMinimumResetResponseTime(startedAt);
	return 'sent_or_unknown' satisfies PasswordResetRequestResult;
}

export async function resetPasswordWithToken(token: string, newPassword: string) {
	const tokenHash = hashResetToken(token);
	const now = new Date();

	return db.transaction(async (tx) => {
		const [claimed] = await tx
			.update(passwordResetToken)
			.set({ usedAt: now })
			.where(
				and(
					eq(passwordResetToken.tokenHash, tokenHash),
					isNull(passwordResetToken.usedAt),
					gt(passwordResetToken.expiresAt, now)
				)
			)
			.returning();

		if (!claimed) return 'invalid_or_expired' satisfies PasswordResetResult;

		const password = await hashPassword(newPassword);
		const [updatedAccount] = await tx
			.update(account)
			.set({ password, updatedAt: now })
			.where(and(eq(account.userId, claimed.userId), eq(account.providerId, 'credential')))
			.returning({ id: account.id });

		if (!updatedAccount) return 'invalid_or_expired' satisfies PasswordResetResult;

		await tx.delete(session).where(eq(session.userId, claimed.userId));
		await tx
			.delete(verification)
			.where(
				and(eq(verification.value, claimed.userId), like(verification.identifier, 'trust-device-%'))
			);
		await tx.insert(auditEvent).values({
			userId: claimed.userId,
			eventType: 'password_reset.completed',
			detail: { sessionsRevoked: true, trustedDevicesRevoked: true }
		});

		return 'reset' satisfies PasswordResetResult;
	});
}

export async function isPasswordResetTokenUsable(token: string): Promise<boolean> {
	const tokenHash = hashResetToken(token);
	const [record] = await db
		.select({ id: passwordResetToken.id })
		.from(passwordResetToken)
		.where(
			and(
				eq(passwordResetToken.tokenHash, tokenHash),
				isNull(passwordResetToken.usedAt),
				gt(passwordResetToken.expiresAt, new Date())
			)
		)
		.limit(1);
	return Boolean(record);
}

export function hashResetToken(token: string): string {
	return createHash('sha256').update(tokenHashDomain).update('\0').update(token).digest('hex');
}

export async function allowPasswordResetRequest(
	email: string,
	clientAddress: string
): Promise<boolean> {
	const result = await consumeSecurityRateLimit(
		passwordResetRequestRateLimitBuckets(email, clientAddress)
	);
	return result.allowed;
}

function logPasswordResetDeliveryFailure(error: unknown): void {
	const record = error as {
		name?: unknown;
		code?: unknown;
		command?: unknown;
		responseCode?: unknown;
	};

	console.error('Password reset email delivery failed.', {
		name: typeof record.name === 'string' ? record.name : 'Error',
		code: typeof record.code === 'string' ? record.code : undefined,
		command: typeof record.command === 'string' ? record.command : undefined,
		responseCode: typeof record.responseCode === 'number' ? record.responseCode : undefined
	});
}

async function deliverPasswordResetEmail(
	userId: string,
	tokenHash: string,
	email: string,
	resetUrl: string
): Promise<void> {
	try {
		await sendPasswordResetEmail({ to: email, resetUrl });
	} catch (error) {
		await markResetTokenDeliveryFailed(userId, tokenHash);
		logPasswordResetDeliveryFailure(error);
	}
}

async function markResetTokenDeliveryFailed(userId: string, tokenHash: string): Promise<void> {
	const now = new Date();
	await db.transaction(async (tx) => {
		await tx
			.update(passwordResetToken)
			.set({ usedAt: now })
			.where(and(eq(passwordResetToken.tokenHash, tokenHash), isNull(passwordResetToken.usedAt)));
		await tx.insert(auditEvent).values({
			userId,
			eventType: 'password_reset.delivery_failed',
			detail: { delivery: 'email' }
		});
	});
}

async function settleMinimumResetResponseTime(startedAt: number): Promise<void> {
	const remainingMs = minimumResetResponseMs - (Date.now() - startedAt);
	if (remainingMs > 0) {
		await new Promise((resolve) => setTimeout(resolve, remainingMs));
	}
}

function buildResetUrl(token: string, requestOrigin: string): string {
	const origin = env['PUBLIC_APP_ORIGIN'] || env['ORIGIN'] || requestOrigin;
	const url = new URL('/login/reset-password', origin);
	url.searchParams.set('token', token);
	return url.href;
}

async function findCredentialUser(email: string) {
	const normalized = email.trim().toLowerCase();
	if (!normalized) return null;

	const [record] = await db
		.select({
			userId: user.id,
			email: user.email
		})
		.from(user)
		.innerJoin(account, and(eq(account.userId, user.id), eq(account.providerId, 'credential')))
		.where(sql`lower(${user.email}) = ${normalized}`)
		.limit(1);

	return record ?? null;
}
