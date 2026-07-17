import { and, asc, eq, isNull, or, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { importSource } from '$lib/server/db/schema';
import { purgeExpiredAuditEvents } from './audit-retention';
import { syncNextcloudSource } from './import-sources';

const workerIntervalMs = 5 * 60 * 1000;

export type ImportWorkerStatus = {
	started: boolean;
	inFlight: boolean;
	lastStartedAt: string | null;
	lastCompletedAt: string | null;
	lastFailureAt: string | null;
};

declare global {
	var runwayImportWorkerStarted: boolean | undefined;
	var runwayImportWorkerInFlight: boolean | undefined;
	var runwayImportWorkerLastStartedAt: string | undefined;
	var runwayImportWorkerLastCompletedAt: string | undefined;
	var runwayImportWorkerLastFailureAt: string | undefined;
}

export function startImportSourceWorker(): void {
	if (globalThis.runwayImportWorkerStarted) return;
	globalThis.runwayImportWorkerStarted = true;

	void runImportWorkerPass();
	setInterval(() => {
		void runImportWorkerPass();
	}, workerIntervalMs).unref();
}

export function getImportWorkerStatus(): ImportWorkerStatus {
	return {
		started: globalThis.runwayImportWorkerStarted === true,
		inFlight: globalThis.runwayImportWorkerInFlight === true,
		lastStartedAt: globalThis.runwayImportWorkerLastStartedAt ?? null,
		lastCompletedAt: globalThis.runwayImportWorkerLastCompletedAt ?? null,
		lastFailureAt: globalThis.runwayImportWorkerLastFailureAt ?? null
	};
}

async function runImportWorkerPass(): Promise<void> {
	if (globalThis.runwayImportWorkerInFlight) return;
	globalThis.runwayImportWorkerInFlight = true;
	globalThis.runwayImportWorkerLastStartedAt = new Date().toISOString();
	try {
		const tasks = await Promise.allSettled([
			syncDueNextcloudSourcesOnce(),
			purgeExpiredAuditEvents()
		]);
		if (tasks.some((task) => task.status === 'rejected')) {
			throw new Error('One or more scheduled maintenance tasks failed.');
		}
		globalThis.runwayImportWorkerLastCompletedAt = new Date().toISOString();
	} catch {
		globalThis.runwayImportWorkerLastFailureAt = new Date().toISOString();
		console.error('Scheduled maintenance pass failed; the worker will retry.');
	} finally {
		globalThis.runwayImportWorkerInFlight = false;
	}
}

export async function syncDueNextcloudSources(): Promise<void> {
	if (globalThis.runwayImportWorkerInFlight) return;
	globalThis.runwayImportWorkerInFlight = true;
	try {
		await syncDueNextcloudSourcesOnce();
	} finally {
		globalThis.runwayImportWorkerInFlight = false;
	}
}

async function syncDueNextcloudSourcesOnce(): Promise<void> {
	const sources = await db
		.select({
			id: importSource.id,
			userId: importSource.userId
		})
		.from(importSource)
		.where(
			and(
				eq(importSource.enabled, true),
				or(
					isNull(importSource.lastCheckedAt),
					sql<boolean>`${importSource.lastCheckedAt} <= now() - (${importSource.syncIntervalMinutes} * interval '1 minute')`
				)
			)
		)
		.orderBy(
			sql`${importSource.lastCheckedAt} is not null`,
			asc(importSource.lastCheckedAt),
			asc(importSource.createdAt)
		)
		.limit(50);

	let failedSources = 0;
	for (const source of sources) {
		try {
			const result = await syncNextcloudSource(source.userId, source.id);
			if (result.status === 'failed') failedSources += 1;
		} catch {
			failedSources += 1;
		}
	}
	if (failedSources > 0)
		throw new Error('One or more scheduled import sources could not be synced.');
}
