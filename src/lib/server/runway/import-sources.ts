import { and, eq, inArray, lt, ne, or, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activityDeletionTombstone,
	activityImport,
	athleteProfile,
	importSource,
	importSourceItem,
	user as authUser
} from '$lib/server/db/schema';
import { hashActivityFile, parseGpx } from '$lib/training/gpx';
import {
	recordNextcloudImportedActivity,
	type NextcloudImportClaim
} from './repositories/activity-mutations';
import type { RunwayTransaction } from './repositories/transaction';
import {
	downloadNextcloudFile,
	isNextcloudAuthenticationRejection,
	listNextcloudGpxFiles,
	maxNextcloudVisibleGpxFiles,
	parseNextcloudShareUrl,
	type NextcloudRemoteFile
} from './nextcloud';
import { sealSecret, secretBlindIndex } from './secrets';
import { withUserImportOperationLease } from './import-operation-lease';

const maxRemoteGpxBytes = 10 * 1024 * 1024;
const staleImportClaimMs = 30 * 60 * 1000;
const maxSourceLabelCharacters = 120;
const maxShareUrlCharacters = 2_048;
const maxSharePasswordCharacters = 1_024;
export const maxImportSourcesPerUser = 10;
export const importSourceLimitMessage = `You can connect up to ${maxImportSourcesPerUser} import folders. Disconnect one before adding another.`;
export const importTimeZoneRequiredMessage = 'Set training time zone before importing.';

export type NextcloudSourceInput = {
	label: string;
	shareUrl: string;
	sharePassword: string;
};

export type NextcloudSyncResult =
	| { status: 'imported'; message: string }
	| { status: 'empty'; message: string }
	| { status: 'skipped'; message: string }
	| { status: 'failed'; message: string };

export type KnownNextcloudSourceItem = {
	remoteKey: string;
	etag: string | null;
	contentLength: number | null;
	lastModifiedAt: Date | null;
	status: string;
	lastCheckedAt: Date;
};

export async function listImportSources(userId: string) {
	return db
		.select({
			id: importSource.id,
			label: importSource.label,
			enabled: importSource.enabled,
			lastCheckedAt: importSource.lastCheckedAt,
			lastSuccessAt: importSource.lastSuccessAt,
			lastImportedAt: importSource.lastImportedAt,
			lastError: importSource.lastError
		})
		.from(importSource)
		.where(eq(importSource.userId, userId))
		.orderBy(importSource.createdAt, importSource.id)
		.limit(maxImportSourcesPerUser);
}

export async function isImportTimeZoneConfigured(userId: string): Promise<boolean> {
	const [profile] = await db
		.select({ timeZone: athleteProfile.timeZone })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	return Boolean(profile?.timeZone?.trim());
}

export async function saveNextcloudSource(userId: string, input: NextcloudSourceInput) {
	return withUserImportOperationLease(userId, 'nextcloud-connect', () =>
		saveNextcloudSourceUnlocked(userId, input)
	);
}

async function saveNextcloudSourceUnlocked(userId: string, input: NextcloudSourceInput) {
	await requireImportTimeZone(userId);
	const normalized = normalizeNextcloudSourceInput(input);
	const parsed = parseNextcloudShareUrl(normalized.shareUrl);
	const { label, sharePassword } = normalized;
	if (!sharePassword) throw new Error('Enter the share password.');
	const { shareTokenSecret, sharePasswordSecret } = await verifyNextcloudShareCredentials(
		parsed,
		sharePassword
	);
	const shareTokenKey = secretBlindIndex(
		`nextcloud-share-token:${userId}:${parsed.shareHost}`,
		parsed.shareToken
	);

	const source = await db.transaction(async (tx) => {
		await lockImportSourceOwner(tx, userId);
		const [[existing], [sourceCount]] = await Promise.all([
			tx
				.select({ id: importSource.id })
				.from(importSource)
				.where(
					and(
						eq(importSource.userId, userId),
						eq(importSource.shareHost, parsed.shareHost),
						eq(importSource.shareTokenKey, shareTokenKey)
					)
				)
				.limit(1),
			tx
				.select({ count: sql<number>`count(*)::int` })
				.from(importSource)
				.where(eq(importSource.userId, userId))
		]);
		enforceImportSourceLimit(Boolean(existing), sourceCount?.count ?? 0);
		const [saved] = await tx
			.insert(importSource)
			.values({
				userId,
				type: 'nextcloud_share',
				label,
				shareHost: parsed.shareHost,
				shareTokenSecret,
				shareTokenKey,
				sharePasswordSecret,
				enabled: true,
				lastCheckedAt: new Date(),
				lastSuccessAt: new Date(),
				lastError: null
			})
			.onConflictDoUpdate({
				target: [importSource.userId, importSource.shareHost, importSource.shareTokenKey],
				set: {
					label,
					shareTokenSecret,
					sharePasswordSecret,
					enabled: true,
					lastCheckedAt: new Date(),
					lastSuccessAt: new Date(),
					lastError: null,
					updatedAt: new Date()
				}
			})
			.returning();
		return saved;
	});

	if (!source) throw new Error('Nextcloud source could not be saved.');
	return source;
}

