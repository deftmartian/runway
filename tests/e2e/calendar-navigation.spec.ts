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
	calendarMonthLabel,
	addIsoDays
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
	await expect(
		page.getByText('Swipe sideways to read every workout and see all seven days.')
	).toHaveCount(0);
	await expect(page.locator('.calendar-weekday-row')).toBeHidden();
	await expect(page.locator('.calendar-month-day:visible').first()).toBeVisible();
	await expect(
		page.locator('.calendar-event.open:visible .open-affordance em').first()
	).toBeVisible();
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
			await expect(
				page.getByText('Swipe sideways to read every workout and see all seven days.')
			).toHaveCount(0);
		}
	}
});

test('mobile day ledger uses chronological arrow navigation without horizontal scrolling', async ({
	page
}) => {
	await createPlan(page);
	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/app');

	const calendarEvents = page.locator('[data-calendar-event-id]:visible');
	const first = calendarEvents.first();
	const second = calendarEvents.nth(1);
	await first.focus();
	await page.keyboard.press('ArrowDown');
	await expect(second).toBeFocused();
	await page.keyboard.press('ArrowUp');
	await expect(first).toBeFocused();
	await page.keyboard.press('ArrowRight');
	await expect(second).toBeFocused();
	await page.keyboard.press('Home');
	await expect(first).toBeFocused();
	await page.keyboard.press('End');
	await expect(calendarEvents.last()).toBeFocused();

	const dimensions = await page.locator('.calendar-month-scroll').evaluate((element) => ({
		clientWidth: element.clientWidth,
		scrollWidth: element.scrollWidth
	}));
	expect(dimensions.scrollWidth).toBeLessThanOrEqual(dimensions.clientWidth + 1);
	await expectNoHorizontalOverflow(page);

	await page.setViewportSize({ width: 1280, height: 900 });
	await expect(
		page.getByText(
			'Use Left and Right for adjacent training days and Up and Down for the same weekday.'
		)
	).toBeVisible();
	const desktopFirst = calendarEvents.first();
	const desktopFirstLabel = await desktopFirst.getAttribute('aria-label');
	const desktopFirstDate = desktopFirstLabel?.slice(0, 10);
	if (!desktopFirstDate) throw new Error('Calendar event did not expose its date.');
	const nextWeek = page.getByRole('button', {
		name: new RegExp(`^${addIsoDays(desktopFirstDate, 7)}:`)
	});
	await desktopFirst.focus();
	await page.keyboard.press('ArrowDown');
	await expect(nextWeek).toBeFocused();
	await page.keyboard.press('ArrowUp');
	await expect(desktopFirst).toBeFocused();
});

test('calendar focus and state labels survive forced colors and reduced motion', async ({
	page
}) => {
	await page.emulateMedia({ forcedColors: 'active', reducedMotion: 'reduce' });
	await createPlan(page);
	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/app');

	const event = page.locator('[data-calendar-event-id]:visible').first();
	await event.focus();
	await expect(event).toBeFocused();
	const focusStyle = await event.evaluate((element) => {
		const style = getComputedStyle(element);
		const durations = style.transitionDuration.split(',').map((duration) => {
			const value = Number.parseFloat(duration);
			return duration.trim().endsWith('ms') ? value : value * 1000;
		});
		return {
			outlineStyle: style.outlineStyle,
			outlineWidth: Number.parseFloat(style.outlineWidth),
			longestTransitionMs: Math.max(0, ...durations)
		};
	});
	expect(focusStyle.outlineStyle).not.toBe('none');
	expect(focusStyle.outlineWidth).toBeGreaterThanOrEqual(2);
	expect(focusStyle.longestTransitionMs).toBeLessThanOrEqual(1);

	await page.getByText('Calendar state key', { exact: true }).click();
	const stateKey = page.locator('.calendar-state-legend');
	for (const label of [
		'Planned',
		'Completed',
		'Shortened',
		'Skipped',
		'Missed',
		'Review',
		'Rest',
		'Removed'
	]) {
		await expect(stateKey.getByText(label, { exact: true })).toBeVisible();
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
	await expect(page.getByRole('heading', { name: 'Stats' })).toBeFocused();
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
	await expect(futureRunButton.locator('.event-title')).toContainText(futureRun.purpose);
	await expect(futureRunButton.locator('.event-meta')).toBeVisible();
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
