import { expect, request as playwrightRequest, type Page } from '@playwright/test';

const nativeClientHeaders = { 'x-runway-client': 'runway-android/2' };

export async function authorizeAndroidSession(page: Page): Promise<string> {
	const nativeClient = await playwrightRequest.newContext({
		baseURL: new URL(page.url()).origin,
		extraHTTPHeaders: nativeClientHeaders
	});
	try {
		const codeResponse = await nativeClient.post('/api/auth/device/code', {
			data: { client_id: 'runway-android', scope: 'runway:mobile' }
		});
		expect(codeResponse.status()).toBe(200);
		const code = (await codeResponse.json()) as {
			device_code: string;
			verification_uri_complete: string;
		};

		await page.goto(code.verification_uri_complete);
		await page.getByRole('button', { name: 'Allow this phone' }).click();
		await expect(page.getByText('Android signed in.')).toBeVisible();

		const tokenResponse = await nativeClient.post('/api/auth/device/token', {
			data: {
				grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
				device_code: code.device_code,
				client_id: 'runway-android'
			}
		});
		expect(tokenResponse.status()).toBe(200);
		const token = (await tokenResponse.json()) as { access_token: string };
		expect(token.access_token).toMatch(/^[\x21-\x7e]{20,1024}$/);
		return token.access_token;
	} finally {
		await nativeClient.dispose();
	}
}

export async function createAndroidImportPairingCode(
	page: Page,
	nativeSession: string,
	label: string
): Promise<string> {
	const nativeClient = await playwrightRequest.newContext({
		baseURL: new URL(page.url()).origin,
		extraHTTPHeaders: {
			...nativeClientHeaders,
			authorization: `Bearer ${nativeSession}`
		}
	});
	try {
		const response = await nativeClient.post('/api/mobile/v1/android/pairing', {
			data: { label }
		});
		expect(response.status()).toBe(200);
		const pairing = (await response.json()) as { code: string; label: string };
		expect(pairing.code).toMatch(/^[0-9A-F]{4}(?:-[0-9A-F]{4}){3}$/);
		expect(pairing.label).toBe(label);
		return pairing.code;
	} finally {
		await nativeClient.dispose();
	}
}
