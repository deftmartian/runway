import { asc, desc, eq } from 'drizzle-orm';
import { db } from '$lib/server/db';
import {
	activity,
	activityDeletionTombstone,
	activityImport,
	androidDevice,
	androidImportRequest,
	athleteProfile,
	auditEvent,
	goal,
	importSource,
	importSourceItem,
	planAdjustment,
	trainingPlan,
	trainingWeek,
	user as authUser,
	workout,
	workoutFeedback
} from '$lib/server/db/schema';
import {
	stageJsonArtifact,
	stageThenRecordSuccess,
	type JsonSink,
	type PageReader,
	type StagedJsonArtifact,
	writePagedJsonArray
} from '$lib/server/runway/staged-json-export';

const exportVersion = 3;
const redactions = [
	'import source share tokens and sealed passwords',
	'import item remote paths, etags, and content hashes',
	'Android bearer token hashes and content digest keys'
] as const;

async function writePropertyName(sink: JsonSink, name: string, first = false): Promise<void> {
	await sink.write(`${first ? '' : ','}${JSON.stringify(name)}:`);
}

async function writeJsonProperty(
	sink: JsonSink,
	name: string,
	value: unknown,
	first = false
): Promise<void> {
	await writePropertyName(sink, name, first);
	const encoded = JSON.stringify(value);
	if (encoded === undefined) throw new Error(`Export property ${name} is not JSON serializable.`);
	await sink.write(encoded);
}

async function writeArrayProperty<Row>(
	sink: JsonSink,
	name: string,
	readPage: PageReader<Row>
): Promise<void> {
	await writePropertyName(sink, name);
	await writePagedJsonArray(sink, readPage);
}

