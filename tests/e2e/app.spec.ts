import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createHash, createHmac, randomBytes } from 'node:crypto';
import { expect, test, type Page, type Route } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import postgres from 'postgres';
import { fixedBrowserClockScript, testDate, testNowIso } from '../support/test-clock';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(fixedBrowserClockScript());
});

async function createAccount(page: Page) {
	await clearPasswordResetRateLimits();
	const email = `runner-${Date.now()}-${Math.random().toString(36).slice(2)}@example.test`;

	await page.goto('/login');
	const signup = page.locator('#create-account');
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill('correct horse battery staple 2026');
	await signup.getByLabel('Name').fill('Runway Tester');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	return email;
}

async function createPlan(page: Page) {
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

async function fillValidPlanIntake(page: Page) {
	const target = new Date(testNowIso);
	target.setUTCDate(target.getUTCDate() + 20 * 7);
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

async function goToOnboardingStep(
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

async function setAvailability(page: Page, selectedDays: string[]) {
	for (const day of ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']) {
		const label = page.locator('.day-choices label').filter({ hasText: day });
		const input = label.locator('input');
		if ((await input.isChecked()) !== selectedDays.includes(day)) await label.click();
	}
}

async function expectVisibleChoiceHeadingsReadable(page: Page) {
	const widths = await page
		.locator('.setup-step:not([hidden]) .choice-track strong')
		.evaluateAll((headings) => headings.map((heading) => heading.getBoundingClientRect().width));
	expect(widths.length).toBeGreaterThan(0);
	for (const width of widths) expect(width).toBeGreaterThan(100);
}

function localSignInForm(page: Page) {
	return page.locator('form').filter({ has: page.getByRole('heading', { name: 'Local sign in' }) });
}

async function holdSettingsAction(page: Page, action: string) {
	return holdPageAction(page, '/app/settings', action);
}

async function holdPageAction(page: Page, pathname: string, action: string) {
	let releaseRequest!: () => void;
	let markObserved!: () => void;
	const gate = new Promise<void>((resolve) => {
		releaseRequest = resolve;
	});
	const observed = new Promise<void>((resolve) => {
		markObserved = resolve;
	});
	const matcher = (url: URL) => url.pathname === pathname && url.searchParams.has(`/${action}`);
	const handler = async (route: Route) => {
		markObserved();
		await gate;
		await route.continue();
	};
	await page.route(matcher, handler);
	return {
		observed,
		release: releaseRequest,
		stop: () => page.unroute(matcher, handler)
	};
}

async function insertPasswordResetToken(
	email: string,
	token: string,
	expiresAt: Date
): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const users = await sql<{ id: string }[]>`
			select id from "user" where lower(email) = ${email.toLowerCase()} limit 1
		`;
		const userId = users[0]?.id;
		if (!userId) throw new Error(`Test user was not found for ${email}.`);
		await sql`
			insert into password_reset_token (user_id, token_hash, expires_at)
			values (${userId}, ${hashResetTokenForTest(token)}, ${expiresAt})
		`;
	} finally {
		await sql.end();
	}
}

async function clearPasswordResetRateLimits(): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`delete from password_reset_rate_limit`;
	} finally {
		await sql.end();
	}
}

async function getUserId(email: string): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const users = await sql<{ id: string }[]>`
			select id from "user" where lower(email) = ${email.toLowerCase()} limit 1
		`;
		const userId = users[0]?.id;
		if (!userId) throw new Error(`Test user was not found for ${email}.`);
		return userId;
	} finally {
		await sql.end();
	}
}

/**
 * Plans intentionally begin on the next Monday. Tests that exercise completed,
 * missed, or imported work move only week one into the fixed browser clock's
 * current week so they do not weaken that production rule.
 */
async function makeFirstPlanWeekCurrent(userId: string): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql.begin(async (transaction) => {
			const [firstWeek] = await transaction<{ id: string; planId: string }[]>`
				select tw.id, tw.plan_id as "planId"
				from training_week tw
				inner join training_plan tp on tp.id = tw.plan_id
				where tp.user_id = ${userId} and tp.status = 'active'
				order by tw.week_number asc
				limit 1
			`;
			if (!firstWeek) throw new Error(`Active plan week was not found for ${userId}.`);
			await transaction`
				update training_plan
				set start_date = start_date - 7, updated_at = now()
				where id = ${firstWeek.planId}
			`;
			await transaction`
				update training_week
				set start_date = start_date - 7
				where id = ${firstWeek.id}
			`;
			await transaction`
				update workout
				set scheduled_date = scheduled_date - 7, updated_at = now()
				where week_id = ${firstWeek.id}
			`;
		});
	} finally {
		await sql.end();
	}
}

async function finishBeginnerPhase(
	userId: string,
	options: { addAcceptedActivities: boolean }
): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql.begin(async (transaction) => {
			const startDate = addIsoDays(testDate, -62);
			await transaction`
				update training_plan
				set start_date = ${startDate}, target_date = ${testDate}, updated_at = now()
				where user_id = ${userId} and status = 'active' and phase in ('foundation', 'calibration')
			`;
			if (!options.addAcceptedActivities) return;
			for (let week = 0; week < 9; week += 1) {
				for (const dayOffset of [0, 2, 5]) {
					const activityDate = addIsoDays(startDate, week * 7 + dayOffset);
					await transaction`
						insert into activity (
							user_id, source, review_state, occurred_at, activity_date,
							distance_meters, duration_seconds, extra_plan_impact_confirmed,
							deviation, route_summary
						) values (
							${userId}, 'manual', 'accepted', ${`${activityDate}T12:00:00.000Z`}, ${activityDate},
							2000, 1800, true, 'unplanned',
							${JSON.stringify({ pointCount: 0, startEndRedacted: true, hasElevation: false })}::jsonb
						)
					`;
				}
			}
		});
	} finally {
		await sql.end();
	}
}

async function setTrainingTimeZone(email: string, timeZone = 'America/Halifax'): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const userId = await getUserId(email);
		await sql`
			insert into athlete_profile (user_id, time_zone)
			values (${userId}, ${timeZone})
			on conflict (user_id) do update set time_zone = excluded.time_zone, updated_at = now()
		`;
	} finally {
		await sql.end();
	}
}

async function insertTrustedDeviceVerification(email: string): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const userId = await getUserId(email);
		const id = `test-trust-${randomBytes(12).toString('hex')}`;
		const identifier = `trust-device-${randomBytes(16).toString('hex')}`;
		await sql`
			insert into verification (id, identifier, value, expires_at)
			values (${id}, ${identifier}, ${userId}, ${new Date(Date.now() + 30 * 24 * 60 * 60_000)})
		`;
		return id;
	} finally {
		await sql.end();
	}
}

async function verificationExists(id: string): Promise<boolean> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ found: boolean }[]>`
			select exists(select 1 from verification where id = ${id}) as found
		`;
		return rows[0]?.found ?? false;
	} finally {
		await sql.end();
	}
}

async function getPlannedRuns(userId: string): Promise<
	{
		id: string;
		scheduledDate: string;
		targetDistanceMeters: number;
		status: string;
	}[]
> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		return await sql`
			select
				w.id,
				w.scheduled_date::text as "scheduledDate",
				w.target_distance_meters as "targetDistanceMeters",
				w.status
			from workout w
			inner join training_plan p on p.id = w.plan_id and p.status = 'active'
			where w.user_id = ${userId}
				and w.type <> 'rest'
			order by w.scheduled_date asc
		`;
	} finally {
		await sql.end();
	}
}

async function getCurrentGoalPlanState(userId: string): Promise<{
	goalKind: string;
	goalState: string;
	startMode: string;
	distance: string | null;
	phase: string | null;
	workoutCount: number;
	timedWorkoutCount: number;
	totalTargetDistanceMeters: number;
	durations: number[];
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<
			{
				goalKind: string;
				goalState: string;
				startMode: string;
				distance: string | null;
				phase: string | null;
				workoutCount: number;
				timedWorkoutCount: number;
				totalTargetDistanceMeters: number;
				durations: number[];
			}[]
		>`
			select
				g.kind as "goalKind",
				g.state as "goalState",
				g.start_mode as "startMode",
				g.distance,
				p.phase,
				count(w.id)::int as "workoutCount",
				count(w.id) filter (where w.prescription_kind = 'timed')::int as "timedWorkoutCount",
				coalesce(sum(w.target_distance_meters), 0)::int as "totalTargetDistanceMeters",
				coalesce(
					array_agg(distinct w.target_duration_seconds)
						filter (where w.target_duration_seconds is not null),
					array[]::integer[]
				) as durations
			from goal g
			left join training_plan p on p.goal_id = g.id and p.user_id = g.user_id and p.status = 'active'
			left join workout w on w.plan_id = p.id and w.user_id = g.user_id and w.is_removed = false
			where g.user_id = ${userId} and g.state in ('pending', 'active')
			group by g.id, p.id
			limit 1
		`;
		const state = rows[0];
		if (!state) throw new Error(`Current goal was not found for ${userId}.`);
		return state;
	} finally {
		await sql.end();
	}
}

