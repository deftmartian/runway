import { and, asc, eq, inArray, lt } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	androidImportRequest,
	healthConnectRequestReceipt,
	mobileRequestReceipt,
	passwordResetRateLimit,
	passwordResetToken
} from '$lib/server/db/schema';

export const operationalRecordRetentionDays = 30;
export const operationalPurgeBatchSize = 500;
export const securityRateLimitRetentionGraceMs = 24 * 60 * 60 * 1_000;

export function operationalRetentionCutoff(now: Date): Date {
	return new Date(now.getTime() - operationalRecordRetentionDays * 24 * 60 * 60 * 1_000);
}

export async function purgeExpiredOperationalRecords(now = new Date()): Promise<{
	passwordResetTokens: number;
	androidImportRequests: number;
	healthConnectRequestReceipts: number;
	mobileRequestReceipts: number;
	securityRateLimits: number;
}> {
	const cutoff = operationalRetentionCutoff(now);
	const rateLimitCutoff = new Date(now.getTime() - securityRateLimitRetentionGraceMs);
	const [
		expiredPasswordTokens,
		expiredAndroidRequests,
		expiredHealthConnectReceipts,
		expiredMobileReceipts,
		expiredSecurityRateLimits
	] = await Promise.all([
		db
			.select({ id: passwordResetToken.id })
			.from(passwordResetToken)
			.where(lt(passwordResetToken.expiresAt, cutoff))
			.orderBy(asc(passwordResetToken.expiresAt), asc(passwordResetToken.id))
			.limit(operationalPurgeBatchSize),
		db
			.select({ id: androidImportRequest.id })
			.from(androidImportRequest)
			.where(
				and(
					eq(androidImportRequest.state, 'completed'),
					lt(androidImportRequest.completedAt, cutoff)
				)
			)
			.orderBy(asc(androidImportRequest.completedAt), asc(androidImportRequest.id))
			.limit(operationalPurgeBatchSize),
		db
			.select({ id: healthConnectRequestReceipt.id })
			.from(healthConnectRequestReceipt)
			.where(lt(healthConnectRequestReceipt.createdAt, cutoff))
			.orderBy(asc(healthConnectRequestReceipt.createdAt), asc(healthConnectRequestReceipt.id))
			.limit(operationalPurgeBatchSize),
		db
			.select({ id: mobileRequestReceipt.id })
			.from(mobileRequestReceipt)
			.where(lt(mobileRequestReceipt.updatedAt, cutoff))
			.orderBy(asc(mobileRequestReceipt.updatedAt), asc(mobileRequestReceipt.id))
			.limit(operationalPurgeBatchSize),
		db
			.select({ keyHash: passwordResetRateLimit.keyHash })
			.from(passwordResetRateLimit)
			.where(lt(passwordResetRateLimit.resetAt, rateLimitCutoff))
			.orderBy(asc(passwordResetRateLimit.resetAt), asc(passwordResetRateLimit.keyHash))
			.limit(operationalPurgeBatchSize)
	]);

	const [
		deletedPasswordTokens,
		deletedAndroidRequests,
		deletedHealthConnectReceipts,
		deletedMobileReceipts,
		deletedSecurityRateLimits
	] = await Promise.all([
		expiredPasswordTokens.length === 0
			? Promise.resolve([])
			: db
					.delete(passwordResetToken)
					.where(
						inArray(
							passwordResetToken.id,
							expiredPasswordTokens.map(({ id }) => id)
						)
					)
					.returning({ id: passwordResetToken.id }),
		expiredAndroidRequests.length === 0
			? Promise.resolve([])
			: db
					.delete(androidImportRequest)
					.where(
						inArray(
							androidImportRequest.id,
							expiredAndroidRequests.map(({ id }) => id)
						)
					)
					.returning({ id: androidImportRequest.id }),
		expiredHealthConnectReceipts.length === 0
			? Promise.resolve([])
			: db
					.delete(healthConnectRequestReceipt)
					.where(
						inArray(
							healthConnectRequestReceipt.id,
							expiredHealthConnectReceipts.map(({ id }) => id)
						)
					)
					.returning({ id: healthConnectRequestReceipt.id }),
		expiredMobileReceipts.length === 0
			? Promise.resolve([])
			: db
					.delete(mobileRequestReceipt)
					.where(
						inArray(
							mobileRequestReceipt.id,
							expiredMobileReceipts.map(({ id }) => id)
						)
					)
					.returning({ id: mobileRequestReceipt.id }),
		expiredSecurityRateLimits.length === 0
			? Promise.resolve([])
			: db
					.delete(passwordResetRateLimit)
					.where(
						inArray(
							passwordResetRateLimit.keyHash,
							expiredSecurityRateLimits.map(({ keyHash }) => keyHash)
						)
					)
					.returning({ keyHash: passwordResetRateLimit.keyHash })
	]);

	return {
		passwordResetTokens: deletedPasswordTokens.length,
		androidImportRequests: deletedAndroidRequests.length,
		healthConnectRequestReceipts: deletedHealthConnectReceipts.length,
		mobileRequestReceipts: deletedMobileReceipts.length,
		securityRateLimits: deletedSecurityRateLimits.length
	};
}
