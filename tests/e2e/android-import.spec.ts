import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createHash, randomUUID } from 'node:crypto';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import { createPlan, openImportSourceSetup } from './support/runway';
import { gpxForDistance } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('Android pairing imports idempotently and privacy deletion revokes the device', async ({
	page
}) => {
	await createPlan(page);
	await page.goto('/app/import');
	await openImportSourceSetup(page, 'Android folder');
	const sourceAccessibility = await new AxeBuilder({ page }).include('.import-sources').analyze();
	expect(sourceAccessibility.violations).toEqual([]);
	await page.getByRole('button', { name: 'Create pairing code' }).click();
	const firstPairingCode = (await page.locator('.pairing-code strong').textContent())?.trim() ?? '';
	expect(firstPairingCode).toMatch(/^[0-9A-F]{4}(?:-[0-9A-F]{4}){3}$/);
	await page.getByRole('button', { name: 'Create pairing code' }).click();
	await expect(page.locator('.pairing-code strong')).not.toHaveText(firstPairingCode);
	const pairingCode = (await page.locator('.pairing-code strong').textContent())?.trim() ?? '';
	expect(pairingCode).toMatch(/^[0-9A-F]{4}(?:-[0-9A-F]{4}){3}$/);
	expect(pairingCode).not.toBe(firstPairingCode);

	const replacedPairing = await page.request.post('/api/android/pair', {
		headers: {
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: firstPairingCode, label: 'Old test phone' }
	});
	expect(replacedPairing.status()).toBe(400);

	const crossOriginPairing = await page.request.post('/api/android/pair', {
		headers: {
			origin: 'https://attacker.example',
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: pairingCode, label: 'Test phone' }
	});
	expect(crossOriginPairing.status()).toBe(403);

	const pairing = await page.request.post('/api/android/pair', {
		headers: {
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: pairingCode, label: 'Test phone' }
	});
	expect(pairing.status()).toBe(201);
	const paired = (await pairing.json()) as {
		result: string;
		deviceId: string;
		token: string;
	};
	expect(paired.result).toBe('paired');
	expect(paired.token).toMatch(/^rwy1_/);

	await page.reload();
	await expect(page.getByText('Test phone', { exact: true })).toBeVisible();

	const gpx = gpxForDistance(testDate, 4_000);
	const requestId = randomUUID();
	const contentDigest = createHash('sha256').update(gpx).digest('hex');
	const importHeaders = {
		authorization: `Bearer ${paired.token}`,
		'content-type': 'application/gpx+xml',
		'x-runway-client': 'runway-android/1',
		'x-runway-content-sha256': contentDigest,
		'x-runway-request-id': requestId
	};
	const imported = await page.request.post('/api/android/import', {
		headers: importHeaders,
		data: gpx
	});
	expect(imported.status()).toBe(201);
	await expect(imported.json()).resolves.toMatchObject({
		result: 'imported',
		requestId,
		replayed: false
	});

	const replayed = await page.request.post('/api/android/import', {
		headers: importHeaders,
		data: gpx
	});
	expect(replayed.status()).toBe(201);
	await expect(replayed.json()).resolves.toMatchObject({
		result: 'imported',
		requestId,
		replayed: true
	});

	const changedGpx = gpxForDistance(testDate, 4_500);
	const conflicting = await page.request.post('/api/android/import', {
		headers: {
			...importHeaders,
			'x-runway-content-sha256': createHash('sha256').update(changedGpx).digest('hex')
		},
		data: changedGpx
	});
	expect(conflicting.status()).toBe(409);
	await expect(conflicting.json()).resolves.toMatchObject({
		result: 'request-conflict',
		requestId
	});

	await page.reload();
	await expect(page.locator('.activity-record')).toHaveCount(1);
	await page.goto('/app/settings');
	await page.getByText('Imported activity data', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Delete imported GPX activities' }).click();
	await expect(
		page.getByText('Disconnected 1 Android device so it cannot import the activity again.')
	).toBeVisible();

	const statusAfterDeletion = await page.request.get('/api/android/status', {
		headers: { authorization: `Bearer ${paired.token}`, 'x-runway-client': 'runway-android/1' }
	});
	expect(statusAfterDeletion.status()).toBe(401);
});
