import { createHash, randomBytes } from 'node:crypto';
import postgres from 'postgres';
import { testDate } from '../../support/test-clock';
import { addIsoDays } from './dates';

type HeldMutationLock = {
	waitForBlockedRequests: (count: number) => Promise<void>;
	release: () => void;
	done: Promise<void>;
};

export async function holdActivityMutationLock(activityId: string): Promise<HeldMutationLock> {
	return holdMutationLock(async (transaction) => {
		await transaction`select id from activity where id = ${activityId} for update`;
	});
}

export async function holdActivityOwnerMutationLock(userId: string): Promise<HeldMutationLock> {
	return holdMutationLock(async (transaction) => {
		await transaction`select id from "user" where id = ${userId} for update`;
	});
}

export async function holdWorkoutMutationLock(workoutId: string): Promise<HeldMutationLock> {
	return holdMutationLock(async (transaction) => {
		await transaction`select id from workout where id = ${workoutId} for update`;
	});
}

export async function holdAdjustmentMutationLock(adjustmentId: string): Promise<HeldMutationLock> {
	return holdMutationLock(async (transaction) => {
		await transaction`select id from plan_adjustment where id = ${adjustmentId} for update`;
	});
}

export async function holdActivePlanMutationLock(userId: string): Promise<HeldMutationLock> {
	return holdMutationLock(async (transaction) => {
		await transaction`
			select id from training_plan
			where user_id = ${userId} and status = 'active'
			for update
		`;
	});
}

async function holdMutationLock(
	lock: (transaction: postgres.TransactionSql) => Promise<void>
): Promise<HeldMutationLock> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 2 }
	);
	let releaseLock!: () => void;
	const released = new Promise<void>((resolve) => {
		releaseLock = resolve;
	});
	let markReady!: () => void;
	let rejectReady!: (error: Error) => void;
	const ready = new Promise<void>((resolve, reject) => {
		markReady = resolve;
		rejectReady = reject;
	});
	const held = sql
		.begin(async (transaction) => {
			await lock(transaction);
			markReady();
			await released;
		})
		.catch((error: unknown) => {
			const failure = error instanceof Error ? error : new Error('Test database lock failed.');
			rejectReady(failure);
			throw failure;
		});
	await ready;
	let releasedOnce = false;
	const release = () => {
		if (releasedOnce) return;
		releasedOnce = true;
		releaseLock();
	};
	return {
		release,
		waitForBlockedRequests: async (count) => {
			const deadline = Date.now() + 5_000;
			while (Date.now() < deadline) {
				const [row] = await sql<{ count: number }[]>`
					select count(*)::int as count
					from pg_stat_activity
					where datname = current_database()
						and pid <> pg_backend_pid()
						and wait_event_type = 'Lock'
				`;
				if ((row?.count ?? 0) >= count) return;
				await new Promise((resolve) => setTimeout(resolve, 25));
			}
			throw new Error(`Expected ${count} blocked runway database requests.`);
		},
		done: held.finally(async () => {
			await sql.end();
		})
	};
}

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

export async function waitForAndroidImportClaim(
	deviceId: string,
	requestId: string
): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const deadline = Date.now() + 5_000;
		while (Date.now() < deadline) {
			const [receipt] = await sql<{ id: string }[]>`
				select id from android_import_request
				where device_id = ${deviceId} and request_id = ${requestId} and state = 'processing'
				limit 1
			`;
			if (receipt) return;
			await new Promise((resolve) => setTimeout(resolve, 25));
		}
		throw new Error(`Android import request ${requestId} was not claimed.`);
	} finally {
		await sql.end();
	}
}

export async function getGpxImportCounts(userId: string): Promise<{
	activities: number;
	imports: number;
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [counts] = await sql<{ activities: number; imports: number }[]>`
			select
				(select count(*) from activity where user_id = ${userId} and source = 'gpx')::int as activities,
				(select count(*) from activity_import where user_id = ${userId})::int as imports
		`;
		return counts ?? { activities: 0, imports: 0 };
	} finally {
		await sql.end();
	}
}

export async function getHealthContext(userId: string): Promise<{
	recentInjury: boolean;
	currentPain: boolean;
	recurringPain: boolean;
	medicalRestriction: boolean;
	notes: string;
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<
			{
				recentInjury: boolean;
				currentPain: boolean;
				recurringPain: boolean;
				medicalRestriction: boolean;
				notes: string;
			}[]
		>`
			select
				coalesce((injury_flags ->> 'recentInjury')::boolean, false) as "recentInjury",
				coalesce((injury_flags ->> 'currentPain')::boolean, false) as "currentPain",
				coalesce((injury_flags ->> 'recurringPain')::boolean, false) as "recurringPain",
				coalesce((injury_flags ->> 'medicalRestriction')::boolean, false) as "medicalRestriction",
				coalesce(injury_flags ->> 'notes', '') as notes
			from athlete_profile
			where user_id = ${userId}
			limit 1
		`;
		if (!row) throw new Error(`Training profile was not found for ${userId}.`);
		return row;
	} finally {
		await sql.end();
	}
}

