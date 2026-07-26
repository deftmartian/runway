import { describe, expect, test } from 'vitest';
import {
	healthConnectConnectionPresentation,
	healthConnectRecordPresentation,
	type HealthConnectConnection,
	type HealthConnectProvenance
} from './presentation';

const currentRecord: HealthConnectProvenance = {
	mappingId: 'mapping-1',
	recordState: 'current',
	originLabel: 'Gadgetbridge',
	recordedAt: '2026-05-14T12:00:00.000Z',
	duplicateCandidate: null
};

describe('Health Connect presentation', () => {
	test('labels provenance without exposing an opaque source record ID', () => {
		expect(healthConnectRecordPresentation(currentRecord)).toEqual({
			mappingId: 'mapping-1',
			sourceLabel: 'Health Connect',
			provenanceLabel: 'Recorded by Gadgetbridge via Health Connect',
			stateNotice: null
		});
	});

	test('does not put a package identifier into product copy', () => {
		expect(
			healthConnectRecordPresentation({
				...currentRecord,
				originLabel: 'com.example.running.watch'
			}).provenanceLabel
		).toBe('Imported through Health Connect');
	});

	test.each([
		['pending_correction', 'A correction from the source is waiting for your decision.'],
		[
			'pending_source_deletion',
			'The source deleted this record. Choose whether runway should remove it too.'
		]
	] as const)('makes %s a deliberate decision', (recordState, stateNotice) => {
		expect(healthConnectRecordPresentation({ ...currentRecord, recordState }).stateNotice).toBe(
			stateNotice
		);
	});

	test('keeps a disconnected source actionable without claiming it is a web integration', () => {
		const connection: HealthConnectConnection = {
			state: 'not_connected',
			deviceLabel: null,
			lastSyncedAt: null,
			message: null
		};
		expect(healthConnectConnectionPresentation(connection)).toEqual({
			status: 'Not connected',
			detail: 'Connect from the runway Android app to import run records.'
		});
	});
});
