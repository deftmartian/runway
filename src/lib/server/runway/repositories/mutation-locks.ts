import { eq } from 'drizzle-orm';
import { user as authUser } from '$lib/server/db/schema';
import type { RunwayTransaction } from './transaction';

/**
 * Serialize activity-derived mutations for one account. This coarse lock keeps
 * link, unlink, feedback, import, and deletion transactions from observing and
 * applying consequences from the same stale activity state.
 */
export async function lockActivityOwner(tx: RunwayTransaction, userId: string): Promise<void> {
	const [owner] = await tx
		.select({ id: authUser.id })
		.from(authUser)
		.where(eq(authUser.id, userId))
		.limit(1)
		.for('update');
	if (!owner) throw new Error('Account not found.');
}
