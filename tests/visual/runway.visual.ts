import { expect, test, type Page } from '@playwright/test';
import postgres from 'postgres';
import { fixedBrowserClockScript, testNowIso } from '../support/test-clock';

const visualPassword = 'correct horse battery staple 2026';
const viewports = [
	{ name: 'mobile', width: 390, height: 844 },
	{ name: 'tablet', width: 768, height: 1024 },
	{ name: 'desktop', width: 1280, height: 900 },
	{ name: 'wide', width: 1440, height: 1000 }
] as const;

test.beforeEach(async ({ page }) => {
	await freezeBrowserDate(page);
});

for (const viewport of viewports) {
	test.describe(`visual qa ${viewport.name}`, () => {
		test.use({ viewport });

		test(`public and auth surfaces render cleanly on ${viewport.name}`, async ({ page }) => {
			await page.emulateMedia({ colorScheme: 'light' });
			await page.goto('/');
			await expect(page.getByRole('heading', { name: 'runway' })).toBeVisible();
			await stableScreenshot(page, `public-home-${viewport.name}.png`);

			await page.goto('/login');
			await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
			await stableScreenshot(page, `login-${viewport.name}.png`);

			await page.goto('/login/forgot-password');
			await expect(page.getByRole('heading', { name: 'Reset password' })).toBeVisible();
			await stableScreenshot(page, `forgot-password-${viewport.name}.png`);

			await page.goto('/login/reset-password');
			await expect(page.getByText('Open this page from the reset link')).toBeVisible();
			await stableScreenshot(page, `reset-password-empty-${viewport.name}.png`);
		});

		test(`authenticated app states render cleanly on ${viewport.name}`, async ({ page }) => {
			await page.emulateMedia({ colorScheme: 'light' });
			await seedVisualAccount(page, viewport.name);
			await page.goto('/app/onboarding');
			await expect(page.getByRole('heading', { name: 'Change goal' })).toBeVisible();
			await stableScreenshot(page, `onboarding-${viewport.name}.png`);

			await page.goto('/app');
			await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
			await stableScreenshot(page, `calendar-${viewport.name}.png`);

			await page
				.getByRole('button', { name: /Easy run/ })
				.first()
				.click();
			const plannedDialog = trainingDetailPanel(page);
			await expect(plannedDialog).toBeVisible();
			await stableElementScreenshot(plannedDialog, `planned-run-modal-${viewport.name}.png`);
			await page.getByText('Close', { exact: true }).click();

			await page.getByRole('button', { name: /Rest/ }).first().click();
			await expect(page.getByText('Record unplanned run')).toBeVisible();
			await page.getByText('Record unplanned run').click();
			const restDialog = trainingDetailPanel(page);
			await stableElementScreenshot(restDialog, `rest-day-modal-${viewport.name}.png`);
			await page.getByText('Close', { exact: true }).click();

			await page.goto('/app/import');
			await expect(page.getByRole('heading', { name: 'Activity inbox' })).toBeVisible();
			await stableScreenshot(page, `import-empty-${viewport.name}.png`);

			await createImportRecords(page);
			await stableScreenshot(page, `import-records-${viewport.name}.png`);

			await page.goto('/app');
			await page
				.getByRole('button', { name: /Imported run, 10 km, Needs review/ })
				.first()
				.click();
			const importedDialog = trainingDetailPanel(page);
			await expect(importedDialog).toBeVisible();
			await stableElementScreenshot(importedDialog, `imported-run-modal-${viewport.name}.png`);
			await page.getByText('Close', { exact: true }).click();

			await page.goto('/app/stats');
			await expect(page.getByRole('heading', { name: 'Stats' })).toBeVisible();
			await stableScreenshot(page, `stats-${viewport.name}.png`);

			await page.goto('/app/history');
			await expect(page.getByRole('heading', { name: 'History' })).toBeVisible();
			await stableScreenshot(page, `history-${viewport.name}.png`);

			await page.goto('/app/settings');
			await expect(page.getByRole('heading', { name: 'Settings' })).toBeVisible();
			await expect(page.getByRole('region', { name: 'Install runway' })).toBeVisible();
			await stableScreenshot(page, `settings-${viewport.name}.png`);
		});
	});
}

test('dark mode app state has visual coverage', async ({ page }) => {
	await page.setViewportSize({ width: 1280, height: 900 });
	await page.emulateMedia({ colorScheme: 'dark' });
	await seedVisualAccount(page, 'dark');
	await page.goto('/app');
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
	await stableScreenshot(page, 'calendar-dark-desktop.png');
});

