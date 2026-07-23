import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	createAccount,
	createPlan,
	fillValidPlanIntake,
	holdSettingsAction,
	getUserId,
	makeFirstPlanWeekCurrent,
	setTrainingTimeZone,
	getPlannedRuns,
	getWorkout,
	getFirstActivityId,
	getActivityDates,
	seedManualActivityRecords,
	getPlanAdjustmentTypes,
	hasDistanceAdjustment,
	activityExists,
	expectNoCriticalAxeViolations,
	openImportSourceSetup,
	waitForTrainingCalendarHydration,
	addIsoDays
} from './support/runway';
import { gpxForDistance } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('an empty inbox offers a direct review-only GPX upload', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app/import');

	await expect(page.getByText('No imported activities.')).toBeVisible();
	await expect(page.locator('details.source-setup')).toHaveAttribute('open', '');
	const sourceChoices = page.getByRole('group', { name: 'Choose an import source' });
	await expect(sourceChoices.getByRole('button', { name: /^Android folder/ })).toBeVisible();
	await expect(sourceChoices.getByRole('button', { name: /^Browser folder/ })).toBeVisible();
	await expect(sourceChoices.getByRole('button', { name: /^Nextcloud/ })).toBeVisible();
	const chooser = page.waitForEvent('filechooser');
	await page.getByRole('button', { name: 'Upload GPX', exact: true }).click();
	await (
		await chooser
	).setFiles({
		name: 'direct-inbox-upload.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(testDate, 2_000)
	});

	await expect(page.getByText(/Added to the activity inbox\./)).toBeVisible();
	const record = page.locator('details.activity-record').first();
	const reviewSummary = record.locator('summary');
	await expect(record.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();
	await expect(reviewSummary).toBeFocused();
	await expect(page.getByRole('button', { name: 'Review imported activity' })).toBeVisible();
	await page.getByRole('button', { name: 'Review imported activity' }).click();
	await expect(record).toHaveAttribute('open', '');
	await expect(record.getByRole('heading', { name: 'Route map', level: 2 })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
});

test('local account can create a conservative training plan and import GPX aggregates', async ({
	page
}) => {
	await createPlan(page);
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
	await page
		.getByRole('button', { name: /Review \d+ missed run/ })
		.first()
		.click();
	await page.locator('#event-detail-panel').getByText('Record run', { exact: true }).click();
	await expect(page.getByLabel('Result')).toHaveValue('skipped');
	await page
		.getByRole('button', { name: /Save feedback/ })
		.first()
		.click();
	await expect(page.getByText('Feedback saved.')).toBeVisible();
	await expect(page.locator('.calendar-event[data-status="skipped"]')).toBeVisible();
	await page.keyboard.press('Escape');

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'aggregate-test.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-14T12:01:00Z</time></trkpt>
			</trkseg></trk></gpx>`)
	});
	await page.getByLabel('Choose a planned workout').check();
	const plannedWorkout = page.locator('select[name="workoutId"]');
	const plannedWorkoutId = await plannedWorkout.evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(plannedWorkoutId).not.toBe('');
	await plannedWorkout.selectOption(plannedWorkoutId);
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText(/Imported .* km\./)).toBeVisible();
	await expectNoCriticalAxeViolations(page);

	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page.getByRole('heading', { name: 'Training calendar' })).toBeVisible();
	await expect(page.locator('.calendar-month-grid').first()).toBeVisible();
	await expect(page.locator('.calendar-event.actual').first()).toBeVisible();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Current-plan distance')).toBeVisible();
	await expect(page.getByText('Recorded pace', { exact: true }).first()).toBeVisible();
	await expect(page.getByText('Missed', { exact: true }).first()).toBeVisible();
});

test('athlete time zone controls future import dates without rewriting saved activities', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const uploadGpx = async (name: string, startedAt: string) => {
		const endedAt = new Date(new Date(startedAt).getTime() + 10 * 60_000).toISOString();
		await page.getByLabel('GPX file').setInputFiles({
			name,
			mimeType: 'application/gpx+xml',
			buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>${startedAt}</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>${endedAt}</time></trkpt>
				</trkseg></trk></gpx>`)
		});
		await page.getByRole('button', { name: 'Import', exact: true }).click();
		await expect(page.getByText(/Imported .* km\./)).toBeVisible();
	};

	await page.goto('/app/import');
	await openImportSourceSetup(page);
	await uploadGpx('halifax-midnight.gpx', '2026-05-15T01:30:00.000Z');
	await expect.poll(() => getActivityDates(userId)).toEqual(['2026-05-14']);

	await page.goto('/app/settings');
	await page.getByText('Time zone', { exact: true }).click();
	await expect(page.getByLabel('Training time zone')).toHaveValue('America/Halifax');
	await expect(
		page.getByText(/Changing it does not move existing saved activity dates/)
	).toBeVisible();
	await page.getByLabel('Training time zone').fill('Pacific/Kiritimati');
	const heldTimeZone = await holdSettingsAction(page, 'updateTimeZone');
	await page.getByRole('button', { name: 'Save time zone' }).click();
	await heldTimeZone.observed;
	await expect(page.getByRole('button', { name: 'Saving time zone…' })).toBeDisabled();
	heldTimeZone.release();
	await expect(page.getByText('Training time zone saved.')).toBeVisible();
	await heldTimeZone.stop();
	await expect.poll(() => getActivityDates(userId)).toEqual(['2026-05-14']);
	await page.reload();
	await page.getByText('Time zone', { exact: true }).click();
	await expect(page.getByLabel('Training time zone')).toHaveValue('Pacific/Kiritimati');

	await page.goto('/app/import');
	await openImportSourceSetup(page);
	await uploadGpx('kiritimati-midnight.gpx', '2026-05-14T13:30:00.000Z');
	await expect.poll(() => getActivityDates(userId)).toEqual(['2026-05-14', '2026-05-15']);
});

