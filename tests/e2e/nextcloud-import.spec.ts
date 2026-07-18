import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript } from '../support/test-clock';
import { createAccount, setTrainingTimeZone, openImportSourceSetup } from './support/runway';
import {
	startHeldShareImport,
	gpxForDistance,
	startNextcloudShareFixture
} from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('Nextcloud share sync backfills files, tracks revisions, and honors deletion tombstones', async ({
	page
}) => {
	const share = await startNextcloudShareFixture();
	const syncNow = async () => {
		const responsePromise = page.waitForResponse(
			(response) => response.request().method() === 'POST' && response.url().includes('/app/import')
		);
		await page.getByRole('button', { name: 'Sync now' }).click();
		const response = await responsePromise;
		expect(response.status()).toBeLessThan(500);
	};

	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page, 'Nextcloud');
		await page.getByLabel('Label').fill('Watch exports');
		await page.getByLabel('Share link').fill(share.url);
		await page.getByLabel('Share password').fill(share.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud folder connected.')).toBeVisible();
		const htmlAfterConnect = await page.content();
		expect(htmlAfterConnect).not.toContain('testToken123');
		expect(htmlAfterConnect).not.toContain(share.password);
		expect(htmlAfterConnect).not.toContain('sharePasswordSecret');

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(1);

		await page.getByText('Review', { exact: true }).click();
		page.once('dialog', (dialog) => dialog.accept());
		await page.getByRole('button', { name: 'Delete activity', exact: true }).click();
		await expect(page.getByText('Activity deleted.')).toBeVisible();

		share.exposeRenamedDuplicate();
		await syncNow();
		await expect(page.getByText('That GPX file was already handled.')).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		await expect.poll(share.renamedDownloads).toBe(1);

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		await expect.poll(share.olderDownloads).toBe(1);

		share.replaceNewer();
		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		await expect.poll(share.newerDownloads).toBe(2);

		await syncNow();
		await expect(page.getByText('All visible GPX files were already handled.')).toBeVisible();
		expect(share.newerDownloads()).toBe(2);
		expect(share.olderDownloads()).toBe(1);
		expect(share.renamedDownloads()).toBe(1);

		const activityRecords = page.locator('.import-inbox');
		await expect(activityRecords.locator('.activity-record')).toHaveCount(2);

		await page.goto('/app/settings');
		await page.getByText('Imported activity data', { exact: true }).click();
		page.once('dialog', (dialog) => dialog.accept());
		await page.getByRole('button', { name: 'Delete imported GPX activities' }).click();
		await expect(
			page.getByText('Disconnected 1 import folder so it cannot sync the activity back.')
		).toBeVisible();

		await page.goto('/app/import');
		await expect(page.getByText('No import sources connected.')).toBeVisible();
	} finally {
		await share.close();
	}
});

test('privacy deletion cancels a Share import that is still uploading', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app/settings');

	const cookies = await page.context().cookies();
	const heldShare = await startHeldShareImport(
		new URL('/app/import/share', page.url()),
		cookies.map(({ name, value }) => `${name}=${value}`).join('; '),
		gpxForDistance('2026-05-12', 5_000)
	);

	await page.getByText('Imported activity data', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Delete imported GPX activities' }).click();
	await expect(page.getByText('Deleted 0 imported GPX activities.')).toBeVisible();

	heldShare.finish();
	const shareResponse = await heldShare.response;
	expect(shareResponse.status).toBe(303);
	expect(shareResponse.location).toContain('/app/import?share=deleted');

	await page.goto('/app/import');
	await expect(page.locator('.activity-record')).toHaveCount(0);
});

test('Nextcloud share sync backfills past a failed revision and retries it only after change', async ({
	page
}) => {
	const share = await startNextcloudShareFixture({ malformedNewest: true });
	const syncNow = async () => {
		const responsePromise = page.waitForResponse(
			(response) => response.request().method() === 'POST' && response.url().includes('/app/import')
		);
		await page.getByRole('button', { name: 'Sync now' }).click();
		const response = await responsePromise;
		expect(response.status()).toBeLessThan(500);
	};

	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page, 'Nextcloud');
		await page.getByLabel('Label').fill('Watch exports');
		await page.getByLabel('Share link').fill(share.url);
		await page.getByLabel('Share password').fill(share.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud folder connected.')).toBeVisible();

		await syncNow();
		await expect(page.getByText('The selected GPX file could not be parsed.')).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		expect(share.olderDownloads()).toBe(0);

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		expect(share.olderDownloads()).toBe(1);

		share.replaceNewer();
		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(2);
	} finally {
		await share.close();
	}
});

test('Nextcloud share setup rejects wrong passwords and unprotected folders', async ({ page }) => {
	const protectedShare = await startNextcloudShareFixture();
	const unprotectedShare = await startNextcloudShareFixture({ requirePassword: false });
	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page, 'Nextcloud');

		await page.getByLabel('Label').fill('Wrong password export');
		await page.getByLabel('Share link').fill(protectedShare.url);
		await page.getByLabel('Share password').fill('not the right password');
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud share password was rejected.')).toBeVisible();

		await page.getByLabel('Label').fill('Unprotected export');
		await page.getByLabel('Share link').fill(unprotectedShare.url);
		await page.getByLabel('Share password').fill(unprotectedShare.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud share must require the password.')).toBeVisible();
		await expect(page.getByText('Nextcloud folder connected.', { exact: true })).not.toBeVisible();
	} finally {
		await protectedShare.close();
		await unprotectedShare.close();
	}
});
