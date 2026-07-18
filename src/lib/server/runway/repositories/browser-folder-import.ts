import { eq, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { athleteProfile } from '$lib/server/db/schema';
import type { ParsedGpxActivity } from '$lib/training/types';
import { recordImportedActivityInTransaction } from './activity-mutations';

export async function getBrowserFolderImportGenerations(userId: string): Promise<{
	activity: number;
	folder: number;
}> {
	const [profile] = await db
		.select({
			activity: athleteProfile.activityImportGeneration,
			folder: athleteProfile.browserFolderGeneration
		})
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return profile ?? { activity: 0, folder: 0 };
}

export async function revokeBrowserFolderImports(userId: string): Promise<number> {
	const [profile] = await db
		.update(athleteProfile)
		.set({
			browserFolderGeneration: sql`${athleteProfile.browserFolderGeneration} + 1`,
			updatedAt: new Date()
		})
		.where(eq(athleteProfile.userId, userId))
		.returning({ generation: athleteProfile.browserFolderGeneration });
	// A pre-onboarding account has no importable activity state to revoke.
	return profile?.generation ?? 0;
}

export async function recordBrowserFolderImportedActivity(
	userId: string,
	fileHash: string,
	parsed: ParsedGpxActivity,
	expectedActivityGeneration: number,
	expectedFolderGeneration: number
) {
	return db.transaction(async (tx) => {
		const [profile] = await tx
			.select({
				activity: athleteProfile.activityImportGeneration,
				folder: athleteProfile.browserFolderGeneration
			})
			.from(athleteProfile)
			.where(eq(athleteProfile.userId, userId))
			.limit(1)
			.for('update');
		if (profile?.activity !== expectedActivityGeneration) {
			throw new Error('Import was cancelled because activity data was deleted.');
		}
		if (profile.folder !== expectedFolderGeneration) {
			throw new Error('Import was cancelled because the browser folder was disconnected.');
		}
		return recordImportedActivityInTransaction(
			tx,
			userId,
			fileHash,
			parsed,
			{ mode: 'unlinked' },
			expectedActivityGeneration
		);
	});
}