test('imported GPX counts actual load before an explicit future-plan decision', async ({
	page
}) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const userId = await getUserId(email);
	await makeFirstPlanWeekCurrent(userId);
	const [targetRun, nextRun] = await getPlannedRuns(userId);
	expect(targetRun).toBeDefined();
	expect(nextRun).toBeDefined();
	if (!targetRun || !nextRun) throw new Error('Plan did not create enough runs for import test.');
	const nextFutureRun = (await getPlannedRuns(userId)).find(
		(run) => run.scheduledDate >= testDate && run.id !== targetRun.id
	);
	if (!nextFutureRun) throw new Error('Plan did not create a future run for import test.');
	const nextFutureWorkout = await getWorkout(nextFutureRun.id);

	const completedDistance = Math.max(800, Math.round(targetRun.targetDistanceMeters * 0.5));
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Choose a planned workout').check();
	await page.locator('select[name="workoutId"]').selectOption(targetRun.id);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'shorter-than-plan.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(targetRun.scheduledDate, completedDistance)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	await expect(page.getByText(/Completed .* below plan\./)).toBeVisible();
	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('shortened');
	await expect
		.poll(async () => (await getWorkout(nextFutureRun.id)).targetDistanceMeters)
		.toBe(nextFutureRun.targetDistanceMeters);
	await expect.poll(() => hasDistanceAdjustment(userId, 'import_match')).toBe(false);

	await page.goto('/app');
	await waitForTrainingCalendarHydration(page);
	await page.getByRole('button', { name: new RegExp(`^${targetRun.scheduledDate}:`) }).click();
	const panel = page.locator('#event-detail-panel');
	await expect(panel.getByRole('heading', { name: 'Choose what changes next' })).toBeVisible();
	const reduceOption = panel.locator('.decision-option').filter({ hasText: 'reduce the next run' });
	await expect(reduceOption).toContainText(nextFutureWorkout.purpose);
	await expect(reduceOption).toContainText('changes from');
	await reduceOption.getByRole('button', { name: /reduce the next run/i }).click();
	await expect
		.poll(async () => (await getWorkout(nextFutureRun.id)).targetDistanceMeters)
		.toBeLessThan(nextFutureRun.targetDistanceMeters);
	await expect.poll(() => hasDistanceAdjustment(userId, 'decision')).toBe(true);
});

