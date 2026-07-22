import { expect, test, type Page } from '@playwright/test';
import postgres from 'postgres';
import { fixedBrowserClockScript } from '../support/test-clock';
import { startHeldDeviceFolderImport } from './support/import-fixtures';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

test('native GPX shares require a session and remain review-only', async ({ page }) => {
	const sharedFile = {
		name: 'shared-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: validGpx()
	};

	const missingMetadata = await page.request.post('/app/import/share', {
		maxRedirects: 0,
		multipart: { gpx: sharedFile }
	});
	expect(missingMetadata.status()).toBe(403);

	const crossSite = await page.request.post('/app/import/share', {
		headers: {
			'sec-fetch-site': 'cross-site',
			'sec-fetch-mode': 'navigate',
			'sec-fetch-dest': 'document'
		},
		maxRedirects: 0,
		multipart: { gpx: sharedFile }
	});
	expect(crossSite.status()).toBe(403);

	const signedOut = await page.request.post('/app/import/share', {
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: { gpx: sharedFile }
	});
	expect(signedOut.status()).toBe(303);
	expect(signedOut.headers()['location']).toBe('/login?share=sign-in-required');

	await page.goto(signedOut.headers()['location'] ?? '/login');
	await expect(
		page.getByText('Sign in, then share the GPX file to runway again. The file was not retained.')
	).toBeVisible();

	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	const imported = await page.request.post('/app/import/share', {
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: { gpx: sharedFile }
	});
	expect(imported.status()).toBe(303);
	expect(imported.headers()['location']).toBe('/app/import?share=imported');
	expect(imported.headers()['cache-control']).toBe('private, no-store');

	await page.goto(imported.headers()['location'] ?? '/app/import');
	await expect(page.getByText('GPX added to the activity inbox.')).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();

	const record = await getImportedRecord(userId);
	expect(record).toMatchObject({
		workoutId: null,
		extraPlanImpactConfirmed: false,
		consequence: null,
		pointCount: 2,
		startEndRedacted: false,
		traceRetained: true
	});

	const duplicate = await page.request.post('/app/import/share', {
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: { gpx: sharedFile }
	});
	expect(duplicate.status()).toBe(303);
	expect(duplicate.headers()['location']).toBe('/app/import?share=duplicate');
});

test('native GPX shares reject malformed, multiple, and oversized files safely', async ({
	page
}) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);

	const malformed = await page.request.post('/app/import/share', {
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: {
			gpx: {
				name: 'not-a-track.gpx',
				mimeType: 'application/gpx+xml',
				buffer: Buffer.from('<gpx><metadata /></gpx>')
			}
		}
	});
	expect(malformed.status()).toBe(303);
	expect(malformed.headers()['location']).toBe('/app/import?share=invalid');

	const multiple = new FormData();
	multiple.append(
		'gpx',
		new File([validGpx().toString('utf8')], 'one.gpx', { type: 'application/gpx+xml' })
	);
	multiple.append(
		'gpx',
		new File([validGpx().toString('utf8')], 'two.gpx', { type: 'application/gpx+xml' })
	);
	const multipleResponse = await page.request.fetch('/app/import/share', {
		method: 'POST',
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: multiple
	});
	expect(multipleResponse.status()).toBe(303);
	expect(multipleResponse.headers()['location']).toBe('/app/import?share=one-file');

	const oversized = await page.request.post('/app/import/share', {
		headers: nativeShareHeaders,
		maxRedirects: 0,
		multipart: {
			gpx: {
				name: 'too-large.gpx',
				mimeType: 'application/gpx+xml',
				buffer: Buffer.alloc(10 * 1024 * 1024 + 1, '<')
			}
		}
	});
	expect(oversized.status()).toBe(303);
	expect(oversized.headers()['location']).toBe('/app/import?share=too-large');
});

