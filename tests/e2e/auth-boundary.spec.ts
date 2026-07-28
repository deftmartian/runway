import { expect, test } from '@playwright/test';

test('unreviewed Better Auth endpoints stay behind runway product actions', async ({ page }) => {
	await page.goto('/login');
	const origin = new URL(page.url()).origin;

	for (const pathname of [
		'/api/auth/sign-in/email',
		'/api/auth/sign-in/oauth2',
		'/api/auth/two-factor/verify-totp',
		'/api/auth/oauth2/link',
		'/api/auth/change-password',
		'/api/auth/delete-user',
		'/api/auth/passkey/delete-passkey'
	]) {
		const response = await page.request.post(pathname, {
			headers: { origin },
			data: {}
		});
		expect(response.status()).toBe(404);
		expect(await response.text()).toBe('Not found');
		expect(response.headers()['cache-control']).toBe('private, no-store');
		expect(response.headers()['x-content-type-options']).toBe('nosniff');
	}
});

test('the passkey authentication endpoint remains available', async ({ page }) => {
	const response = await page.request.get('/api/auth/passkey/generate-authenticate-options');
	expect(response.status()).toBe(200);
	expect(response.headers()['cache-control']).toBe('private, no-store');
	await expect(response.json()).resolves.toMatchObject({
		challenge: expect.any(String),
		rpId: expect.any(String)
	});
});

test('Android device approval redirects are private and never stored', async ({ page }) => {
	const response = await page.request.get('/device?user_code=ABCDEFGH', { maxRedirects: 0 });
	expect(response.status()).toBe(302);
	expect(response.headers()['cache-control']).toBe('private, no-store');
});

test('only the fixed native app return marker survives sign-in and two-factor pages', async ({ page }) => {
	const nativeReturnTo = '/device?user_code=ABCDEFGH&return_to_app=runway-native';
	const encodedReturnTo = encodeURIComponent(nativeReturnTo);

	for (const pathname of ['/login', '/login/two-factor']) {
		await page.goto(`${pathname}?returnTo=${encodedReturnTo}`);
		await expect(page.locator('input[name="returnTo"]').first()).toHaveValue(nativeReturnTo);
	}

	await page.goto(
		`/login?returnTo=${encodeURIComponent('/device?user_code=ABCDEFGH&return_to_app=https://attacker.example')}`
	);
	await expect(page.locator('input[name="returnTo"]').first()).toHaveValue('/app');
});
