import { describe, expect, test } from 'vitest';
import {
	assessWorkerHealth,
	workerMaximumInFlightMs,
	workerSuccessFreshnessMs,
	type WorkerRuntimeStatus
} from './worker-health';

const now = new Date('2026-07-18T12:00:00.000Z');
const base: WorkerRuntimeStatus = {
	started: true,
	inFlight: false,
	lastStartedAt: '2026-07-18T11:55:00.000Z',
	lastCompletedAt: '2026-07-18T11:56:00.000Z',
	lastFailureAt: null
};

describe('worker health', () => {
	test('reports a recent successful pass as ready', () => {
		expect(assessWorkerHealth(base, now)).toEqual({
			ready: true,
			state: 'ready',
			inFlightAgeSeconds: null,
			lastSuccessfulPassAgeSeconds: 240
		});
	});

	test('allows a bounded initial pass to finish', () => {
		expect(
			assessWorkerHealth(
				{
					...base,
					inFlight: true,
					lastStartedAt: '2026-07-18T11:50:00.000Z',
					lastCompletedAt: null
				},
				now
			)
		).toMatchObject({ ready: true, state: 'starting', inFlightAgeSeconds: 600 });
	});

	test('reports an active pass as running even while recovering from a prior failure', () => {
		expect(
			assessWorkerHealth(
				{
					...base,
					inFlight: true,
					lastFailureAt: '2026-07-18T11:58:00.000Z',
					lastStartedAt: '2026-07-18T11:59:00.000Z'
				},
				now
			)
		).toMatchObject({ ready: true, state: 'running' });
	});

	test('fails when an in-flight pass exceeds the maximum age', () => {
		const startedAt = new Date(now.getTime() - workerMaximumInFlightMs - 1_000).toISOString();
		expect(
			assessWorkerHealth({ ...base, inFlight: true, lastStartedAt: startedAt }, now)
		).toMatchObject({ ready: false, state: 'stalled' });
	});

	test('fails after a pass failure until a later pass completes', () => {
		expect(
			assessWorkerHealth({ ...base, lastFailureAt: '2026-07-18T11:59:00.000Z' }, now)
		).toMatchObject({ ready: false, state: 'failed' });
	});

	test('fails when successful work is stale', () => {
		const completedAt = new Date(now.getTime() - workerSuccessFreshnessMs - 1_000).toISOString();
		expect(assessWorkerHealth({ ...base, lastCompletedAt: completedAt }, now)).toMatchObject({
			ready: false,
			state: 'stale'
		});
	});

	test('fails closed for impossible or invalid worker state', () => {
		expect(assessWorkerHealth({ ...base, started: false }, now).state).toBe('not-started');
		expect(
			assessWorkerHealth({ ...base, inFlight: true, lastStartedAt: 'not-a-date' }, now).state
		).toBe('stalled');
	});
});
