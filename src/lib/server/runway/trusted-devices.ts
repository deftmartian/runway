import { and, eq, like } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { verification } from '$lib/server/db/schema';

export async function revokeTrustedDevices(userId: string): Promise<void> {
	await db
		.delete(verification)
		.where(and(eq(verification.value, userId), like(verification.identifier, 'trust-device-%')));
}

/**
 * Better Auth's generic verification table cannot declare a user foreign key:
 * most rows store challenge material, while trusted-device and in-progress 2FA
 * rows store the user id as their value. Remove every such row before deleting
 * a user so no bearer grant or opaque account identifier survives the account.
 */
export async function revokeUserVerificationRecords(userId: string): Promise<void> {
	await db.delete(verification).where(eq(verification.value, userId));
}