test('a selected off-day GPX match moves only that planned run to the actual date', async ({
	page
}) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const userId = await getUserId(email);
	await makeFirstPlanWeekCurrent(userId);
	const runs = await getPlannedRuns(userId);
	const targetRun = runs[1];
	expect(targetRun).toBeDefined();
	if (!targetRun) throw new Error('Plan did not create enough runs for import test.');
	const nextFutureRun = runs.find(
		(run) => run.scheduledDate >= testDate && run.id !== targetRun.id
	);
	if (!nextFutureRun) throw new Error('Plan did not create a future run for import test.');

	const offDate = addIsoDays(targetRun.scheduledDate, 1);

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Choose a planned workout').check();
	await page.locator('select[name="workoutId"]').selectOption(targetRun.id);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'off-day-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(offDate, targetRun.targetDistanceMeters + 1_000)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('done');
	await expect.poll(async () => (await getWorkout(targetRun.id)).scheduledDate).toBe(offDate);
	await expect
		.poll(async () => (await getWorkout(nextFutureRun.id)).targetDistanceMeters)
		.toBe(nextFutureRun.targetDistanceMeters);
});

test('import page can link, unlink, and delete activity records', async ({ page }) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const userId = await getUserId(email);
	await makeFirstPlanWeekCurrent(userId);
	const runs = await getPlannedRuns(userId);
	const targetRun = runs[0];
	expect(targetRun).toBeDefined();
	if (!targetRun) throw new Error('Plan did not create a run for record management test.');

	const today = testDate;
	const scheduledRunDates = new Set(runs.map((run) => run.scheduledDate));
	const offDate = [1, 2, 3, 4, -1, -2, -3]
		.map((offset) => addIsoDays(targetRun.scheduledDate, offset))
		.find((date) => date <= today && !scheduledRunDates.has(date));
	if (!offDate) throw new Error('Could not find a past off day for activity record test.');
	const adjustedRun = runs.find((run) => run.scheduledDate > today);
	if (!adjustedRun) throw new Error('Could not find a future run for activity adjustment test.');
	const adjustedRunBefore = await getWorkout(adjustedRun.id);

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'unlinked-record.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(offDate, Math.max(10_000, targetRun.targetDistanceMeters * 3))
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	const records = page.locator('.import-inbox');
	const record = records.locator('.activity-record').first();
	await expect(page.getByText(/Added to the activity inbox\./)).toBeVisible();
	const activityId = await getFirstActivityId(userId);
	await expect(record.locator('.state-marker')).toContainText('Needs review');
	await expect
		.poll(async () => (await getWorkout(adjustedRun.id)).targetDistanceMeters)
		.toBe(adjustedRun.targetDistanceMeters);

	await record.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await record.getByRole('button', { name: 'Count as extra training' }).click();
	await expect(page.getByText(/Unplanned run added .* to actual training load\./)).toBeVisible();
	await expect(record.getByText('Included in training load')).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(adjustedRun.id)).targetDistanceMeters)
		.toBe(adjustedRun.targetDistanceMeters);
	const adjustedAfterExtra = await getWorkout(adjustedRun.id);

	const availableWorkoutId = await record.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(availableWorkoutId).not.toBe('');
	const linkedWorkoutBefore = await getWorkout(availableWorkoutId);
	await record.locator('select[name="workoutId"]').selectOption(availableWorkoutId);
	await record.getByRole('button', { name: 'Link to workout' }).click();
	await expect(record.locator('.state-marker')).toContainText('Linked');
	await expect.poll(async () => (await getWorkout(availableWorkoutId)).scheduledDate).toBe(offDate);

	await record.getByRole('button', { name: 'Unlink' }).click();
	await expect(page.getByText('Activity unlinked from the workout.')).toBeVisible();
	await expect(record.getByText('Included in training load')).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(availableWorkoutId)).scheduledDate)
		.toBe(linkedWorkoutBefore.scheduledDate);
	await expect
		.poll(async () => (await getWorkout(adjustedRun.id)).targetDistanceMeters)
		.toBe(adjustedAfterExtra.targetDistanceMeters);

	await record.getByRole('button', { name: 'Delete activity' }).click();
	await expect(page.getByText('Activity deleted.')).toBeVisible();
	await expect(records.getByText('No imported activities.')).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(adjustedRun.id)).targetDistanceMeters)
		.toBe(adjustedRun.targetDistanceMeters);
	await expect
		.poll(async () => (await getWorkout(adjustedRun.id)).type)
		.toBe(adjustedRunBefore.type);
	const adjustmentTypes = await getPlanAdjustmentTypes(userId);
	expect(adjustmentTypes).not.toContain('link');
	expect(adjustmentTypes).not.toContain('import_extra');

	const exportResponse = await page.request.post('/app/settings/export.json', {
		headers: { origin: new URL(page.url()).origin }
	});
	expect(exportResponse.status()).toBe(200);
	const exported = (await exportResponse.json()) as { adjustments: unknown[] };
	expect(JSON.stringify(exported.adjustments)).not.toContain(activityId);
	expect(JSON.stringify(exported.adjustments)).not.toContain(offDate);
});

