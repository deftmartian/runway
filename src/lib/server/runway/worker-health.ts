export type WorkerRuntimeStatus = {
	started: boolean;
	inFlight: boolean;
	lastStartedAt: string | null;
	lastCompletedAt: string | null;
	lastFailureAt: string | null;
};

export type WorkerHealthState =
	| 'not-started'
	| 'starting'
	| 'running'
	| 'ready'
	| 'failed'
	| 'stale'
	| 'stalled';

export type WorkerHealthAssessment = {
	ready: boolean;
	state: WorkerHealthState;
	inFlightAgeSeconds: number | null;
	lastSuccessfulPassAgeSeconds: number | null;
};

export const workerSuccessFreshnessMs = 20 * 60 * 1_000;
export const workerMaximumInFlightMs = 35 * 60 * 1_000;

export function assessWorkerHealth(
	worker: WorkerRuntimeStatus,
	now = new Date()
): WorkerHealthAssessment {
	const nowMs = now.getTime();
	const startedMs = timestamp(worker.lastStartedAt);
	const completedMs = timestamp(worker.lastCompletedAt);
	const failureMs = timestamp(worker.lastFailureAt);
	const inFlightAgeSeconds =
		worker.inFlight && startedMs !== null ? ageSeconds(nowMs, startedMs) : null;
	const lastSuccessfulPassAgeSeconds = completedMs === null ? null : ageSeconds(nowMs, completedMs);

	if (!worker.started) {
		return assessment(false, 'not-started', inFlightAgeSeconds, lastSuccessfulPassAgeSeconds);
	}

	if (worker.inFlight) {
		if (startedMs === null || nowMs - startedMs > workerMaximumInFlightMs) {
			return assessment(false, 'stalled', inFlightAgeSeconds, lastSuccessfulPassAgeSeconds);
		}
		return assessment(
			true,
			completedMs === null ? 'starting' : 'running',
			inFlightAgeSeconds,
			lastSuccessfulPassAgeSeconds
		);
	}

	if (completedMs === null) {
		return assessment(
			false,
			failureMs === null ? 'starting' : 'failed',
			inFlightAgeSeconds,
			lastSuccessfulPassAgeSeconds
		);
	}
	if (failureMs !== null && failureMs > completedMs) {
		return assessment(false, 'failed', inFlightAgeSeconds, lastSuccessfulPassAgeSeconds);
	}
	if (nowMs - completedMs > workerSuccessFreshnessMs) {
		return assessment(false, 'stale', inFlightAgeSeconds, lastSuccessfulPassAgeSeconds);
	}

	return assessment(true, 'ready', inFlightAgeSeconds, lastSuccessfulPassAgeSeconds);
}

function assessment(
	ready: boolean,
	state: WorkerHealthState,
	inFlightAgeSeconds: number | null,
	lastSuccessfulPassAgeSeconds: number | null
): WorkerHealthAssessment {
	return { ready, state, inFlightAgeSeconds, lastSuccessfulPassAgeSeconds };
}

function timestamp(value: string | null): number | null {
	if (value === null) return null;
	const parsed = Date.parse(value);
	return Number.isFinite(parsed) ? parsed : null;
}

function ageSeconds(nowMs: number, timestampMs: number): number {
	return Math.max(0, Math.floor((nowMs - timestampMs) / 1_000));
}
