import { randomUUID } from 'node:crypto';
import { and, eq } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { importOperationLease } from '$lib/server/db/schema';

const leaseDurationMs = 2 * 60 * 1_000;

export type ImportOperation =
	| 'manual-gpx'
	| 'browser-folder-gpx'
	| 'share-target-gpx'
	| 'nextcloud-connect'
	| 'nextcloud-test'
	| 'nextcloud-sync';

export class ImportOperationBusyError extends Error {
	constructor(readonly retryAfterSeconds: number) {
		super('Another import operation is already running.');
		this.name = 'ImportOperationBusyError';
	}
}

export async function withUserImportOperationLease<T>(
	userId: string,
	operation: ImportOperation,
	work: () => Promise<T>
): Promise<T> {
	const claim = await claimImportOperationLease(userId, operation);
	if (!claim.acquired) throw new ImportOperationBusyError(claim.retryAfterSeconds);
	try {
		return await work();
	} finally {
		// A crashed or temporarily disconnected database cannot leave a permanent
		// lock: the next claimant may take over after the bounded expiry.
		await releaseImportOperationLease(userId, claim.token).catch(() => undefined);
	}
}

async function claimImportOperationLease(
	userId: string,
	operation: ImportOperation
): Promise<{ acquired: true; token: string } | { acquired: false; retryAfterSeconds: number }> {
	const token = randomUUID();
	return db.transaction(async (tx) => {
		const now = new Date();
		const expiresAt = new Date(now.getTime() + leaseDurationMs);
		await tx
			.insert(importOperationLease)
			.values({ userId, token, operation, expiresAt })
			.onConflictDoNothing();

		const [current] = await tx
			.select({
				token: importOperationLease.token,
				expiresAt: importOperationLease.expiresAt
			})
			.from(importOperationLease)
			.where(eq(importOperationLease.userId, userId))
			.limit(1)
			.for('update');
		if (!current) throw new Error('Import operation lease could not be created.');
		if (current.token !== token && current.expiresAt.getTime() > now.getTime()) {
			return {
				acquired: false as const,
				retryAfterSeconds: Math.max(
					1,
					Math.ceil((current.expiresAt.getTime() - now.getTime()) / 1_000)
				)
			};
		}
		if (current.token !== token) {
			await tx
				.update(importOperationLease)
				.set({ token, operation, expiresAt, updatedAt: now })
				.where(eq(importOperationLease.userId, userId));
		}
		return { acquired: true as const, token };
	});
}

async function releaseImportOperationLease(userId: string, token: string): Promise<void> {
	await db
		.delete(importOperationLease)
		.where(and(eq(importOperationLease.userId, userId), eq(importOperationLease.token, token)));
}