test('review-only imports do not enter actual totals until the runner accepts them', async ({
	page
}) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'review-before-actual.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(testDate, 10_000)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	const record = page.locator('.activity-record').first();
	await expect(record.locator('.state-marker')).toContainText('Needs review');
	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page.locator('.calendar-event.actual')).toHaveCount(0);

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Nothing to compare yet', { exact: true })).toBeVisible();
	await expect(page.getByText('Recorded distance', { exact: true })).toHaveCount(0);

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	const reviewRecord = page.locator('.activity-record').first();
	await reviewRecord.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await reviewRecord.getByRole('button', { name: 'Count as extra training' }).click();
	await expect(reviewRecord.getByText('Included in training load')).toBeVisible();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Recorded distance', { exact: true }).locator('..')).toContainText(
		'10 km'
	);
});

test('auto-match completes a single close planned workout without a no-op adjustment', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const [targetRun] = await getPlannedRuns(userId);
	if (!targetRun) throw new Error('Plan did not create an auto-match candidate.');

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Auto-match by date and distance').check();
	await page.getByLabel('GPX file').setInputFiles({
		name: 'single-close-match.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(targetRun.scheduledDate, targetRun.targetDistanceMeters)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	await expect(page.getByText(/Auto-matched to a planned workout\./)).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Linked' })).toBeVisible();
	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('done');
	await expect.poll(() => hasDistanceAdjustment(userId, 'import_match')).toBe(false);
});

test('ambiguous auto-match leaves the activity and plan unchanged for review', async ({ page }) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const [mondayRun, wednesdayRun] = await getPlannedRuns(userId);
	if (!mondayRun || !wednesdayRun) throw new Error('Plan did not create two match candidates.');
	const activityDate = addIsoDays(mondayRun.scheduledDate, 1);

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Auto-match by date and distance').check();
	await page.getByLabel('GPX file').setInputFiles({
		name: 'ambiguous-off-day.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(activityDate, mondayRun.targetDistanceMeters)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();

	await expect(page.getByText('No workout matched. Added to the activity inbox.')).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();
	await expect.poll(async () => (await getWorkout(mondayRun.id)).status).toBe('planned');
	await expect.poll(async () => (await getWorkout(wednesdayRun.id)).status).toBe('planned');
	await expect.poll(() => hasDistanceAdjustment(userId, 'import_match')).toBe(false);
});

test('old activity can be kept as extra history without changing the current plan', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Plan did not create a future run for history-only import test.');

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'historical-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2024-05-12', 5_000)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await page.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Count as extra training' }).click();

	await expect(
		page.getByText('Activity counted as extra training. Current plan unchanged.')
	).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(futureRun.id)).targetDistanceMeters)
		.toBe(futureRun.targetDistanceMeters);
	await expect.poll(() => hasDistanceAdjustment(userId, 'import_extra')).toBe(false);
});