export async function getUserOwnedRowCount(userId: string): Promise<number> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<{ count: number }[]>`
			select (
				(select count(*) from "user" where id = ${userId}) +
				(select count(*) from session where user_id = ${userId}) +
				(select count(*) from account where user_id = ${userId}) +
				(select count(*) from verification where value = ${userId}) +
				(select count(*) from two_factor where user_id = ${userId}) +
				(select count(*) from passkey where user_id = ${userId}) +
				(select count(*) from athlete_profile where user_id = ${userId}) +
				(select count(*) from goal where user_id = ${userId}) +
				(select count(*) from training_plan where user_id = ${userId}) +
				(select count(*) from training_week where user_id = ${userId}) +
				(select count(*) from workout where user_id = ${userId}) +
				(select count(*) from workout_feedback where user_id = ${userId}) +
				(select count(*) from activity where user_id = ${userId}) +
				(select count(*) from activity_import where user_id = ${userId}) +
				(select count(*) from plan_adjustment where user_id = ${userId}) +
				(select count(*) from activity_deletion_tombstone where user_id = ${userId}) +
				(select count(*) from import_source where user_id = ${userId}) +
				(select count(*) from import_source_item where user_id = ${userId}) +
				(select count(*) from import_operation_lease where user_id = ${userId}) +
				(select count(*) from android_pairing_request where user_id = ${userId}) +
				(select count(*) from android_device where user_id = ${userId}) +
				(select count(*) from android_import_request where user_id = ${userId}) +
				(select count(*) from password_reset_token where user_id = ${userId}) +
				(select count(*) from audit_event where user_id = ${userId})
			)::int as count
		`;
		return row?.count ?? 0;
	} finally {
		await sql.end();
	}
}

export async function seedUserVerificationRecords(userId: string): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const expiresAt = new Date(Date.now() + 30 * 24 * 60 * 60 * 1_000);
		await sql`
			insert into verification (id, identifier, value, expires_at, created_at, updated_at)
			values
				(${`test-trust-${randomBytes(16).toString('hex')}`}, ${`trust-device-${randomBytes(16).toString('hex')}`}, ${userId}, ${expiresAt}, now(), now()),
				(${`test-2fa-${randomBytes(16).toString('hex')}`}, ${`2fa-${randomBytes(10).toString('hex')}`}, ${userId}, ${expiresAt}, now(), now())
		`;
	} finally {
		await sql.end();
	}
}

export async function setUserSessionCreatedAt(userId: string, createdAt: Date): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`update session set created_at = ${createdAt} where user_id = ${userId}`;
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

export async function setActivePlanWeekCount(userId: string, weeks: number): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql`
			update training_plan
			set weeks = ${weeks}, updated_at = now()
			where user_id = ${userId} and status = 'active'
		`;
	} finally {
		await sql.end();
	}
}

export async function getBeginnerContinuationState(userId: string): Promise<{
	planWeeks: number;
	weekRows: number;
	workoutRows: number;
	continuationAudits: number;
	targetDate: string;
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [state] = await sql<
			{
				planWeeks: number;
				weekRows: number;
				workoutRows: number;
				continuationAudits: number;
				targetDate: string;
			}[]
		>`
			select
				p.weeks::int as "planWeeks",
				(select count(*)::int from training_week tw where tw.plan_id = p.id) as "weekRows",
				(select count(*)::int from workout w where w.plan_id = p.id) as "workoutRows",
				(
					select count(*)::int
					from audit_event ae
					where ae.user_id = ${userId}
						and ae.event_type = 'plan.phase_continued'
				) as "continuationAudits",
				p.target_date::text as "targetDate"
			from training_plan p
			where p.user_id = ${userId} and p.status = 'active'
			limit 1
		`;
		if (!state) throw new Error(`Active beginner plan was not found for ${userId}.`);
		return state;
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

export async function getFirstPlanWeekStartDate(userId: string): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<{ startDate: string }[]>`
			select tw.start_date::text as "startDate"
			from training_week tw
			inner join training_plan tp on tp.id = tw.plan_id
			where tp.user_id = ${userId} and tp.status = 'active'
			order by tw.week_number asc
			limit 1
		`;
		if (!row) throw new Error(`First active plan week was not found for ${userId}.`);
		return row.startDate;
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

export async function getPastRestWorkoutId(userId: string): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<{ id: string }[]>`
			select id from workout
			where user_id = ${userId}
				and type = 'rest'
				and status = 'planned'
				and scheduled_date <= ${testDate}
			order by scheduled_date desc
			limit 1
		`;
		if (!row) throw new Error(`Past rest workout was not found for ${userId}.`);
		return row.id;
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

export async function seedImportedActivityRecords(userId: string, count: number): Promise<void> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		await sql.begin(async (transaction) => {
			await transaction`
				with inserted as (
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
						'gpx',
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
					returning id
				)
				insert into activity_import (user_id, activity_id, file_hash, result, metadata)
				select
					${userId},
					id,
					'large-delete-test-' || id::text,
					'imported',
					jsonb_build_object(
						'pointCount', 0,
						'hasHeartRate', false,
						'hasCadence', false,
						'hasSpeed', false
					)
				from inserted
			`;
			await transaction`
				insert into audit_event (user_id, event_type, detail)
				select
					${userId},
					'activity.imported',
					jsonb_build_object('activityId', id::text, 'source', 'gpx')
				from activity
				where user_id = ${userId} and source = 'gpx'
			`;
		});
	} finally {
		await sql.end();
	}
}

