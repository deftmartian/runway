import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate, testNowIso } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	fillValidPlanIntake,
	goToOnboardingStep,
	setAvailability,
	expectVisibleChoiceHeadingsReadable,
	holdPageAction,
	getUserId,
	makeFirstPlanWeekCurrent,
	finishBeginnerPhase,
	getCurrentGoalPlanState,
	expectNoHorizontalOverflow,
	addIsoDays
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('established onboarding creates the distance phase from a repeatable baseline', async ({
	page
}) => {
	const email = await createAccount(page);
	await expect(page.getByLabel('Race distance')).toHaveCount(0);
	await expect(page.getByLabel(/Established week/)).not.toBeChecked();
	await fillValidPlanIntake(page);
	await expect(page.getByText('Distance plan from an established week')).toBeVisible();
	const review = page.locator('.review-ledger');
	await expect(review).toContainText('12 km/week · 3 runs/week · longest 8 km');
	await expect(review).toContainText('Returning runner');
	await expect(review).toContainText('No health or running limits selected');
	await expect(review).toContainText('Mon · Wed · Sat');
	await expect(review).toContainText('Saturday');
	await expect(review).toContainText('America/Halifax');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);
	await expect(page.locator('.plan-assessment-evidence')).toContainText(
		/needed each week · .* runway default/
	);
	await expect(page.getByRole('link', { name: 'Change goal for ramp' })).toBeVisible();

	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({
		goalKind: 'race',
		goalState: 'active',
		startMode: 'established',
		distance: 'half',
		phase: 'distance'
	});
	expect(state.workoutCount).toBeGreaterThan(0);
	expect(state.totalTargetDistanceMeters).toBeGreaterThan(0);
});

test('a two-run established baseline can create a plan from two available days on mobile', async ({
	page
}) => {
	await page.setViewportSize({ width: 360, height: 800 });
	const email = await createAccount(page);
	await expectNoHorizontalOverflow(page);
	await expectVisibleChoiceHeadingsReadable(page);

	await page.getByLabel(/Established week/).check();
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 20 * 7));
	await page.getByRole('button', { name: 'Continue' }).click();
	const startingHeading = page.getByRole('heading', { name: 'Starting point' });
	await expect(startingHeading).toBeVisible();
	await expect
		.poll(async () => (await startingHeading.boundingBox())?.y ?? -1)
		.toBeGreaterThanOrEqual(60);
	await expectNoHorizontalOverflow(page);
	await page.getByLabel('Weekly distance (km)').fill('6');
	await page.getByLabel('Runs per week').fill('2');
	await page.getByLabel('Longest recent run (km)').fill('3');
	await page.getByLabel('Running experience').selectOption('new');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Tue', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await page.getByLabel('Preferred long-run day').selectOption('6');
	await expectNoHorizontalOverflow(page);
	await goToOnboardingStep(page, 'Review');
	await expectNoHorizontalOverflow(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);

	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({
		goalKind: 'race',
		goalState: 'active',
		startMode: 'established',
		distance: '5k',
		phase: 'distance'
	});
});

test('a two-day half-marathon plan requires an explicit concentration acknowledgement', async ({
	page
}) => {
	const email = await createAccount(page);
	await page.getByLabel(/Established week/).check();
	await page.getByLabel('Race distance').selectOption('half');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 30 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Weekly distance (km)').fill('24');
	await page.getByLabel('Runs per week').fill('2');
	await page.getByLabel('Longest recent run (km)').fill('14');
	await page.getByLabel('Running experience').selectOption('returning');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Tue', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await page.getByLabel('Preferred long-run day').selectOption('6');
	await goToOnboardingStep(page, 'Review');

	await expect(page.getByText('Two run days concentrate the weekly distance')).toBeVisible();
	const directActionBody = await page
		.locator('form[action="?/createPlan"]')
		.evaluate(async (form) => {
			const response = await fetch((form as HTMLFormElement).action, {
				method: 'POST',
				headers: { accept: 'application/json', 'x-sveltekit-action': 'true' },
				body: new FormData(form as HTMLFormElement)
			});
			return await response.text();
		});
	expect(directActionBody).toContain(
		'Confirm the two-day concentration before creating this plan.'
	);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding/);
	await expect(
		page.getByText('Confirm the two-day concentration before creating this plan.')
	).toBeVisible();

	await page.getByLabel(/Use two run days anyway/).check();
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);
	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({ distance: 'half', phase: 'distance' });
});

