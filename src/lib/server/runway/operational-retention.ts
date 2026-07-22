import { and, asc, eq, inArray, lt } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { androidImportRequest, passwordResetToken } from '$lib/server/db/schema';

export const operationalRecordRetentionDays = 30;
export const operationalPurgeBatchSize = 500;

export function operationalRetentionCutoff(now: Date): Date {
	return new Date(now.getTime() - operationalRecordRetentionDays * 24 * 60 * 60 * 1_000);
}

export async function purgeExpiredOperationalRecords(now = new Date()): Promise<{
	passwordResetTokens: number;
	androidImportRequests: number;
}> {
	const cutoff = operationalRetentionCutoff(now);
	const [expiredPasswordTokens, expiredAndroidRequests] = await Promise.all([
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
			.limit(operationalPurgeBatchSize)
	]);

	const [deletedPasswordTokens, deletedAndroidRequests] = await Promise.all([
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
					.returning({ id: androidImportRequest.id })
	]);

	return {
		passwordResetTokens: deletedPasswordTokens.length,
		androidImportRequests: deletedAndroidRequests.length
	};
}