test('device-folder uploads require exact origin and remain review-only', async ({ page }) => {
	await page.goto('/login');
	const origin = new URL(page.url()).origin;
	const file = {
		name: 'activity.gpx',
		mimeType: 'application/gpx+xml',
		buffer: validGpx()
	};

	const missingOrigin = await page.request.post('/app/import/device', {
		multipart: { gpx: file }
	});
	expect(missingOrigin.status()).toBe(403);

	const signedOut = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: { gpx: file }
	});
	expect(signedOut.status()).toBe(401);
	expect(await signedOut.json()).toEqual({ result: 'signed-out' });
	const signedOutGeneration = await page.request.get('/app/import/device/generation');
	expect(signedOutGeneration.status()).toBe(401);
	expect(signedOutGeneration.headers()['cache-control']).toBe('private, no-store');

	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	const generationResponse = await page.request.get('/app/import/device/generation');
	expect(generationResponse.status()).toBe(200);
	expect(generationResponse.headers()['cache-control']).toBe('private, no-store');
	const generations = (await generationResponse.json()) as {
		activityGeneration: number;
		folderGeneration: number;
	};
	const missingGeneration = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: { gpx: file }
	});
	expect(missingGeneration.status()).toBe(400);
	expect(await missingGeneration.json()).toEqual({ result: 'generation-required' });
	const imported = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: {
			activityGeneration: String(generations.activityGeneration),
			folderGeneration: String(generations.folderGeneration),
			gpx: file
		}
	});
	expect(imported.status()).toBe(201);
	expect(imported.headers()['cache-control']).toBe('private, no-store');
	expect(await imported.json()).toEqual({ result: 'imported' });
	expect(await getImportedRecord(userId)).toMatchObject({
		workoutId: null,
		extraPlanImpactConfirmed: false,
		consequence: null
	});

	const duplicate = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: {
			activityGeneration: String(generations.activityGeneration),
			folderGeneration: String(generations.folderGeneration),
			gpx: file
		}
	});
	expect(duplicate.status()).toBe(200);
	expect(await duplicate.json()).toEqual({ result: 'duplicate' });
});

test('activity deletion rejects a device-folder upload held after generation capture', async ({
	page
}) => {
	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	const generationResponse = await page.request.get('/app/import/device/generation');
	const generations = (await generationResponse.json()) as {
		activityGeneration: number;
		folderGeneration: number;
	};
	const cookies = await page.context().cookies();
	const heldImport = await startHeldDeviceFolderImport(
		new URL('/app/import/device', page.url()),
		cookies.map(({ name, value }) => `${name}=${value}`).join('; '),
		validGpx(),
		generations.activityGeneration,
		generations.folderGeneration
	);

	await page.goto('/app/settings');
	await page.getByText('Imported activity data', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await page.getByRole('button', { name: 'Delete imported GPX activities' }).click();
	await expect(page.getByText('Deleted 0 imported GPX activities.')).toBeVisible();

	heldImport.finish();
	await expect(heldImport.response).resolves.toEqual({ status: 200, body: { result: 'deleted' } });
	await expect.poll(() => getImportedRecordCount(userId)).toBe(0);
});

test('device-folder parsing has a persistent per-user request budget', async ({ page }) => {
	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	const origin = new URL(page.url()).origin;
	const file = {
		name: 'private-device-file.gpx',
		mimeType: 'application/gpx+xml',
		buffer: validGpx()
	};
	for (let attempt = 0; attempt < 30; attempt += 1) {
		const response = await page.request.post('/app/import/device', {
			headers: { origin },
			multipart: { gpx: file }
		});
		expect(response.status()).toBe(400);
	}
	const blocked = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: { gpx: file }
	});
	expect(blocked.status()).toBe(429);
	expect(blocked.headers()['retry-after']).toMatch(/^\d+$/);
	expect(await blocked.json()).toEqual({ result: 'rate-limited' });
});