test('foundation-first onboarding keeps the race goal and creates the exact timed phase', async ({
	page
}) => {
	const email = await createAccount(page);
	const target = addIsoDays(testDate, 20 * 7);
	await page.getByLabel(/Foundation first/).check();
	await page.getByLabel('Race distance').selectOption('half');
	await page.getByLabel('Target date').fill(target);
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Running experience').selectOption('new');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Mon', 'Wed', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByText('Baseline confirmation required after week 9')).toBeVisible();
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);

	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({
		goalKind: 'race',
		goalState: 'active',
		startMode: 'foundation_to_goal',
		distance: 'half',
		phase: 'foundation',
		workoutCount: 63,
		timedWorkoutCount: 27,
		totalTargetDistanceMeters: 0
	});

	await makeFirstPlanWeekCurrent(await getUserId(email));
	await page.goto('/app/stats');
	const emptyStats = page.getByRole('region', { name: 'Start with the next workout' });
	await expect(emptyStats).toContainText('Plan length');
	await expect(emptyStats).toContainText('9 weeks');
	await expect(page.getByRole('region', { name: 'Plan versus actual' })).toHaveCount(0);
});

test('completed foundation work requires confirmation before the retained race phase starts', async ({
	page
}) => {
	const email = await createAccount(page);
	await page.getByLabel(/Foundation first/).check();
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 20 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Running experience').selectOption('new');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Mon', 'Wed', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);

	const userId = await getUserId(email);
	await finishBeginnerPhase(userId, { addAcceptedActivities: true });
	await page.goto('/app/history');
	await expect(
		page.getByRole('heading', { name: 'Confirm the recorded starting point' })
	).toBeVisible();
	await expect(page.locator('.phase-measures')).toContainText('Activities 6');
	await expect(page.locator('.phase-measures')).toContainText('Total distance 12 km');
	await expect(page.locator('.phase-measures')).toContainText('Recent weekly average 6 km');
	await expect(page.getByRole('heading', { name: 'Proposed race phase' })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Mark plan complete' })).toHaveCount(0);

	await page.getByLabel('Use these recorded values as the race-plan baseline').check();
	await page.getByRole('button', { name: 'Confirm and build race phase' }).click();
	await expect(
		page.getByText('Recorded baseline confirmed. The race phase is now active.')
	).toBeVisible();
	const state = await getCurrentGoalPlanState(userId);
	expect(state).toMatchObject({ goalKind: 'race', goalState: 'active', phase: 'distance' });
});

test('a completed beginner phase can be continued without inventing a baseline', async ({
	page
}) => {
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
	await page.goto('/app/history');
	await expect(page.locator('.phase-measures')).toContainText('0 km');
	await page.getByLabel('Repeat the latest foundation week').check();
	await page.getByRole('button', { name: 'Add another beginner week' }).click();
	await expect(
		page.getByText('One more beginner week was added. The recorded baseline was not changed.')
	).toBeVisible();
	const state = await getCurrentGoalPlanState(userId);
	expect(state).toMatchObject({
		goalKind: 'foundation',
		goalState: 'active',
		phase: 'foundation',
		workoutCount: 70,
		timedWorkoutCount: 30
	});
});

test('foundation-only onboarding works at a mobile viewport without inventing distance', async ({
	page
}) => {
	await page.setViewportSize({ width: 390, height: 844 });
	const email = await createAccount(page);
	await page.getByLabel(/30-minute foundation/).check();
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Running experience').selectOption('new');
	await expect(page.getByText('NHS Couch to 5K foundation')).toBeVisible();
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Tue', 'Thu', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);
	await expect(page.locator('body')).not.toHaveCSS('overflow-x', 'scroll');

	const userId = await getUserId(email);
	const state = await getCurrentGoalPlanState(userId);
	expect(state).toMatchObject({
		goalKind: 'foundation',
		goalState: 'active',
		startMode: 'foundation_only',
		distance: null,
		phase: 'foundation',
		workoutCount: 63,
		timedWorkoutCount: 27,
		totalTargetDistanceMeters: 0
	});
	await makeFirstPlanWeekCurrent(userId);
	await page.reload();
	await page.getByRole('button', { name: /Review \d+ missed runs?/ }).click();
	const panel = page.locator('#event-detail-panel');
	await expect(panel.getByRole('heading', { name: 'Run/walk instructions' })).toBeVisible();
	await expect(panel.getByText('Warm up · walk 5 min')).toBeVisible();
	await panel.getByText('Record run', { exact: true }).click();
	await expect(panel.getByLabel('Distance observed (km) Optional')).toBeVisible();
	await expect(
		panel.getByText(/does not turn this timed session into a distance target/)
	).toBeVisible();
	await panel.getByLabel('Result').selectOption('done');
	await panel.getByLabel('Distance observed (km) Optional').fill('2.2');
	await panel.getByRole('button', { name: /Save feedback/ }).click();
	await expect(panel.getByRole('heading', { name: 'Saved result' })).toBeVisible();
	await expect(panel.getByText('2.2 km', { exact: true })).toBeVisible();
	await panel.getByRole('button', { name: 'Close training detail' }).click();
	await expect(page.locator('.calendar-week-load').first()).toContainText('29 min done of 86 min');
	await page.getByRole('link', { name: 'History' }).click();
	await page.getByRole('link', { name: 'Plan record' }).click();
	await expect(
		page.getByRole('heading', { name: 'Run continuously for 30 minutes' })
	).toBeVisible();
	await expect(page.getByText('Training 1h 26m')).toBeVisible();
	await expect(page.getByText('29 min', { exact: true }).first()).toBeVisible();
});