async function getWorkout(id: string): Promise<{
	scheduledDate: string;
	targetDistanceMeters: number;
	targetDurationSeconds: number | null;
	prescriptionKind: string;
	status: string;
	type: string;
	purpose: string;
	isRemoved: boolean;
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<
			{
				scheduledDate: string;
				targetDistanceMeters: number;
				targetDurationSeconds: number | null;
				prescriptionKind: string;
				status: string;
				type: string;
				purpose: string;
				isRemoved: boolean;
			}[]
		>`
				select
					scheduled_date::text as "scheduledDate",
					target_distance_meters as "targetDistanceMeters",
					target_duration_seconds as "targetDurationSeconds",
					prescription_kind as "prescriptionKind",
					status,
					type,
					purpose,
					is_removed as "isRemoved"
				from workout
				where id = ${id}
				limit 1
		`;
		const workout = rows[0];
		if (!workout) throw new Error(`Workout was not found for ${id}.`);
		return workout;
	} finally {
		await sql.end();
	}
}

async function getVisibleWorkoutsOnDate(
	userId: string,
	scheduledDate: string
): Promise<
	{
		id: string;
		purpose: string;
		targetDistanceMeters: number;
	}[]
> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		return await sql`
			select id, purpose, target_distance_meters as "targetDistanceMeters"
			from workout
			where user_id = ${userId} and scheduled_date = ${scheduledDate} and is_removed = false
			order by created_at asc, id asc
		`;
	} finally {
		await sql.end();
	}
}

async function moveWorkoutToDate(id: string, scheduledDate: string): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`
			update workout
			set scheduled_date = ${scheduledDate}, updated_at = now()
			where id = ${id}
		`;
	} finally {
		await sql.end();
	}
}

async function moveActivePlanTargetDate(userId: string, targetDate: string): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`
			update training_plan
			set target_date = ${targetDate}, updated_at = now()
			where user_id = ${userId} and status = 'active'
		`;
	} finally {
		await sql.end();
	}
}

async function getFirstActivityId(userId: string): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ id: string }[]>`
			select id
			from activity
			where user_id = ${userId}
			order by created_at desc
			limit 1
		`;
		const activityId = rows[0]?.id;
		if (!activityId) throw new Error(`Activity was not found for ${userId}.`);
		return activityId;
	} finally {
		await sql.end();
	}
}

async function getActivityDates(userId: string): Promise<string[]> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ activityDate: string }[]>`
			select activity_date::text as "activityDate"
			from activity
			where user_id = ${userId}
			order by created_at asc, id asc
		`;
		return rows.map((row) => row.activityDate);
	} finally {
		await sql.end();
	}
}

async function seedManualActivityRecords(userId: string, count: number): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`
			insert into activity (
				user_id,
				source,
				review_state,
				occurred_at,
				activity_date,
				distance_meters,
				duration_seconds,
				extra_plan_impact_confirmed,
				route_summary,
				created_at
			)
			select
				${userId},
				'manual',
				'accepted',
				timestamptz '2026-05-15 12:00:00Z' - (sequence * interval '1 minute'),
				date '2026-05-15',
				1000 + sequence,
				600,
				true,
				jsonb_build_object(
					'pointCount', 0,
					'startEndRedacted', true,
					'hasElevation', false
				),
				timestamptz '2026-05-15 12:00:00Z' - (sequence * interval '1 minute')
			from generate_series(1, ${count}) as sequence
		`;
	} finally {
		await sql.end();
	}
}

async function getPlanAdjustmentTypes(userId: string): Promise<string[]> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ triggerType: string }[]>`
			select trigger_type as "triggerType"
			from plan_adjustment
			where user_id = ${userId}
			order by created_at asc
		`;
		return rows.map((row) => row.triggerType);
	} finally {
		await sql.end();
	}
}

async function hasDistanceAdjustment(userId: string, triggerType: string): Promise<boolean> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ found: boolean }[]>`
			select exists(
				select 1
				from plan_adjustment
				where user_id = ${userId}
					and trigger_type = ${triggerType}
					and previous_target_distance_meters <> new_target_distance_meters
			) as found
		`;
		return rows[0]?.found ?? false;
	} finally {
		await sql.end();
	}
}

async function activityExists(activityId: string): Promise<boolean> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const rows = await sql<{ id: string }[]>`
			select id
			from activity
			where id = ${activityId}
			limit 1
		`;
		return Boolean(rows[0]);
	} finally {
		await sql.end();
	}
}

function hashResetTokenForTest(token: string): string {
	return createHash('sha256')
		.update('runway-password-reset-v1')
		.update('\0')
		.update(token)
		.digest('hex');
}

function totpForSecret(secret: string): string {
	const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
	let bits = '';
	for (const character of secret.toUpperCase().replace(/=+$/, '')) {
		const value = alphabet.indexOf(character);
		if (value < 0) throw new Error('Authenticator secret was not valid base32.');
		bits += value.toString(2).padStart(5, '0');
	}
	const bytes: number[] = [];
	for (let offset = 0; offset + 8 <= bits.length; offset += 8) {
		bytes.push(Number.parseInt(bits.slice(offset, offset + 8), 2));
	}
	const counter = Buffer.alloc(8);
	counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30_000)));
	const digest = createHmac('sha1', Buffer.from(bytes)).update(counter).digest();
	const offset = (digest.at(-1) ?? 0) & 0x0f;
	const value =
		(((digest[offset] ?? 0) & 0x7f) << 24) |
		((digest[offset + 1] ?? 0) << 16) |
		((digest[offset + 2] ?? 0) << 8) |
		(digest[offset + 3] ?? 0);
	return String(value % 1_000_000).padStart(6, '0');
}

async function expectNoCriticalAxeViolations(page: Page) {
	await page.waitForLoadState('networkidle');
	const results = await new AxeBuilder({ page }).analyze();
	expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
}

async function expectNoHorizontalOverflow(page: Page) {
	const result = await page.evaluate(() => {
		const root = document.documentElement;
		const clientWidth = root.clientWidth;
		return {
			scrollWidth: root.scrollWidth,
			clientWidth,
			offenders: Array.from(document.querySelectorAll<HTMLElement>('body *'))
				.filter((element) => {
					const bounds = element.getBoundingClientRect();
					return bounds.right > clientWidth + 2 || bounds.left < -2;
				})
				.slice(0, 12)
				.map((element) => {
					const bounds = element.getBoundingClientRect();
					return {
						tag: element.tagName.toLowerCase(),
						className: element.className,
						text: element.textContent?.trim().slice(0, 80) ?? '',
						bounds: {
							left: bounds.left,
							right: bounds.right,
							width: bounds.width
						}
					};
				})
		};
	});

	expect(result.scrollWidth, JSON.stringify(result.offenders, null, 2)).toBeLessThanOrEqual(
		result.clientWidth + 2
	);
}

async function openImportSourceSetup(page: Page) {
	const setup = page.locator('details.source-setup');
	if ((await setup.getAttribute('open')) === null) {
		await setup.getByText('Add import source', { exact: true }).click();
	}
}

function gpxForDistance(date: string, distanceMeters: number): Buffer {
	const latitude = 45;
	const startLongitude = -63;
	const longitudeDelta = distanceMeters / (111_320 * Math.cos((latitude * Math.PI) / 180));
	return Buffer.from(`<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="${latitude}" lon="${startLongitude}"><time>${date}T12:00:00Z</time></trkpt>
			<trkpt lat="${latitude}" lon="${startLongitude + longitudeDelta}"><time>${date}T12:30:00Z</time></trkpt>
		</trkseg></trk></gpx>`);
}

function addIsoDays(date: string, days: number): string {
	const parsed = new Date(`${date}T00:00:00.000Z`);
	parsed.setUTCDate(parsed.getUTCDate() + days);
	return parsed.toISOString().slice(0, 10);
}

