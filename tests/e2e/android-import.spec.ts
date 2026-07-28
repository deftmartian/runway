import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { createHash, randomUUID } from 'node:crypto';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	authorizeAndroidSession,
	createAndroidImportPairingCode,
	createPlan,
	openImportSourceSetup
} from './support/runway';
import { getGpxImportCounts, getUserId, waitForAndroidImportClaim } from './support/db';
import { gpxForDistance, startHeldAndroidImport } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('Android pairing imports idempotently and privacy deletion revokes the device', async ({
	page,
	request
}) => {
	await createPlan(page);
	const nativeSession = await authorizeAndroidSession(page);
	const firstPairingCode = await createAndroidImportPairingCode(
		page,
		nativeSession,
		'Old test phone'
	);
	const pairingCode = await createAndroidImportPairingCode(page, nativeSession, 'Test phone');
	expect(pairingCode).not.toBe(firstPairingCode);

	await page.goto('/app/import');
	await openImportSourceSetup(page, 'Android app');
	const sourceAccessibility = await new AxeBuilder({ page }).include('.import-sources').analyze();
	expect(sourceAccessibility.violations).toEqual([]);
	await expect(page.getByRole('button', { name: 'Create pairing code' })).toHaveCount(0);
	await expect(page.getByText('There is no code to copy between apps.')).toBeVisible();

	const replacedPairing = await request.post('/api/android/pair', {
		headers: {
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: firstPairingCode, label: 'Old test phone' }
	});
	expect(replacedPairing.status()).toBe(400);

	const crossOriginPairing = await request.post('/api/android/pair', {
		headers: {
			origin: 'https://attacker.example',
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: pairingCode, label: 'Test phone' }
	});
	expect(crossOriginPairing.status()).toBe(403);

	const pairing = await request.post('/api/android/pair', {
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
	const imported = await request.post('/api/android/import', {
		headers: importHeaders,
		data: gpx
	});
	expect(imported.status()).toBe(201);
	await expect(imported.json()).resolves.toMatchObject({
		result: 'imported',
		requestId,
		replayed: false
	});

	const replayed = await request.post('/api/android/import', {
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
	const conflicting = await request.post('/api/android/import', {
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
	await page.getByRole('button', { name: 'Delete imported activities' }).click();
	await expect(
		page.getByText('Disconnected 1 Android device so it cannot import the activity again.')
	).toBeVisible();

	const statusAfterDeletion = await request.get('/api/android/status', {
		headers: { authorization: `Bearer ${paired.token}`, 'x-runway-client': 'runway-android/1' }
	});
	expect(statusAfterDeletion.status()).toBe(401);
});

test('disconnecting an Android device cancels its claimed in-flight import', async ({
	page,
	request
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const nativeSession = await authorizeAndroidSession(page);
	const pairingCode = await createAndroidImportPairingCode(
		page,
		nativeSession,
		'Held import phone'
	);
	await page.goto('/app/import');
	const pairing = await request.post('/api/android/pair', {
		headers: {
			'content-type': 'application/json',
			'x-runway-client': 'runway-android/1'
		},
		data: { code: pairingCode, label: 'Held import phone' }
	});
	expect(pairing.status()).toBe(201);
	const paired = (await pairing.json()) as { deviceId: string; token: string };
	await page.reload();

	const gpx = gpxForDistance(testDate, 4_200);
	const requestId = randomUUID();
	const held = startHeldAndroidImport(
		new URL('/api/android/import', page.url()),
		{
			authorization: `Bearer ${paired.token}`,
			'content-type': 'application/gpx+xml',
			'x-runway-client': 'runway-android/1',
			'x-runway-content-sha256': createHash('sha256').update(gpx).digest('hex'),
			'x-runway-request-id': requestId
		},
		gpx
	);
	await waitForAndroidImportClaim(paired.deviceId, requestId);

	const crossOriginDisconnect = await request.delete('/api/android/status', {
		headers: {
			authorization: `Bearer ${paired.token}`,
			origin: 'https://attacker.example',
			'x-runway-client': 'runway-android/1'
		}
	});
	expect(crossOriginDisconnect.status()).toBe(403);

	const disconnected = await request.delete('/api/android/status', {
		headers: {
			authorization: `Bearer ${paired.token}`,
			'x-runway-client': 'runway-android/1'
		}
	});
	expect(disconnected.status()).toBe(200);
	await expect(disconnected.json()).resolves.toEqual({ result: 'disconnected' });
	const disconnectedStatus = await request.get('/api/android/status', {
		headers: {
			authorization: `Bearer ${paired.token}`,
			'x-runway-client': 'runway-android/1'
		}
	});
	expect(disconnectedStatus.status()).toBe(401);
	await page.reload();
	await expect(page.getByText('Held import phone', { exact: true })).not.toBeVisible();
	held.finish();
	await expect(held.response).resolves.toMatchObject({
		status: 401,
		body: { result: 'unauthorized', reason: 'device-revoked' }
	});
	await expect.poll(() => getGpxImportCounts(userId)).toEqual({ activities: 0, imports: 0 });
});
