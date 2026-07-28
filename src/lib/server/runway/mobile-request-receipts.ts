import { createHash } from 'node:crypto';
import { and, eq, lt } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { mobileRequestReceipt } from '$lib/server/db/schema';

export type MobileReceiptResponse = {
	status: number;
	body: Record<string, unknown>;
};

export type MobileRequestClaim =
	| { result: 'claimed'; payloadHash: string }
	| { result: 'replay'; response: MobileReceiptResponse }
	| { result: 'recovered'; response: MobileReceiptResponse }
	| { result: 'conflict' }
	| { result: 'processing' };

/**
 * A receipt is a safety boundary, not a job queue. When the process that owns
 * a mutation disappears, we cannot prove whether it committed before dying.
 * Bound the wait, then terminally record that uncertainty; never run the
 * mutation a second time under the same idempotency key.
 */
export const mobileRequestProcessingLeaseMs = 5 * 60 * 1_000;

const uncertainOutcomeResponse: MobileReceiptResponse = {
	status: 409,
	body: {
		ok: false,
		error: 'request_outcome_unknown',
		message:
			'The connection ended before the result was recorded. Refresh before making another change.'
	}
};

export async function claimMobileRequest(input: {
	userId: string;
	requestId: string;
	action: string;
	payload: Buffer;
	now?: Date;
}): Promise<MobileRequestClaim> {
	const now = input.now ?? new Date();
	const payloadHash = createHash('sha256').update(input.payload).digest('hex');
	const [created] = await db
		.insert(mobileRequestReceipt)
		.values({
			userId: input.userId,
			requestId: input.requestId,
			action: input.action,
			payloadHash,
			createdAt: now,
			updatedAt: now
		})
		.onConflictDoNothing()
		.returning({ id: mobileRequestReceipt.id });
	if (created) return { result: 'claimed', payloadHash };

	const [existing] = await db
		.select({
			action: mobileRequestReceipt.action,
			payloadHash: mobileRequestReceipt.payloadHash,
			state: mobileRequestReceipt.state,
			responseStatus: mobileRequestReceipt.responseStatus,
			responseBody: mobileRequestReceipt.responseBody,
			updatedAt: mobileRequestReceipt.updatedAt
		})
		.from(mobileRequestReceipt)
		.where(
			and(
				eq(mobileRequestReceipt.userId, input.userId),
				eq(mobileRequestReceipt.requestId, input.requestId)
			)
		)
		.limit(1);
	if (!existing) return { result: 'processing' };
	if (existing.action !== input.action || existing.payloadHash !== payloadHash) {
		return { result: 'conflict' };
	}
	if (
		existing.state === 'completed' &&
		existing.responseStatus !== null &&
		existing.responseBody !== null
	) {
		return {
			result: 'replay',
			response: {
				status: existing.responseStatus,
				body: existing.responseBody
			}
		};
	}
	if (existing.state !== 'processing') return { result: 'processing' };

	const staleBefore = new Date(now.getTime() - mobileRequestProcessingLeaseMs);
	if (existing.updatedAt >= staleBefore) return { result: 'processing' };

	// This compare-and-set is deliberately a terminal recovery, not a lease
	// handoff. Re-running a stale in-flight mutation could create a second run,
	// feedback record, or plan adjustment after an ambiguous process failure.
	const [recovered] = await db
		.update(mobileRequestReceipt)
		.set({
			state: 'completed',
			responseStatus: uncertainOutcomeResponse.status,
			responseBody:
				uncertainOutcomeResponse.body as (typeof mobileRequestReceipt.$inferInsert)['responseBody'],
			completedAt: now,
			updatedAt: now
		})
		.where(
			and(
				eq(mobileRequestReceipt.userId, input.userId),
				eq(mobileRequestReceipt.requestId, input.requestId),
				eq(mobileRequestReceipt.action, input.action),
				eq(mobileRequestReceipt.payloadHash, payloadHash),
				eq(mobileRequestReceipt.state, 'processing'),
				lt(mobileRequestReceipt.updatedAt, staleBefore)
			)
		)
		.returning({ id: mobileRequestReceipt.id });
	if (recovered) return { result: 'recovered', response: uncertainOutcomeResponse };
	return { result: 'processing' };
}

export async function completeMobileRequest(input: {
	userId: string;
	requestId: string;
	payloadHash: string;
	response: MobileReceiptResponse;
}): Promise<void> {
	const completedAt = new Date();
	const responseBody = JSON.parse(
		JSON.stringify(input.response.body)
	) as (typeof mobileRequestReceipt.$inferInsert)['responseBody'];
	const [completed] = await db
		.update(mobileRequestReceipt)
		.set({
			state: 'completed',
			responseStatus: input.response.status,
			responseBody,
			completedAt,
			updatedAt: completedAt
		})
		.where(
			and(
				eq(mobileRequestReceipt.userId, input.userId),
				eq(mobileRequestReceipt.requestId, input.requestId),
				eq(mobileRequestReceipt.payloadHash, input.payloadHash),
				eq(mobileRequestReceipt.state, 'processing')
			)
		)
		.returning({ id: mobileRequestReceipt.id });
	if (!completed) throw new Error('Native request receipt could not be finalized.');
}
