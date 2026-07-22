import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	getUserId,
	setTrainingTimeZone,
	getPlannedRuns,
	expectNoHorizontalOverflow,
	currentCalendarMonth,
	shiftCalendarMonth,
	calendarMonthLabel
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('training calendar month controls are URL-backed', async ({ page }) => {
	await createPlan(page);
	const currentMonth = currentCalendarMonth();
	const nextMonth = shiftCalendarMonth(currentMonth, 1);
	await expect(page.locator('.calendar-month-week:visible').first()).toBeVisible();
	await expect(page.locator('.calendar-week-load').first()).toBeVisible();
	await expect(page.locator('.week-load-track').first()).toBeVisible();
	await expect(page.getByText(/done of/).first()).toBeVisible();
	await expect(page.locator('.calendar-weekday-row')).toBeVisible();
	const visibleDayCount = await page.locator('.calendar-month-day').count();
	expect(visibleDayCount).toBeGreaterThanOrEqual(35);
	expect(visibleDayCount).toBeLessThanOrEqual(42);
	expect(visibleDayCount % 7).toBe(0);
	await page.getByRole('button', { name: /^Today\b/ }).click();
	await expect(page.locator('#event-detail-panel')).toBeVisible();

	await page.getByRole('link', { name: 'Next month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${nextMonth}`));
	await expect(page.getByText(calendarMonthLabel(nextMonth))).toBeVisible();

	await page.getByRole('link', { name: 'Previous month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${currentMonth}`));
	await expect(page.getByText(calendarMonthLabel(currentMonth))).toBeVisible();

	await page.getByRole('link', { name: 'Current month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${currentMonth}`));
});

test('authenticated app avoids horizontal overflow on mobile and desktop', async ({ page }) => {
	await createPlan(page);
	await page.setViewportSize({ width: 320, height: 800 });
	await page.goto('/app');
	await expect(page.getByText('Scroll sideways to see all seven days.')).toBeVisible();
	await expectNoHorizontalOverflow(page);

	for (const viewport of [
		{ width: 390, height: 844 },
		{ width: 1366, height: 900 }
	]) {
		await page.setViewportSize(viewport);
		for (const label of ['Calendar', 'Inbox', 'Stats', 'Settings']) {
			await page.getByRole('link', { name: label, exact: true }).click();
			await expectNoHorizontalOverflow(page);
		}
	}

	await page.setViewportSize({ width: 390, height: 844 });
	await page.evaluate(() => {
		document.documentElement.style.setProperty('font-size', '200%', 'important');
	});
	for (const [label, heading] of [
		['Calendar', 'Training calendar'],
		['Inbox', 'Activity inbox'],
		['Stats', 'Stats'],
		['History', 'History'],
		['Settings', 'Settings']
	] as const) {
		await page.getByRole('link', { name: label, exact: true }).click();
		await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible();
		await expectNoHorizontalOverflow(page);
		if (label === 'Calendar') {
			await expect(page.getByText('Scroll sideways to see all seven days.')).toBeVisible();
		}
	}
});

test('app navigation exposes route titles, skip navigation, and roving calendar focus', async ({
	page
}) => {
	await createPlan(page);
	await page.goto('/app');
	await expect(page).toHaveTitle('Training calendar · runway');

	await page.keyboard.press('Tab');
	const skipLink = page.getByRole('link', { name: 'Skip to main content' });
	await expect(skipLink).toBeFocused();
	await skipLink.press('Enter');
	await expect(page.locator('#app-content')).toBeFocused();

	const calendarEvents = page.locator('[data-calendar-event-id]:visible');
	await calendarEvents.first().focus();
	await page.keyboard.press('ArrowRight');
	await expect(calendarEvents.nth(1)).toBeFocused();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page).toHaveTitle('Stats · runway');
});

test('mobile training detail contains focus and locks background scrolling', async ({ page }) => {
	const email = await createPlan(page);
	await page.setViewportSize({ width: 390, height: 844 });
	const futureRun = (await getPlannedRuns(await getUserId(email))).find(
		(run) => run.scheduledDate > testDate
	);
	if (!futureRun) throw new Error('Plan did not create a future workout for the dialog test.');
	const futureRunButton = page
		.getByRole('button', { name: new RegExp(`^${futureRun.scheduledDate}:`) })
		.first();
	await expect(futureRunButton.locator('.event-compact em')).toContainText(futureRun.purpose);
	await futureRunButton.click();
	const panel = page.getByRole('dialog');
	await expect(panel).toBeVisible();
	await expect.poll(() => page.evaluate(() => document.body.style.overflow)).toBe('hidden');
	await panel.getByText('Edit planned workout', { exact: true }).click();
	await panel.evaluate((element) => {
		element.scrollTop = element.scrollHeight;
	});
	await expect(panel.getByRole('button', { name: 'Close training detail' })).toBeInViewport();
	const lastFocusable = panel
		.locator(
			'a[href]:visible, button:not([disabled]):visible, details summary:visible, input:not([disabled]):visible, select:not([disabled]):visible, textarea:not([disabled]):visible, [tabindex]:not([tabindex="-1"]):visible'
		)
		.last();
	await lastFocusable.focus();
	await page.keyboard.press('Tab');
	await expect(panel.getByRole('button', { name: 'Close training detail' })).toBeFocused();
	await expect(
		page.getByRole('navigation', { name: 'App navigation' }).getByText('Calendar')
	).not.toBeFocused();
	await page.keyboard.press('Escape');
	await expect(panel).toHaveCount(0);
	await expect.poll(() => page.evaluate(() => document.body.style.overflow)).not.toBe('hidden');
});

test('an empty past calendar day can record an unplanned run', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app');
	const openDay = page.getByRole('button', { name: /Open day, No plan/ }).first();
	await expect(openDay).toBeVisible();
	await openDay.click();
	await expect(page.getByRole('heading', { name: 'Open day' })).toBeVisible();
	await page.getByText('Record unplanned run', { exact: true }).click();
	await expect(page.getByText('No future workout changes automatically.')).toBeVisible();
});
