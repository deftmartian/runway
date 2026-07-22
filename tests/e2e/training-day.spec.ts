import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	createPlan,
	getUserId,
	getPlannedRuns,
	getWorkout,
	getVisibleWorkoutsOnDate,
	expectNoCriticalAxeViolations,
	addIsoDays
} from './support/runway';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('rest days can record manual unplanned runs', async ({ page }) => {
	await createPlan(page);
	const restEvents = page.locator('.calendar-event.rest');
	const restCount = await restEvents.count();
	let foundRecordableRestDay = false;

	for (let index = 0; index < restCount; index += 1) {
		await restEvents.nth(index).click();
		try {
			await expect(page.getByText('Record unplanned run')).toBeVisible({ timeout: 500 });
			foundRecordableRestDay = true;
			break;
		} catch {
			await page.getByText('Close', { exact: true }).click();
		}
	}

	expect(foundRecordableRestDay).toBe(true);
	await page.getByText('Record unplanned run').click();
	await page.getByLabel('Distance (km)').fill('2');
	await page.getByLabel('Duration (min)').fill('20');
	await page.getByRole('button', { name: /Save unplanned run/ }).click();
	await expect(page.getByText('Run recorded.')).toBeVisible();
	await page.getByText('Close', { exact: true }).click();

	await page.getByRole('link', { name: 'Inbox' }).click();
	const records = page.locator('.import-inbox');
	await expect(records.getByText('MANUAL · 20 min')).toBeVisible();
	await expect(records.locator('.record-copy strong')).toContainText('2 km');
});

test('today keeps saved unsafe feedback visible after reload', async ({ page }) => {
	await createPlan(page);
	await page
		.getByRole('button', { name: /Review \d+ missed run/ })
		.first()
		.click();
	await page.locator('#event-detail-panel').getByText('Record run', { exact: true }).click();
	await page.getByRole('checkbox', { name: 'Pain changed or limited this run' }).first().check();
	await page
		.getByRole('button', { name: /Save feedback/ })
		.first()
		.click();
	await expect(page.getByText('Feedback saved.')).toBeVisible();
	await expect(
		page
			.locator('#event-detail-panel')
			.getByText(/Pain was reported for this run\./)
			.first()
	).toBeVisible();

	await page.reload();
	await page.locator('.calendar-event.pain').first().click();
	await expect(
		page
			.locator('#event-detail-panel')
			.getByText(/Pain was reported for this run\./)
			.first()
	).toBeVisible();
});

test('future workout edits preview effects and support reset, undo, remove, and restore', async ({
	page
}) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Plan did not create a future workout for editing.');
	const original = await getWorkout(futureRun.id);
	const movedDate = addIsoDays(original.scheduledDate, 1);
	const editedDistanceKm = original.targetDistanceMeters / 1_000 + 1;

	await page
		.getByRole('button', { name: new RegExp(`^${original.scheduledDate}:`) })
		.first()
		.click();
	const panel = page.locator('#event-detail-panel');
	await panel.getByText('Edit planned workout', { exact: true }).click();
	const editor = panel.locator('.workout-editor').first();
	await editor.getByLabel('Date').fill(movedDate);
	await editor.getByLabel('Distance (km)').fill(String(editedDistanceKm));
	await editor.getByLabel('Purpose').fill('Edited easy run');
	await editor.getByLabel('Reason for the change (optional)').fill('E2E edit review');
	await editor.getByRole('button', { name: 'Preview change' }).click();

	const preview = panel.locator('.edit-preview');
	await expect(preview.getByRole('heading', { name: 'Before applying' })).toBeVisible();
	await expect(preview).toContainText('Generated');
	await expect(preview).toContainText('Current');
	await expect(preview).toContainText('Proposed');
	await expect(preview).toContainText('Projected plan ramp');
	await preview.getByRole('button', { name: /^Apply/ }).click();
	await expect
		.poll(async () => await getWorkout(futureRun.id))
		.toMatchObject({
			scheduledDate: movedDate,
			targetDistanceMeters: Math.round(editedDistanceKm * 1_000),
			purpose: 'Edited easy run'
		});

	await panel.getByRole('button', { name: 'Reset to generated' }).click();
	await expect
		.poll(async () => await getWorkout(futureRun.id))
		.toMatchObject({
			scheduledDate: original.scheduledDate,
			targetDistanceMeters: original.targetDistanceMeters,
			type: original.type,
			isRemoved: false
		});

	await panel.getByText('Edit planned workout', { exact: true }).click();
	await editor.getByLabel('Workout target').selectOption('rest');
	await editor.getByLabel('Purpose').fill('Extra recovery');
	await editor.getByRole('button', { name: 'Preview change' }).click();
	await panel
		.locator('.edit-preview')
		.getByRole('button', { name: /^Apply/ })
		.click();
	await expect
		.poll(async () => await getWorkout(futureRun.id))
		.toMatchObject({
			prescriptionKind: 'rest',
			type: 'rest',
			targetDistanceMeters: 0
		});

	await panel.getByRole('button', { name: 'Undo last change' }).click();
	await expect
		.poll(async () => await getWorkout(futureRun.id))
		.toMatchObject({
			prescriptionKind: original.prescriptionKind,
			type: original.type,
			targetDistanceMeters: original.targetDistanceMeters
		});

	await panel.getByRole('button', { name: 'Preview removal' }).click();
	await expect(panel.getByRole('heading', { name: 'Review removal' })).toBeVisible();
	await panel
		.getByRole('checkbox', {
			name: 'I reviewed the removed prescription, weekly load, and projected ramp.'
		})
		.check();
	await panel.getByRole('button', { name: 'Remove workout' }).click();
	await expect.poll(async () => (await getWorkout(futureRun.id)).isRemoved).toBe(true);
	await panel.getByRole('button', { name: 'Undo removal' }).click();
	await expect.poll(async () => (await getWorkout(futureRun.id)).isRemoved).toBe(false);
});

