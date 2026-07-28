import { expect, test } from '@playwright/test';
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
