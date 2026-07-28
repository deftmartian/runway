import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	getBulkActivityDeletionState,
	getCurrentGoalPlanState,
	getFirstActivityId,
	getHealthConnectRoutePrivacyState,
	getHealthContext,
	getPlannedRuns,
	getUserId,
	getUserOwnedRowCount,
	openImportSourceSetup,
	seedUserVerificationRecords,
	seedImportedActivityRecords,
	seedHealthConnectActivity,
	seedManualActivityRecords,
	setTrainingTimeZone,
	setUserSessionCreatedAt
} from './support/runway';
import { gpxForDistance } from './support/import-fixtures';

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
			confirmation: 'DELETE'
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
				confirmation: 'delete'
			}
		});
		await expect(response.text()).resolves.toContain('Type DELETE exactly');
	}

	const blocked = await page.request.post('/app/settings?/deleteAccount', {
		headers: { origin },
		multipart: {
			confirmation: 'delete'
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
	await page.getByText('Imported activity data', { exact: true }).click();
	const importedActivityDeletionCopy = page.getByText(
		/Deleting imported activities also disconnects/
	);
	await expect(importedActivityDeletionCopy).toContainText(
		'disconnects import folders and paired Android devices'
	);
	await expect(importedActivityDeletionCopy).not.toContainText('browser');
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

test('discarding route maps also clears a pending Health Connect correction', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	const userId = await getUserId(email);
	await page.goto('/app/settings');
	await page.locator('summary').filter({ hasText: 'Route maps' }).click();
	await page.getByLabel(/^Keep the route trace/).check();
	await page.getByRole('button', { name: 'Save route privacy' }).click();
	await expect(page.getByText('Route maps enabled for future imports.')).toBeVisible();

	const seeded = await seedHealthConnectActivity(userId, 'correction');
	await expect
		.poll(() => getHealthConnectRoutePrivacyState(seeded.activityId, seeded.mappingId))
		.toEqual({ activityRetained: true, pendingRetained: true });

	await page.goto('/app/settings');
	await page.locator('summary').filter({ hasText: 'Route maps' }).click();
	await page.getByLabel(/^Discard route points/).check();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Save route privacy' }).click();
	await expect(page.getByText(/Route points will be discarded after import/)).toBeVisible();
	await expect
		.poll(() => getHealthConnectRoutePrivacyState(seeded.activityId, seeded.mappingId))
		.toEqual({ activityRetained: false, pendingRetained: false });

	await page.goto('/app/import');
	const record = page.locator('details.activity-record').first();
	await record.locator('summary').click();
	await record.getByRole('button', { name: 'Accept correction' }).click();
	await expect(page.getByText('Health Connect correction applied.')).toBeVisible();
	await expect
		.poll(() => getHealthConnectRoutePrivacyState(seeded.activityId, seeded.mappingId))
		.toEqual({ activityRetained: false, pendingRetained: false });
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
	await page.getByRole('button', { name: 'Delete imported activities' }).click();
	await expect(page.getByText('Deleted 2500 imported activities.')).toBeVisible();
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

test('bulk activity deletion removes activity-derived adjustment state from exports', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const runs = await getPlannedRuns(userId);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Choose a planned workout').check();
	const workoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	const targetRun = runs.find((run) => run.id === workoutId);
	if (!targetRun) throw new Error('A workout candidate was not available for the privacy test.');
	await page.locator('select[name="workoutId"]').selectOption(workoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'bulk-delete-private-adjustment.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(targetRun.scheduledDate, targetRun.targetDistanceMeters)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	const activityId = await getFirstActivityId(userId);

	await page.goto('/app/settings');
	await page.getByText('Imported activity data', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Delete imported activities' }).click();
	await expect(page.getByText('Deleted 1 imported activity.')).toBeVisible();

	const exportResponse = await page.request.post('/app/settings/export.json', {
		headers: { origin: new URL(page.url()).origin }
	});
	expect(exportResponse.status()).toBe(200);
	const exported = (await exportResponse.json()) as {
		adjustments: { triggerId: string | null; triggerType: string }[];
	};
	expect(JSON.stringify(exported.adjustments)).not.toContain(activityId);
	expect(exported.adjustments.map(({ triggerType }) => triggerType)).not.toContain('import_match');
});

test('account deletion cascades every user-owned record', async ({ page }) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	await seedManualActivityRecords(userId, 2);
	await seedUserVerificationRecords(userId);
	await expect.poll(() => getUserOwnedRowCount(userId)).toBeGreaterThan(10);

	await page.goto('/app/settings');
	await page.getByText('Account deletion', { exact: true }).click();
	await page.getByLabel('Type DELETE to confirm').fill('DELETE');
	await page.getByRole('button', { name: 'Delete account permanently' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect(page.getByRole('link', { name: 'Sign in', exact: true })).toBeVisible();
	await expect.poll(() => getUserOwnedRowCount(userId)).toBe(0);
});
