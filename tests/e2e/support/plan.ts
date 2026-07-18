import { expect, type Page } from '@playwright/test';
import { testNowIso } from '../../support/test-clock';
import { createAccount } from './account';
import { getUserId, makeFirstPlanWeekCurrent } from './db';

export async function createPlan(page: Page) {
	const email = await createAccount(page);
	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.waitForLoadState('networkidle');
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
	await expect(page.getByRole('link', { name: 'Change goal' })).toBeVisible();
	await makeFirstPlanWeekCurrent(await getUserId(email));
	await page.reload();
	return email;
}

export async function fillValidPlanIntake(page: Page) {
	const target = new Date(testNowIso);
	target.setUTCDate(target.getUTCDate() + 20 * 7);
	await page.getByLabel(/Established week/).check();
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
}

export async function goToOnboardingStep(
	page: Page,
	step: 'Goal' | 'Starting point' | 'Schedule' | 'Review'
) {
	await page
		.getByRole('navigation', { name: 'Plan setup progress' })
		.getByRole('button', {
			name: new RegExp(`${step}$`)
		})
		.click();
}

export async function setAvailability(page: Page, selectedDays: string[]) {
	for (const day of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']) {
		const label = page.locator('.day-choices label').filter({ hasText: day });
		const input = label.locator('input');
		if ((await input.isChecked()) !== selectedDays.includes(day)) await label.click();
	}
}

export async function expectVisibleChoiceHeadingsReadable(page: Page) {
	const widths = await page
		.locator('.setup-step:not([hidden]) .choice-track strong')
		.evaluateAll((headings) => headings.map((heading) => heading.getBoundingClientRect().width));
	expect(widths.length).toBeGreaterThan(0);
	for (const width of widths) expect(width).toBeGreaterThan(100);
}
