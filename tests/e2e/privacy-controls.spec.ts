import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	getBulkActivityDeletionState,
	getCurrentGoalPlanState,
	getHealthContext,
	getUserId,
	getUserOwnedRowCount,
	openImportSourceSetup,
	seedUserVerificationRecords,
	seedImportedActivityRecords,
	seedManualActivityRecords,
	setTrainingTimeZone,
	setUserSessionCreatedAt
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('health context can be edited and cleared without replacing the plan', async ({ page }) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const planBefore = await getCurrentGoalPlanState(userId);

	await page.goto('/app/settings');
	await page.locator('summary').filter({ hasText: 'Health and running limits' }).click();
	await page.getByLabel('Recovering from an injury').check();
	await page.getByLabel('Pain has returned during past runs').check();
	await page.getByLabel('Private profile context').fill('Avoid steep descents.');
	await page.getByRole('button', { name: 'Save health context' }).click();
	await expect(page.getByText('Health context saved.', { exact: true })).toBeVisible();
	await expect
		.poll(() => getHealthContext(userId))
		.toEqual({
			recentInjury: true,
			currentPain: false,
			recurringPain: true,
			medicalRestriction: false,
			notes: 'Avoid steep descents.'
		});
	await expect.poll(() => getCurrentGoalPlanState(userId)).toEqual(planBefore);

	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Clear health context' }).click();
	await expect(page.getByText('Health context cleared.', { exact: true })).toBeVisible();
	await expect
		.poll(() => getHealthContext(userId))
		.toEqual({
			recentInjury: false,
			currentPain: false,
			recurringPain: false,
			medicalRestriction: false,
			notes: ''
		});
	await expect.poll(() => getCurrentGoalPlanState(userId)).toEqual(planBefore);
});

test('account deletion rejects a stale session', async ({ page }) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	await setUserSessionCreatedAt(userId, new Date(Date.now() - 20 * 60 * 1_000));

	const response = await page.request.post('/app/settings?/deleteAccount', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			confirmation: 'DELETE',
			browserFolderDataCleared: 'yes'
		}
	});
	await expect(response.text()).resolves.toContain(
		'Sign out and sign in again before deleting the account.'
	);
	await expect.poll(() => getUserOwnedRowCount(userId)).toBeGreaterThan(0);
});

test('account-deletion confirmations are persistently rate limited', async ({ page }) => {
	await createPlan(page);
	const origin = new URL(page.url()).origin;

	for (let attempt = 0; attempt < 5; attempt += 1) {
		const response = await page.request.post('/app/settings?/deleteAccount', {
			headers: { origin },
			multipart: {
				confirmation: 'delete',
				browserFolderDataCleared: 'yes'
			}
		});
		await expect(response.text()).resolves.toContain('Type DELETE exactly');
	}

	const blocked = await page.request.post('/app/settings?/deleteAccount', {
		headers: { origin },
		multipart: {
			confirmation: 'delete',
			browserFolderDataCleared: 'yes'
		}
	});
	expect(blocked.headers()['retry-after']).toMatch(/^\d+$/);
	await expect(blocked.text()).resolves.toContain('Too many account-deletion attempts.');
});

