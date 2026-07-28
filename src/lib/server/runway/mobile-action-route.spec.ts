import { beforeEach, describe, expect, test, vi } from 'vitest';

const dependencies = vi.hoisted(() => ({
	authenticate: vi.fn(),
	claim: vi.fn(),
	complete: vi.fn(),
	consumeRateLimit: vi.fn(),
	isAction: vi.fn(),
	mobileRateLimitBuckets: vi.fn(),
	nextcloudRateLimitBuckets: vi.fn(),
	readBody: vi.fn(),
	runAction: vi.fn()
}));

vi.mock('$lib/server/runway/mobile-api', () => ({
	authenticateMobileRequest: dependencies.authenticate
}));
vi.mock('$lib/server/runway/mobile-mutations', () => ({
	isMobileActionName: dependencies.isAction,
	runMobileAction: dependencies.runAction
}));
vi.mock('$lib/server/runway/mobile-request-receipts', () => ({
	claimMobileRequest: dependencies.claim,
	completeMobileRequest: dependencies.complete
}));
vi.mock('$lib/server/runway/bounded-request-body', () => ({
	readBoundedRequestBody: dependencies.readBody
}));
vi.mock('$lib/server/runway/security-rate-limit', () => ({
	consumeSecurityRateLimit: dependencies.consumeRateLimit,
	mobileMutationRateLimitBuckets: dependencies.mobileRateLimitBuckets,
	nextcloudImportRateLimitBuckets: dependencies.nextcloudRateLimitBuckets
}));

import { POST } from '../../../routes/api/mobile/v1/action/[action]/+server';

const session = { user: { id: 'runner-1' } };

function requireRecord(value: unknown): Record<string, unknown> {
	if (typeof value !== 'object' || value === null || Array.isArray(value)) {
		throw new Error('Expected a recorded object.');
	}
	return value as Record<string, unknown>;
}

function actionEvent(request: Request) {
	return {
		request,
		params: { action: 'record-feedback' },
		getClientAddress: () => '192.0.2.25'
	} as Parameters<typeof POST>[0];
}

