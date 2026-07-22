import { and, asc, eq, isNull, or, sql } from 'drizzle-orm';
import { db } from '$lib/server/db';
import { importSource } from '$lib/server/db/schema';
import { purgeExpiredAuditEvents } from './audit-retention';
import { syncNextcloudSource } from './import-sources';
import { purgeExpiredOperationalRecords } from './operational-retention';

const workerIntervalMs = 5 * 60 * 1000;
export const importWorkerUserConcurrency = 3;

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
		await runScheduledMaintenanceTasks();
		globalThis.runwayImportWorkerLastCompletedAt = new Date().toISOString();
	} catch {
		globalThis.runwayImportWorkerLastFailureAt = new Date().toISOString();
		console.error('Scheduled maintenance pass failed; the worker will retry.');
	} finally {
		globalThis.runwayImportWorkerInFlight = false;
	}
}

export type ScheduledMaintenanceTasks = {
	syncImports: () => Promise<unknown>;
	purgeAuditEvents: () => Promise<unknown>;
	purgeOperationalRecords: () => Promise<unknown>;
};

export async function runScheduledMaintenanceTasks(
	tasks: ScheduledMaintenanceTasks = {
		syncImports: syncDueNextcloudSourcesOnce,
		purgeAuditEvents: purgeExpiredAuditEvents,
		purgeOperationalRecords: purgeExpiredOperationalRecords
	}
): Promise<void> {
	const results = await Promise.allSettled([
		tasks.syncImports(),
		tasks.purgeAuditEvents(),
		tasks.purgeOperationalRecords()
	]);
	if (results.some((task) => task.status === 'rejected')) {
		throw new Error('One or more scheduled maintenance tasks failed.');
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

	const failedByUser = await mapWithBoundedConcurrency(
		groupImportSourcesByUser(sources),
		importWorkerUserConcurrency,
		async (userSources) => {
			let failures = 0;
			// One account owns one import-operation lease, so its sources remain
			// ordered while unrelated accounts can make progress independently.
			for (const source of userSources) {
				try {
					const result = await syncNextcloudSource(source.userId, source.id);
					if (result.status === 'failed') failures += 1;
				} catch {
					failures += 1;
				}
			}
			return failures;
		}
	);
	const failedSources = failedByUser.reduce((sum, count) => sum + count, 0);
	if (failedSources > 0)
		throw new Error('One or more scheduled import sources could not be synced.');
}

export function groupImportSourcesByUser<T extends { userId: string }>(
	sources: readonly T[]
): T[][] {
	const groups = new Map<string, T[]>();
	for (const source of sources) {
		const group = groups.get(source.userId) ?? [];
		group.push(source);
		groups.set(source.userId, group);
	}
	return [...groups.values()];
}

export async function mapWithBoundedConcurrency<T, R>(
	items: readonly T[],
	concurrency: number,
	work: (item: T, index: number) => Promise<R>
): Promise<R[]> {
	if (!Number.isInteger(concurrency) || concurrency < 1) {
		throw new Error('Worker concurrency must be a positive integer.');
	}
	const results = new Array<R>(items.length);
	let nextIndex = 0;
	async function worker(): Promise<void> {
		while (nextIndex < items.length) {
			const index = nextIndex;
			nextIndex += 1;
			const item = items[index] as T;
			results[index] = await work(item, index);
		}
	}
	await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, () => worker()));
	return results;
}