export async function verifyNextcloudShareCredentials(
	parsed: ReturnType<typeof parseNextcloudShareUrl>,
	sharePassword: string
): Promise<{ shareTokenSecret: string; sharePasswordSecret: string }> {
	const [shareTokenSecret, sharePasswordSecret] = await Promise.all([
		sealSecret(parsed.shareToken),
		sealSecret(sharePassword)
	]);
	await assertShareRequiresPassword(parsed, shareTokenSecret, sharePassword);
	await listNextcloudGpxFiles({
		shareHost: parsed.shareHost,
		shareTokenSecret,
		sharePasswordSecret
	});
	return { shareTokenSecret, sharePasswordSecret };
}

async function assertShareRequiresPassword(
	parsed: ReturnType<typeof parseNextcloudShareUrl>,
	shareTokenSecret: string,
	sharePassword: string
): Promise<void> {
	try {
		await listNextcloudGpxFiles({
			shareHost: parsed.shareHost,
			shareTokenSecret,
			sharePasswordSecret: await sealSecret(`${sharePassword}\0runway-password-check`)
		});
	} catch (error) {
		if (isNextcloudAuthenticationRejection(error)) return;
		throw error;
	}
	throw new Error('Nextcloud share must require the password.');
}

export function normalizeNextcloudSourceInput(input: NextcloudSourceInput): NextcloudSourceInput {
	const label = input.label.trim() || 'Nextcloud GPX folder';
	const shareUrl = input.shareUrl.trim();
	const sharePassword = input.sharePassword;

	if (label.length > maxSourceLabelCharacters) {
		throw new Error('Nextcloud folder label is too long.');
	}
	if (!shareUrl) throw new Error('Enter the Nextcloud share URL.');
	if (shareUrl.length > maxShareUrlCharacters) {
		throw new Error('Nextcloud share URL is too long.');
	}
	if (!sharePassword) throw new Error('Enter the share password.');
	if (sharePassword.length > maxSharePasswordCharacters) {
		throw new Error('Nextcloud share password is too long.');
	}

	return { label, shareUrl, sharePassword };
}

export function enforceImportSourceLimit(existingSource: boolean, sourceCount: number): void {
	if (!existingSource && sourceCount >= maxImportSourcesPerUser) {
		throw new Error(importSourceLimitMessage);
	}
}

export async function testNextcloudSource(userId: string, sourceId: string) {
	return withUserImportOperationLease(userId, 'nextcloud-test', () =>
		testNextcloudSourceUnlocked(userId, sourceId)
	);
}

async function testNextcloudSourceUnlocked(userId: string, sourceId: string) {
	const source = await getOwnedSource(userId, sourceId);
	await requireImportTimeZone(userId);
	if (!source.enabled || !source.sharePasswordSecret) {
		throw new Error('That import source is not connected.');
	}
	const files = await listNextcloudGpxFiles(source);
	await markSourceSuccess(source.id, null);
	return {
		count: files.length
	};
}

export async function disconnectImportSource(userId: string, sourceId: string) {
	const source = await db.transaction(async (tx) => {
		await lockImportSourceOwner(tx, userId);
		const [deleted] = await tx
			.delete(importSource)
			.where(and(eq(importSource.userId, userId), eq(importSource.id, sourceId)))
			.returning({ id: importSource.id });
		return deleted;
	});
	if (!source) throw new Error('Import source was not found.');
}