function currentCalendarMonth(): string {
	return testDate.slice(0, 7);
}

function shiftCalendarMonth(month: string, offset: number): string {
	const [yearText, monthText] = month.split('-');
	const date = new Date(Date.UTC(Number(yearText), Number(monthText) - 1 + offset, 1));
	return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
}

function calendarMonthLabel(month: string): string {
	return new Date(`${month}-01T00:00:00`).toLocaleDateString(undefined, {
		month: 'long',
		year: 'numeric'
	});
}

test('protected app redirects to login', async ({ page }) => {
	await page.goto('/app');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
});

test('theme follows system until light or dark is selected', async ({ page }) => {
	await page.emulateMedia({ colorScheme: 'dark' });
	await page.goto('/');
	await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.+/);

	await page.getByRole('button', { name: 'Switch to light theme' }).click();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');
	await expect(page.getByRole('button', { name: 'Switch to dark theme' })).toBeVisible();
	await expect
		.poll(async () => page.evaluate(() => localStorage.getItem('runway-theme')))
		.toBe('light');

	await page.reload();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'light');

	await page.getByRole('button', { name: 'Switch to dark theme' }).click();
	await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
	await expect
		.poll(async () => page.evaluate(() => localStorage.getItem('runway-theme')))
		.toBe('dark');
});

test('auth recovery and account creation failures do not enumerate accounts', async ({ page }) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	await page.getByRole('button', { name: 'Sign out' }).click();
	await expect(page.getByRole('heading', { name: 'runway' })).toBeVisible();
	await page.goto('/login');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();

	const signup = page.locator('#create-account');
	await signup.getByLabel('Email').fill(email);
	await signup.getByLabel('Password').fill('correct horse battery staple 2026');
	await signup.getByLabel('Name').fill('Runway Tester');
	const heldSignup = await holdPageAction(page, '/login', 'signUpEmail');
	await signup.getByRole('button', { name: 'Create account' }).click();
	await heldSignup.observed;
	await expect(signup.getByRole('button', { name: 'Creating account…' })).toBeDisabled();
	await expect(localSignInForm(page).locator('.message')).toHaveCount(0);
	heldSignup.release();
	await expect(signup.getByText('Account could not be created.')).toBeVisible();
	await expect(signup).not.toContainText('already exists');
	await heldSignup.stop();

	await page.getByRole('link', { name: 'Reset password' }).click();
	await expect(page).toHaveURL(/\/login\/forgot-password$/);
	await page.getByLabel('Email').fill(email);
	const heldResetRequest = await holdPageAction(page, '/login/forgot-password', 'requestReset');
	await page.getByRole('button', { name: 'Send reset link' }).click();
	await heldResetRequest.observed;
	await expect(page.getByRole('button', { name: 'Sending reset link…' })).toBeDisabled();
	heldResetRequest.release();
	await expect(
		page.getByText('Password reset email is not available yet. Ask the workspace owner for help.')
	).toBeVisible();
	await heldResetRequest.stop();

	await page.goto('/login/reset-password?token=bad-token');
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);
});

test('local sign-in actions are throttled outside the Better Auth router', async ({ page }) => {
	await clearPasswordResetRateLimits();
	await page.goto('/login');
	const email = `missing-${randomBytes(8).toString('hex')}@example.test`;
	const signIn = localSignInForm(page);
	await signIn.getByLabel('Email').fill(email);
	await signIn.getByLabel('Password').fill('incorrect password value');
	const heldSignIn = await holdPageAction(page, '/login', 'signInEmail');
	await signIn.getByRole('button', { name: 'Sign in' }).click();
	await heldSignIn.observed;
	await expect(signIn).toHaveAttribute('aria-busy', 'true');
	await expect(signIn.getByRole('button', { name: 'Signing in…' })).toBeVisible();
	await expect(page.locator('#create-account .message')).toHaveCount(0);
	heldSignIn.release();
	await expect(signIn.getByText('Email or password is not correct.')).toBeVisible();
	await heldSignIn.stop();
	await clearPasswordResetRateLimits();
	let finalResponseBody = '';
	for (let attempt = 0; attempt < 11; attempt += 1) {
		const response = await page.request.post('/login?/signInEmail', {
			headers: {
				accept: 'application/json',
				origin: new URL(page.url()).origin,
				'x-sveltekit-action': 'true'
			},
			form: { email, password: 'incorrect password value' }
		});
		finalResponseBody = await response.text();
	}
	expect(finalResponseBody).toContain('Too many sign-in attempts. Try again later.');
});

test('local sign-up actions are throttled outside the Better Auth router', async ({ page }) => {
	await clearPasswordResetRateLimits();
	await page.goto('/login');
	const email = `signup-limit-${randomBytes(8).toString('hex')}@example.test`;
	let finalResponseBody = '';
	for (let attempt = 0; attempt < 4; attempt += 1) {
		const response = await page.request.post('/login?/signUpEmail', {
			headers: {
				accept: 'application/json',
				origin: new URL(page.url()).origin,
				'x-sveltekit-action': 'true'
			},
			form: {
				email,
				password: 'correct horse battery staple 2026',
				name: 'Signup Limit Test'
			}
		});
		finalResponseBody = await response.text();
	}
	expect(finalResponseBody).toContain('Too many account-creation attempts. Try again later.');
});

test('TOTP setup reveals recovery codes only after verification and backup sign-in works', async ({
	page
}) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	await page.goto('/app/settings');
	const enableForm = page.locator('form[action="?/enableTwoFactor"]');
	await enableForm.getByLabel('Password').fill('correct horse battery staple 2026');
	const heldEnable = await holdSettingsAction(page, 'enableTwoFactor');
	await enableForm.getByRole('button', { name: 'Set up authenticator' }).click();
	await heldEnable.observed;
	await expect(enableForm.getByRole('button', { name: 'Starting…' })).toBeDisabled();
	heldEnable.release();
	await expect(page.getByText('Cannot scan it? Enter this setup key manually:')).toBeVisible();
	await heldEnable.stop();
	await expect(page.getByText('Recovery codes')).toHaveCount(0);
	const secret = (await page.locator('.setup-qr code').textContent())?.trim();
	if (!secret) throw new Error('TOTP setup did not render the manual secret.');
	await page.getByLabel('Authenticator code').fill(totpForSecret(secret));
	const heldVerify = await holdSettingsAction(page, 'verifySetupTotp');
	await page.getByRole('button', { name: 'Verify code' }).click();
	await heldVerify.observed;
	await expect(page.getByRole('button', { name: 'Verifying…' })).toBeDisabled();
	heldVerify.release();
	await expect(page.getByText('Recovery codes', { exact: true })).toBeVisible();
	await heldVerify.stop();
	const backupCodes = (await page.locator('.setup-codes pre').textContent())?.trim().split('\n');
	expect(backupCodes).toHaveLength(10);
	const backupCode = backupCodes?.[0];
	if (!backupCode) throw new Error('TOTP verification did not return backup codes.');

	await page.getByRole('button', { name: 'Sign out' }).click();
	await expect(page).toHaveURL(/\/$/);
	await page.goto('/login');
	await localSignInForm(page).getByLabel('Email').fill(email);
	await localSignInForm(page).getByLabel('Password').fill('correct horse battery staple 2026');
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page).toHaveURL(/\/login\/two-factor$/);
	const backupInput = page.getByLabel('Backup code');
	await expect(backupInput).toHaveAttribute('inputmode', 'text');
	await backupInput.fill(backupCode);
	const heldBackupCode = await holdPageAction(page, '/login/two-factor', 'verifyBackupCode');
	await page.getByRole('button', { name: 'Use backup code' }).click();
	await heldBackupCode.observed;
	await expect(page.getByRole('button', { name: 'Checking backup code…' })).toBeDisabled();
	heldBackupCode.release();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	await heldBackupCode.stop();

	await page.goto('/app/settings');
	const disableForm = page.locator('form[action="?/disableTwoFactor"]');
	await disableForm.getByLabel('Password').fill('correct horse battery staple 2026');
	const heldDisable = await holdSettingsAction(page, 'disableTwoFactor');
	await disableForm.getByRole('button', { name: 'Disable authenticator' }).click();
	await heldDisable.observed;
	await expect(disableForm.getByRole('button', { name: 'Disabling…' })).toBeDisabled();
	heldDisable.release();
	await expect(page.getByText('Two-factor authentication disabled.')).toBeVisible();
	await heldDisable.stop();
});

