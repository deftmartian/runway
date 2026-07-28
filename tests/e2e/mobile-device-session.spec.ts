import { expect, request as playwrightRequest, test } from '@playwright/test';
import { authorizeAndroidSession } from './support/android';
import { createAccount } from './support/account';

test('only a session minted by Android device authorization can use the native API', async ({
	page,
	request
}) => {
	await createAccount(page);
	const browserSession = (await page.context().cookies()).find(
		(cookie) => cookie.name === 'better-auth.session_token'
	);
	if (!browserSession) throw new Error('Expected the browser account to have a session cookie.');

	const nativeHeaders = {
		'x-runway-client': 'runway-android/2'
	};
	const browserBearer = await request.get('/api/mobile/v1/view/bootstrap', {
		headers: {
			...nativeHeaders,
			authorization: `Bearer ${browserSession.value}`
		}
	});
	expect(browserBearer.status()).toBe(401);

	const token = await authorizeAndroidSession(page);

	const androidBearer = await request.get('/api/mobile/v1/view/bootstrap', {
		headers: {
			...nativeHeaders,
			authorization: `Bearer ${token}`
		}
	});
	expect(androidBearer.status()).toBe(200);
	expect(await androidBearer.json()).toMatchObject({
		schemaVersion: 1,
		view: 'bootstrap'
	});
});

test('native local sign-in mints the same narrowly scoped Android session', async ({ page }) => {
	const email = await createAccount(page);
	const origin = new URL(page.url()).origin;
	const nativeClient = await playwrightRequest.newContext({
		baseURL: origin,
		extraHTTPHeaders: { 'x-runway-client': 'runway-android/2' }
	});
	try {
		const signIn = await nativeClient.post('/api/auth/sign-in/email', {
			data: {
				email,
				password: 'correct horse battery staple 2026',
				rememberMe: true
			}
		});
		expect(signIn.status()).toBe(200);
		const body = (await signIn.json()) as { token: string };
		expect(body.token).toMatch(/^[\x21-\x7e]{20,1024}$/);

		const bootstrap = await nativeClient.get('/api/mobile/v1/view/bootstrap', {
			headers: { authorization: `Bearer ${body.token}` }
		});
		expect(bootstrap.status()).toBe(200);
		expect(await bootstrap.json()).toMatchObject({
			schemaVersion: 1,
			view: 'bootstrap',
			user: { email }
		});
	} finally {
		await nativeClient.dispose();
	}
});

test('native device approval returns only the fixed result callback', async ({ page }) => {
	await createAccount(page);
	const origin = new URL(page.url()).origin;
	const cookieHeader = (await page.context().cookies(origin))
		.map((cookie) => `${cookie.name}=${cookie.value}`)
		.join('; ');
	if (!cookieHeader) throw new Error('Expected the browser account to have a session cookie.');
	const nativeClient = await playwrightRequest.newContext({
		baseURL: origin,
		extraHTTPHeaders: { 'x-runway-client': 'runway-android/2' }
	});
	try {
		for (const { action, result } of [
			{ action: 'approve', result: 'approved' },
			{ action: 'deny', result: 'denied' }
		] as const) {
			const codeResponse = await nativeClient.post('/api/auth/device/code', {
				data: { client_id: 'runway-android', scope: 'runway:mobile' }
			});
			expect(codeResponse.status()).toBe(200);
			const code = (await codeResponse.json()) as {
				device_code: string;
				user_code: string;
				verification_uri_complete: string;
			};
			const verification = new URL(code.verification_uri_complete);
			verification.searchParams.set('return_to_app', 'runway-native');
			const returnTo = `${verification.pathname}${verification.search}`;

			await page.goto(verification.toString());
			await expect(page.getByRole('button', { name: 'Continue to runway' })).toBeVisible();
			const response = await fetch(`${origin}/device?/${action}`, {
				method: 'POST',
				redirect: 'manual',
				headers: {
					accept: 'text/html',
					origin,
					cookie: cookieHeader,
					'content-type': 'application/x-www-form-urlencoded'
				},
				body: new URLSearchParams({ userCode: code.user_code, returnTo })
			});
			expect(response.status).toBe(303);
			const callback = response.headers.get('location') ?? '';
			expect(callback).toBe(`runway-native://auth?result=${result}`);
			expect(callback).not.toContain(code.device_code);
			expect(callback).not.toContain(code.user_code);
			expect(new URL(callback).searchParams.get('result')).toBe(result);
		}
	} finally {
		await nativeClient.dispose();
	}
});