export async function syncNextcloudSource(
	userId: string,
	sourceId: string
): Promise<NextcloudSyncResult> {
	return withUserImportOperationLease(userId, 'nextcloud-sync', () =>
		syncNextcloudSourceUnlocked(userId, sourceId)
	);
}

async function syncNextcloudSourceUnlocked(
	userId: string,
	sourceId: string
): Promise<NextcloudSyncResult> {
	const source = await getOwnedSource(userId, sourceId);
	if (!source.enabled || !source.sharePasswordSecret) {
		return { status: 'failed', message: 'That import source is not connected.' };
	}
	if (!(await isImportTimeZoneConfigured(userId))) {
		await markSourceFailure(source.id, importTimeZoneRequiredMessage);
		return { status: 'failed', message: importTimeZoneRequiredMessage };
	}
	const [generationRecord] = await db
		.select({ generation: athleteProfile.activityImportGeneration })
		.from(athleteProfile)
		.where(eq(athleteProfile.userId, userId))
		.limit(1);
	const importGeneration = generationRecord?.generation ?? 0;

	try {
		const files = await listNextcloudGpxFiles(source);
		const candidate = await newestRemoteCandidate(userId, source.id, files);
		if (!candidate.file) {
			await markSourceSuccess(source.id, null);
			return candidate.alreadyHandled
				? { status: 'skipped', message: 'All visible GPX files were already handled.' }
				: { status: 'empty', message: 'No new GPX files are visible in that folder.' };
		}

		const buffer = await downloadNewest(source, candidate.file);
		const contentHash = hashActivityFile(buffer, userId);
		const claimed = await claimSourceItem(userId, source.id, candidate.file, contentHash);
		if (!claimed) {
			await markSourceSuccess(source.id, null);
			return { status: 'skipped', message: 'That GPX file was already handled.' };
		}

		if (await alreadyHandledContent(userId, contentHash, claimed.itemId)) {
			await markSourceItemImported(userId, claimed, null);
			await markSourceSuccess(source.id, null);
			return { status: 'skipped', message: 'That GPX file was already handled.' };
		}

		let parsed;
		try {
			parsed = parseGpx(buffer);
		} catch {
			await markSourceItemFailed(userId, claimed, 'The selected GPX file could not be parsed.');
			await markSourceFailure(source.id, 'The selected GPX file could not be parsed.');
			return { status: 'failed', message: 'The selected GPX file could not be parsed.' };
		}

		try {
			await recordNextcloudImportedActivity(
				userId,
				{
					sourceId: source.id,
					itemId: claimed.itemId,
					contentHash: claimed.contentHash,
					claimedAt: claimed.claimedAt
				},
				parsed,
				importGeneration
			);
		} catch (error) {
			if (isDuplicateActivityError(error)) {
				await markSourceItemImported(userId, claimed, null);
				await markSourceSuccess(source.id, null);
				return { status: 'skipped', message: 'That GPX file was already handled.' };
			}
			if (error instanceof Error && error.message === importTimeZoneRequiredMessage) {
				await releaseSourceItemClaim(userId, claimed);
				await markSourceFailure(source.id, importTimeZoneRequiredMessage);
				return { status: 'failed', message: importTimeZoneRequiredMessage };
			}
			if (
				error instanceof Error &&
				error.message === 'Import was cancelled because activity data was deleted.'
			) {
				await releaseSourceItemClaim(userId, claimed);
				return {
					status: 'skipped',
					message: 'Activity data was deleted while this import was running.'
				};
			}
			if (
				error instanceof Error &&
				(error.message === 'Import was cancelled because the source was disconnected.' ||
					error.message === 'Import source claim changed before completion.')
			) {
				return {
					status: 'skipped',
					message: 'The import folder was disconnected while this import was running.'
				};
			}

			await markSourceItemFailed(userId, claimed, 'The selected GPX file could not be imported.');
			await markSourceFailure(source.id, 'The selected GPX file could not be imported.');
			return { status: 'failed', message: 'The selected GPX file could not be imported.' };
		}

		return {
			status: 'imported',
			message: `Imported ${Math.round((parsed.distanceMeters / 1000) * 10) / 10} km to the activity inbox for review.`
		};
	} catch (error) {
		if (!(await isConnectedImportSource(userId, sourceId))) {
			return {
				status: 'skipped',
				message: 'The import folder was disconnected while this import was running.'
			};
		}
		const message = safeSyncError(error);
		await markSourceFailure(source.id, message);
		return { status: 'failed', message };
	}
}

