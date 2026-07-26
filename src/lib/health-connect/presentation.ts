/**
 * Web-facing contract for Health Connect ingestion. The Android/server adapter
 * owns record IDs and transport; this projection intentionally exposes only
 * the provenance and decisions a runner can act on.
 */
export type HealthConnectRecordState = 'current' | 'pending_correction' | 'pending_source_deletion';

export type HealthConnectDuplicateCandidate = {
	activityId: string;
	activityDate: Date | string;
	distanceMeters: number;
	sourceLabel: string;
};

export type HealthConnectProvenance = {
	mappingId: string;
	recordState: HealthConnectRecordState;
	originLabel: string | null;
	recordedAt: Date | string | null;
	duplicateCandidate: HealthConnectDuplicateCandidate | null;
};

export type HealthConnectConnectionState =
	| 'connected'
	| 'needs_attention'
	| 'not_connected'
	| 'unavailable';

export type HealthConnectConnection = {
	state: HealthConnectConnectionState;
	deviceLabel: string | null;
	lastSyncedAt: Date | string | null;
	message: string | null;
};

export type HealthConnectRecordPresentation = {
	mappingId: string;
	sourceLabel: string;
	provenanceLabel: string;
	stateNotice: string | null;
};

export function healthConnectRecordPresentation(
	provenance: HealthConnectProvenance
): HealthConnectRecordPresentation {
	const sourceLabel = 'Health Connect';
	const originLabel = friendlyOriginLabel(provenance.originLabel);
	const provenanceLabel = originLabel
		? `Recorded by ${originLabel} via Health Connect`
		: 'Imported through Health Connect';
	const stateNotice =
		provenance.recordState === 'pending_correction'
			? 'A correction from the source is waiting for your decision.'
			: provenance.recordState === 'pending_source_deletion'
				? 'The source deleted this record. Choose whether runway should remove it too.'
				: null;
	return { mappingId: provenance.mappingId, sourceLabel, provenanceLabel, stateNotice };
}

function friendlyOriginLabel(value: string | null): string | null {
	const label = value?.trim();
	if (!label || /^[a-z\d_]+(?:\.[a-z\d_]+){1,}$/i.test(label)) return null;
	return label;
}

export function healthConnectConnectionPresentation(connection: HealthConnectConnection): {
	status: string;
	detail: string;
} {
	if (connection.state === 'connected') {
		return {
			status: 'Connected',
			detail: connection.lastSyncedAt
				? `Last sync ${formatDateTime(connection.lastSyncedAt)}`
				: 'Waiting for the first sync'
		};
	}
	if (connection.state === 'needs_attention') {
		return {
			status: 'Needs attention',
			detail: connection.message ?? 'Check the Android app permissions.'
		};
	}
	if (connection.state === 'unavailable') {
		return {
			status: 'Unavailable',
			detail: connection.message ?? 'Health Connect is not available on this device.'
		};
	}
	return {
		status: 'Not connected',
		detail: 'Connect from the runway Android app to import run records.'
	};
}

function formatDateTime(value: Date | string): string {
	return new Date(value).toLocaleString(undefined, {
		month: 'short',
		day: 'numeric',
		hour: 'numeric',
		minute: '2-digit'
	});
}