test('two-week baseline creates two identical timed sessions per week', async ({ page }) => {
	const email = await createAccount(page);
	await page.getByLabel(/Two-week baseline/).check();
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 12 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Running experience').selectOption('new');
	await page.getByLabel('Comfortable total duration').selectOption('20');
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Tue', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByText('Distance remains observational')).toBeVisible();
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);

	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({
		goalKind: 'race',
		goalState: 'active',
		startMode: 'calibration',
		distance: '5k',
		phase: 'calibration',
		workoutCount: 14,
		timedWorkoutCount: 4,
		totalTargetDistanceMeters: 0,
		durations: [1_200]
	});
});

test('onboarding surfaces a hidden six-week date error and prevents duplicate submits', async ({
	page
}) => {
	await createAccount(page);
	await fillValidPlanIntake(page);
	await goToOnboardingStep(page, 'Goal');
	const targetInput = page.getByLabel('Target date');
	await targetInput.fill(addIsoDays(testDate, 6 * 7));
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(page.getByText('Choose a race date 8 to 52 weeks away.')).toBeVisible();
	await expect(targetInput).toHaveValue(addIsoDays(testDate, 6 * 7));

	await targetInput.fill(addIsoDays(testDate, 20 * 7));
	await goToOnboardingStep(page, 'Review');
	const heldCreate = await holdPageAction(page, '/app/onboarding', 'createPlan');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await heldCreate.observed;
	await expect(page.getByRole('button', { name: 'Saving…' })).toBeDisabled();
	heldCreate.release();
	await page.waitForURL(/\/app$/);
	await heldCreate.stop();
});

test('onboarding rejects a distance ramp unsupported by the established baseline', async ({
	page
}) => {
	await createAccount(page);
	await fillValidPlanIntake(page);
	const target = new Date(testNowIso);
	target.setUTCDate(target.getUTCDate() + 56);
	await goToOnboardingStep(page, 'Goal');
	await page.getByLabel('Target date').fill(target.toISOString().slice(0, 10));
	await page.getByLabel('Race distance').selectOption('marathon');
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Weekly distance (km)').fill('3');
	await page.getByLabel('Longest recent run (km)').fill('1');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page.getByText('This setup cannot produce a plan yet.')).toBeVisible();
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(
		page.getByText(
			"This goal is outside runway's plan-generation limits. Choose a later date or a shorter distance."
		)
	).toBeVisible();
	await expect(page.getByLabel('Race distance')).toHaveValue('marathon');
	await expect(page.getByLabel('Target date')).toHaveValue(target.toISOString().slice(0, 10));
});

test('onboarding enforces the chosen schedule and preserves the submitted values', async ({
	page
}) => {
	await createAccount(page);
	await fillValidPlanIntake(page);
	await goToOnboardingStep(page, 'Schedule');
	await page.getByLabel('Preferred long-run day').selectOption('1');
	await setAvailability(page, ['Mon', 'Wed']);
	await expect(page.locator('select[name="preferredLongRunDay"] option[value="6"]')).toHaveCount(0);
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByRole('heading', { name: 'Schedule' })).toBeVisible();
	await expect(
		page.getByText('Choose at least as many available days as current weekly runs.')
	).toBeVisible();
	await expect(
		page.locator('.day-choices label').filter({ hasText: 'Sat' }).locator('input')
	).not.toBeChecked();

	await setAvailability(page, ['Mon', 'Sat', 'Sun']);
	await page.getByLabel('Preferred long-run day').selectOption('6');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await goToOnboardingStep(page, 'Schedule');
	await expect(
		page.getByText('Availability must leave a recovery day after the long run.')
	).toBeVisible();
});

test('onboarding does not allow forward navigation past an incomplete step', async ({ page }) => {
	await createAccount(page);
	await expect(page.getByLabel('Running experience')).toHaveValue('');
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(page.getByText('Complete this step before continuing.')).toBeVisible();
	await expect(page.getByLabel(/Established week/)).toBeFocused();
});

test('onboarding requires an explicit experience choice before schedule setup', async ({
	page
}) => {
	await createAccount(page);
	await page.getByLabel(/Established week/).check();
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 20 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Weekly distance (km)').fill('6');
	await page.getByLabel('Runs per week').fill('2');
	await page.getByLabel('Longest recent run (km)').fill('3');
	await goToOnboardingStep(page, 'Schedule');
	await expect(page.getByRole('heading', { name: 'Starting point' })).toBeVisible();
	await expect(page.getByText('Complete this step before continuing.')).toBeVisible();
	await expect(page.getByLabel('Running experience')).toBeFocused();
});