test('editing extra-activity pain never changes the current plan without a decision', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Plan did not create a future run for feedback replay test.');

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'editable-extra.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2026-05-12', 1_000)
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	const record = page.locator('.activity-record').first();
	await record.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await record.getByRole('button', { name: 'Count as extra training' }).click();
	await expect
		.poll(async () => (await getWorkout(futureRun.id)).targetDistanceMeters)
		.toBe(futureRun.targetDistanceMeters);
	const countedExtraTarget = (await getWorkout(futureRun.id)).targetDistanceMeters;

	await record.getByRole('checkbox', { name: 'Pain changed or limited this run' }).check();
	await record.getByRole('button', { name: 'Save feedback' }).click();
	await expect(page.getByText('Activity feedback updated.')).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(futureRun.id)).targetDistanceMeters)
		.toBe(countedExtraTarget);

	await record.getByRole('checkbox', { name: 'Pain changed or limited this run' }).uncheck();
	await record.getByRole('button', { name: 'Save feedback' }).click();
	await expect
		.poll(async () => (await getWorkout(futureRun.id)).targetDistanceMeters)
		.toBe(countedExtraTarget);
});

test('activity inbox paging keeps the total while loading older records', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await seedManualActivityRecords(await getUserId(email), 55);
	await page.goto('/app/import');

	const records = page.locator('.import-inbox');
	await expect(records.locator('.activity-record')).toHaveCount(50);
	await page.getByRole('link', { name: 'Older activities' }).click();
	await expect(page).toHaveURL(/\/app\/import\?offset=50$/);
	await expect(records.locator('.activity-record')).toHaveCount(5);
	await expect(page.getByRole('link', { name: 'Older activities' })).toHaveCount(0);
});

test('manual activity linking rejects workouts outside the match window', async ({ page }) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const userId = await getUserId(email);
	await makeFirstPlanWeekCurrent(userId);
	const runs = await getPlannedRuns(userId);
	const targetRun = runs[0];
	expect(targetRun).toBeDefined();
	if (!targetRun) throw new Error('Plan did not create a run for stale link test.');

	const scheduledRunDates = new Set(runs.map((run) => run.scheduledDate));
	const offDate = [1, 2, 3, 4, -1, -2, -3]
		.map((offset) => addIsoDays(targetRun.scheduledDate, offset))
		.find((date) => date <= testDate && !scheduledRunDates.has(date));
	if (!offDate) throw new Error('Could not find a past off day for stale link test.');

	const farRun = runs.find(
		(run) => Math.abs(Date.parse(run.scheduledDate) - Date.parse(offDate)) > 4 * 86_400_000
	);
	if (!farRun) throw new Error('Plan did not create a far workout for stale link test.');

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'stale-link-record.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(offDate, Math.max(10_000, targetRun.targetDistanceMeters * 3))
	});
	await page.getByRole('button', { name: 'Import', exact: true }).click();
	await expect(page.getByText(/Added to the activity inbox\./)).toBeVisible();
	const activityId = await getFirstActivityId(userId);

	const response = await page.request.post('/app/import?/linkActivity', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			activityId,
			workoutId: farRun.id
		}
	});
	expect(response.status()).toBeLessThan(500);
	await expect(response.text()).resolves.toContain('Workout is outside the activity match window.');
	await expect.poll(() => activityExists(activityId)).toBe(true);
});
