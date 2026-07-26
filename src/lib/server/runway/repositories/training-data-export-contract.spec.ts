import { describe, expect, test } from 'vitest';
import {
	healthConnectExportContract,
	trainingDataExportRedactions,
	trainingDataExportVersion
} from './training-data-export';

describe('training-data export Health Connect contract', () => {
	test('versions the new lifecycle sections and exposes only opaque identifier labels', () => {
		expect(trainingDataExportVersion).toBe(4);
		expect(Object.values(healthConnectExportContract.sections)).toEqual([
			'healthConnectConnections',
			'healthConnectActivities',
			'healthConnectRequestReceipts',
			'healthConnectTombstones'
		]);
		expect(Object.values(healthConnectExportContract.opaqueKeys)).toEqual([
			'recordKey',
			'originKey',
			'fingerprintKey',
			'requestKey',
			'payloadKey'
		]);
		expect(Object.values(healthConnectExportContract.opaqueKeys)).not.toContain('recordId');
		expect(trainingDataExportRedactions.at(-1)).toContain(
			'Health Connect provider record identifiers'
		);
	});
});