async function isConnectedImportSource(userId: string, sourceId: string): Promise<boolean> {
	const [source] = await db
		.select({ id: importSource.id })
		.from(importSource)
		.where(
			and(
				eq(importSource.id, sourceId),
				eq(importSource.userId, userId),
				eq(importSource.enabled, true)
			)
		)
		.limit(1);
	return Boolean(source);
}

function safeSyncError(error: unknown): string {
	const message = error instanceof Error ? error.message : '';
	const knownMessages = new Set([
		'Nextcloud share could not be reached.',
		importTimeZoneRequiredMessage,
		'Nextcloud source credentials could not be opened.',
		'Nextcloud share password was rejected.',
		'Nextcloud share must require the password.',
		'Nextcloud share folder was not found.',
		'Nextcloud share could not be read.',
		'Nextcloud share returned an invalid WebDAV response.',
		'Nextcloud response is too large.',
		'The selected GPX file is too large for import.',
		`Nextcloud folders can expose at most ${maxNextcloudVisibleGpxFiles} GPX files per sync.`,
		'The selected GPX file could not be parsed.',
		'The selected GPX file could not be imported.'
	]);
	return knownMessages.has(message) ? message : 'Nextcloud share could not be synced.';
}

async function requireImportTimeZone(userId: string): Promise<void> {
	if (!(await isImportTimeZoneConfigured(userId))) throw new Error(importTimeZoneRequiredMessage);
}

async function getOwnedSource(userId: string, sourceId: string) {
	const [source] = await db
		.select()
		.from(importSource)
		.where(and(eq(importSource.userId, userId), eq(importSource.id, sourceId)))
		.limit(1);
	if (!source) throw new Error('Import source was not found.');
	return source;
}

async function newestRemoteCandidate(
	userId: string,
	sourceId: string,
	files: NextcloudRemoteFile[]
): Promise<{ file: NextcloudRemoteFile | null; alreadyHandled: boolean }> {
	const newestFirst = [...files].sort((a, b) => {
		const modifiedDelta = (b.lastModifiedAt?.getTime() ?? 0) - (a.lastModifiedAt?.getTime() ?? 0);
		if (modifiedDelta !== 0) return modifiedDelta;
		return a.href.localeCompare(b.href);
	});

	if (newestFirst.length === 0) return { file: null, alreadyHandled: false };

	const remoteKeys = newestFirst.map((file) => nextcloudRemoteKey(userId, sourceId, file.href));
	const knownItems = await db
		.select({
			remoteKey: importSourceItem.remoteKey,
			etag: importSourceItem.etag,
			contentLength: importSourceItem.contentLength,
			lastModifiedAt: importSourceItem.lastModifiedAt,
			status: importSourceItem.status,
			lastCheckedAt: importSourceItem.lastCheckedAt
		})
		.from(importSourceItem)
		.where(
			and(
				eq(importSourceItem.userId, userId),
				eq(importSourceItem.sourceId, sourceId),
				inArray(importSourceItem.remoteKey, remoteKeys)
			)
		)
		.limit(maxNextcloudVisibleGpxFiles);

	return selectNewestRemoteCandidate(newestFirst, knownItems, (file) =>
		nextcloudRemoteKey(userId, sourceId, file.href)
	);
}

export function selectNewestRemoteCandidate(
	newestFirst: NextcloudRemoteFile[],
	knownItems: KnownNextcloudSourceItem[],
	remoteKeyForFile: (file: NextcloudRemoteFile) => string,
	now = Date.now()
): { file: NextcloudRemoteFile | null; alreadyHandled: boolean } {
	const knownByKey = new Map(knownItems.map((item) => [item.remoteKey, item]));

	for (const file of newestFirst) {
		const known = knownByKey.get(remoteKeyForFile(file));
		if (!known) return { file, alreadyHandled: false };
		if (known.status === 'failed') {
			if (compareRemoteRevision(file, known) === 'changed') {
				return { file, alreadyHandled: false };
			}
			continue;
		}
		if (known.status === 'importing') {
			if (now - known.lastCheckedAt.getTime() > staleImportClaimMs) {
				return { file, alreadyHandled: false };
			}
			return { file: null, alreadyHandled: true };
		}
		if (known.status !== 'imported' || compareRemoteRevision(file, known) !== 'same') {
			return { file, alreadyHandled: false };
		}
	}

	return { file: null, alreadyHandled: true };
}