test('a future day can hold a second runner-added workout', async ({ page }) => {
	const email = await createPlan(page);
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Plan did not create a future day for adding a workout.');
	const before = await getVisibleWorkoutsOnDate(userId, futureRun.scheduledDate);

	await page
		.getByRole('button', { name: new RegExp(`^${futureRun.scheduledDate}:`) })
		.first()
		.click();
	const panel = page.locator('#event-detail-panel');
	const addSummary = panel.getByText('Add another planned workout', { exact: true });
	const addDetails = addSummary.locator('..');
	await addSummary.click();
	const addEditor = addDetails.locator('.workout-editor');
	await addEditor.getByLabel('Distance (km)').fill('0.1');
	await addEditor.getByLabel('Purpose').fill('Short shakeout');
	await addEditor.getByLabel('Reason for the change (optional)').fill('Second session test');
	await addEditor.getByRole('button', { name: 'Preview workout' }).click();
	await expect(addDetails.locator('.edit-preview')).toContainText(
		'No generated recommendation; this is a runner-added workout.'
	);
	await expect(addDetails.locator('.edit-preview')).toContainText('No workout scheduled.');
	await expect(addDetails.locator('.edit-preview')).toContainText('Within default');
	await expect(addDetails.locator('.edit-preview')).not.toContainText('Outside default');
	await addDetails
		.locator('.edit-preview')
		.getByRole('button', { name: /^Apply/ })
		.click();

	await expect
		.poll(async () => await getVisibleWorkoutsOnDate(userId, futureRun.scheduledDate))
		.toHaveLength(before.length + 1);
	const added = (await getVisibleWorkoutsOnDate(userId, futureRun.scheduledDate)).find(
		(workout) => workout.purpose === 'Short shakeout'
	);
	expect(added).toMatchObject({ purpose: 'Short shakeout', targetDistanceMeters: 100 });
	if (!added) throw new Error('Runner-added workout was not found.');

	await panel.getByRole('button', { name: 'Close training detail' }).click();
	await page
		.getByRole('button', { name: new RegExp(`^${futureRun.scheduledDate}: Short shakeout`) })
		.click();
	await panel.getByRole('button', { name: 'Preview removal' }).click();
	await panel
		.getByRole('checkbox', {
			name: 'I reviewed the removed prescription, weekly load, and projected ramp.'
		})
		.check();
	await panel.getByRole('button', { name: 'Remove workout' }).click();
	await expect.poll(async () => (await getWorkout(added.id)).isRemoved).toBe(true);
	await panel.getByRole('button', { name: 'Undo removal' }).click();
	await expect.poll(async () => (await getWorkout(added.id)).isRemoved).toBe(false);
});

test('saved workout feedback shows actuals and can be undone and recorded again', async ({
	page
}) => {
	await createPlan(page);
	await page
		.getByRole('button', { name: /Review \d+ missed run/ })
		.first()
		.click();
	const panel = page.locator('#event-detail-panel');
	await panel.getByText('Record run').click();
	await panel.getByLabel('Result').selectOption('done');
	await panel.getByLabel('Distance completed (km)').fill('1');
	await panel.getByLabel('Duration completed (min, optional)').fill('12');
	await panel.getByRole('button', { name: /Save feedback/ }).click();

	await expect(panel.getByRole('heading', { name: 'Saved result' })).toBeVisible();
	await expect(panel.getByText('1 km', { exact: true })).toBeVisible();
	await expect(panel.getByText('12 min', { exact: true })).toBeVisible();
	await expect(page.locator('.calendar-event.actual[data-status="shortened"]')).toBeVisible();
	await expectNoCriticalAxeViolations(page);

	page.once('dialog', (dialog) => dialog.accept());
	await panel.getByRole('button', { name: 'Undo saved result' }).click();
	await expect(panel.getByRole('heading', { name: 'Saved result' })).not.toBeVisible();
	await expect(panel.getByText('Record run')).toBeVisible();

	await panel.getByText('Record run').click();
	await panel.getByLabel('Result').selectOption('done');
	await panel.getByLabel('Duration completed (min, optional)').fill('15');
	await panel.getByRole('button', { name: /Save feedback/ }).click();
	await expect(panel.getByRole('heading', { name: 'Saved result' })).toBeVisible();
	await expect(page.locator('.calendar-event.actual[data-status="completed"]')).toBeVisible();
});