async function stageUserDataSnapshot(userId: string): Promise<StagedJsonArtifact> {
	const exportedAt = new Date().toISOString();
	return stageJsonArtifact(async (sink) => {
		await db.transaction(
			async (tx) => {
				await sink.write('{');
				await writeJsonProperty(sink, 'version', exportVersion, true);
				await writeJsonProperty(sink, 'exportedAt', exportedAt);

				const [account] = await tx
					.select({
						id: authUser.id,
						name: authUser.name,
						email: authUser.email,
						createdAt: authUser.createdAt
					})
					.from(authUser)
					.where(eq(authUser.id, userId))
					.limit(1);
				await writeJsonProperty(sink, 'account', account ?? null);

				const [profile] = await tx
					.select()
					.from(athleteProfile)
					.where(eq(athleteProfile.userId, userId))
					.limit(1);
				await writeJsonProperty(sink, 'profile', profile ?? null);

				await writeArrayProperty(sink, 'goals', (offset, limit) =>
					tx
						.select()
						.from(goal)
						.where(eq(goal.userId, userId))
						.orderBy(desc(goal.createdAt), asc(goal.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'plans', (offset, limit) =>
					tx
						.select()
						.from(trainingPlan)
						.where(eq(trainingPlan.userId, userId))
						.orderBy(desc(trainingPlan.createdAt), asc(trainingPlan.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'weeks', (offset, limit) =>
					tx
						.select()
						.from(trainingWeek)
						.where(eq(trainingWeek.userId, userId))
						.orderBy(asc(trainingWeek.startDate), asc(trainingWeek.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'workouts', (offset, limit) =>
					tx
						.select()
						.from(workout)
						.where(eq(workout.userId, userId))
						.orderBy(asc(workout.scheduledDate), asc(workout.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'feedback', (offset, limit) =>
					tx
						.select()
						.from(workoutFeedback)
						.where(eq(workoutFeedback.userId, userId))
						.orderBy(desc(workoutFeedback.createdAt), asc(workoutFeedback.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'activities', (offset, limit) =>
					tx
						.select()
						.from(activity)
						.where(eq(activity.userId, userId))
						.orderBy(desc(activity.occurredAt), asc(activity.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'imports', (offset, limit) =>
					tx
						.select()
						.from(activityImport)
						.where(eq(activityImport.userId, userId))
						.orderBy(desc(activityImport.createdAt), asc(activityImport.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'adjustments', (offset, limit) =>
					tx
						.select()
						.from(planAdjustment)
						.where(eq(planAdjustment.userId, userId))
						.orderBy(desc(planAdjustment.createdAt), asc(planAdjustment.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'importSources', (offset, limit) =>
					tx
						.select({
							id: importSource.id,
							type: importSource.type,
							label: importSource.label,
							shareHost: importSource.shareHost,
							enabled: importSource.enabled,
							syncIntervalMinutes: importSource.syncIntervalMinutes,
							lastCheckedAt: importSource.lastCheckedAt,
							lastSuccessAt: importSource.lastSuccessAt,
							lastImportedAt: importSource.lastImportedAt,
							lastError: importSource.lastError,
							createdAt: importSource.createdAt,
							updatedAt: importSource.updatedAt
						})
						.from(importSource)
						.where(eq(importSource.userId, userId))
						.orderBy(asc(importSource.createdAt), asc(importSource.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'importItems', (offset, limit) =>
					tx
						.select({
							id: importSourceItem.id,
							sourceId: importSourceItem.sourceId,
							status: importSourceItem.status,
							contentLength: importSourceItem.contentLength,
							firstSeenAt: importSourceItem.firstSeenAt,
							lastCheckedAt: importSourceItem.lastCheckedAt,
							importedAt: importSourceItem.importedAt,
							errorSummary: importSourceItem.errorSummary
						})
						.from(importSourceItem)
						.where(eq(importSourceItem.userId, userId))
						.orderBy(asc(importSourceItem.firstSeenAt), asc(importSourceItem.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'androidDevices', (offset, limit) =>
					tx
						.select({
							id: androidDevice.id,
							label: androidDevice.label,
							expiresAt: androidDevice.expiresAt,
							lastSeenAt: androidDevice.lastSeenAt,
							lastImportedAt: androidDevice.lastImportedAt,
							revokedAt: androidDevice.revokedAt,
							createdAt: androidDevice.createdAt,
							updatedAt: androidDevice.updatedAt
						})
						.from(androidDevice)
						.where(eq(androidDevice.userId, userId))
						.orderBy(asc(androidDevice.createdAt), asc(androidDevice.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'androidImportRequests', (offset, limit) =>
					tx
						.select({
							id: androidImportRequest.id,
							deviceId: androidImportRequest.deviceId,
							requestId: androidImportRequest.requestId,
							state: androidImportRequest.state,
							result: androidImportRequest.result,
							reason: androidImportRequest.reason,
							createdAt: androidImportRequest.createdAt,
							completedAt: androidImportRequest.completedAt
						})
						.from(androidImportRequest)
						.where(eq(androidImportRequest.userId, userId))
						.orderBy(asc(androidImportRequest.createdAt), asc(androidImportRequest.id))
						.limit(limit)
						.offset(offset)
				);
				await writeArrayProperty(sink, 'deletionTombstones', (offset, limit) =>
					tx
						.select()
						.from(activityDeletionTombstone)
						.where(eq(activityDeletionTombstone.userId, userId))
						.orderBy(desc(activityDeletionTombstone.createdAt), asc(activityDeletionTombstone.id))
						.limit(limit)
						.offset(offset)
				);
				await writeJsonProperty(sink, 'redactions', redactions);
				await writeArrayProperty(sink, 'auditEvents', (offset, limit) =>
					tx
						.select()
						.from(auditEvent)
						.where(eq(auditEvent.userId, userId))
						.orderBy(desc(auditEvent.createdAt), asc(auditEvent.id))
						.limit(limit)
						.offset(offset)
				);
				await sink.write('}');
			},
			{ isolationLevel: 'repeatable read', accessMode: 'read only' }
		);
	});
}

export async function prepareUserDataExport(userId: string): Promise<StagedJsonArtifact> {
	return stageThenRecordSuccess(
		() => stageUserDataSnapshot(userId),
		async (artifact) => {
			await db.insert(auditEvent).values({
				userId,
				eventType: 'account.export',
				detail: { version: exportVersion, byteLength: artifact.byteLength }
			});
		}
	);
}
