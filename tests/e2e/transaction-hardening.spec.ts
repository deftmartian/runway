import { expect, test } from '@playwright/test';
import { fixedBrowserClockScript, testDate } from '../support/test-clock';
import {
	activityExists,
	addIsoDays,
	createPlan,
	getActivityDeletionResidue,
	getActivityDates,
	getActivityLinkRaceState,
	getFirstActivityId,
	getFirstPlanWeekStartDate,
	getLatestManualAdjustmentId,
	getPastRestWorkoutId,
	getPlannedRuns,
	getUndoRaceState,
	getUserId,
	getWorkout,
	holdActivePlanMutationLock,
	holdActivityMutationLock,
	holdActivityOwnerMutationLock,
	holdAdjustmentMutationLock,
	holdWorkoutMutationLock,
	moveWorkoutToDate
} from './support/runway';
import { gpxForDistance } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('activity deletion serializes behind a claimed consequence decision', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const recorded = await page.request.post('/app?/recordManualRun', {
		multipart: {
			occurredDate: testDate,
			distanceKm: '3',
			durationMinutes: '25',
			pain: 'on'
		}
	});
	expect(recorded.status()).toBe(200);
	const activityId = await getFirstActivityId(userId);
	const held = await holdActivityMutationLock(activityId);
	try {
		const decision = page.request.post('/app?/applyPlanDecision', {
			multipart: { source: 'activity', sourceId: activityId, decision: 'reduce_next' }
		});
		await held.waitForBlockedRequests(1);
		const deletion = page.request.post('/app?/deleteActivity', {
			multipart: { activityId }
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const [decisionResponse, deletionResponse] = await Promise.all([decision, deletion]);
		expect(decisionResponse.status()).toBe(200);
		expect(deletionResponse.status()).toBe(200);
	} finally {
		held.release();
		await held.done;
	}

	await expect.poll(() => activityExists(activityId)).toBe(false);
	await expect
		.poll(() => getActivityDeletionResidue(userId, activityId))
		.toEqual({
			activityCount: 0,
			adjustmentCount: 0,
			auditCount: 0
		});
});

test('concurrent links complete one workout and write one ledger trail', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const [firstRun, secondRun] = await getPlannedRuns(userId);
	if (!firstRun || !secondRun) throw new Error('Two link candidates were not found.');
	const activityDate = addIsoDays(firstRun.scheduledDate, 1);
	if (activityDate > testDate || secondRun.scheduledDate > addIsoDays(activityDate, 3)) {
		throw new Error('The current plan did not create two past link candidates.');
	}
	const recorded = await page.request.post('/app?/recordManualRun', {
		multipart: {
			occurredDate: activityDate,
			distanceKm: String(firstRun.targetDistanceMeters / 1_000),
			durationMinutes: '25'
		}
	});
	expect(recorded.status()).toBe(200);
	const activityId = await getFirstActivityId(userId);
	const held = await holdActivityOwnerMutationLock(userId);
	try {
		const firstLink = page.request.post('/app?/linkActivity', {
			multipart: { activityId, workoutId: firstRun.id }
		});
		await held.waitForBlockedRequests(1);
		const secondLink = page.request.post('/app?/linkActivity', {
			multipart: { activityId, workoutId: secondRun.id }
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const responses = await Promise.all([firstLink, secondLink]);
		expect(responses.map((response) => response.status())).toEqual([200, 200]);
		const responseBodies = await Promise.all(responses.map((response) => response.text()));
		expect(
			responseBodies.filter((body) => body.includes('Activity is already linked.'))
		).toHaveLength(1);
	} finally {
		held.release();
		await held.done;
	}

	const state = await getActivityLinkRaceState(userId, activityId, firstRun.id, secondRun.id);
	expect([firstRun.id, secondRun.id]).toContain(state.activityWorkoutId);
	expect(state).toMatchObject({
		feedbackCount: 1,
		completedWorkoutCount: 1,
		activeLinkAdjustmentCount: 1,
		linkAuditCount: 1
	});
});

test('link followed by deletion cannot leave a completed phantom workout', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const [targetRun] = await getPlannedRuns(userId);
	if (!targetRun) throw new Error('A link candidate was not found.');
	const originalWorkout = await getWorkout(targetRun.id);
	const recorded = await page.request.post('/app?/recordManualRun', {
		multipart: {
			occurredDate: targetRun.scheduledDate,
			distanceKm: String(targetRun.targetDistanceMeters / 1_000),
			durationMinutes: '25'
		}
	});
	expect(recorded.status()).toBe(200);
	const activityId = await getFirstActivityId(userId);
	const held = await holdActivityOwnerMutationLock(userId);
	try {
		const link = page.request.post('/app?/linkActivity', {
			multipart: { activityId, workoutId: targetRun.id }
		});
		await held.waitForBlockedRequests(1);
		const deletion = page.request.post('/app?/deleteActivity', {
			multipart: { activityId }
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const [linkResponse, deletionResponse] = await Promise.all([link, deletion]);
		expect(linkResponse.status()).toBe(200);
		expect(deletionResponse.status()).toBe(200);
	} finally {
		held.release();
		await held.done;
	}

	await expect.poll(() => getWorkout(targetRun.id)).toEqual(originalWorkout);
	await expect
		.poll(() => getActivityDeletionResidue(userId, activityId))
		.toEqual({ activityCount: 0, adjustmentCount: 0, auditCount: 0 });
});

test('unlink followed by deletion restores the workout exactly once', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const [targetRun] = await getPlannedRuns(userId);
	if (!targetRun) throw new Error('A link candidate was not found.');
	const originalWorkout = await getWorkout(targetRun.id);
	const recorded = await page.request.post('/app?/recordManualRun', {
		multipart: {
			occurredDate: targetRun.scheduledDate,
			distanceKm: String(targetRun.targetDistanceMeters / 1_000),
			durationMinutes: '25'
		}
	});
	expect(recorded.status()).toBe(200);
	const activityId = await getFirstActivityId(userId);
	const linked = await page.request.post('/app?/linkActivity', {
		multipart: { activityId, workoutId: targetRun.id }
	});
	expect(linked.status()).toBe(200);

	const held = await holdActivityOwnerMutationLock(userId);
	try {
		const unlink = page.request.post('/app?/unlinkActivity', { multipart: { activityId } });
		await held.waitForBlockedRequests(1);
		const deletion = page.request.post('/app?/deleteActivity', {
			multipart: { activityId }
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const [unlinkResponse, deletionResponse] = await Promise.all([unlink, deletion]);
		expect(unlinkResponse.status()).toBe(200);
		expect(deletionResponse.status()).toBe(200);
	} finally {
		held.release();
		await held.done;
	}

	await expect.poll(() => getWorkout(targetRun.id)).toEqual(originalWorkout);
	await expect
		.poll(() => getActivityDeletionResidue(userId, activityId))
		.toEqual({ activityCount: 0, adjustmentCount: 0, auditCount: 0 });
});

test('an import match wins cleanly over a queued future workout edit', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const targetRun = (await getPlannedRuns(userId)).find(
		(run) => run.scheduledDate > testDate && run.scheduledDate <= addIsoDays(testDate, 3)
	);
	if (!targetRun) throw new Error('A near future import candidate was not found.');
	const originalWorkout = await getWorkout(targetRun.id);
	const held = await holdWorkoutMutationLock(targetRun.id);
	try {
		const imported = page.request.post('/app/import?/importGpx', {
			multipart: {
				file: {
					name: 'edit-import-race.gpx',
					mimeType: 'application/gpx+xml',
					buffer: gpxForDistance(testDate, targetRun.targetDistanceMeters)
				},
				matchMode: 'workout',
				workoutId: targetRun.id
			}
		});
		await held.waitForBlockedRequests(1);
		const edit = page.request.post('/app?/applyWorkoutEdit', {
			multipart: {
				workoutId: targetRun.id,
				scheduledDate: originalWorkout.scheduledDate,
				type: originalWorkout.type,
				prescriptionKind: 'distance',
				distanceKm: String(originalWorkout.targetDistanceMeters / 1_000 + 1),
				intensity: 'easy',
				purpose: 'Queued edit should not overwrite imported completion',
				userReason: 'Transaction hardening test',
				confirmRisk: 'on'
			}
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const [importResponse, editResponse] = await Promise.all([imported, edit]);
		expect(importResponse.status()).toBe(200);
		expect(editResponse.status()).toBe(200);
		await expect(editResponse.text()).resolves.toContain('Future workout not found.');
	} finally {
		held.release();
		await held.done;
	}

	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('done');
	await expect
		.poll(async () => (await getWorkout(targetRun.id)).targetDistanceMeters)
		.toBe(originalWorkout.targetDistanceMeters);
});

test('link and import matches reject dates outside real plan weeks', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const [targetRun] = await getPlannedRuns(userId);
	if (!targetRun) throw new Error('A boundary match candidate was not found.');
	const outsideDate = addIsoDays(await getFirstPlanWeekStartDate(userId), -1);
	await moveWorkoutToDate(targetRun.id, outsideDate);
	const recorded = await page.request.post('/app?/recordManualRun', {
		multipart: {
			occurredDate: outsideDate,
			distanceKm: String(targetRun.targetDistanceMeters / 1_000),
			durationMinutes: '25'
		}
	});
	expect(recorded.status()).toBe(200);
	const activityId = await getFirstActivityId(userId);
	const linked = await page.request.post('/app?/linkActivity', {
		multipart: { activityId, workoutId: targetRun.id }
	});
	expect(linked.status()).toBe(200);
	await expect(linked.text()).resolves.toContain('Activity date is outside the active plan weeks.');

	const imported = await page.request.post('/app/import?/importGpx', {
		multipart: {
			file: {
				name: 'outside-real-plan-week.gpx',
				mimeType: 'application/gpx+xml',
				buffer: gpxForDistance(outsideDate, targetRun.targetDistanceMeters)
			},
			matchMode: 'workout',
			workoutId: targetRun.id
		}
	});
	expect(imported.status()).toBe(200);
	await expect(imported.text()).resolves.toContain(
		'Activity date is outside the active plan weeks.'
	);
	await expect.poll(() => getActivityDates(userId)).toEqual([outsideDate]);
	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('planned');
});

test('concurrent undo requests reverse and audit a manual change once', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Future workout was not found.');
	const original = await getWorkout(futureRun.id);
	const applied = await page.request.post('/app?/applyWorkoutEdit', {
		multipart: {
			workoutId: futureRun.id,
			scheduledDate: original.scheduledDate,
			type: original.type,
			prescriptionKind: 'distance',
			distanceKm: String(original.targetDistanceMeters / 1_000 + 1),
			intensity: 'easy',
			purpose: 'Concurrent undo check',
			userReason: 'Race hardening test',
			confirmRisk: 'on'
		}
	});
	expect(applied.status()).toBe(200);
	const adjustmentId = await getLatestManualAdjustmentId(userId, futureRun.id);
	const held = await holdAdjustmentMutationLock(adjustmentId);
	try {
		const undo = page.request.post('/app?/undoWorkoutAdjustment', {
			multipart: { adjustmentId }
		});
		await held.waitForBlockedRequests(1);
		const reset = page.request.post('/app?/resetWorkout', {
			multipart: { workoutId: futureRun.id }
		});
		await held.waitForBlockedRequests(2);
		held.release();
		const [undoResponse, resetResponse] = await Promise.all([undo, reset]);
		expect(undoResponse.status()).toBe(200);
		expect(resetResponse.status()).toBe(200);
		await expect(resetResponse.text()).resolves.toContain(
			'No reversible workout change was found.'
		);
	} finally {
		held.release();
		await held.done;
	}

	await expect.poll(() => getWorkout(futureRun.id)).toEqual(original);
	await expect
		.poll(() => getUndoRaceState(userId, adjustmentId))
		.toEqual({
			reversed: true,
			undoAuditCount: 1,
			resetAuditCount: 0
		});
});

test('workout previews stay available while the active plan row is locked', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const futureRun = (await getPlannedRuns(userId)).find((run) => run.scheduledDate > testDate);
	if (!futureRun) throw new Error('Future workout was not found.');
	const original = await getWorkout(futureRun.id);
	const held = await holdActivePlanMutationLock(userId);
	try {
		const preview = page.request.post('/app?/previewWorkoutRemoval', {
			multipart: { workoutId: futureRun.id }
		});
		const response = await Promise.race([
			preview,
			new Promise<never>((_, reject) => {
				setTimeout(() => {
					reject(new Error('Workout preview waited on a write lock.'));
				}, 5_000);
			})
		]);
		expect(response.status()).toBe(200);
		await expect(response.text()).resolves.toContain(
			'Review the weekly load after removing this workout.'
		);
	} finally {
		held.release();
		await held.done;
	}
	await expect.poll(() => getWorkout(futureRun.id)).toEqual(original);
});

test('forged feedback cannot mark a rest prescription complete or skipped', async ({ page }) => {
	const email = await createPlan(page);
	await page.context().setExtraHTTPHeaders({ origin: new URL(page.url()).origin });
	const userId = await getUserId(email);
	const workoutId = await getPastRestWorkoutId(userId);
	const response = await page.request.post('/app?/recordFeedback', {
		multipart: { workoutId, status: 'skipped', choice: 'reduce_next' }
	});
	expect(response.status()).toBe(200);
	await expect(response.text()).resolves.toContain(
		'Rest days do not accept workout feedback. Record an unplanned run instead.'
	);
	expect((await getWorkout(workoutId)).status).toBe('planned');
});
