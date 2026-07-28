import { beforeEach, describe, expect, test, vi } from 'vitest';

const dependencies = vi.hoisted(() => ({
	authenticate: vi.fn(),
	consumeRateLimit: vi.fn(),
	createPairing: vi.fn(),
	normalizeLabel: vi.fn(),
	rateLimitBuckets: vi.fn(),
	readBody: vi.fn()
}));

vi.mock('$lib/server/runway/mobile-api', () => ({
	authenticateMobileRequest: dependencies.authenticate
}));
vi.mock('$lib/server/runway/security-rate-limit', () => ({
	consumeSecurityRateLimit: dependencies.consumeRateLimit,
	androidPairingCreateRateLimitBuckets: dependencies.rateLimitBuckets
}));
vi.mock('$lib/server/runway/android-devices', () => ({
	createAndroidPairingRequest: dependencies.createPairing,
	normalizeAndroidDeviceLabel: dependencies.normalizeLabel
}));
vi.mock('$lib/server/runway/bounded-request-body', () => ({
	readBoundedRequestBody: dependencies.readBody
}));

import { POST } from '../../../routes/api/mobile/v1/android/pairing/+server';

const session = { user: { id: 'runner-1' } };

function event(request: Request) {
	return { request, getClientAddress: () => '192.0.2.25' } as Parameters<typeof POST>[0];
}

function validRequest(body = '{"label":"  Andrew’s phone  "}') {
	return new Request('https://runway.example.test/api/mobile/v1/android/pairing', {
		method: 'POST',
		headers: {
			authorization: 'Bearer session',
			'x-runway-client': 'runway-android/2',
			'content-type': 'application/json'
		},
		body
	});
}

describe('native pairing-code endpoint', () => {
	beforeEach(() => {
		dependencies.authenticate.mockReset();
		dependencies.consumeRateLimit.mockReset();
		dependencies.createPairing.mockReset();
		dependencies.normalizeLabel.mockReset();
		dependencies.rateLimitBuckets.mockReset();
		dependencies.readBody.mockReset();
		dependencies.authenticate.mockResolvedValue(session);
		dependencies.consumeRateLimit.mockResolvedValue({ allowed: true, retryAfterSeconds: 0 });
		dependencies.rateLimitBuckets.mockReturnValue([]);
		dependencies.normalizeLabel.mockImplementation((label: string) =>
			label.trim().replace(/\s+/g, ' ')
		);
		dependencies.readBody.mockResolvedValue({
			result: 'ok',
			buffer: Buffer.from('{"label":"  Andrew’s phone  "}')
		});
		dependencies.createPairing.mockResolvedValue({
			code: 'ABCD-1234-EF56-7890',
			expiresAt: new Date('2026-07-28T15:00:00.000Z')
		});
	});

	test('requires a native bearer session before it creates a code', async () => {
		dependencies.authenticate.mockResolvedValue(null);
		const response = await POST(event(validRequest()));

		expect(response.status).toBe(401);
		expect(dependencies.createPairing).not.toHaveBeenCalled();
	});

	test('rate-limits code creation by authenticated user and client address', async () => {
		dependencies.consumeRateLimit.mockResolvedValue({ allowed: false, retryAfterSeconds: 71 });
		const response = await POST(event(validRequest()));

		expect(dependencies.rateLimitBuckets).toHaveBeenCalledWith('runner-1', '192.0.2.25');
		expect(response.status).toBe(429);
		expect(response.headers.get('retry-after')).toBe('71');
		expect(dependencies.createPairing).not.toHaveBeenCalled();
	});

	test('rejects invalid device labels before it creates a code', async () => {
		dependencies.normalizeLabel.mockReturnValue(null);
		const response = await POST(event(validRequest()));

		expect(response.status).toBe(400);
		await expect(response.json()).resolves.toMatchObject({ error: 'validation' });
		expect(dependencies.createPairing).not.toHaveBeenCalled();
	});

	test('creates a no-store, short-lived code without creating an import credential', async () => {
		const response = await POST(event(validRequest()));

		expect(dependencies.createPairing).toHaveBeenCalledWith('runner-1');
		expect(response.headers.get('cache-control')).toBe('private, no-store');
		await expect(response.json()).resolves.toEqual({
			ok: true,
			code: 'ABCD-1234-EF56-7890',
			expiresAt: '2026-07-28T15:00:00.000Z',
			label: 'Andrew’s phone'
		});
	});
});
