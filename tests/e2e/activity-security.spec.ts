import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	fillValidPlanIntake,
	getUserId,
	makeFirstPlanWeekCurrent,
	getPlannedRuns,
	getFirstActivityId,
	activityExists,
	openImportSourceSetup
} from './support/runway';
import { gpxForDistance, gpx, longGpx } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('GPX import rejects tampered workout matches outside the activity window', async ({
	page
}) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'tampered-match.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2020-01-01T12:00:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2020-01-01T12:01:00Z</time></trkpt>
				</trkseg></trk></gpx>`)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	await expect(
		page.getByText('The GPX file could not be saved with that workout match.')
	).toBeVisible();
});

test('matched GPX overruns carry the same load-spike consequence as feedback', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'long-matched-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(longGpx('2026-05-11T12:00:00Z'))
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText(/Completed .* above plan\./)).toBeVisible();

	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page.locator('.plan-assessment')).toContainText('Extra-load review');
	await expect(page.locator('.plan-assessment')).toContainText('Recent activity');
	await page.locator('.plan-assessment > summary').click();
	await expect(page.getByText(/Completed .* above plan\./).first()).toBeVisible();
	await page.getByRole('link', { name: 'Stats' }).click();
	const currentAssessment = page.getByRole('region', { name: 'Current assessment' });
	await expect(currentAssessment).toBeVisible();
	await expect(currentAssessment).toContainText(/Completed .* above plan\./);
	await expect(currentAssessment).toContainText('runway default');
	await expect(page.getByText('Based on recent activity', { exact: true })).toBeVisible();
});

test('stale GPX import cannot attach a second activity to the same workout', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'first-workout-match.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(gpx('2026-05-11T12:00:00Z'))
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();

	const response = await page.request.post('/app/import?/importGpx', {
		headers: {
			origin: new URL(page.url()).origin
		},
		multipart: {
			matchMode: 'workout',
			workoutId: selectedWorkoutId,
			file: {
				name: 'second-workout-match.gpx',
				mimeType: 'application/gpx+xml',
				buffer: Buffer.from(gpx('2026-05-11T12:05:00Z'))
			}
		}
	});
	expect(response.status()).toBeLessThan(500);
	const body = await response.text();
	expect(body).toContain('That workout already has an imported activity.');
});

test('server actions require the exact app origin', async ({ page }) => {
	await createPlan(page);

	const crossSiteResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: 'https://evil.example.test' },
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});

	expect(crossSiteResponse.status()).toBe(403);
	expect(crossSiteResponse.headers()['cache-control']).toBe('private, no-store');
	expect(crossSiteResponse.headers()['content-security-policy']).toContain(
		"frame-ancestors 'none'"
	);
	expect(crossSiteResponse.headers()['x-frame-options']).toBe('DENY');
	await expect(crossSiteResponse.text()).resolves.toContain('Cross-site');

	const siblingOriginResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: 'https://admin.example.test' },
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});
	expect(siblingOriginResponse.status()).toBe(403);

	const noOriginResponse = await page.request.post('/app/import?/deleteActivity', {
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});
	expect(noOriginResponse.status()).toBe(403);
});

test('cross-user activity and workout tampering is rejected server-side', async ({ page }) => {
	const ownerEmail = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const ownerId = await getUserId(ownerEmail);
	await makeFirstPlanWeekCurrent(ownerId);
	const [ownerWorkout] = await getPlannedRuns(ownerId);
	if (!ownerWorkout) throw new Error('Owner plan did not create a workout.');

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Choose a planned workout').check();
	await page.locator('select[name="workoutId"]').selectOption(ownerWorkout.id);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'owner-activity.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(ownerWorkout.scheduledDate, ownerWorkout.targetDistanceMeters)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	const ownerActivityId = await getFirstActivityId(ownerId);

	await page.getByRole('button', { name: 'Sign out' }).click();
	await page.waitForURL((url) => url.pathname === '/' || url.pathname === '/login');
	await createPlan(page);

	const deleteResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: new URL(page.url()).origin },
		multipart: { activityId: ownerActivityId }
	});
	expect(deleteResponse.status()).toBeLessThan(500);
	await expect(deleteResponse.text()).resolves.toContain('Activity not found.');
	await expect.poll(() => activityExists(ownerActivityId)).toBe(true);

	const feedbackResponse = await page.request.post('/app?/recordFeedback', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			workoutId: ownerWorkout.id,
			status: 'skipped',
			choice: 'reduce_next'
		}
	});
	expect(feedbackResponse.status()).toBeLessThan(500);
	await expect(feedbackResponse.text()).resolves.toContain('Workout not found.');
});

test('GPX import rejects malformed and oversized uploads without server errors', async ({
	page
}) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	await page.getByLabel('GPX file').setInputFiles({
		name: 'not-a-track.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from('<gpx><metadata /></gpx>')
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('The GPX file could not be parsed.')).toBeVisible();

	await page.getByLabel('GPX file').setInputFiles({
		name: 'too-large.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.alloc(10 * 1024 * 1024 + 1, '<')
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('GPX file is too large for import.')).toBeVisible();

	await page.getByLabel('GPX file').setInputFiles({
		name: 'future-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2099-01-01', 3_000)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('Imported activities cannot be in the future.')).toBeVisible();
});

test('import requires an explicit training time zone before upload or source connection', async ({
	page
}) => {
	await createAccount(page);
	await page.goto('/app/import');
	await openImportSourceSetup(page, 'Nextcloud');

	await expect(
		page.getByRole('alert').getByText('Set the training time zone before importing.', {
			exact: true
		})
	).toBeVisible();
	await expect(
		page
			.locator('a[href="/app/settings"]')
			.filter({ hasText: /Settings/ })
			.first()
	).toBeVisible();
	await expect(page.getByRole('button', { name: 'Connect folder' })).toBeDisabled();
	await openImportSourceSetup(page);
	await expect(page.getByRole('button', { name: 'Import', exact: true })).toBeDisabled();

	const response = await page.request.post('/app/import?/saveNextcloudSource', {
		headers: { origin: new URL(page.url()).origin },
		form: {
			label: 'Blocked source',
			shareUrl: 'https://cloud.example.test/s/token',
			sharePassword: 'password'
		}
	});
	expect(response.status()).toBeLessThan(500);
	await expect(response.text()).resolves.toContain('Set training time zone before importing.');
});
