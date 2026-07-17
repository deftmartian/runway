import { expect, test, type Page } from '@playwright/test';
import postgres from 'postgres';
import { fixedBrowserClockScript } from '../support/test-clock';

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
		startEndRedacted: true
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

	const email = await createAccount(page);
	const userId = await setTrainingTimeZone(email);
	const imported = await page.request.post('/app/import/device', {
		headers: { origin },
		multipart: { gpx: file }
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
		multipart: { gpx: file }
	});
	expect(duplicate.status()).toBe(200);
	expect(await duplicate.json()).toEqual({ result: 'duplicate' });
});

test('approved device folder imports once on foreground and stays account-scoped', async ({
	page
}) => {
	await page.addInitScript((gpx) => {
		Object.defineProperty(globalThis, 'showDirectoryPicker', {
			configurable: true,
			value: async () => {
				const root = await navigator.storage.getDirectory();
				const file = await root.getFileHandle('gadgetbridge-private-name.gpx', {
					create: true
				});
				const writer = await file.createWritable();
				await writer.write(gpx);
				await writer.close();
				return root;
			}
		});
	}, validGpx().toString('utf8'));

	const email = await createAccount(page);
	await setTrainingTimeZone(email);
	await page.goto('/app/import');
	await page.getByText('Add import source', { exact: true }).click();
	await page.getByRole('button', { name: 'Allow device folder' }).click();
	await expect(page.getByText('GPX added to the activity inbox.')).toBeVisible();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toBeVisible();
	await expect(page.getByText('Connected on this browser')).toBeVisible();

	await page.evaluate(async (gpx) => {
		const root = await navigator.storage.getDirectory();
		const file = await root.getFileHandle('another-private-name.gpx', { create: true });
		const writer = await file.createWritable();
		await writer.write(gpx);
		await writer.close();
	}, secondValidGpx().toString('utf8'));
	await page.waitForTimeout(5_100);
	await page.evaluate(() => globalThis.dispatchEvent(new Event('focus')));
	await expect(page.getByText('A GPX from the device folder is ready for review.')).toBeVisible();
	await page.reload();
	await expect(page.locator('.state-marker').filter({ hasText: 'Needs review' })).toHaveCount(2);

	await page.getByRole('button', { name: 'Scan now' }).click();
	await expect(page.getByText('No new GPX files found.')).toBeVisible();

	const localMarkers = await page.evaluate(async () => {
		const database = await new Promise<IDBDatabase>((resolve, reject) => {
			const request = indexedDB.open('runway-device-folders', 1);
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
	expect(JSON.stringify(localMarkers)).not.toContain('another-private-name.gpx');
	expect(localMarkers).toHaveLength(2);

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
	await expect(page.getByRole('button', { name: 'Allow device folder' })).toBeVisible();
	await expect(page.getByText('Connected on this browser')).not.toBeVisible();
});

const nativeShareHeaders = {
	'sec-fetch-site': 'none',
	'sec-fetch-mode': 'navigate',
	'sec-fetch-dest': 'document'
};

async function createAccount(page: Page): Promise<string> {
	const email = `share-target-${Date.now()}-${Math.random().toString(36).slice(2)}@example.test`;
	await page.goto('/login');
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
			}[]
		>`
			select
				a.workout_id as "workoutId",
				a.extra_plan_impact_confirmed as "extraPlanImpactConfirmed",
				a.consequence,
				(a.route_summary ->> 'pointCount')::integer as "pointCount",
				(a.route_summary ->> 'startEndRedacted')::boolean as "startEndRedacted"
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