function compareRemoteRevision(
	file: NextcloudRemoteFile,
	known: KnownNextcloudSourceItem
): 'same' | 'changed' | 'unknown' {
	if (file.etag !== null || known.etag !== null) {
		return file.etag !== null && known.etag !== null && file.etag === known.etag
			? 'same'
			: 'changed';
	}

	let compared = false;
	if (file.contentLength !== null || known.contentLength !== null) {
		compared = true;
		if (
			file.contentLength === null ||
			known.contentLength === null ||
			file.contentLength !== known.contentLength
		) {
			return 'changed';
		}
	}
	if (file.lastModifiedAt !== null || known.lastModifiedAt !== null) {
		compared = true;
		if (file.lastModifiedAt?.getTime() !== known.lastModifiedAt?.getTime()) {
			return 'changed';
		}
	}
	return compared ? 'same' : 'unknown';
}

async function alreadyHandledContent(
	userId: string,
	contentHash: string,
	claimedItemId: string
): Promise<boolean> {
	const [existing] = await db
		.select({ id: activityImport.id })
		.from(activityImport)
		.where(and(eq(activityImport.userId, userId), eq(activityImport.fileHash, contentHash)))
		.limit(1);
	if (existing) return true;

	const [deletionTombstone] = await db
		.select({ id: activityDeletionTombstone.id })
		.from(activityDeletionTombstone)
		.where(
			and(
				eq(activityDeletionTombstone.userId, userId),
				eq(activityDeletionTombstone.fileHash, contentHash)
			)
		)
		.limit(1);
	if (deletionTombstone) return true;

	const [sourceItemTombstone] = await db
		.select({ id: importSourceItem.id })
		.from(importSourceItem)
		.where(
			and(
				eq(importSourceItem.userId, userId),
				eq(importSourceItem.contentHash, contentHash),
				eq(importSourceItem.status, 'imported'),
				ne(importSourceItem.id, claimedItemId)
			)
		)
		.limit(1);
	return Boolean(sourceItemTombstone);
}

async function downloadNewest(
	source: Awaited<ReturnType<typeof getOwnedSource>>,
	file: NextcloudRemoteFile
) {
	if (file.contentLength !== null && file.contentLength > maxRemoteGpxBytes) {
		throw new Error('The selected GPX file is too large for import.');
	}
	const buffer = await downloadNextcloudFile(source, file);
	if (buffer.byteLength > maxRemoteGpxBytes) {
		throw new Error('The selected GPX file is too large for import.');
	}
	return buffer;
}

async function claimSourceItem(
	userId: string,
	sourceId: string,
	file: NextcloudRemoteFile,
	contentHash: string
): Promise<NextcloudImportClaim | null> {
	const remoteKey = nextcloudRemoteKey(userId, sourceId, file.href);
	const claimedAt = new Date();
	const [existing] = await db
		.select({
			id: importSourceItem.id,
			status: importSourceItem.status,
			lastCheckedAt: importSourceItem.lastCheckedAt,
			contentHash: importSourceItem.contentHash
		})
		.from(importSourceItem)
		.where(
			and(
				eq(importSourceItem.userId, userId),
				eq(importSourceItem.sourceId, sourceId),
				eq(importSourceItem.remoteKey, remoteKey)
			)
		)
		.limit(1);

	if (existing) {
		if (existing.status === 'importing' && !isStaleImportClaim(existing.lastCheckedAt)) return null;
		if (existing.status === 'imported' && existing.contentHash === contentHash) {
			await db
				.update(importSourceItem)
				.set({
					etag: file.etag,
					contentLength: file.contentLength,
					lastModifiedAt: file.lastModifiedAt,
					lastCheckedAt: claimedAt,
					errorSummary: null
				})
				.where(and(eq(importSourceItem.id, existing.id), eq(importSourceItem.status, 'imported')));
			return null;
		}

		const staleBefore = new Date(Date.now() - staleImportClaimMs);
		const [claimed] = await db
			.update(importSourceItem)
			.set({
				etag: file.etag,
				contentLength: file.contentLength,
				lastModifiedAt: file.lastModifiedAt,
				contentHash,
				status: 'importing',
				activityId: null,
				importedAt: null,
				lastCheckedAt: claimedAt,
				errorSummary: null
			})
			.where(
				and(
					eq(importSourceItem.id, existing.id),
					or(
						eq(importSourceItem.status, 'failed'),
						eq(importSourceItem.status, 'imported'),
						lt(importSourceItem.lastCheckedAt, staleBefore)
					)
				)
			)
			.returning({
				id: importSourceItem.id,
				contentHash: importSourceItem.contentHash,
				claimedAt: importSourceItem.lastCheckedAt
			});
		return claimed?.contentHash
			? {
					sourceId,
					itemId: claimed.id,
					contentHash: claimed.contentHash,
					claimedAt: claimed.claimedAt
				}
			: null;
	}

	const [claimed] = await db
		.insert(importSourceItem)
		.values({
			userId,
			sourceId,
			remoteKey,
			etag: file.etag,
			contentLength: file.contentLength,
			lastModifiedAt: file.lastModifiedAt,
			contentHash,
			status: 'importing',
			lastCheckedAt: claimedAt
		})
		.onConflictDoNothing()
		.returning({
			id: importSourceItem.id,
			contentHash: importSourceItem.contentHash,
			claimedAt: importSourceItem.lastCheckedAt
		});

	return claimed?.contentHash
		? {
				sourceId,
				itemId: claimed.id,
				contentHash: claimed.contentHash,
				claimedAt: claimed.claimedAt
			}
		: null;
}