test('password reset tokens are single-use and revoke existing sessions', async ({ page }) => {
	await clearPasswordResetRateLimits();
	const email = await createAccount(page);
	const resetToken = randomBytes(32).toString('base64url');
	const trustedVerificationId = await insertTrustedDeviceVerification(email);
	const newPassword = 'correct horse battery staple 2028';
	await insertPasswordResetToken(email, resetToken, new Date(Date.now() + 30 * 60_000));

	await page.goto(`/login/reset-password?token=${encodeURIComponent(resetToken)}`);
	await expect(page).toHaveURL(/\/login\/reset-password$/);
	await page.getByLabel('New password').fill(newPassword);
	await page.getByLabel('Confirm password').fill(newPassword);
	const heldPasswordReset = await holdPageAction(page, '/login/reset-password', 'resetPassword');
	await page.getByRole('button', { name: 'Change password' }).click();
	await heldPasswordReset.observed;
	await expect(page.getByRole('button', { name: 'Changing password…' })).toBeDisabled();
	heldPasswordReset.release();
	await expect(page.getByText('Password changed. Sign in with the new password.')).toBeVisible();
	await heldPasswordReset.stop();
	expect(await verificationExists(trustedVerificationId)).toBe(false);

	await page.goto('/app');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();

	await page.goto('/login');
	await localSignInForm(page).getByLabel('Email').fill(email);
	await localSignInForm(page).getByLabel('Password').fill('correct horse battery staple 2026');
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page.getByText('Email or password is not correct.')).toBeVisible();

	await localSignInForm(page).getByLabel('Password').fill(newPassword);
	await localSignInForm(page).getByRole('button', { name: 'Sign in' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);

	await page.goto(`/login/reset-password?token=${encodeURIComponent(resetToken)}`);
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);

	const expiredToken = randomBytes(32).toString('base64url');
	await insertPasswordResetToken(email, expiredToken, new Date(Date.now() - 60_000));
	await page.goto(`/login/reset-password?token=${encodeURIComponent(expiredToken)}`);
	await expect(
		page.getByText('That reset link is invalid, expired, or already used.')
	).toBeVisible();
	await expect(page.getByLabel('New password')).toHaveCount(0);
});

test('established onboarding creates the distance phase from a repeatable baseline', async ({
	page
}) => {
	const email = await createAccount(page);
	await expect(page.getByLabel('Race distance')).toHaveValue('');
	await fillValidPlanIntake(page);
	await expect(page.getByText('Distance plan from an established week')).toBeVisible();
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);

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

	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 20 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await expectNoHorizontalOverflow(page);
	await expectVisibleChoiceHeadingsReadable(page);
	await page.getByLabel('Weekly distance (km)').fill('6');
	await page.getByLabel('Runs per week').fill('2');
	await page.getByLabel('Longest recent run (km)').fill('3');
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

test('foundation-first onboarding keeps the race goal and creates the exact timed phase', async ({
	page
}) => {
	const email = await createAccount(page);
	const target = addIsoDays(testDate, 20 * 7);
	await page.getByLabel('Race distance').selectOption('half');
	await page.getByLabel('Target date').fill(target);
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel(/Foundation first/).check();
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
});

test('completed foundation work requires confirmation before the retained race phase starts', async ({
	page
}) => {
	const email = await createAccount(page);
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 20 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel(/Foundation first/).check();
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
	await expect(page.locator('.phase-measures')).toContainText('27');
	await expect(page.locator('.phase-measures')).toContainText('54 km');
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
	await expect(page.getByText('NHS Couch to 5K foundation')).toBeVisible();
	await goToOnboardingStep(page, 'Schedule');
	await setAvailability(page, ['Tue', 'Thu', 'Sat']);
	await page.getByLabel('Training time zone').fill('America/Halifax');
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page).toHaveURL(/\/app$/);
	await expect(page.locator('body')).not.toHaveCSS('overflow-x', 'scroll');

	const state = await getCurrentGoalPlanState(await getUserId(email));
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
	await expect(page.locator('.calendar-week-load').first()).toContainText('0 min done of 86 min');
	await page
		.getByRole('button', { name: /Foundation run\/walk/ })
		.first()
		.click();
	await expect(page.getByRole('heading', { name: 'Run/walk instructions' })).toBeVisible();
	await expect(page.getByText('Warm up · walk 5 min')).toBeVisible();
	await page.getByRole('button', { name: 'Close training detail' }).click();
	await page.getByRole('link', { name: 'History' }).click();
	await page.getByRole('link', { name: 'Plan record' }).click();
	await expect(
		page.getByRole('heading', { name: 'Run continuously for 30 minutes' })
	).toBeVisible();
	await expect(page.getByText('Training 1h 26m')).toBeVisible();
	await expect(page.getByText('29 min', { exact: true }).first()).toBeVisible();
});

test('short calibration creates two identical timed sessions per week', async ({ page }) => {
	const email = await createAccount(page);
	await page.getByLabel('Race distance').selectOption('5k');
	await page.getByLabel('Target date').fill(addIsoDays(testDate, 12 * 7));
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel(/Short calibration/).check();
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
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(page.getByText(/Choose a date from .* to .*\./)).toBeVisible();
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
	await goToOnboardingStep(page, 'Goal');
	await expect(
		page.getByText('Move the target date later or choose a shorter goal.')
	).toBeVisible();
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
	await page.getByRole('button', { name: 'Create plan' }).click();
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

test('onboarding returns hidden required fields to the visible step before submission', async ({
	page
}) => {
	await createAccount(page);
	await goToOnboardingStep(page, 'Review');
	await page.getByRole('button', { name: 'Create plan' }).click();
	await expect(page.getByRole('heading', { name: 'Goal' })).toBeVisible();
	await expect(
		page.getByText('Complete the required fields before reviewing this plan.')
	).toBeVisible();
	await expect(page.getByLabel('Race distance')).toBeFocused();
});

test('current pain saves a pending goal without creating workouts', async ({ page }) => {
	const email = await createAccount(page);
	await fillValidPlanIntake(page);
	await goToOnboardingStep(page, 'Starting point');
	await page.getByLabel('Current pain').check();
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
	await page.getByRole('link', { name: 'Change goal' }).click();
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
	await page.getByRole('link', { name: 'Change goal' }).click();
	await goToOnboardingStep(page, 'Review');
	await expect(page.getByRole('button', { name: 'Replace active plan' })).toBeVisible();

	await page.getByRole('link', { name: 'History' }).click();
	await expect(page.getByRole('heading', { name: 'Past plans' })).toBeVisible();
	await expect(page.getByText('Goal changed')).toBeVisible();
});

test('an active plan can be stopped explicitly and reviewed in History', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'History' }).click();
	await expect(page.getByRole('heading', { name: 'Half marathon plan' })).toBeVisible();
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
	await expect(page.locator('.stats-signal strong')).toHaveText('no active plan');
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

test('stats keep archived completed work separate from the current plan', async ({ page }) => {
	const email = await createAccount(page);
	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.waitForLoadState('networkidle');

	const userId = await getUserId(email);
	const targetRun = (await getPlannedRuns(userId))[0];
	if (!targetRun) throw new Error('Plan did not create a run for archived stats test.');
	await moveWorkoutToDate(targetRun.id, testDate);

	const completedDistanceKm = targetRun.targetDistanceMeters / 1_000;
	const feedbackResponse = await page.request.post('/app?/recordFeedback', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			workoutId: targetRun.id,
			status: 'done',
			completedDistanceKm: String(completedDistanceKm),
			completedDurationMinutes: '20',
			choice: 'reduce_next'
		}
	});
	expect(feedbackResponse.status()).toBeLessThan(400);

	await page.goto('/app/onboarding');
	await expect(page.getByRole('heading', { name: 'Change goal' })).toBeVisible();
	await page.getByLabel('Race distance').selectOption('10k');
	await goToOnboardingStep(page, 'Review');
	await page.getByLabel(/Archive the current goal/).check();
	await page.getByRole('button', { name: 'Replace active plan' }).click();
	await page.waitForURL(/\/app$/);

	await page.goto('/app/stats');
	const recordedHistory = page.getByRole('region', { name: 'Recorded history' });
	await expect(recordedHistory).toBeVisible();
	await expect(page.getByText('Current-plan distance')).toBeVisible();
	await expect(recordedHistory.getByText('Archived plans')).toBeVisible();
	await expect(recordedHistory.getByText('1 archived run still counted.')).toBeVisible();
	await expect(
		recordedHistory.getByText(`${Math.round(completedDistanceKm * 10) / 10} km`).first()
	).toBeVisible();
});

