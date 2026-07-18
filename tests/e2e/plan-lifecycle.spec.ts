import { expect, test, type Page } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	addIsoDays,
	createAccount,
	createPlan,
	finishBeginnerPhase,
	fillValidPlanIntake,
	getBeginnerContinuationState,
	getUserId,
	goToOnboardingStep,
	holdActivePlanMutationLock,
	holdPageAction,
	moveActivePlanTargetDate,
	setActivePlanWeekCount,
	setAvailability
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('an active plan can be stopped explicitly and reviewed in History', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'History' }).click();
	await expect(page.getByRole('heading', { name: 'Half marathon plan' })).toBeVisible();
	await expect(page.getByRole('region', { name: 'Half marathon plan' })).toContainText(
		'generated-week cap'
	);
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

test('concurrent beginner continuation requests add exactly one week', async ({ page }) => {
	const userId = await createCompletedFoundationPlan(page);
	await page.goto('/app/history');
	const lock = await holdActivePlanMutationLock(userId);
	const requests = page.evaluate(async () => {
		return await Promise.all(
			[0, 1].map(async () => {
				const response = await fetch('/app/history?/continuePhase', {
					method: 'POST',
					headers: {
						accept: 'application/json',
						'x-sveltekit-action': 'true',
						'content-type': 'application/x-www-form-urlencoded'
					},
					body: 'confirmContinuation=on'
				});
				return { status: response.status, body: await response.text() };
			})
		);
	});
	try {
		await lock.waitForBlockedRequests(2);
	} finally {
		lock.release();
	}
	const responses = await requests;
	await lock.done;

	expect(responses.map((response) => response.status)).toEqual([200, 200]);
	expect(
		responses.filter((response) =>
			response.body.includes(
				'One more beginner week was added. The recorded baseline was not changed.'
			)
		)
	).toHaveLength(1);
	expect(
		responses.filter((response) =>
			response.body.includes('That beginner week was already added. Nothing else changed.')
		)
	).toHaveLength(1);
	expect(await getBeginnerContinuationState(userId)).toEqual({
		planWeeks: 10,
		weekRows: 10,
		workoutRows: 70,
		continuationAudits: 1,
		targetDate: addIsoDays(testDate, 7)
	});
});

test('a beginner phase at 52 weeks reports the plan cap without changing data', async ({
	page
}) => {
	const userId = await createCompletedFoundationPlan(page);
	await setActivePlanWeekCount(userId, 52);
	const before = await getBeginnerContinuationState(userId);
	await page.goto('/app/history');
	await page.getByLabel('Repeat the latest foundation week').check();
	await page.getByRole('button', { name: 'Add another beginner week' }).click();
	await expect(
		page.getByText('The beginner phase has reached the 52-week plan limit.')
	).toBeVisible();
	expect(await getBeginnerContinuationState(userId)).toEqual(before);
});

async function createCompletedFoundationPlan(page: Page): Promise<string> {
	const email = await createAccount(page);
	await page.getByLabel(/30-minute foundation/).check();
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Running experience').selectOption('new');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Mon', 'Wed', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const userId = await getUserId(email);
	await finishBeginnerPhase(userId, { addAcceptedActivities: false });
	return userId;
}
