import { convertSetCookieToCookie, getTestInstance } from 'better-auth/test';
import { genericOAuth } from 'better-auth/plugins';
import { afterEach, describe, expect, test, vi } from 'vitest';
import { authentikOidcConfig } from './auth-config';

const appOrigin = 'http://localhost:3147';
const issuer = 'https://auth.example.test/application/o/runway/';
const authorizationEndpoint = `${issuer}authorize/`;
const tokenEndpoint = `${issuer}token/`;
const userInfoEndpoint = `${issuer}userinfo/`;

afterEach(() => {
	vi.unstubAllGlobals();
});

describe('Authentik OAuth callback compatibility', () => {
	test('accepts code and valid state without an iss parameter', async () => {
		const requests: Request[] = [];
		stubAuthentik(requests);
		const { customFetchImpl } = await createAuth();
		const flow = await beginFlow(customFetchImpl);

		const response = await customFetchImpl(
			`${appOrigin}/api/auth/oauth2/callback/authentik?code=synthetic-code&state=${encodeURIComponent(flow.state)}`,
			{
				headers: { cookie: flow.cookie },
				redirect: 'manual'
			}
		);

		expect(response.status).toBe(302);
		expect(response.headers.get('location')).toBe(`${appOrigin}/app`);
		expect(response.headers.get('set-cookie')).toContain('better-auth.session_token=');

		const tokenRequest = requests.find((request) => request.url === tokenEndpoint);
		expect(tokenRequest).toBeDefined();
		const tokenBody = new URLSearchParams(await tokenRequest?.clone().text());
		expect(tokenBody.get('code')).toBe('synthetic-code');
		expect(tokenBody.get('code_verifier')).toMatch(/^[A-Za-z0-9_-]{43,}$/);
	});

	test('still rejects a mismatched supplied issuer before token exchange', async () => {
		const requests: Request[] = [];
		stubAuthentik(requests);
		const { customFetchImpl } = await createAuth();
		const flow = await beginFlow(customFetchImpl);
		const tokenRequestsBeforeCallback = countRequests(requests, tokenEndpoint);

		const callback = new URL(`${appOrigin}/api/auth/oauth2/callback/authentik`);
		callback.searchParams.set('code', 'synthetic-code');
		callback.searchParams.set('state', flow.state);
		callback.searchParams.set('iss', 'https://other-issuer.example.test/');
		const response = await customFetchImpl(callback, {
			headers: { cookie: flow.cookie },
			redirect: 'manual'
		});

		expect(response.status).toBe(302);
		expect(response.headers.get('location')).toBe(`${appOrigin}/login?error=issuer_mismatch`);
		expect(response.headers.get('set-cookie') ?? '').not.toContain('better-auth.session_token=');
		expect(countRequests(requests, tokenEndpoint)).toBe(tokenRequestsBeforeCallback);
	});

	test('rejects tampered state before discovery or token exchange', async () => {
		const requests: Request[] = [];
		stubAuthentik(requests);
		const { customFetchImpl } = await createAuth();
		const flow = await beginFlow(customFetchImpl);
		const providerRequestsBeforeCallback = requests.length;

		const response = await customFetchImpl(
			`${appOrigin}/api/auth/oauth2/callback/authentik?code=synthetic-code&state=tampered-${flow.state}`,
			{
				headers: { cookie: flow.cookie },
				redirect: 'manual'
			}
		);

		expect(response.status).toBe(302);
		expect(response.headers.get('location')).toBe(
			`${appOrigin}/api/auth/error?error=state_mismatch`
		);
		expect(response.headers.get('set-cookie') ?? '').not.toContain('better-auth.session_token=');
		expect(requests).toHaveLength(providerRequestsBeforeCallback);
	});
});

async function createAuth() {
	return getTestInstance(
		{
			baseURL: appOrigin,
			trustedOrigins: [appOrigin],
			plugins: [
				genericOAuth({
					config: [
						authentikOidcConfig({
							issuer,
							clientId: 'runway-test-client',
							clientSecret: 'synthetic-client-secret',
							signupsEnabled: true
						})
					]
				})
			]
		},
		{ disableTestUser: true, port: 3147 }
	);
}

async function beginFlow(
	customFetchImpl: Awaited<ReturnType<typeof createAuth>>['customFetchImpl']
) {
	const response = await customFetchImpl(`${appOrigin}/api/auth/sign-in/oauth2`, {
		method: 'POST',
		headers: { 'content-type': 'application/json' },
		body: JSON.stringify({
			providerId: 'authentik',
			callbackURL: `${appOrigin}/app`,
			errorCallbackURL: `${appOrigin}/login`,
			disableRedirect: true
		})
	});
	expect(response.status).toBe(200);

	const payload = (await response.json()) as { url: string };
	const state = new URL(payload.url).searchParams.get('state');
	const cookie = convertSetCookieToCookie(new Headers(response.headers)).get('cookie');
	expect(state).toBeTruthy();
	expect(cookie).toBeTruthy();
	if (!state || !cookie) throw new Error('Better Auth did not return OAuth state and cookie data.');
	return { state, cookie };
}

function stubAuthentik(requests: Request[]) {
	vi.stubGlobal(
		'fetch',
		vi.fn((input: URL | RequestInfo, init?: RequestInit) => {
			const request = new Request(input, init);
			requests.push(request);

			if (request.url === `${issuer}.well-known/openid-configuration`) {
				return Promise.resolve(
					jsonResponse({
						issuer,
						authorization_endpoint: authorizationEndpoint,
						token_endpoint: tokenEndpoint,
						userinfo_endpoint: userInfoEndpoint
					})
				);
			}
			if (request.url === tokenEndpoint) {
				return Promise.resolve(
					jsonResponse({
						access_token: 'synthetic-access-token',
						token_type: 'Bearer',
						expires_in: 3600
					})
				);
			}
			if (request.url === userInfoEndpoint) {
				return Promise.resolve(
					jsonResponse({
						sub: 'authentik-user-1',
						email: 'runner@example.test',
						email_verified: true,
						name: 'Test Runner'
					})
				);
			}
			throw new Error(`Unexpected Authentik request: ${request.method} ${request.url}`);
		})
	);
}

function jsonResponse(body: unknown): Response {
	return new Response(JSON.stringify(body), {
		status: 200,
		headers: { 'content-type': 'application/json' }
	});
}

function countRequests(requests: Request[], url: string): number {
	return requests.filter((request) => request.url === url).length;
}
