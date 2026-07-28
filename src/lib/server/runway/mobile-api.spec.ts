import { beforeEach, describe, expect, test, vi } from 'vitest';

const authApi = vi.hoisted(() => ({
	getSession: vi.fn()
}));

vi.mock('$lib/server/auth', () => ({ auth: { api: authApi } }));

import {
	authenticateMobileRequest,
	boundedActivityId,
	boundedPlanId,
	mobileActivityEvidenceDetail,
	mobileClientHeader
} from './mobile-api';

describe('native mobile session boundary', () => {
	beforeEach(() => {
		authApi.getSession.mockReset();
	});

	test('accepts only a device-authorized Android bearer presented by the current native client', async () => {
		const session = {
			user: { id: 'runner-1' },
			session: { id: 'session-1', mobileClientId: 'runway-android' }
		};
		authApi.getSession.mockResolvedValue(session);
		const request = new Request('https://runway.example.test/api/mobile/v1/view/bootstrap', {
			headers: {
				authorization: 'Bearer better-auth-session-token',
				'x-runway-client': mobileClientHeader
			}
		});

		await expect(authenticateMobileRequest(request)).resolves.toBe(session);
		expect(authApi.getSession).toHaveBeenCalledWith({ headers: request.headers });
	});

	test('rejects a valid Better Auth bearer that did not come from Android device authorization', async () => {
		authApi.getSession.mockResolvedValue({
			user: { id: 'runner-1' },
			session: { id: 'browser-session-1', mobileClientId: null }
		});
		const request = new Request('https://runway.example.test/api/mobile/v1/view/bootstrap', {
			headers: {
				authorization: 'Bearer better-auth-browser-session-token',
				'x-runway-client': mobileClientHeader
			}
		});

		await expect(authenticateMobileRequest(request)).resolves.toBeNull();
	});

	test.each([
		['legacy background-import credential', 'Bearer rwy1_device_secret', mobileClientHeader],
		['missing bearer', null, mobileClientHeader],
		['old native client generation', 'Bearer better-auth-session-token', 'runway-android/1'],
		['unmarked caller', 'Bearer better-auth-session-token', null]
	])(
		'rejects %s before asking Better Auth for a session',
		async (_label, authorization, client) => {
			const headers = new Headers();
			if (authorization) headers.set('authorization', authorization);
			if (client) headers.set('x-runway-client', client);
			const request = new Request('https://runway.example.test/api/mobile/v1/view/bootstrap', {
				headers
			});

			await expect(authenticateMobileRequest(request)).resolves.toBeNull();
			expect(authApi.getSession).not.toHaveBeenCalled();
		}
	);
});

describe('native activity trace query boundary', () => {
	test('accepts only a bounded UUID activity identifier', () => {
		expect(boundedActivityId('6f4d8d2c-6b26-4d68-9b69-4f93b5e94923')).toBe(
			'6f4d8d2c-6b26-4d68-9b69-4f93b5e94923'
		);
		expect(boundedActivityId('other-user-activity')).toBeNull();
	});

	test('includes bounded selected evidence only in the lazy detail projection', () => {
		const detail = mobileActivityEvidenceDetail({
			id: '6f4d8d2c-6b26-4d68-9b69-4f93b5e94923',
			routeTrace: {
				version: 1,
				sourcePointCount: 601,
				points: Array.from({ length: 601 }, (_, index) => ({
					latitudeE6: index,
					longitudeE6: -index,
					elapsedSeconds: index,
					segmentIndex: 0,
					speedMetersPerSecond: null
				}))
			},
			heartRateSeries: {
				version: 1,
				sourceSampleCount: 1_001,
				points: Array.from({ length: 1_001 }, (_, index) => ({ elapsedSeconds: index, bpm: 140 }))
			},
			averageCadence: 168,
			routeSummary: { pointCount: 601, startEndRedacted: true, hasElevation: false, traceRetained: true }
		} as never);

		expect(detail).toMatchObject({
			averageCadence: 168,
			disclosure: { routeTraceRetained: true, heartRateSeriesRetained: true, startEndRedacted: true }
		});
		expect(detail?.routeTrace?.points).toHaveLength(600);
		expect(detail?.heartRateSeries?.points).toHaveLength(1_000);
	});
});

describe('native history detail query boundary', () => {
	test('accepts only a bounded UUID plan identifier', () => {
		expect(boundedPlanId('6f4d8d2c-6b26-4d68-9b69-4f93b5e94923')).toBe(
			'6f4d8d2c-6b26-4d68-9b69-4f93b5e94923'
		);
		expect(boundedPlanId('not-a-plan')).toBeNull();
		expect(boundedPlanId('6f4d8d2c-6b26-4d68-7b69-4f93b5e94923')).toBeNull();
	});
});
