import { and, eq, like } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { verification } from '$lib/server/db/schema';

export async function revokeTrustedDevices(userId: string): Promise<void> {
	await db
		.delete(verification)
		.where(and(eq(verification.value, userId), like(verification.identifier, 'trust-device-%')));
}
