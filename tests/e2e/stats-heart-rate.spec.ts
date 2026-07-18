import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	createAccount,
	fillValidPlanIntake,
	goToOnboardingStep,
	getUserId,
	makeFirstPlanWeekCurrent,
	getPlannedRuns,
	moveWorkoutToDate,
	openImportSourceSetup
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('stats keep archived completed work separate from the current plan', async ({ page }) => {
	const email = await createAccount(page);
	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.waitForLoadState('networkidle');

	const userId = await getUserId(email);
	const targetRun = (await getPlannedRuns(userId))[0];
	if (!targetRun) throw new Error('Plan did not create a run for archived stats test.');
	await moveWorkoutToDate(targetRun.id, testDate);

	const completedDistanceKm = targetRun.targetDistanceMeters / 1_000;
	const feedbackResponse = await page.request.post('/app?/recordFeedback', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			workoutId: targetRun.id,
			status: 'done',
			completedDistanceKm: String(completedDistanceKm),
			completedDurationMinutes: '20',
			choice: 'reduce_next'
		}
	});
	expect(feedbackResponse.status()).toBeLessThan(400);

	await page.goto('/app/onboarding');
	await expect(page.getByRole('heading', { name: 'Change goal' })).toBeVisible();
	await page.getByLabel('Race distance').selectOption('10k');
	await goToOnboardingStep(page, 'Review');
	await page.getByLabel(/Archive the current goal/).check();
	await page.getByRole('button', { name: 'Replace active plan' }).click();
	await page.waitForURL(/\/app$/);

	await page.goto('/app/stats');
	const recordedHistory = page.getByRole('region', { name: 'Recorded history' });
	await expect(recordedHistory).toBeVisible();
	await expect(page.getByText('Current-plan distance')).toBeVisible();
	await expect(recordedHistory.getByText('Archived plans')).toBeVisible();
	await expect(recordedHistory.getByText('1 archived run still counted.')).toBeVisible();
	await expect(
		recordedHistory.getByText(`${Math.round(completedDistanceKm * 10) / 10} km`).first()
	).toBeVisible();
});

test('heart-rate imports stay descriptive while stats show the measured zones', async ({
	page
}) => {
	const email = await createAccount(page);
	await page.getByRole('link', { name: 'Settings' }).click();
	await page.getByText('Heart-rate zones', { exact: true }).click();
	await page.getByLabel('Sex used for estimates').selectOption('female');
	await page.getByLabel('Age').fill('35');
	await page.getByRole('button', { name: 'Use estimate' }).click();
	await page.getByRole('button', { name: 'Save training profile' }).click();
	await expect(page.getByText('Training profile saved.')).toBeVisible();

	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await makeFirstPlanWeekCurrent(await getUserId(email));

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');
	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'high-effort-heart-rate.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-11T12:00:00Z</time><extensions><gpxtpx:hr>168</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-11T12:10:00Z</time><extensions><gpxtpx:hr>170</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0020" lon="-63.0020"><time>2026-05-11T12:20:00Z</time><extensions><gpxtpx:hr>172</gpxtpx:hr></extensions></trkpt>
				</trkseg></trk></gpx>`)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText('Heart-rate zone time was included.')).toBeVisible();
	await page.getByText('Manage', { exact: true }).click();
	await expect(
		page.getByRole('checkbox', { name: 'Effort was unusually hard' }).first()
	).not.toBeChecked();
	await expect(page.getByRole('heading', { name: 'Route map' })).toBeVisible();
	await expect(page.locator('svg.route-map')).toBeVisible();
	await expect(page.locator('svg.heart-chart')).toBeVisible();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Average heart rate', { exact: true })).toBeVisible();
	await expect(page.getByText('170 bpm').first()).toBeVisible();
	await expect(page.getByText('High-zone time', { exact: true })).toBeVisible();
	await expect(page.getByText('Latest max 172 bpm', { exact: true })).toBeVisible();
});

test('heart-rate stats remain available without an active plan', async ({ page }) => {
	await createAccount(page);
	await page.goto('/app/settings');
	await page.getByText('Time zone', { exact: true }).click();
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await page.getByRole('button', { name: 'Save time zone' }).click();
	await expect(page.getByText('Training time zone saved.')).toBeVisible();

	await page.goto('/app/import');
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'unplanned-heart-rate.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
			<gpx><trk><trkseg>
				<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time><extensions><hr>138</hr></extensions></trkpt>
				<trkpt lat="45.0020" lon="-63.0010"><time>2026-05-14T12:10:00Z</time><extensions><hr>142</hr></extensions></trkpt>
			</trkseg></trk></gpx>`)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await page.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Count as extra training' }).click();
	await expect(page.getByText('Included in training load')).toBeVisible();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByRole('heading', { name: 'No active plan' })).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Heart rate' })).toBeVisible();
	await expect(page.getByText('140 bpm')).toBeVisible();
});

test('settings keeps training profile values visible after save', async ({ page }) => {
	await createAccount(page);
	await page.getByRole('link', { name: 'Settings' }).click();
	await page.getByText('Heart-rate zones', { exact: true }).click();
	await expect(page.getByLabel('Age')).toHaveValue('');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('');
	await expect(page.getByLabel('Zone 2 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 3 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 4 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('');
	await expect(
		page.locator('.estimate-panel').getByText('Not configured', { exact: true })
	).toBeVisible();
	await expect(page.getByText('Estimate unavailable')).toBeVisible();
	await page.getByLabel('Sex used for estimates').selectOption('female');
	await page.getByLabel('Age').fill('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');
	await page.getByLabel('Max heart rate').fill('172');
	await page.getByLabel('Zone 2 starts').fill('104');
	await page.getByLabel('Zone 3 starts').fill('121');
	await page.getByLabel('Zone 4 starts').fill('138');
	await page.getByLabel('Zone 5 starts').fill('155');
	await page.getByRole('button', { name: 'Save training profile' }).click();

	await expect(page.getByText('Training profile saved.')).toBeVisible();
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.locator('.estimate-panel').getByText('Custom', { exact: true })).toBeVisible();

	await page.reload();
	await page.getByText('Heart-rate zones', { exact: true }).click();
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');

	await page.goto('/app/onboarding');
	await expect(page.getByLabel('Sex used for estimates')).toHaveCount(0);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.goto('/app/onboarding');
	await page.getByLabel('Race distance').selectOption('10k');
	await goToOnboardingStep(page, 'Review');
	await page.getByLabel(/Archive the current goal/).check();
	await page.getByRole('button', { name: 'Replace active plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.goto('/app/settings');
	await page.getByText('Heart-rate zones', { exact: true }).click();
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');
});