test('plain folder disconnect rejects a device-folder POST already waiting on its body', async ({
	context,
	page
}) => {
	await context.addInitScript(() => {
		Object.defineProperty(globalThis, 'showDirectoryPicker', {
			configurable: true,
			value: () => navigator.storage.getDirectory()
		});
	});
	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	await page.goto('/app/import');
	await page.getByText('Add import source', { exact: true }).click();
	await page.getByRole('button', { name: /^Browser folder/ }).click();
	await page.getByRole('button', { name: 'Allow device folder' }).click();
	await expect(page.getByText('No new GPX files found.')).toBeVisible();

	const generationResponse = await page.request.get('/app/import/device/generation');
	const generations = (await generationResponse.json()) as {
		activityGeneration: number;
		folderGeneration: number;
	};
	const cookies = await page.context().cookies();
	const heldImport = await startHeldDeviceFolderImport(
		new URL('/app/import/device', page.url()),
		cookies.map(({ name, value }) => `${name}=${value}`).join('; '),
		validGpx(),
		generations.activityGeneration,
		generations.folderGeneration
	);
	const competing = await page.request.post('/app/import/device', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			activityGeneration: String(generations.activityGeneration),
			folderGeneration: String(generations.folderGeneration),
			gpx: {
				name: 'competing.gpx',
				mimeType: 'application/gpx+xml',
				buffer: secondValidGpx()
			}
		}
	});
	expect(competing.status()).toBe(429);
	expect(competing.headers()['retry-after']).toMatch(/^\d+$/);

	const otherTab = await context.newPage();
	await otherTab.goto('/app/import');
	await expect(otherTab.getByText('Connected on this browser · one file per scan')).toBeVisible();
	await otherTab.getByRole('button', { name: 'Disconnect' }).click();
	await expect(
		otherTab.getByText('Gadgetbridge folder disconnected. No files were changed.')
	).toBeVisible();

	heldImport.finish();
	await expect(heldImport.response).resolves.toEqual({
		status: 409,
		body: { result: 'disconnected' }
	});
	await expect.poll(() => getImportedRecordCount(userId)).toBe(0);
	await otherTab.close();
});

test('approved device folder imports once on foreground and stays account-scoped', async ({
	page
}) => {
	await page.addInitScript(
		(files: { name: string; gpx: string }[]) => {
			Object.defineProperty(globalThis, 'showDirectoryPicker', {
				configurable: true,
				value: async () => {
					const root = await navigator.storage.getDirectory();
					for (const { name, gpx } of files) {
						const file = await root.getFileHandle(name, { create: true });
						const writer = await file.createWritable();
						await writer.write(gpx);
						await writer.close();
					}
					return root;
				}
			});
		},
		[
			{ name: 'gadgetbridge-private-name.gpx', gpx: validGpx().toString('utf8') },
			{ name: 'gadgetbridge-waiting-name.gpx', gpx: secondValidGpx().toString('utf8') }
		]
	);

	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app/import');
	await page.getByText('Add import source', { exact: true }).click();
	await page.getByRole('button', { name: /^Browser folder/ }).click();
	await page.getByRole('button', { name: 'Allow device folder' }).click();
	await expect(
		page.getByText(/GPX added to the activity inbox\. 1 more file is waiting/)
	).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();
	await expect(page.getByText('Connected · 1 file waiting')).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toHaveCount(1);
	await page.getByRole('button', { name: 'Scan next (1)' }).click();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toHaveCount(2);
	await expect(page.getByText('Connected on this browser · one file per scan')).toBeVisible();

	await page.evaluate(async (gpx) => {
		const root = await navigator.storage.getDirectory();
		const file = await root.getFileHandle('another-private-name.gpx', { create: true });
		const writer = await file.createWritable();
		await writer.write(gpx);
		await writer.close();
	}, thirdValidGpx().toString('utf8'));
	await page.waitForTimeout(5_100);
	await page.evaluate(() => globalThis.dispatchEvent(new Event('focus')));
	await expect(page.getByText('A GPX from the device folder is ready for review.')).toBeVisible();
	await page.reload();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toHaveCount(3);

	await page.getByRole('button', { name: 'Scan now' }).click();
	await expect(page.getByText('No new GPX files found.')).toBeVisible();

	const localMarkers = await page.evaluate(async () => {
		const database = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open('runway-device-folders', 2);
			request.onsuccess = () => {
				resolve(request.result);
			};
			request.onerror = () => {
				reject(request.error ?? new Error('Could not open device folder test storage.'));
			};
		});
		try {
			const transaction = database.transaction('seen-files', 'readonly');
			const request = transaction.objectStore('seen-files').getAll();
			return await new Promise<unknown[]>((resolve, reject) => {
				request.onsuccess = () => {
					const value: unknown = request.result;
					resolve(Array.isArray(value) ? value : []);
				};
				request.onerror = () => {
					reject(request.error ?? new Error('Could not read device folder test storage.'));
				};
			});
		} finally {
			database.close();
		}
	});
	expect(JSON.stringify(localMarkers)).not.toContain('gadgetbridge-private-name.gpx');
	expect(JSON.stringify(localMarkers)).not.toContain('gadgetbridge-waiting-name.gpx');
	expect(JSON.stringify(localMarkers)).not.toContain('another-private-name.gpx');
	expect(localMarkers).toHaveLength(3);

	await page.getByRole('button', { name: 'Sign out' }).click();
	await expect(page).toHaveURL(/\/$/);
	await expect
		.poll(() =>
			page.evaluate(async () => {
				if (!indexedDB.databases) return false;
				return (await indexedDB.databases()).some(
					(database) => database.name === 'runway-device-folders'
				);
			})
		)
		.toBe(false);
	await createAccount(page);
	await page.goto('/app/import');
	await page.getByText('Add import source', { exact: true }).click();
	await page.getByRole('button', { name: /^Browser folder/ }).click();
	await expect(page.getByRole('button', { name: 'Allow device folder' })).toBeVisible();
	await expect(page.getByText('Connected on this browser')).not.toBeVisible();
});

