import { createHash, randomBytes } from 'node:crypto';
import postgres from 'postgres';
import { testDate } from '../../support/test-clock';
import { addIsoDays } from './dates';

export async function insertPasswordResetToken(
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

export async function clearPasswordResetRateLimits(): Promise<void> {
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

export async function getUserId(email: string): Promise<string> {
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

export async function makeFirstPlanWeekCurrent(userId: string): Promise<void> {
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

export async function finishBeginnerPhase(
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

export async function setTrainingTimeZone(
	email: string,
	timeZone = 'America/Halifax'
): Promise<void> {
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

export async function insertTrustedDeviceVerification(email: string): Promise<string> {
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

export async function verificationExists(id: string): Promise<boolean> {
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

export async function getPlannedRuns(userId: string): Promise<
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

export async function getCurrentGoalPlanState(userId: string): Promise<{
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

export async function getWorkout(id: string): Promise<{
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

export async function getVisibleWorkoutsOnDate(
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

export async function moveWorkoutToDate(id: string, scheduledDate: string): Promise<void> {
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

export async function moveActivePlanTargetDate(userId: string, targetDate: string): Promise<void> {
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

export async function getFirstActivityId(userId: string): Promise<string> {
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

export async function getActivityDates(userId: string): Promise<string[]> {
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

export async function seedManualActivityRecords(userId: string, count: number): Promise<void> {
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

export async function getPlanAdjustmentTypes(userId: string): Promise<string[]> {
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

export async function hasDistanceAdjustment(userId: string, triggerType: string): Promise<boolean> {
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

export async function activityExists(activityId: string): Promise<boolean> {
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

export function hashResetTokenForTest(token: string): string {
	return createHash('sha256')
		.update('runway-password-reset-v1')
		.update('\0')
		.update(token)
		.digest('hex');
}