async function seedVisualAccount(page: Page, fixtureName: string) {
	const email = `visual-${fixtureName}@example.test`;
	await page.goto('/login');
	const signup = page.locator('#create-account');
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill(visualPassword);
	await signup.getByLabel('Name').fill('Visual Runner');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	const target = new Date(testNowIso);
	target.setUTCDate(target.getUTCDate() + 20 * 7);
	await page.getByLabel('Race distance').selectOption('half');
	await page.getByLabel('Target date').fill(target.toISOString().slice(0, 10));
	await page.getByLabel('Priority').selectOption('finish_healthy');
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Weekly distance (km)').fill('12');
	await page.getByLabel('Runs per week').fill('3');
	await page.getByLabel('Longest recent run (km)').fill('8');
	await page.getByLabel('Running experience').selectOption('returning');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Mon', 'Wed', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await page.getByLabel('Preferred long-run day').selectOption('6');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.waitForLoadState('networkidle');
	await makeFirstPlanWeekCurrent(email);
	await page.reload();
}

async function goToOnboardingStep(
	page: Page,
	step: 'Goal' | 'Starting point' | 'Schedule' | 'Review'
) {
	await page
		.getByRole('navigation', { name: 'Plan setup progress' })
		.getByRole('button', { name: new RegExp(`${step}$`) })
		.click();
}

async function setAvailability(page: Page, selectedDays: string[]) {
	for (const day of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']) {
		const label = page.locator('.day-choices label').filter({ hasText: day });
		const input = label.locator('input');
		if ((await input.isChecked()) !== selectedDays.includes(day)) await label.click();
	}
}

async function makeFirstPlanWeekCurrent(email: string) {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql.begin(async (transaction) => {
			const [firstWeek] = await transaction<{ id: string; planId: string }[]>`
				select tw.id, tw.plan_id as "planId"
				from training_week tw
				inner join training_plan tp on tp.id = tw.plan_id
				inner join "user" u on u.id = tp.user_id
				where lower(u.email) = ${email.toLowerCase()} and tp.status = 'active'
				order by tw.week_number asc
				limit 1
			`;
			if (!firstWeek) throw new Error('Visual fixture could not find the first plan week.');
			await transaction`
				update training_plan
				set start_date = start_date - 7, updated_at = now()
				where id = ${firstWeek.planId}
			`;
			await transaction`update training_week set start_date = start_date - 7 where id = ${firstWeek.id}`;
			await transaction`
				update workout
				set scheduled_date = scheduled_date - 7, updated_at = now()
				where week_id = ${firstWeek.id}
			`;
		});
	} finally {
		await sql.end();
	}
}

async function createImportRecords(page: Page) {
	await page.getByText('Add import source', { exact: true }).click();
	const firstSelect = page.locator('select[name="workoutId"]').first();
	const selectedWorkoutId = await firstSelect.evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	if (!selectedWorkoutId) throw new Error('Visual fixture needs at least one workout candidate.');

	await page.getByLabel('Choose a planned workout').check();
	await firstSelect.selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'visual-linked.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2026-05-13', 3_100)
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();

	await page.getByLabel('Leave in inbox for review').check();
	await page.getByLabel('GPX file').setInputFiles({
		name: 'visual-unlinked.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2026-05-10', 10_000)
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText(/Added to the activity inbox\./)).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();
}

async function stableScreenshot(page: Page, name: string) {
	await page.evaluate(() => document.fonts.ready.then(() => undefined));
	await page.evaluate(() => {
		window.scrollTo(0, 0);
	});
	await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(resolve)));
	await expect(page).toHaveScreenshot(name, {
		animations: 'disabled',
		caret: 'hide',
		fullPage: true
	});
}

async function stableElementScreenshot(locator: ReturnType<Page['getByRole']>, name: string) {
	await locator.page().evaluate(() => document.fonts.ready.then(() => undefined));
	await expect(locator).toHaveScreenshot(name, {
		animations: 'disabled',
		caret: 'hide'
	});
}

function trainingDetailPanel(page: Page) {
	return page.locator('#event-detail-panel');
}

async function freezeBrowserDate(page: Page) {
	await page.addInitScript(fixedBrowserClockScript());
}

function gpxForDistance(date: string, distanceMeters: number): Buffer {
	const latitude = 45;
	const startLongitude = -63;
	const longitudeDelta = distanceMeters / (111_320 * Math.cos((latitude * Math.PI) / 180));
	const body = `<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="${latitude}" lon="${startLongitude}"><time>${date}T12:00:00Z</time></trkpt>
			<trkpt lat="${latitude}" lon="${startLongitude + longitudeDelta}"><time>${date}T12:30:00Z</time></trkpt>
		</trkseg></trk></gpx>`;
	return Buffer.from(body);
}