test('a second tab disconnects a stale folder scan before it can upload with a new generation', async ({
	context,
	page
}) => {
	await page.addInitScript(() => {
		Object.defineProperty(globalThis, 'showDirectoryPicker', {
			configurable: true,
			value: () => navigator.storage.getDirectory()
		});
	});
	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	await page.goto('/app/import');
	await page.getByText('Add import source', { exact: true }).click();
	await page.getByRole('button', { name: /^Browser folder/ }).click();
	await page.getByRole('button', { name: 'Allow device folder' }).click();
	await expect(page.getByText('No new GPX files found.')).toBeVisible();

	const otherTab = await context.newPage();
	// Keep this tab from running its own foreground scan; it exists only to
	// disconnect the shared capability while the first tab is held.
	await otherTab.route('**/app/import/device/generation', (route) => route.abort());
	await otherTab.goto('/app/settings');
	await page.evaluate(async (gpx) => {
		const root = await navigator.storage.getDirectory();
		const file = await root.getFileHandle('late-private-name.gpx', { create: true });
		const writer = await file.createWritable();
		await writer.write(gpx);
		await writer.close();
	}, validGpx().toString('utf8'));

	let releaseGeneration!: () => void;
	let noteGenerationRequest!: () => void;
	const generationHeld = new Promise<void>((resolve) => {
		noteGenerationRequest = resolve;
	});
	const generationRelease = new Promise<void>((resolve) => {
		releaseGeneration = resolve;
	});
	await page.route('**/app/import/device/generation', async (route) => {
		noteGenerationRequest();
		await generationRelease;
		await route.continue();
	});
	let deviceUploads = 0;
	page.on('request', (request) => {
		const url = new URL(request.url());
		if (request.method() === 'POST' && url.pathname === '/app/import/device') deviceUploads += 1;
	});
	const controlMessage = page.evaluate(
		() =>
			new Promise<unknown>((resolve, reject) => {
				const channel = new BroadcastChannel('runway-device-folder-control-v1');
				const timer = setTimeout(() => {
					channel.close();
					reject(new Error('Device-folder control message was not broadcast.'));
				}, 5_000);
				channel.addEventListener(
					'message',
					(event) => {
						clearTimeout(timer);
						channel.close();
						resolve(event.data as unknown);
					},
					{ once: true }
				);
			})
	);

	await page.getByRole('button', { name: 'Scan now' }).click();
	await generationHeld;
	await otherTab.getByText('Imported activity data', { exact: true }).click();
	otherTab.once('dialog', (dialog) => dialog.accept());
	await otherTab.getByRole('button', { name: 'Delete imported GPX activities' }).click();
	await expect(otherTab.getByText('Deleted 0 imported GPX activities.')).toBeVisible();
	await expect(controlMessage).resolves.toEqual({ type: 'disconnected', userId });

	releaseGeneration();
	await expect(page.getByText('Allow a Gadgetbridge folder before scanning.')).toBeVisible();
	expect(deviceUploads).toBe(0);
	await expect.poll(() => getImportedRecordCount(userId)).toBe(0);
	await otherTab.close();
});