test('training calendar month controls are URL-backed', async ({ page }) => {
	await createPlan(page);
	const currentMonth = currentCalendarMonth();
	const nextMonth = shiftCalendarMonth(currentMonth, 1);
	await expect(page.locator('.calendar-month-week').first()).toBeVisible();
	await expect(page.locator('.calendar-week-load').first()).toBeVisible();
	await expect(page.locator('.week-load-track').first()).toBeVisible();
	await expect(page.getByText(/done of/).first()).toBeVisible();
	await expect(page.locator('.calendar-weekday-row')).toBeVisible();
	const visibleDayCount = await page.locator('.calendar-month-day').count();
	expect(visibleDayCount).toBeGreaterThanOrEqual(35);
	expect(visibleDayCount).toBeLessThanOrEqual(42);
	expect(visibleDayCount % 7).toBe(0);

	await page.getByRole('link', { name: 'Next month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${nextMonth}`));
	await expect(page.getByText(calendarMonthLabel(nextMonth))).toBeVisible();

	await page.getByRole('link', { name: 'Previous month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${currentMonth}`));
	await expect(page.getByText(calendarMonthLabel(currentMonth))).toBeVisible();

	await page.getByRole('link', { name: 'Current month' }).click();
	await expect(page).toHaveURL(new RegExp(`/app\\?month=${currentMonth}`));
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
	await page.getByText('Record run').first().click();
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
	await page.getByRole('button', { name: 'Import' }).click();
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
	await expect(page.getByLabel('Training time zone')).toHaveValue('Pacific/Kiritimati');

	await page.goto('/app/import');
	await openImportSourceSetup(page);
	await uploadGpx('kiritimati-midnight.gpx', '2026-05-14T13:30:00.000Z');
	await expect.poll(() => getActivityDates(userId)).toEqual(['2026-05-14', '2026-05-15']);
});

test('heart-rate imports stay descriptive while stats show the measured zones', async ({
	page
}) => {
	const email = await createAccount(page);
	await page.getByRole('link', { name: 'Settings' }).click();
	await page.getByLabel('Sex used for estimates').selectOption('female');
	await page.getByLabel('Age').fill('35');
	await page.getByRole('button', { name: 'Use estimate' }).click();
	await page.getByRole('button', { name: 'Save training profile' }).click();
	await expect(page.getByText('Training profile saved.')).toBeVisible();

	await page.goto('/app/onboarding');
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await makeFirstPlanWeekCurrent(await getUserId(email));

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');
	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'high-effort-heart-rate.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2026-05-11T12:00:00Z</time><extensions><gpxtpx:hr>168</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2026-05-11T12:10:00Z</time><extensions><gpxtpx:hr>170</gpxtpx:hr></extensions></trkpt>
					<trkpt lat="45.0020" lon="-63.0020"><time>2026-05-11T12:20:00Z</time><extensions><gpxtpx:hr>172</gpxtpx:hr></extensions></trkpt>
				</trkseg></trk></gpx>`)
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('Heart-rate zone time was included.')).toBeVisible();
	await page.getByText('Manage', { exact: true }).click();
	await expect(
		page.getByRole('checkbox', { name: 'Felt harder than expected' }).first()
	).not.toBeChecked();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Average heart rate', { exact: true })).toBeVisible();
	await expect(page.getByText('170 bpm').first()).toBeVisible();
	await expect(page.getByText('High-zone time', { exact: true })).toBeVisible();
	await expect(page.getByText('Latest max 172 bpm. Descriptive only.')).toBeVisible();
});

test('settings keeps training profile values visible after save', async ({ page }) => {
	await createAccount(page);
	await page.getByRole('link', { name: 'Settings' }).click();
	await expect(page.getByLabel('Age')).toHaveValue('');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('');
	await expect(page.getByLabel('Zone 2 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 3 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 4 starts')).toHaveValue('');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('');
	await expect(
		page.locator('.estimate-panel').getByText('not configured', { exact: true })
	).toBeVisible();
	await expect(page.getByText('Estimate unavailable')).toBeVisible();
	await page.getByLabel('Sex used for estimates').selectOption('female');
	await page.getByLabel('Age').fill('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');
	await page.getByLabel('Max heart rate').fill('172');
	await page.getByLabel('Zone 2 starts').fill('104');
	await page.getByLabel('Zone 3 starts').fill('121');
	await page.getByLabel('Zone 4 starts').fill('138');
	await page.getByLabel('Zone 5 starts').fill('155');
	await page.getByRole('button', { name: 'Save training profile' }).click();

	await expect(page.getByText('Training profile saved.')).toBeVisible();
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.locator('.estimate-panel').getByText('custom', { exact: true })).toBeVisible();

	await page.reload();
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');

	await page.goto('/app/onboarding');
	await expect(page.getByLabel('Sex used for estimates')).toHaveCount(0);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.goto('/app/onboarding');
	await page.getByLabel('Race distance').selectOption('10k');
	await goToOnboardingStep(page, 'Review');
	await page.getByLabel(/Archive the current goal/).check();
	await page.getByRole('button', { name: 'Replace active plan' }).click();
	await page.waitForURL(/\/app$/);
	await page.goto('/app/settings');
	await expect(page.getByLabel('Sex used for estimates')).toHaveValue('female');
	await expect(page.getByLabel('Age')).toHaveValue('39');
	await expect(page.getByLabel('Max heart rate')).toHaveValue('172');
	await expect(page.getByLabel('Zone 5 starts')).toHaveValue('155');
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
	await page.getByRole('button', { name: 'Import' }).click();

	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	await expect(page.getByText(/Completed .* below plan\./)).toBeVisible();
	await expect.poll(async () => (await getWorkout(targetRun.id)).status).toBe('shortened');
	await expect
		.poll(async () => (await getWorkout(nextFutureRun.id)).targetDistanceMeters)
		.toBe(nextFutureRun.targetDistanceMeters);
	await expect.poll(() => hasDistanceAdjustment(userId, 'import_match')).toBe(false);

	await page.goto('/app');
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
	await page.getByRole('button', { name: 'Import' }).click();

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
	await page.getByRole('button', { name: 'Import' }).click();

	const records = page.locator('.import-inbox');
	const record = records.locator('.activity-record').first();
	await expect(page.getByText(/Added to the activity inbox\./)).toBeVisible();
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
	await expect
		.poll(async () => await getPlanAdjustmentTypes(userId))
		.toEqual(expect.arrayContaining(['link']));
	expect(await getPlanAdjustmentTypes(userId)).not.toContain('import_extra');
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

	await page.getByRole('link', { name: 'Stats' }).click();
	const recordedDistance = page.getByText('Recorded distance', { exact: true }).locator('..');
	await expect(recordedDistance).toContainText('0 km');

	await page.getByRole('link', { name: 'Inbox', exact: true }).click();
	await record.getByText('Review', { exact: true }).click();
	page.once('dialog', (dialog) => dialog.accept());
	await record.getByRole('button', { name: 'Count as extra training' }).click();
	await expect(record.getByText('Included in training load')).toBeVisible();

	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(page.getByText('Recorded distance', { exact: true }).locator('..')).toContainText(
		'10 km'
	);
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

	await record.getByRole('checkbox', { name: 'Pain affected this run' }).check();
	await record.getByRole('button', { name: 'Save feedback' }).click();
	await expect(page.getByText('Activity feedback updated.')).toBeVisible();
	await expect
		.poll(async () => (await getWorkout(futureRun.id)).targetDistanceMeters)
		.toBe(countedExtraTarget);

	await record.getByRole('checkbox', { name: 'Pain affected this run' }).uncheck();
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
	await page.getByRole('button', { name: 'Import' }).click();
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
	await page.getByText('Record run').first().click();
	await page.getByRole('checkbox', { name: 'Pain affected this run' }).first().check();
	await page
		.getByRole('button', { name: /Save feedback/ })
		.first()
		.click();
	await expect(page.getByText('Feedback saved.')).toBeVisible();
	await expect(page.getByText(/Pain was reported for this run\./).first()).toBeVisible();

	await page.reload();
	await page.locator('.calendar-event.pain').first().click();
	await expect(page.getByText(/Pain was reported for this run\./).first()).toBeVisible();
});

test('GPX import rejects tampered workout matches outside the activity window', async ({
	page
}) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'tampered-match.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(`<?xml version="1.0"?>
				<gpx><trk><trkseg>
					<trkpt lat="45.0000" lon="-63.0000"><time>2020-01-01T12:00:00Z</time></trkpt>
					<trkpt lat="45.0010" lon="-63.0010"><time>2020-01-01T12:01:00Z</time></trkpt>
				</trkseg></trk></gpx>`)
	});
	await page.getByRole('button', { name: 'Import' }).click();

	await expect(
		page.getByText('The GPX file could not be saved with that workout match.')
	).toBeVisible();
});

test('matched GPX overruns carry the same load-spike consequence as feedback', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'long-matched-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(longGpx('2026-05-11T12:00:00Z'))
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText(/Completed .* above plan\./)).toBeVisible();

	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page.locator('.plan-assessment')).toContainText('aggressive');
	await expect(page.locator('.plan-assessment')).toContainText('Recent activity');
	await page.locator('.plan-assessment > summary').click();
	await expect(page.getByText(/Completed .* above plan\./).first()).toBeVisible();
	await page.getByRole('link', { name: 'Stats' }).click();
	await expect(
		page.getByRole('heading', { name: 'Does the current plan need attention?' })
	).toBeVisible();
	await expect(page.getByText('Based on recent activity', { exact: true })).toBeVisible();
});

test('stale GPX import cannot attach a second activity to the same workout', async ({ page }) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	const selectedWorkoutId = await page.locator('select[name="workoutId"]').evaluate((select) => {
		if (!(select instanceof HTMLSelectElement)) return '';
		return Array.from(select.options).find((option) => option.value)?.value ?? '';
	});
	expect(selectedWorkoutId).not.toBe('');

	await page.locator('select[name="workoutId"]').selectOption(selectedWorkoutId);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'first-workout-match.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from(gpx('2026-05-11T12:00:00Z'))
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();

	const response = await page.request.post('/app/import?/importGpx', {
		headers: {
			origin: new URL(page.url()).origin
		},
		multipart: {
			matchMode: 'workout',
			workoutId: selectedWorkoutId,
			file: {
				name: 'second-workout-match.gpx',
				mimeType: 'application/gpx+xml',
				buffer: Buffer.from(gpx('2026-05-11T12:05:00Z'))
			}
		}
	});
	expect(response.status()).toBeLessThan(500);
	const body = await response.text();
	expect(body).toContain('That workout already has an imported activity.');
});

test('server actions require the exact app origin', async ({ page }) => {
	await createPlan(page);

	const crossSiteResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: 'https://evil.example.test' },
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});

	expect(crossSiteResponse.status()).toBe(403);
	expect(crossSiteResponse.headers()['cache-control']).toBe('private, no-store');
	expect(crossSiteResponse.headers()['content-security-policy']).toContain(
		"frame-ancestors 'none'"
	);
	expect(crossSiteResponse.headers()['x-frame-options']).toBe('DENY');
	await expect(crossSiteResponse.text()).resolves.toContain('Cross-site');

	const siblingOriginResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: 'https://admin.example.test' },
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});
	expect(siblingOriginResponse.status()).toBe(403);

	const noOriginResponse = await page.request.post('/app/import?/deleteActivity', {
		multipart: { activityId: '00000000-0000-0000-0000-000000000000' }
	});
	expect(noOriginResponse.status()).toBe(403);
});

test('cross-user activity and workout tampering is rejected server-side', async ({ page }) => {
	const ownerEmail = await createAccount(page);
	await fillValidPlanIntake(page);
	await page.getByRole('button', { name: 'Create plan' }).click();
	await page.waitForURL(/\/app$/);
	const ownerId = await getUserId(ownerEmail);
	await makeFirstPlanWeekCurrent(ownerId);
	const [ownerWorkout] = await getPlannedRuns(ownerId);
	if (!ownerWorkout) throw new Error('Owner plan did not create a workout.');

	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);
	await page.getByLabel('Choose a planned workout').check();
	await page.locator('select[name="workoutId"]').selectOption(ownerWorkout.id);
	await page.getByLabel('GPX file').setInputFiles({
		name: 'owner-activity.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance(ownerWorkout.scheduledDate, ownerWorkout.targetDistanceMeters)
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('Matched to the selected planned workout.')).toBeVisible();
	const ownerActivityId = await getFirstActivityId(ownerId);

	await page.getByRole('button', { name: 'Sign out' }).click();
	await createPlan(page);

	const deleteResponse = await page.request.post('/app/import?/deleteActivity', {
		headers: { origin: new URL(page.url()).origin },
		multipart: { activityId: ownerActivityId }
	});
	expect(deleteResponse.status()).toBeLessThan(500);
	await expect(deleteResponse.text()).resolves.toContain('Activity not found.');
	await expect.poll(() => activityExists(ownerActivityId)).toBe(true);

	const feedbackResponse = await page.request.post('/app?/recordFeedback', {
		headers: { origin: new URL(page.url()).origin },
		multipart: {
			workoutId: ownerWorkout.id,
			status: 'skipped',
			choice: 'reduce_next'
		}
	});
	expect(feedbackResponse.status()).toBeLessThan(500);
	await expect(feedbackResponse.text()).resolves.toContain('Workout not found.');
});

test('GPX import rejects malformed and oversized uploads without server errors', async ({
	page
}) => {
	await createPlan(page);
	await page.getByRole('link', { name: 'Inbox' }).click();
	await openImportSourceSetup(page);

	await page.getByLabel('GPX file').setInputFiles({
		name: 'not-a-track.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.from('<gpx><metadata /></gpx>')
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('The GPX file could not be parsed.')).toBeVisible();

	await page.getByLabel('GPX file').setInputFiles({
		name: 'too-large.gpx',
		mimeType: 'application/gpx+xml',
		buffer: Buffer.alloc(10 * 1024 * 1024 + 1, '<')
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('GPX file is too large for import.')).toBeVisible();

	await page.getByLabel('GPX file').setInputFiles({
		name: 'future-run.gpx',
		mimeType: 'application/gpx+xml',
		buffer: gpxForDistance('2099-01-01', 3_000)
	});
	await page.getByRole('button', { name: 'Import' }).click();
	await expect(page.getByText('Imported activities cannot be in the future.')).toBeVisible();
});

test('import requires an explicit training time zone before upload or source connection', async ({
	page
}) => {
	await createAccount(page);
	await page.goto('/app/import');
	await openImportSourceSetup(page);

	await expect(
		page.getByRole('alert').getByText('Set the training time zone before importing.', {
			exact: true
		})
	).toBeVisible();
	await expect(
		page
			.locator('a[href="/app/settings"]')
			.filter({ hasText: /Settings/ })
			.first()
	).toBeVisible();
	await expect(page.getByRole('button', { name: 'Connect folder' })).toBeDisabled();
	await expect(page.getByRole('button', { name: 'Import', exact: true })).toBeDisabled();

	const response = await page.request.post('/app/import?/saveNextcloudSource', {
		headers: { origin: new URL(page.url()).origin },
		form: {
			label: 'Blocked source',
			shareUrl: 'https://cloud.example.test/s/token',
			sharePassword: 'password'
		}
	});
	expect(response.status()).toBeLessThan(500);
	await expect(response.text()).resolves.toContain('Set training time zone before importing.');
});

test('Nextcloud share sync backfills files, tracks revisions, and honors deletion tombstones', async ({
	page
}) => {
	const share = await startNextcloudShareFixture();
	const syncNow = async () => {
		const responsePromise = page.waitForResponse(
			(response) => response.request().method() === 'POST' && response.url().includes('/app/import')
		);
		await page.getByRole('button', { name: 'Sync now' }).click();
		const response = await responsePromise;
		expect(response.status()).toBeLessThan(500);
	};

	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page);
		await page.getByLabel('Label').fill('Watch exports');
		await page.getByLabel('Share link').fill(share.url);
		await page.getByLabel('Share password').fill(share.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud folder connected.')).toBeVisible();
		const htmlAfterConnect = await page.content();
		expect(htmlAfterConnect).not.toContain('testToken123');
		expect(htmlAfterConnect).not.toContain(share.password);
		expect(htmlAfterConnect).not.toContain('sharePasswordSecret');

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(1);

		await page.getByText('Review', { exact: true }).click();
		page.once('dialog', (dialog) => dialog.accept());
		await page.getByRole('button', { name: 'Delete activity', exact: true }).click();
		await expect(page.getByText('Activity deleted.')).toBeVisible();

		share.exposeRenamedDuplicate();
		await syncNow();
		await expect(page.getByText('That GPX file was already handled.')).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		await expect.poll(share.renamedDownloads).toBe(1);

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		await expect.poll(share.olderDownloads).toBe(1);

		share.replaceNewer();
		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		await expect.poll(share.newerDownloads).toBe(2);

		await syncNow();
		await expect(page.getByText('All visible GPX files were already handled.')).toBeVisible();
		expect(share.newerDownloads()).toBe(2);
		expect(share.olderDownloads()).toBe(1);
		expect(share.renamedDownloads()).toBe(1);

		const activityRecords = page.locator('.import-inbox');
		await expect(activityRecords.locator('.activity-record')).toHaveCount(2);

		await page.goto('/app/settings');
		page.once('dialog', (dialog) => dialog.accept());
		await page.getByRole('button', { name: 'Delete imported route data' }).click();
		await expect(
			page.getByText('Disconnected 1 import folder so it cannot sync the activity back.')
		).toBeVisible();

		await page.goto('/app/import');
		await expect(page.getByText('No import sources connected.')).toBeVisible();
	} finally {
		await share.close();
	}
});

test('Nextcloud share sync backfills past a failed revision and retries it only after change', async ({
	page
}) => {
	const share = await startNextcloudShareFixture({ malformedNewest: true });
	const syncNow = async () => {
		const responsePromise = page.waitForResponse(
			(response) => response.request().method() === 'POST' && response.url().includes('/app/import')
		);
		await page.getByRole('button', { name: 'Sync now' }).click();
		const response = await responsePromise;
		expect(response.status()).toBeLessThan(500);
	};

	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page);
		await page.getByLabel('Label').fill('Watch exports');
		await page.getByLabel('Share link').fill(share.url);
		await page.getByLabel('Share password').fill(share.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud folder connected.')).toBeVisible();

		await syncNow();
		await expect(page.getByText('The selected GPX file could not be parsed.')).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		expect(share.olderDownloads()).toBe(0);

		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(1);
		expect(share.olderDownloads()).toBe(1);

		share.replaceNewer();
		await syncNow();
		await expect(page.getByText(/Imported .* to the activity inbox for review/)).toBeVisible();
		expect(share.newerDownloads()).toBe(2);
	} finally {
		await share.close();
	}
});

test('Nextcloud share setup rejects wrong passwords and unprotected folders', async ({ page }) => {
	const protectedShare = await startNextcloudShareFixture();
	const unprotectedShare = await startNextcloudShareFixture({ requirePassword: false });
	try {
		const email = await createAccount(page);
		await setTrainingTimeZone(email);
		await page.goto('/app/import');
		await openImportSourceSetup(page);

		await page.getByLabel('Label').fill('Wrong password export');
		await page.getByLabel('Share link').fill(protectedShare.url);
		await page.getByLabel('Share password').fill('not the right password');
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud share password was rejected.')).toBeVisible();

		await page.getByLabel('Label').fill('Unprotected export');
		await page.getByLabel('Share link').fill(unprotectedShare.url);
		await page.getByLabel('Share password').fill(unprotectedShare.password);
		await page.getByRole('button', { name: 'Connect folder' }).click();
		await expect(page.getByText('Nextcloud share must require the password.')).toBeVisible();
		await expect(page.getByText('Nextcloud folder connected.', { exact: true })).not.toBeVisible();
	} finally {
		await protectedShare.close();
		await unprotectedShare.close();
	}
});

test('public pages are accessible at desktop and mobile widths', async ({ page }) => {
	const cspErrors: string[] = [];
	page.on('console', (message) => {
		if (message.type() === 'error' && message.text().includes('Content Security Policy')) {
			cspErrors.push(message.text());
		}
	});

	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/');
	await expect(page.getByRole('heading', { name: 'runway' })).toBeVisible();
	await expectNoCriticalAxeViolations(page);

	await page.setViewportSize({ width: 1280, height: 900 });
	await page.goto('/login');
	await expect(page.getByRole('heading', { name: 'Sign in', exact: true })).toBeVisible();
	await expect(page.getByRole('button', { name: 'Sign in with passkey' })).toBeVisible();
	await expectNoCriticalAxeViolations(page);
	expect(cspErrors).toEqual([]);
});

test('PWA files and private routes carry the expected cache boundaries', async ({ page }) => {
	const manifest = await page.request.get('/manifest.webmanifest');
	expect(manifest.ok()).toBe(true);
	await expect(manifest.json()).resolves.toMatchObject({
		id: '/',
		name: 'runway',
		short_name: 'runway',
		start_url: '/app',
		scope: '/',
		display: 'standalone',
		share_target: {
			action: '/app/import/share',
			method: 'POST',
			enctype: 'multipart/form-data',
			params: { files: [{ name: 'gpx' }] }
		}
	});

	const serviceWorker = await page.request.get('/service-worker.js');
	expect(serviceWorker.ok()).toBe(true);
	expect(serviceWorker.headers()['cache-control']).toBe('public, max-age=0, must-revalidate');
	const serviceWorkerBody = await serviceWorker.text();
	expect(serviceWorkerBody).toContain(
		"PRIVATE_PREFIXES = ['/app', '/api/auth', '/login', '/logout']"
	);
	expect(serviceWorkerBody).toContain("'/offline.css'");
	expect(serviceWorkerBody).toContain("event.data?.type !== 'ACTIVATE_UPDATE'");
	expect(serviceWorkerBody).toContain('event.waitUntil(self.skipWaiting())');
	expect(serviceWorkerBody).toContain('self.registration.navigationPreload?.enable()');
	expect(serviceWorkerBody).toContain('event.preloadResponse');
	const cacheRevision = /const CACHE_REVISION = "([^"]+)"/.exec(serviceWorkerBody)?.[1];
	const live = await page.request.get('/health/live');
	expect(live.ok()).toBe(true);
	const liveBody: unknown = await live.json();
	expect(liveBody).toEqual(expect.objectContaining({ version: cacheRevision }));

	const offline = await page.request.get('/offline.html');
	expect(offline.ok()).toBe(true);
	const offlineBody = await offline.text();
	expect(offlineBody).toContain('Reconnect to open your calendar and training data.');
	expect(offlineBody).toContain('href="/app">Try again</a>');
	expect(offlineBody).not.toContain('<style');
	const offlineCss = await page.request.get('/offline.css');
	expect(offlineCss.ok()).toBe(true);
	expect(offlineCss.headers()['cache-control']).toBe('public, max-age=86400');
	await page.setViewportSize({ width: 390, height: 844 });
	await page.goto('/offline.html');
	expect(
		await page.evaluate(() => ({
			scrollWidth: document.documentElement.scrollWidth,
			clientWidth: document.documentElement.clientWidth
		}))
	).toMatchObject({ scrollWidth: 390, clientWidth: 390 });

	const app = await page.request.get('/app', { maxRedirects: 0 });
	expect([302, 303, 307, 308]).toContain(app.status());
	expect(app.headers()['cache-control']).toBe('private, no-store');
});

test('PWA lifecycle shows connection state and offers install only from settings', async ({
	context,
	page
}) => {
	await createAccount(page);
	await page.goto('/app/settings');

	const installNotice = page.getByRole('region', { name: 'Install runway' });
	const installButton = installNotice.getByRole('button', { name: 'Install', exact: true });
	await expect(
		installNotice.getByText('Use the browser menu and choose Install app or Add to Home screen.')
	).toBeVisible();
	await expect
		.poll(async () => {
			await page.evaluate(() => {
				const installEvent = new Event('beforeinstallprompt', { cancelable: true });
				Object.defineProperties(installEvent, {
					prompt: {
						value: () => {
							(
								globalThis as typeof globalThis & { runwayInstallPrompted?: boolean }
							).runwayInstallPrompted = true;
							return Promise.resolve();
						}
					},
					userChoice: {
						value: Promise.resolve({ outcome: 'accepted', platform: 'test' })
					}
				});
				globalThis.dispatchEvent(installEvent);
			});
			return installButton.isVisible();
		})
		.toBe(true);
	await page.getByRole('link', { name: 'Calendar' }).click();
	await expect(page).toHaveURL(/\/app\/onboarding$/);
	await expect(installNotice).not.toBeVisible();
	await page.getByRole('link', { name: 'Settings' }).click();
	await expect(page).toHaveURL(/\/app\/settings$/);
	await expect(installNotice).toBeVisible();
	await installButton.click();
	await expect
		.poll(() =>
			page.evaluate(
				() =>
					(globalThis as typeof globalThis & { runwayInstallPrompted?: boolean })
						.runwayInstallPrompted
			)
		)
		.toBe(true);
	await expect(installNotice).not.toBeVisible();

	await context.setOffline(true);
	try {
		await expect(
			page.getByText('Reconnect to view or change private training data.')
		).toBeVisible();
	} finally {
		await context.setOffline(false);
	}
	await expect(page.getByText('Back online')).toBeVisible();
});

test('service worker serves the offline fallback without caching private app pages', async ({
	context,
	page
}) => {
	await page.goto('/');
	await page.evaluate(async () => {
		if (!('serviceWorker' in navigator)) throw new Error('Service workers are unavailable.');
		await navigator.serviceWorker.ready;
	});
	await page.reload();
	await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

	const cachedPaths = await page.evaluate(async () => {
		const paths: string[] = [];
		for (const cacheName of await caches.keys()) {
			if (!cacheName.startsWith('runway-')) continue;
			const cache = await caches.open(cacheName);
			for (const request of await cache.keys()) {
				paths.push(new URL(request.url).pathname);
			}
		}
		return paths;
	});
	expect(cachedPaths).toContain('/offline.html');
	expect(cachedPaths).toContain('/offline.css');
	expect(cachedPaths.some((path) => path.startsWith('/app'))).toBe(false);

	await context.setOffline(true);
	try {
		await page.goto('/app');
		await expect(page.getByRole('heading', { name: 'Offline' })).toBeVisible();
		await expect(
			page.getByText('Reconnect to open your calendar and training data.')
		).toBeVisible();
	} finally {
		await context.setOffline(false);
	}
});

async function startNextcloudShareFixture(options?: {
	requirePassword?: boolean;
	malformedNewest?: boolean;
}): Promise<{
	url: string;
	password: string;
	newerDownloads: () => number;
	olderDownloads: () => number;
	renamedDownloads: () => number;
	exposeRenamedDuplicate: () => void;
	replaceNewer: () => void;
	close: () => Promise<void>;
}> {
	const requirePassword = options?.requirePassword ?? true;
	const token = 'testToken123';
	const password = 'correct share password';
	const authorization = `Basic ${Buffer.from(`anonymous:${password}`).toString('base64')}`;
	let newerDownloads = 0;
	let olderDownloads = 0;
	let renamedDownloads = 0;
	let renamedDuplicateVisible = false;
	let newerRevision = 1;
	let malformedNewest = options?.malformedNewest ?? false;
	const server: Server = createServer((request, response) => {
		if (requirePassword && request.headers.authorization !== authorization) {
			response.writeHead(401);
			response.end('unauthorized');
			return;
		}

		if (request.method === 'PROPFIND') {
			sendXml(response, webdavListing(token, renamedDuplicateVisible, newerRevision));
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/renamed.gpx')) {
			renamedDownloads += 1;
			sendXml(response, gpx('2026-05-14T12:00:00Z'));
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/newer.gpx')) {
			newerDownloads += 1;
			sendXml(
				response,
				malformedNewest
					? '<gpx><metadata /></gpx>'
					: gpx(newerRevision === 1 ? '2026-05-14T12:00:00Z' : '2026-05-12T18:00:00Z')
			);
			return;
		}

		if (request.method === 'GET' && request.url?.endsWith('/older.gpx')) {
			olderDownloads += 1;
			sendXml(response, gpx('2026-05-13T12:00:00Z'));
			return;
		}

		response.writeHead(404);
		response.end('not found');
	});

	await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
	const address = server.address() as AddressInfo;
	return {
		url: `http://127.0.0.1:${address.port}/s/${token}`,
		password,
		newerDownloads: () => newerDownloads,
		olderDownloads: () => olderDownloads,
		renamedDownloads: () => renamedDownloads,
		exposeRenamedDuplicate: () => {
			renamedDuplicateVisible = true;
		},
		replaceNewer: () => {
			newerRevision = 2;
			malformedNewest = false;
		},
		close: () =>
			new Promise((resolve) => {
				server.close(() => {
					resolve();
				});
			})
	};
}

function sendXml(response: import('node:http').ServerResponse, body: string): void {
	response.writeHead(207, { 'content-type': 'application/xml' });
	response.end(body);
}

function webdavListing(
	token: string,
	includeRenamedDuplicate: boolean,
	newerRevision: number
): string {
	return `<?xml version="1.0"?>
		<d:multistatus xmlns:d="DAV:">
			<d:response>
				<d:href>/public.php/dav/files/${token}/</d:href>
				<d:propstat><d:prop><d:resourcetype><d:collection /></d:resourcetype></d:prop></d:propstat>
			</d:response>
			${
				includeRenamedDuplicate
					? `<d:response>
				<d:href>/public.php/dav/files/${token}/renamed.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Fri, 15 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"renamed"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>`
					: ''
			}
			<d:response>
				<d:href>/public.php/dav/files/${token}/older.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>Wed, 13 May 2026 12:00:00 GMT</d:getlastmodified><d:getetag>"older"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
			<d:response>
				<d:href>/public.php/dav/files/${token}/newer.gpx</d:href>
				<d:propstat><d:prop><d:getlastmodified>${newerRevision === 1 ? 'Thu, 14 May 2026 12:00:00 GMT' : 'Fri, 15 May 2026 13:00:00 GMT'}</d:getlastmodified><d:getetag>"newer-v${newerRevision}"</d:getetag><d:getcontentlength>240</d:getcontentlength><d:resourcetype /></d:prop></d:propstat>
			</d:response>
		</d:multistatus>`;
}

function gpx(start: string): string {
	return `<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="45.0000" lon="-63.0000"><time>${start}</time></trkpt>
			<trkpt lat="45.0010" lon="-63.0010"><time>${new Date(new Date(start).getTime() + 60_000).toISOString()}</time></trkpt>
		</trkseg></trk></gpx>`;
}

function longGpx(start: string): string {
	return `<?xml version="1.0"?>
		<gpx><trk><trkseg>
			<trkpt lat="45.0000" lon="-63.0000"><time>${start}</time></trkpt>
			<trkpt lat="45.2000" lon="-63.0000"><time>${new Date(new Date(start).getTime() + 7_200_000).toISOString()}</time></trkpt>
		</trkseg></trk></gpx>`;
}

test('authenticated app avoids horizontal overflow on mobile and desktop', async ({ page }) => {
	await createPlan(page);

	for (const viewport of [
		{ width: 390, height: 844 },
		{ width: 1366, height: 900 }
	]) {
		await page.setViewportSize(viewport);
		for (const label of ['Calendar', 'Inbox', 'Stats', 'Settings']) {
			await page.getByRole('link', { name: label }).click();
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
		await page.getByRole('link', { name: label }).click();
		await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible();
		await expectNoHorizontalOverflow(page);
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

	const calendarEvents = page.locator('[data-calendar-event-id]');
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
	await page
		.getByRole('button', { name: new RegExp(`^${futureRun.scheduledDate}:`) })
		.first()
		.click();
	const panel = page.getByRole('dialog');
	await expect(panel).toBeVisible();
	await expect.poll(() => page.evaluate(() => document.body.style.overflow)).toBe('hidden');
	const lastSummary = panel.getByText('Why this workout?', { exact: true });
	await lastSummary.focus();
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
	await expect(page.getByText('Record unplanned run')).toBeVisible();
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
	await expect(preview).toContainText('Projected ramp');
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
	await editor.getByLabel('Prescription').selectOption('rest');
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
	await expect(panel.getByRole('heading', { name: 'Before removing' })).toBeVisible();
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
	await addEditor.getByLabel('Distance (km)').fill('1.5');
	await addEditor.getByLabel('Purpose').fill('Short shakeout');
	await addEditor.getByLabel('Reason for the change (optional)').fill('Second session test');
	await addEditor.getByRole('button', { name: 'Preview workout' }).click();
	await expect(addDetails.locator('.edit-preview')).toContainText(
		'No generated recommendation; this is a runner-added workout.'
	);
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
	expect(added).toMatchObject({ purpose: 'Short shakeout', targetDistanceMeters: 1_500 });
	if (!added) throw new Error('Runner-added workout was not found.');

	await panel.getByRole('button', { name: 'Close training detail' }).click();
	await page
		.getByRole('button', { name: new RegExp(`^${futureRun.scheduledDate}: Short shakeout`) })
		.click();
	await panel.getByRole('button', { name: 'Preview removal' }).click();
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