describe('native action endpoint', () => {
	beforeEach(() => {
		dependencies.authenticate.mockReset();
		dependencies.claim.mockReset();
		dependencies.complete.mockReset();
		dependencies.consumeRateLimit.mockReset();
		dependencies.isAction.mockReset();
		dependencies.mobileRateLimitBuckets.mockReset();
		dependencies.nextcloudRateLimitBuckets.mockReset();
		dependencies.readBody.mockReset();
		dependencies.runAction.mockReset();
		dependencies.authenticate.mockResolvedValue(session);
		dependencies.consumeRateLimit.mockResolvedValue({ allowed: true, retryAfterSeconds: 0 });
		dependencies.isAction.mockReturnValue(true);
		dependencies.mobileRateLimitBuckets.mockReturnValue([]);
		dependencies.nextcloudRateLimitBuckets.mockReturnValue([]);
	});

	test('requires a UUID Idempotency-Key before reading or claiming a mutation', async () => {
		const response = await POST(
			actionEvent(
				new Request('https://runway.example.test/api/mobile/v1/action/record-feedback', {
					method: 'POST',
					headers: { authorization: 'Bearer session' },
					body: JSON.stringify({ outcome: 'completed' })
				})
			)
		);

		expect(response.status).toBe(400);
		await expect(response.json()).resolves.toMatchObject({ error: 'invalid_request_id' });
		expect(dependencies.readBody).not.toHaveBeenCalled();
		expect(dependencies.claim).not.toHaveBeenCalled();
	});

	test('returns the stored response rather than running a replayed action twice', async () => {
		dependencies.readBody.mockResolvedValue({
			result: 'ok',
			buffer: Buffer.from('{"outcome":"completed"}')
		});
		dependencies.claim.mockResolvedValue({
			result: 'replay',
			response: { status: 201, body: { ok: true, activityId: 'activity-1' } }
		});

		const response = await POST(
			actionEvent(
				new Request('https://runway.example.test/api/mobile/v1/action/record-feedback', {
					method: 'POST',
					headers: {
						authorization: 'Bearer session',
						'content-type': 'application/json',
						'idempotency-key': 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
					},
					body: JSON.stringify({ outcome: 'completed' })
				})
			)
		);

		expect(response.status).toBe(201);
		expect(response.headers.get('idempotency-replayed')).toBe('true');
		await expect(response.json()).resolves.toEqual({ ok: true, activityId: 'activity-1' });
		expect(dependencies.runAction).not.toHaveBeenCalled();
		expect(dependencies.complete).not.toHaveBeenCalled();
	});

	test('returns a terminal uncertainty result for an expired receipt without rerunning it', async () => {
		dependencies.readBody.mockResolvedValue({
			result: 'ok',
			buffer: Buffer.from('{"outcome":"completed"}')
		});
		dependencies.claim.mockResolvedValue({
			result: 'recovered',
			response: {
				status: 409,
				body: {
					ok: false,
					error: 'request_outcome_unknown',
					message:
						'The connection ended before the result was recorded. Refresh before making another change.'
				}
			}
		});

		const response = await POST(
			actionEvent(
				new Request('https://runway.example.test/api/mobile/v1/action/record-feedback', {
					method: 'POST',
					headers: {
						authorization: 'Bearer session',
						'content-type': 'application/json',
						'idempotency-key': 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
					},
					body: JSON.stringify({ outcome: 'completed' })
				})
			)
		);

		expect(response.status).toBe(409);
		expect(response.headers.get('idempotency-recovered')).toBe('true');
		await expect(response.json()).resolves.toMatchObject({ error: 'request_outcome_unknown' });
		expect(dependencies.runAction).not.toHaveBeenCalled();
		expect(dependencies.complete).not.toHaveBeenCalled();
	});

	test('stores a terminal uncertainty result when an action throws, so a retry cannot run it twice', async () => {
		dependencies.readBody.mockResolvedValue({
			result: 'ok',
			buffer: Buffer.from('{"outcome":"completed"}')
		});
		dependencies.claim
			.mockResolvedValueOnce({
				result: 'claimed',
				payloadHash: '12be39fdea3651a12e0975c46c3d4981ae2def5381bc7b330109e36221bb43c4'
			})
			.mockResolvedValueOnce({
				result: 'replay',
				response: {
					status: 409,
					body: {
						ok: false,
						error: 'request_outcome_unknown',
						message: 'The change could not be confirmed. Refresh before trying again.'
					}
				}
			});
		dependencies.runAction.mockRejectedValue(new Error('database connection ended'));
		dependencies.complete.mockResolvedValue(undefined);

		const request = () =>
			POST(
				actionEvent(
					new Request('https://runway.example.test/api/mobile/v1/action/record-feedback', {
						method: 'POST',
						headers: {
							authorization: 'Bearer session',
							'content-type': 'application/json',
							'idempotency-key': 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
						},
						body: JSON.stringify({ outcome: 'completed' })
					})
				)
			);
		const response = await request();
		const replay = await request();

		expect(response.status).toBe(409);
		expect(replay.status).toBe(409);
		expect(replay.headers.get('idempotency-replayed')).toBe('true');
		expect(dependencies.runAction).toHaveBeenCalledTimes(1);
		const completion = requireRecord(dependencies.complete.mock.calls[0]?.[0] as unknown);
		const completionResponse = requireRecord(completion['response']);
		const completionBody = requireRecord(completionResponse['body']);
		expect(completionResponse['status']).toBe(409);
		expect(completionBody['error']).toBe('request_outcome_unknown');
	});

	test.each(['conflict', 'processing'] as const)(
		'never runs an action after a receipt %s response',
		async (result) => {
			dependencies.readBody.mockResolvedValue({
				result: 'ok',
				buffer: Buffer.from('{"outcome":"completed"}')
			});
			dependencies.claim.mockResolvedValue({ result });

			const response = await POST(
				actionEvent(
					new Request('https://runway.example.test/api/mobile/v1/action/record-feedback', {
						method: 'POST',
						headers: {
							authorization: 'Bearer session',
							'content-type': 'application/json',
							'idempotency-key': 'f47ac10b-58cc-4372-a567-0e02b2c3d479'
						},
						body: JSON.stringify({ outcome: 'completed' })
					})
				)
			);

			expect(response.status).toBe(409);
			expect(dependencies.runAction).not.toHaveBeenCalled();
			expect(dependencies.complete).not.toHaveBeenCalled();
		}
	);
});