test('foundation-first onboarding reserves time for foundation and race phases', async ({
	page
}) => {
	await createAccount(page);
	await expect(page.getByLabel('Target date')).toHaveCount(0);
	await expect(page.getByText('Choose this before the race date.')).toBeVisible();
	await page.getByLabel(/Established week/).check();
	await page.getByLabel('Race distance').selectOption('5k');
	const targetDate = page.getByLabel('Target date');
	await targetDate.fill(addIsoDays(testDate, 12 * 7));
	await page.getByLabel(/Foundation first/).check();
	await expect(targetDate).toHaveValue('');
	await expect(
		page.getByText(
			'Foundation first needs a different target date. Choose the path first, then set the date.'
		)
	).toBeVisible();
	await expect(page.getByText(/17–52 weeks ahead/)).toBeVisible();
	const minimum = await targetDate.getAttribute('min');
	if (!minimum) throw new Error('Foundation target-date minimum was not rendered.');
	await targetDate.fill(addIsoDays(minimum, -1));
	await goToOnboardingStep(page, 'Starting point');
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(
		page.getByText('Foundation first needs a race date 17 to 52 weeks away.')
	).toBeVisible();

	const response = await page.request.post('/app/onboarding?/createPlan', {
		headers: {
			origin: new URL(page.url()).origin,
			accept: 'application/json',
			'x-sveltekit-action': 'true'
		},
		form: {
			goalKind: 'race',
			startMode: 'foundation_to_goal',
			raceDistance: '5k',
			targetDate: addIsoDays(minimum, -1),
			priority: 'finish_healthy',
			experience: 'new',
			availability: '1',
			timeZone: 'America/Halifax'
		}
	});
	expect(response.status()).toBe(200);
	expect(await response.text()).toContain('Choose a date from');

	const pathlessResponse = await page.request.post('/app/onboarding?/createPlan', {
		headers: {
			origin: new URL(page.url()).origin,
			accept: 'application/json',
			'x-sveltekit-action': 'true'
		},
		form: {
			goalKind: 'race',
			raceDistance: '5k',
			targetDate: addIsoDays(testDate, 20 * 7),
			priority: 'finish_healthy',
			experience: 'new',
			availability: '1',
			timeZone: 'America/Halifax'
		}
	});
	const pathlessBody = await pathlessResponse.text();
	expect(pathlessBody).toContain('Choose how you are starting.');
	expect(pathlessBody).not.toContain('Choose a date from');
});

test('current pain saves a pending goal without creating workouts', async ({ page }) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await goToOnboardingStep(page, 'Starting point');
	await expect(page.getByLabel('Recovering from an injury').locator('..')).toContainText(
		'workouts can still be scheduled'
	);
	await expect(page.getByLabel('Pain is present now').locator('..')).toContainText(
		'without workouts'
	);
	await expect(
		page.getByLabel('A clinician has limited or paused my running').locator('..')
	).toContainText('without workouts');
	await page.getByLabel('Pain is present now').check();
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByText('Goal saved without workouts')).toBeVisible();
	await page.getByRole('button', { name: 'Save pending goal' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding\?pending=1$/);
	await expect(page.getByText(/Goal saved\. No workouts will be created/)).toBeVisible();

	const state = await getCurrentGoalPlanState(await getUserId(email));
	expect(state).toMatchObject({ goalKind: 'race', goalState: 'pending', phase: null });
	expect(state.workoutCount).toBe(0);
});

test('active goal can be replaced from the app flow', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Change goal', exact: true }).click();
	await expect(page.getByRole('heading', { name: 'Change goal' })).toBeVisible();
	await page.getByLabel('Race distance').selectOption('10k');
	await goToOnboardingStep(page, 'Starting point');
	await expect(page.getByLabel('Weekly distance (km)')).toHaveValue('12');
	await expect(page.getByLabel('Runs per week')).toHaveValue('3');
	await goToOnboardingStep(page, 'Review');
	await page.getByLabel(/Archive the current goal/).check();
	await page.getByRole('button', { name: 'Replace active plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.waitForLoadState('networkidle');
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();

	await page.goto('/app/plan');
	await expect(page).toHaveURL(/\/app$/);
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
	await page.getByRole('link', { name: 'Change goal', exact: true }).click();
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByRole('button', { name: 'Replace active plan' })).toBeVisible();

	await page.getByRole('link', { name: 'History' }).click();
	await expect(page.getByRole('heading', { name: 'Past plans' })).toBeVisible();
	await expect(page.getByText('Goal changed')).toBeVisible();
});
