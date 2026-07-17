import { asc, inArray, lt } from 'drizzle-orm';
import { env } from '$env/dynamic/private';
import { db } from '$lib/server/db';
import { auditEvent } from '$lib/server/db/schema';

export const defaultAuditRetentionDays = 365;
export const auditPurgeBatchSize = 500;
const maxAuditRetentionDays = 3_650;

export type AuditRetentionPolicy =
	| { enabled: true; retentionDays: number }
	| { enabled: false; retentionDays: null };

export function readAuditRetentionPolicy(
	configuredValue = process.env['AUDIT_EVENT_RETENTION_DAYS'] ?? env['AUDIT_EVENT_RETENTION_DAYS']
): AuditRetentionPolicy {
	const value = configuredValue?.trim().toLowerCase() ?? '';
	if (!value) return { enabled: true, retentionDays: defaultAuditRetentionDays };
	if (value === 'disabled') return { enabled: false, retentionDays: null };
	if (!/^\d+$/.test(value)) throw invalidRetentionConfiguration();

	const retentionDays = Number(value);
	if (retentionDays < 1 || retentionDays > maxAuditRetentionDays) {
		throw invalidRetentionConfiguration();
	}
	return { enabled: true, retentionDays };
}

export function auditRetentionCutoff(now: Date, retentionDays: number): Date {
	return new Date(now.getTime() - retentionDays * 24 * 60 * 60 * 1_000);
}

export async function purgeExpiredAuditEvents(now = new Date()): Promise<{
	enabled: boolean;
	retentionDays: number | null;
	deleted: number;
}> {
	const policy = readAuditRetentionPolicy();
	if (!policy.enabled) return { ...policy, deleted: 0 };

	const expired = await db
		.select({ id: auditEvent.id })
		.from(auditEvent)
		.where(lt(auditEvent.createdAt, auditRetentionCutoff(now, policy.retentionDays)))
		.orderBy(asc(auditEvent.createdAt), asc(auditEvent.id))
		.limit(auditPurgeBatchSize);
	if (expired.length === 0) return { ...policy, deleted: 0 };

	const deleted = await db
		.delete(auditEvent)
		.where(
			inArray(
				auditEvent.id,
				expired.map((record) => record.id)
			)
		)
		.returning({ id: auditEvent.id });
	return { ...policy, deleted: deleted.length };
}

function invalidRetentionConfiguration(): Error {
	return new Error('AUDIT_EVENT_RETENTION_DAYS must be an integer from 1 to 3650, or disabled.');
}
