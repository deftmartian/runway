import { beforeEach, describe, expect, test, vi } from 'vitest';

const dependencies = vi.hoisted(() => ({
	authenticate: vi.fn(),
	consumeRateLimit: vi.fn(),
	rateLimitBuckets: vi.fn(),
	prepareExport: vi.fn(),
	openBody: vi.fn(),
	cleanup: vi.fn()
}));

vi.mock('$lib/server/runway/mobile-api', () => ({
	authenticateMobileRequest: dependencies.authenticate
}));
vi.mock('$lib/server/runway/security-rate-limit', () => ({
	accountSecurityRateLimitBuckets: dependencies.rateLimitBuckets,
	consumeSecurityRateLimit: dependencies.consumeRateLimit
}));
vi.mock('$lib/server/runway/repositories/training-data-export', () => ({
	prepareUserDataExport: dependencies.prepareExport
}));

import { POST } from '../../../routes/api/mobile/v1/account/export/+server';
import { isAndroidNativeApiRequest } from './request-security';

function exportEvent() {
	const url = new URL('https://runway.example.test/api/mobile/v1/account/export');
	return {
		request: new Request(url, {
			method: 'POST',
			headers: {
				authorization: 'Bearer opaque-native-session',
				'content-type': 'application/json',
				'x-runway-client': 'runway-android/2'
			},
			body: '{}'
		}),
		url,
		getClientAddress: () => '192.0.2.25'
	} as Parameters<typeof POST>[0] & { __createdAt?: Date };
}

describe('native account export route', () => {
	beforeEach(() => {
		for (const dependency of Object.values(dependencies)) dependency.mockReset();
		dependencies.authenticate.mockImplementation((request: Request) => ({
			user: { id: 'runner-1' },
			session: {
				id: 'native-session',
				createdAt:
					request.headers.get('x-test-stale') === 'true'
						? new Date(Date.now() - 11 * 60 * 1_000)
						: new Date(),
				mobileClientId: 'runway-android'
			}
		}));
		dependencies.consumeRateLimit.mockResolvedValue({ allowed: true, retryAfterSeconds: 0 });
		dependencies.rateLimitBuckets.mockReturnValue([]);
		dependencies.openBody.mockReturnValue(
			new ReadableStream({
				start(controller) {
					controller.enqueue(new TextEncoder().encode('{"version":4}'));
					controller.close();
				}
			})
		);
		dependencies.prepareExport.mockResolvedValue({
			byteLength: 13,
			openBody: dependencies.openBody,
			cleanup: dependencies.cleanup
		});
	});

	test('passes the originless Android hook boundary before streaming the staged export', async () => {
		const event = exportEvent();
		expect(isAndroidNativeApiRequest(event.request, event.url.pathname)).toBe(true);

		const response = await POST(event);

		expect(response.status).toBe(200);
		expect(response.headers.get('cache-control')).toBe('private, no-store');
		expect(response.headers.get('content-length')).toBe('13');
		expect(response.headers.get('content-disposition')).toContain('runway-training-data.json');
		expect(await response.text()).toBe('{"version":4}');
		expect(dependencies.prepareExport).toHaveBeenCalledWith('runner-1');
		expect(dependencies.openBody).toHaveBeenCalledTimes(1);
	});

	test('does not qualify for the hook exception without the JSON request contract', () => {
		const event = exportEvent();
		event.request.headers.delete('content-type');

		expect(isAndroidNativeApiRequest(event.request, event.url.pathname)).toBe(false);
	});

	test('rejects stale sessions and rate-limited exports before staging data', async () => {
		const staleEvent = exportEvent();
		staleEvent.request.headers.set('x-test-stale', 'true');
		const stale = await POST(staleEvent);
		expect(stale.status).toBe(403);
		expect(dependencies.prepareExport).not.toHaveBeenCalled();

		dependencies.consumeRateLimit.mockResolvedValue({
			allowed: false,
			retryAfterSeconds: 91
		});
		const limited = await POST(exportEvent());
		expect(limited.status).toBe(429);
		expect(limited.headers.get('retry-after')).toBe('91');
		expect(dependencies.prepareExport).not.toHaveBeenCalled();
	});
});