export async function getBulkActivityDeletionState(userId: string): Promise<{
	gpxActivities: number;
	manualActivities: number;
	imports: number;
	deletionTombstones: number;
	activityAudits: number;
}> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<
			{
				gpxActivities: number;
				manualActivities: number;
				imports: number;
				deletionTombstones: number;
				activityAudits: number;
			}[]
		>`
			select
				(select count(*) from activity where user_id = ${userId} and source = 'gpx')::int as "gpxActivities",
				(select count(*) from activity where user_id = ${userId} and source = 'manual')::int as "manualActivities",
				(select count(*) from activity_import where user_id = ${userId})::int as imports,
				(select count(*) from activity_deletion_tombstone where user_id = ${userId})::int as "deletionTombstones",
				(select count(*) from audit_event where user_id = ${userId} and detail ? 'activityId')::int as "activityAudits"
		`;
		return (
			row ?? {
				gpxActivities: 0,
				manualActivities: 0,
				imports: 0,
				deletionTombstones: 0,
				activityAudits: 0
			}
		);
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

export async function getLatestManualAdjustmentId(
	userId: string,
	workoutId: string
): Promise<string> {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<{ id: string }[]>`
			select id from plan_adjustment
			where user_id = ${userId}
				and workout_id = ${workoutId}
				and trigger_type in ('manual_edit', 'manual_add', 'manual_remove', 'rebalance')
				and reversed_at is null
			order by created_at desc, id desc
			limit 1
		`;
		if (!row) throw new Error(`Active manual adjustment was not found for ${workoutId}.`);
		return row.id;
	} finally {
		await sql.end();
	}
}

export async function getActivityDeletionResidue(userId: string, activityId: string) {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<
			{ activityCount: number; activeAdjustmentCount: number; auditCount: number }[]
		>`
			select
				(select count(*) from activity where user_id = ${userId} and id = ${activityId})::int as "activityCount",
				(select count(*) from plan_adjustment where user_id = ${userId} and trigger_id = ${activityId} and reversed_at is null)::int as "activeAdjustmentCount",
				(select count(*) from audit_event where user_id = ${userId} and (detail ->> 'activityId' = ${activityId} or detail ->> 'sourceId' = ${activityId}))::int as "auditCount"
		`;
		return row ?? { activityCount: 0, activeAdjustmentCount: 0, auditCount: 0 };
	} finally {
		await sql.end();
	}
}

export async function getActivityLinkRaceState(
	userId: string,
	activityId: string,
	firstWorkoutId: string,
	secondWorkoutId: string
) {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<
			{
				activityWorkoutId: string | null;
				feedbackCount: number;
				completedWorkoutCount: number;
				activeLinkAdjustmentCount: number;
				linkAuditCount: number;
			}[]
		>`
			select
				(select workout_id from activity where user_id = ${userId} and id = ${activityId}) as "activityWorkoutId",
				(select count(*) from workout_feedback where user_id = ${userId} and workout_id in (${firstWorkoutId}, ${secondWorkoutId}))::int as "feedbackCount",
				(select count(*) from workout where user_id = ${userId} and id in (${firstWorkoutId}, ${secondWorkoutId}) and status in ('done', 'shortened'))::int as "completedWorkoutCount",
				(select count(*) from plan_adjustment where user_id = ${userId} and trigger_id = ${activityId} and trigger_type = 'link' and reversed_at is null)::int as "activeLinkAdjustmentCount",
				(select count(*) from audit_event where user_id = ${userId} and event_type = 'activity.linked' and detail ->> 'activityId' = ${activityId})::int as "linkAuditCount"
		`;
		return (
			row ?? {
				activityWorkoutId: null,
				feedbackCount: 0,
				completedWorkoutCount: 0,
				activeLinkAdjustmentCount: 0,
				linkAuditCount: 0
			}
		);
	} finally {
		await sql.end();
	}
}

export async function getUndoRaceState(userId: string, adjustmentId: string) {
	const sql = postgres(
		process.env['DATABASE_URL'] ?? 'postgres://runway:runway_dev_password@127.0.0.1:5432/runway',
		{ max: 1 }
	);
	try {
		const [row] = await sql<
			{ reversed: boolean; undoAuditCount: number; resetAuditCount: number }[]
		>`
			select
				coalesce((select reversed_at is not null from plan_adjustment where user_id = ${userId} and id = ${adjustmentId}), false) as reversed,
				(select count(*) from audit_event where user_id = ${userId} and event_type = 'workout.adjustment_undone')::int as "undoAuditCount",
				(select count(*) from audit_event where user_id = ${userId} and event_type = 'workout.reset')::int as "resetAuditCount"
		`;
		return row ?? { reversed: false, undoAuditCount: 0, resetAuditCount: 0 };
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