test('privacy copy names the training export and retained GPX fields', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app/settings');
	await expect(page.getByRole('button', { name: 'Export training data' })).toBeVisible();
	await page.getByText('Audit history', { exact: true }).click();
	await expect(
		page.getByText(/private record of security and training-data changes/)
	).toContainText('365 days');
	await expect(
		page.getByText(/private record of security and training-data changes/)
	).toContainText('not route coordinates');

	const getResponse = await page.request.get('/app/settings/export.json', {
		headers: { origin: 'https://evil.example.test' }
	});
	expect(getResponse.status()).toBe(405);

	const crossSiteResponse = await page.request.post('/app/settings/export.json', {
		headers: { origin: 'https://evil.example.test' }
	});
	expect(crossSiteResponse.status()).toBe(403);

	const exportResponse = await page.request.post('/app/settings/export.json', {
		headers: { origin: new URL(page.url()).origin }
	});
	expect(exportResponse.headers()['content-disposition']).toContain('runway-training-data.json');
	await expect(exportResponse.json()).resolves.toMatchObject({
		account: { email: expect.any(String) },
		profile: expect.any(Object),
		plans: expect.any(Array),
		activities: expect.any(Array)
	});

	await page.goto('/app/import');
	await openImportSourceSetup(page);
	await page.getByText('What runway stores', { exact: true }).click();
	const disclosure = page.locator('details.import-privacy');
	await expect(disclosure).toContainText('activity start time');
	await expect(disclosure).toContainText('average cadence');
	await expect(disclosure).toContainText('up to 600 samples with elapsed times');
	await expect(disclosure).toContainText('including the first and last points');
});

test('bulk activity deletion remains complete beyond a request-sized bind list', async ({
	page
}) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	const userId = await getUserId(email);
	await seedManualActivityRecords(userId, 1);
	await seedImportedActivityRecords(userId, 2_500);
	await expect
		.poll(() => getBulkActivityDeletionState(userId))
		.toEqual({
			gpxActivities: 2_500,
			manualActivities: 1,
			imports: 2_500,
			deletionTombstones: 0,
			activityAudits: 2_500
		});

	await page.goto('/app/settings');
	await page.getByText('Imported activity data', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Delete imported GPX activities' }).click();
	await expect(page.getByText('Deleted 2500 imported GPX activities.')).toBeVisible();
	await expect
		.poll(() => getBulkActivityDeletionState(userId))
		.toEqual({
			gpxActivities: 0,
			manualActivities: 1,
			imports: 0,
			deletionTombstones: 2_500,
			activityAudits: 0
		});
});

test('account deletion clears browser folder data across tabs and cascades every user-owned record', async ({
	context,
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	await seedManualActivityRecords(userId, 2);
	await seedUserVerificationRecords(userId);
	await expect.poll(() => getUserOwnedRowCount(userId)).toBeGreaterThan(10);

	await page.goto('/app/settings');
	await page.evaluate(async () => {
		await new Promise<void>((resolve, reject) => {
			const request = indexedDB.open('runway-device-folders', 1);
			request.onupgradeneeded = () => {
				request.result.createObjectStore('folders', { keyPath: 'userId' });
				const seen = request.result.createObjectStore('seen-files', {
					keyPath: ['userId', 'digest']
				});
				seen.createIndex('user-id', 'userId');
			};
			request.onsuccess = () => {
				request.result.close();
				resolve();
			};
			request.onerror = () => {
				reject(request.error ?? new Error('Test folder database could not be opened.'));
			};
		});
	});
	const otherTab = await context.newPage();
	await otherTab.goto('/app/settings');
	const controlMessage = otherTab.evaluate(
		() =>
			new Promise<unknown>((resolve, reject) => {
				const channel = new BroadcastChannel('runway-device-folder-control-v1');
				const timer = setTimeout(() => {
					channel.close();
					reject(new Error('Account deletion was not broadcast to the other tab.'));
				}, 5_000);
				channel.addEventListener(
					'message',
					(event) => {
						clearTimeout(timer);
						channel.close();
						resolve(event.data as unknown);
					},
					{ once: true }
				);
			})
	);

	await page.getByText('Account deletion', { exact: true }).click();
	await page.getByLabel('Type DELETE to confirm').fill('DELETE');
	await page.getByRole('button', { name: 'Delete account permanently' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
	await expect(controlMessage).resolves.toEqual({ type: 'clear-all' });
	await expect
		.poll(() =>
			page.evaluate(async () =>
				(await indexedDB.databases()).some((database) => database.name === 'runway-device-folders')
			)
		)
		.toBe(false);
	await expect.poll(() => getUserOwnedRowCount(userId)).toBe(0);
	await otherTab.close();
});