const nativeShareHeaders = {
	'sec-fetch-site': 'none',
	'sec-fetch-mode': 'navigate',
	'sec-fetch-dest': 'document'
};

async function createAccount(page: Page): Promise<string> {
	const email = `share-target-${Date.now()}-${Math.random().toString(36).slice(2)}@example.test`;
	await page.goto('/login');
	await page.getByRole('button', { name: 'Create account', exact: true }).click();
	const signup = page.locator('#create-account');
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill('correct horse battery staple 2026');
	await signup.getByLabel('Name').fill('Share Target Tester');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	return email;
}

async function setTrainingTimeZone(email: string): Promise<string> {
	const sql = testDatabase();
	try {
		const [runner] = await sql<{ id: string }[]>`
			select id from "user" where lower(email) = ${email.toLowerCase()} limit 1
		`;
		if (!runner) throw new Error('Share-target test account was not found.');
		await sql`
			insert into athlete_profile (user_id, time_zone)
			values (${runner.id}, 'America/Halifax')
			on conflict (user_id) do update set time_zone = excluded.time_zone, updated_at = now()
		`;
		return runner.id;
	} finally {
		await sql.end();
	}
}

async function getImportedRecord(userId: string) {
	const sql = testDatabase();
	try {
		const [record] = await sql<
			{
				workoutId: string | null;
				extraPlanImpactConfirmed: boolean;
				consequence: unknown;
				pointCount: number;
				startEndRedacted: boolean;
				traceRetained: boolean;
			}[]
		>`
			select
				a.workout_id as "workoutId",
				a.extra_plan_impact_confirmed as "extraPlanImpactConfirmed",
				a.consequence,
				(a.route_summary ->> 'pointCount')::integer as "pointCount",
				(a.route_summary ->> 'startEndRedacted')::boolean as "startEndRedacted",
				(a.route_summary ->> 'traceRetained')::boolean as "traceRetained"
			from activity a
			where a.user_id = ${userId} and a.source = 'gpx'
			order by a.created_at desc
			limit 1
		`;
		if (!record) throw new Error('Shared GPX activity was not recorded.');
		return record;
	} finally {
		await sql.end();
	}
}

async function getImportedRecordCount(userId: string): Promise<number> {
	const sql = testDatabase();
	try {
		const [record] = await sql<{ count: number }[]>`
			select count(*)::integer as count
			from activity
			where user_id = ${userId} and source = 'gpx'
		`;
		return record?.count ?? 0;
	} finally {
		await sql.end();
	}
}

function testDatabase() {
	return postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
}

function validGpx(): Buffer {
	return Buffer.from(`<gpx><trk><trkseg>
		<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-14T12:00:00Z</time></trkpt>
		<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-14T12:10:00Z</time></trkpt>
	</trkseg></trk></gpx>`);
}

function secondValidGpx(): Buffer {
	return Buffer.from(`<gpx><trk><trkseg>
		<trkpt lat="45.0100" lon="-63.0100"><time>2026-05-14T13:00:00Z</time></trkpt>
		<trkpt lat="45.0120" lon="-63.0120"><time>2026-05-14T13:12:00Z</time></trkpt>
	</trkseg></trk></gpx>`);
}

function thirdValidGpx(): Buffer {
	return Buffer.from(`<gpx><trk><trkseg>
		<trkpt lat="45.0200" lon="-63.0200"><time>2026-05-14T14:00:00Z</time></trkpt>
		<trkpt lat="45.0230" lon="-63.0230"><time>2026-05-14T14:15:00Z</time></trkpt>
	</trkseg></trk></gpx>`);
}