function nextcloudRemoteKey(userId: string, sourceId: string, href: string): string {
	return secretBlindIndex(`nextcloud-remote-item:${userId}:${sourceId}`, href);
}

async function markSourceItemImported(
	userId: string,
	claim: NextcloudImportClaim,
	activityId: string | null
) {
	await db
		.update(importSourceItem)
		.set({
			status: 'imported',
			activityId,
			importedAt: new Date(),
			lastCheckedAt: new Date(),
			errorSummary: null
		})
		.where(sourceItemClaimCondition(userId, claim));
}

function isStaleImportClaim(lastCheckedAt: Date): boolean {
	return Date.now() - lastCheckedAt.getTime() > staleImportClaimMs;
}

function isDuplicateActivityError(error: unknown): boolean {
	return (
		error instanceof Error &&
		(error.message === 'This activity file has already been imported.' ||
			error.message === 'This workout already has an imported activity.')
	);
}

async function markSourceItemFailed(userId: string, claim: NextcloudImportClaim, message: string) {
	await db
		.update(importSourceItem)
		.set({
			status: 'failed',
			lastCheckedAt: new Date(),
			errorSummary: message.slice(0, 240)
		})
		.where(sourceItemClaimCondition(userId, claim));
}

async function releaseSourceItemClaim(userId: string, claim: NextcloudImportClaim) {
	await db.delete(importSourceItem).where(sourceItemClaimCondition(userId, claim));
}

function sourceItemClaimCondition(userId: string, claim: NextcloudImportClaim) {
	return and(
		eq(importSourceItem.id, claim.itemId),
		eq(importSourceItem.userId, userId),
		eq(importSourceItem.sourceId, claim.sourceId),
		eq(importSourceItem.status, 'importing'),
		eq(importSourceItem.contentHash, claim.contentHash),
		eq(importSourceItem.lastCheckedAt, claim.claimedAt)
	);
}

async function markSourceSuccess(sourceId: string, importedAt: Date | null) {
	await db
		.update(importSource)
		.set({
			lastCheckedAt: new Date(),
			lastSuccessAt: new Date(),
			...(importedAt ? { lastImportedAt: importedAt } : {}),
			lastError: null,
			updatedAt: new Date()
		})
		.where(eq(importSource.id, sourceId));
}

async function markSourceFailure(sourceId: string, message: string) {
	await db
		.update(importSource)
		.set({
			lastCheckedAt: new Date(),
			lastError: message.slice(0, 240),
			updatedAt: new Date()
		})
		.where(eq(importSource.id, sourceId));
}

async function lockImportSourceOwner(tx: RunwayTransaction, userId: string): Promise<void> {
	const [owner] = await tx
		.select({ id: authUser.id })
		.from(authUser)
		.where(eq(authUser.id, userId))
		.limit(1)
		.for('update');
	if (!owner) throw new Error('Account not found.');
}
