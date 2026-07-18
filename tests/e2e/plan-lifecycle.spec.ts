import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	fillValidPlanIntake,
	holdPageAction,
	getUserId,
	moveActivePlanTargetDate
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('an active plan can be stopped explicitly and reviewed in History', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'History' }).click();
	await expect(page.getByRole('heading', { name: 'Half marathon plan' })).toBeVisible();
	await page.getByText('Stop this plan without marking it complete').click();
	await page.getByLabel('Stop Half marathon plan').check();
	const heldStop = await holdPageAction(page, '/app/history', 'archivePlan');
	await page.getByRole('button', { name: 'Stop plan' }).click();
	await heldStop.observed;
	await expect(page.getByRole('button', { name: 'Stopping plan…' })).toBeDisabled();
	heldStop.release();
	await expect(
		page.getByText('Plan stopped. Its workouts and recorded runs remain in History.')
	).toBeVisible();
	await heldStop.stop();
	await expect(page.getByRole('heading', { name: 'No active plan' })).toBeVisible();
	await expect(
		page
			.getByRole('article')
			.filter({ has: page.getByRole('heading', { name: 'Half marathon plan' }) })
	).toContainText('Stopped');
	await expect(page.getByRole('link', { name: 'Build a plan' })).toBeVisible();

	await page.getByRole('link', { name: 'Stats', exact: true }).click();
	await expect(page.locator('.stats-signal strong')).toHaveText('No active plan');
	await expect(page.getByRole('heading', { name: 'No active plan' })).toBeVisible();
	await expect(page.getByText('controlled', { exact: true })).toHaveCount(0);
	await expect(page.getByText('0% weekly increase target.')).toHaveCount(0);
});

test('a plan at its target date can be completed and reviewed in History', async ({ page }) => {
	const email = await createAccount(page);
	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await moveActivePlanTargetDate(await getUserId(email), testDate);

	await page.goto('/app');
	await expect(page.getByText('Target date reached.')).toBeVisible();
	await page.getByRole('link', { name: 'Review ended plan' }).click();
	await expect(page.getByRole('region', { name: 'Half marathon plan' })).toContainText(
		'Target date reached'
	);
	await page.getByLabel('Close this training block').check();
	await page.getByRole('button', { name: 'Mark plan complete' }).click();
	await expect(
		page.getByText('Plan completed. Its workouts and recorded runs remain in History.')
	).toBeVisible();
	await expect(page.getByRole('heading', { name: 'No active plan' })).toBeVisible();
	await expect(
		page
			.getByRole('article')
			.filter({ has: page.getByRole('heading', { name: 'Half marathon plan' }) })
	).toContainText('Completed');
});
