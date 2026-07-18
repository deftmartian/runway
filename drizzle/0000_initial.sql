CREATE TYPE "public"."activity_review_state" AS ENUM('review', 'accepted');--> statement-breakpoint
CREATE TYPE "public"."activity_source" AS ENUM('manual', 'gpx');--> statement-breakpoint
CREATE TYPE "public"."consequence_choice" AS ENUM('skip_continue', 'reduce_next');--> statement-breakpoint
CREATE TYPE "public"."deviation_classification" AS ENUM('near_plan', 'short', 'over', 'skipped', 'unplanned', 'not_applicable');--> statement-breakpoint
CREATE TYPE "public"."goal_kind" AS ENUM('race', 'foundation');--> statement-breakpoint
CREATE TYPE "public"."goal_priority" AS ENUM('finish_healthy', 'consistency');--> statement-breakpoint
CREATE TYPE "public"."goal_state" AS ENUM('pending', 'active', 'completed', 'archived');--> statement-breakpoint
CREATE TYPE "public"."plan_adjustment_trigger" AS ENUM('feedback', 'manual', 'import_match', 'import_extra', 'link', 'decision', 'manual_edit', 'manual_add', 'manual_remove', 'rebalance');--> statement-breakpoint
CREATE TYPE "public"."plan_decision" AS ENUM('keep_plan', 'reduce_next', 'next_rest', 'repeat_prescription', 'rebalance_week');--> statement-breakpoint
CREATE TYPE "public"."plan_phase" AS ENUM('distance', 'foundation', 'calibration');--> statement-breakpoint
CREATE TYPE "public"."plan_status" AS ENUM('draft', 'active', 'archived');--> statement-breakpoint
CREATE TYPE "public"."race_distance" AS ENUM('5k', '10k', 'half', 'marathon');--> statement-breakpoint
CREATE TYPE "public"."risk_rating" AS ENUM('conservative', 'moderate', 'aggressive', 'unsafe');--> statement-breakpoint
CREATE TYPE "public"."sex_for_estimates" AS ENUM('female', 'male', 'not_specified');--> statement-breakpoint
CREATE TYPE "public"."start_mode" AS ENUM('established', 'foundation_to_goal', 'foundation_only', 'calibration');--> statement-breakpoint
CREATE TYPE "public"."workout_prescription_kind" AS ENUM('distance', 'timed', 'rest');--> statement-breakpoint
CREATE TYPE "public"."workout_status" AS ENUM('planned', 'done', 'skipped', 'shortened');--> statement-breakpoint
CREATE TYPE "public"."workout_type" AS ENUM('easy', 'long', 'recovery', 'rest', 'race');--> statement-breakpoint
CREATE TABLE "activity" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"workout_id" uuid,
	"source" "activity_source" NOT NULL,
	"review_state" "activity_review_state" NOT NULL,
	"occurred_at" timestamp with time zone NOT NULL,
	"activity_date" date NOT NULL,
	"distance_meters" integer NOT NULL,
	"duration_seconds" integer,
	"average_pace_seconds_per_km" real,
	"average_heart_rate" integer,
	"max_heart_rate" integer,
	"heart_rate_summary" jsonb,
	"heart_rate_series" jsonb,
	"route_trace" jsonb,
	"average_cadence" integer,
	"felt_hard" boolean DEFAULT false NOT NULL,
	"pain" boolean DEFAULT false NOT NULL,
	"extra_plan_impact_confirmed" boolean DEFAULT false NOT NULL,
	"deviation" "deviation_classification" DEFAULT 'unplanned' NOT NULL,
	"applied_decision" "plan_decision",
	"consequence" jsonb,
	"consequence_plan_id" uuid,
	"route_summary" jsonb NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "activity_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "activity_distance_nonnegative" CHECK ("activity"."distance_meters" >= 0),
	CONSTRAINT "activity_duration_positive" CHECK ("activity"."duration_seconds" is null or "activity"."duration_seconds" > 0),
	CONSTRAINT "activity_average_heart_rate_range" CHECK ("activity"."average_heart_rate" is null or "activity"."average_heart_rate" between 30 and 240),
	CONSTRAINT "activity_max_heart_rate_range" CHECK ("activity"."max_heart_rate" is null or "activity"."max_heart_rate" between 30 and 260)
);
--> statement-breakpoint
CREATE TABLE "activity_deletion_tombstone" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"file_hash" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "activity_import" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"activity_id" uuid,
	"file_hash" text NOT NULL,
	"parser" text DEFAULT 'gpx' NOT NULL,
	"result" text NOT NULL,
	"metadata" jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "activity_import_result_known" CHECK ("activity_import"."result" in ('imported'))
);
--> statement-breakpoint
CREATE TABLE "athlete_profile" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"units" text DEFAULT 'metric' NOT NULL,
	"time_zone" text,
	"sex_for_estimates" "sex_for_estimates" DEFAULT 'not_specified' NOT NULL,
	"age_years" integer,
	"heart_rate_settings" jsonb,
	"route_data_mode" text DEFAULT 'private' NOT NULL,
	"current_weekly_distance_meters" integer DEFAULT 0 NOT NULL,
	"current_runs_per_week" integer DEFAULT 0 NOT NULL,
	"longest_recent_run_meters" integer DEFAULT 0 NOT NULL,
	"experience" text DEFAULT 'unspecified' NOT NULL,
	"preferred_long_run_day" integer,
	"availability" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"activity_import_generation" integer DEFAULT 0 NOT NULL,
	"injury_flags" jsonb DEFAULT '{"recentInjury":false,"currentPain":false,"recurringPain":false,"medicalRestriction":false,"notes":""}'::jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "athlete_profile_age_range" CHECK ("athlete_profile"."age_years" is null or "athlete_profile"."age_years" between 18 and 100),
	CONSTRAINT "athlete_profile_time_zone_nonempty" CHECK ("athlete_profile"."time_zone" is null or length(trim("athlete_profile"."time_zone")) between 1 and 255),
	CONSTRAINT "athlete_profile_import_generation_nonnegative" CHECK ("athlete_profile"."activity_import_generation" >= 0),
	CONSTRAINT "athlete_profile_route_data_mode_known" CHECK ("athlete_profile"."route_data_mode" in ('discard', 'private'))
);
--> statement-breakpoint
CREATE TABLE "audit_event" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"event_type" text NOT NULL,
	"detail" jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "goal" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"title" text NOT NULL,
	"kind" "goal_kind" NOT NULL,
	"state" "goal_state" NOT NULL,
	"start_mode" "start_mode" NOT NULL,
	"distance" "race_distance",
	"target_date" date NOT NULL,
	"priority" "goal_priority" DEFAULT 'finish_healthy' NOT NULL,
	"target_time_seconds" integer,
	"notes" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "goal_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "goal_kind_distance_consistent" CHECK (("goal"."kind" = 'race' and "goal"."distance" is not null) or ("goal"."kind" = 'foundation' and "goal"."distance" is null)),
	CONSTRAINT "goal_start_mode_consistent" CHECK (("goal"."start_mode" = 'foundation_only' and "goal"."kind" = 'foundation') or ("goal"."start_mode" <> 'foundation_only'))
);
--> statement-breakpoint
CREATE TABLE "import_source" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"type" text DEFAULT 'nextcloud_share' NOT NULL,
	"label" text NOT NULL,
	"share_host" text NOT NULL,
	"share_token_secret" text NOT NULL,
	"share_token_key" text NOT NULL,
	"share_password_secret" text NOT NULL,
	"enabled" boolean DEFAULT true NOT NULL,
	"sync_interval_minutes" integer DEFAULT 5 NOT NULL,
	"last_checked_at" timestamp,
	"last_success_at" timestamp,
	"last_imported_at" timestamp,
	"last_error" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "import_source_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "import_source_type_known" CHECK ("import_source"."type" in ('nextcloud_share')),
	CONSTRAINT "import_source_interval_positive" CHECK ("import_source"."sync_interval_minutes" > 0)
);
--> statement-breakpoint
CREATE TABLE "import_source_item" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"source_id" uuid NOT NULL,
	"remote_key" text NOT NULL,
	"etag" text,
	"content_length" integer,
	"last_modified_at" timestamp,
	"content_hash" text,
	"status" text NOT NULL,
	"activity_id" uuid,
	"first_seen_at" timestamp DEFAULT now() NOT NULL,
	"last_checked_at" timestamp DEFAULT now() NOT NULL,
	"imported_at" timestamp,
	"error_summary" text,
	CONSTRAINT "import_source_item_status_known" CHECK ("import_source_item"."status" in ('importing', 'imported', 'failed'))
);
--> statement-breakpoint
CREATE TABLE "password_reset_rate_limit" (
	"key_hash" text PRIMARY KEY NOT NULL,
	"count" integer DEFAULT 0 NOT NULL,
	"reset_at" timestamp NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "password_reset_rate_limit_count_nonnegative" CHECK ("password_reset_rate_limit"."count" >= 0)
);
--> statement-breakpoint
CREATE TABLE "password_reset_token" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"token_hash" text NOT NULL,
	"expires_at" timestamp NOT NULL,
	"used_at" timestamp,
	"requested_at" timestamp DEFAULT now() NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "plan_adjustment" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"plan_id" uuid NOT NULL,
	"workout_id" uuid NOT NULL,
	"trigger_type" "plan_adjustment_trigger" NOT NULL,
	"trigger_id" uuid,
	"previous_target_distance_meters" integer NOT NULL,
	"new_target_distance_meters" integer NOT NULL,
	"previous_scheduled_date" date,
	"new_scheduled_date" date,
	"previous_state" jsonb NOT NULL,
	"new_state" jsonb NOT NULL,
	"consequence" jsonb,
	"reason" text NOT NULL,
	"reversed_at" timestamp with time zone,
	"reversal_reason" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "plan_adjustment_previous_target_nonnegative" CHECK ("plan_adjustment"."previous_target_distance_meters" >= 0),
	CONSTRAINT "plan_adjustment_new_target_nonnegative" CHECK ("plan_adjustment"."new_target_distance_meters" >= 0)
);
--> statement-breakpoint
CREATE TABLE "training_plan" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"goal_id" uuid NOT NULL,
	"status" "plan_status" DEFAULT 'active' NOT NULL,
	"phase" "plan_phase" NOT NULL,
	"start_date" date NOT NULL,
	"target_date" date NOT NULL,
	"weeks" integer NOT NULL,
	"risk" "risk_rating" NOT NULL,
	"plan_summary" jsonb NOT NULL,
	"source_refs" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"completed_at" timestamp with time zone,
	"archived_at" timestamp with time zone,
	"lifecycle_reason" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "training_plan_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "training_plan_weeks_range" CHECK ("training_plan"."weeks" between 1 and 52),
	CONSTRAINT "training_plan_lifecycle_reason_known" CHECK ("training_plan"."lifecycle_reason" is null or "training_plan"."lifecycle_reason" in ('completed', 'changed_goal', 'abandoned')),
	CONSTRAINT "training_plan_lifecycle_status_consistent" CHECK ((
				("training_plan"."status" in ('active', 'draft') and "training_plan"."archived_at" is null and "training_plan"."completed_at" is null and "training_plan"."lifecycle_reason" is null)
				or
				("training_plan"."status" = 'archived' and "training_plan"."archived_at" is not null and "training_plan"."lifecycle_reason" is not null)
			)),
	CONSTRAINT "training_plan_completed_reason_consistent" CHECK ((
				("training_plan"."completed_at" is null and "training_plan"."lifecycle_reason" is distinct from 'completed')
				or
				("training_plan"."completed_at" is not null and "training_plan"."lifecycle_reason" = 'completed')
			)),
	CONSTRAINT "training_plan_lifecycle_chronology" CHECK ("training_plan"."completed_at" is null or "training_plan"."archived_at" is null or "training_plan"."completed_at" <= "training_plan"."archived_at")
);
--> statement-breakpoint
CREATE TABLE "training_week" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"plan_id" uuid NOT NULL,
	"week_number" integer NOT NULL,
	"start_date" date NOT NULL,
	"target_distance_meters" integer NOT NULL,
	"target_duration_seconds" integer DEFAULT 0 NOT NULL,
	"long_run_meters" integer NOT NULL,
	"risk" "risk_rating" NOT NULL,
	"is_down_week" boolean DEFAULT false NOT NULL,
	"is_taper" boolean DEFAULT false NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "training_week_id_user_plan_unique" UNIQUE("id","user_id","plan_id"),
	CONSTRAINT "training_week_distance_nonnegative" CHECK ("training_week"."target_distance_meters" >= 0),
	CONSTRAINT "training_week_duration_nonnegative" CHECK ("training_week"."target_duration_seconds" >= 0),
	CONSTRAINT "training_week_long_run_nonnegative" CHECK ("training_week"."long_run_meters" >= 0)
);
--> statement-breakpoint
CREATE TABLE "workout" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"plan_id" uuid NOT NULL,
	"week_id" uuid NOT NULL,
	"scheduled_date" date NOT NULL,
	"type" "workout_type" NOT NULL,
	"status" "workout_status" DEFAULT 'planned' NOT NULL,
	"prescription_kind" "workout_prescription_kind" NOT NULL,
	"target_distance_meters" integer DEFAULT 0 NOT NULL,
	"target_duration_seconds" integer,
	"interval_structure" jsonb,
	"intensity" text DEFAULT 'easy' NOT NULL,
	"purpose" text NOT NULL,
	"reason" text NOT NULL,
	"source_refs" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"is_removed" boolean DEFAULT false NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "workout_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "workout_id_user_plan_unique" UNIQUE("id","user_id","plan_id"),
	CONSTRAINT "workout_target_distance_nonnegative" CHECK ("workout"."target_distance_meters" >= 0),
	CONSTRAINT "workout_prescription_valid" CHECK ((
				("workout"."prescription_kind" = 'distance' and "workout"."target_distance_meters" > 0 and "workout"."target_duration_seconds" is null and "workout"."interval_structure" is null and "workout"."type" <> 'rest')
				or
				("workout"."prescription_kind" = 'timed' and "workout"."target_distance_meters" = 0 and "workout"."target_duration_seconds" > 0 and "workout"."interval_structure" is not null and "workout"."type" not in ('rest', 'race'))
				or
				("workout"."prescription_kind" = 'rest' and "workout"."target_distance_meters" = 0 and "workout"."target_duration_seconds" is null and "workout"."interval_structure" is null and "workout"."type" = 'rest')
			))
);
--> statement-breakpoint
CREATE TABLE "workout_feedback" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"workout_id" uuid NOT NULL,
	"completed_distance_meters" integer,
	"completed_duration_seconds" integer,
	"felt_hard" boolean DEFAULT false NOT NULL,
	"pain" boolean DEFAULT false NOT NULL,
	"choice" "consequence_choice",
	"deviation" "deviation_classification" NOT NULL,
	"applied_decision" "plan_decision",
	"consequence" jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "workout_feedback_completed_distance_nonnegative" CHECK ("workout_feedback"."completed_distance_meters" is null or "workout_feedback"."completed_distance_meters" >= 0),
	CONSTRAINT "workout_feedback_completed_duration_nonnegative" CHECK ("workout_feedback"."completed_duration_seconds" is null or "workout_feedback"."completed_duration_seconds" >= 0)
);
--> statement-breakpoint
CREATE TABLE "account" (
	"id" text PRIMARY KEY NOT NULL,
	"account_id" text NOT NULL,
	"provider_id" text NOT NULL,
	"user_id" text NOT NULL,
	"access_token" text,
	"refresh_token" text,
	"id_token" text,
	"access_token_expires_at" timestamp,
	"refresh_token_expires_at" timestamp,
	"scope" text,
	"password" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp NOT NULL
);
--> statement-breakpoint
CREATE TABLE "passkey" (
	"id" text PRIMARY KEY NOT NULL,
	"name" text,
	"public_key" text NOT NULL,
	"user_id" text NOT NULL,
	"credential_id" text NOT NULL,
	"counter" integer NOT NULL,
	"device_type" text NOT NULL,
	"backed_up" boolean NOT NULL,
	"transports" text,
	"created_at" timestamp,
	"aaguid" text
);
--> statement-breakpoint
CREATE TABLE "session" (
	"id" text PRIMARY KEY NOT NULL,
	"expires_at" timestamp NOT NULL,
	"token" text NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp NOT NULL,
	"ip_address" text,
	"user_agent" text,
	"user_id" text NOT NULL,
	CONSTRAINT "session_token_unique" UNIQUE("token")
);
--> statement-breakpoint
CREATE TABLE "two_factor" (
	"id" text PRIMARY KEY NOT NULL,
	"secret" text NOT NULL,
	"backup_codes" text NOT NULL,
	"user_id" text NOT NULL,
	"verified" boolean DEFAULT true
);
--> statement-breakpoint
CREATE TABLE "user" (
	"id" text PRIMARY KEY NOT NULL,
	"name" text NOT NULL,
	"email" text NOT NULL,
	"email_verified" boolean DEFAULT false NOT NULL,
	"image" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	"two_factor_enabled" boolean DEFAULT false,
	CONSTRAINT "user_email_unique" UNIQUE("email")
);
--> statement-breakpoint
CREATE TABLE "verification" (
	"id" text PRIMARY KEY NOT NULL,
	"identifier" text NOT NULL,
	"value" text NOT NULL,
	"expires_at" timestamp NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_workout_user_fk" FOREIGN KEY ("workout_id","user_id") REFERENCES "public"."workout"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_consequence_plan_user_fk" FOREIGN KEY ("consequence_plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_deletion_tombstone" ADD CONSTRAINT "activity_deletion_tombstone_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_activity_user_fk" FOREIGN KEY ("activity_id","user_id") REFERENCES "public"."activity"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "audit_event" ADD CONSTRAINT "audit_event_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "goal" ADD CONSTRAINT "goal_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source" ADD CONSTRAINT "import_source_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_source_user_fk" FOREIGN KEY ("source_id","user_id") REFERENCES "public"."import_source"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_activity_user_fk" FOREIGN KEY ("activity_id","user_id") REFERENCES "public"."activity"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "password_reset_token" ADD CONSTRAINT "password_reset_token_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_workout_user_plan_fk" FOREIGN KEY ("workout_id","user_id","plan_id") REFERENCES "public"."workout"("id","user_id","plan_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_goal_user_fk" FOREIGN KEY ("goal_id","user_id") REFERENCES "public"."goal"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_week_user_plan_fk" FOREIGN KEY ("week_id","user_id","plan_id") REFERENCES "public"."training_week"("id","user_id","plan_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_workout_user_fk" FOREIGN KEY ("workout_id","user_id") REFERENCES "public"."workout"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "account" ADD CONSTRAINT "account_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "passkey" ADD CONSTRAINT "passkey_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "session" ADD CONSTRAINT "session_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "two_factor" ADD CONSTRAINT "two_factor_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "activity_user_occurred_idx" ON "activity" USING btree ("user_id","occurred_at");--> statement-breakpoint
CREATE INDEX "activity_user_date_idx" ON "activity" USING btree ("user_id","activity_date");--> statement-breakpoint
CREATE INDEX "activity_consequence_plan_idx" ON "activity" USING btree ("consequence_plan_id");--> statement-breakpoint
CREATE INDEX "activity_workout_idx" ON "activity" USING btree ("workout_id");--> statement-breakpoint
CREATE UNIQUE INDEX "activity_workout_unique" ON "activity" USING btree ("workout_id") WHERE "activity"."workout_id" is not null;--> statement-breakpoint
CREATE UNIQUE INDEX "activity_deletion_tombstone_user_hash_unique" ON "activity_deletion_tombstone" USING btree ("user_id","file_hash");--> statement-breakpoint
CREATE INDEX "activity_deletion_tombstone_user_created_idx" ON "activity_deletion_tombstone" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "activity_import_user_created_idx" ON "activity_import" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "activity_import_activity_id_idx" ON "activity_import" USING btree ("activity_id");--> statement-breakpoint
CREATE UNIQUE INDEX "activity_import_user_hash_unique" ON "activity_import" USING btree ("user_id","file_hash");--> statement-breakpoint
CREATE UNIQUE INDEX "athlete_profile_user_id_unique" ON "athlete_profile" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "athlete_profile_user_id_idx" ON "athlete_profile" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "athlete_profile_updated_at_idx" ON "athlete_profile" USING btree ("updated_at");--> statement-breakpoint
CREATE INDEX "audit_event_user_created_idx" ON "audit_event" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "audit_event_created_idx" ON "audit_event" USING btree ("created_at");--> statement-breakpoint
CREATE INDEX "audit_event_type_idx" ON "audit_event" USING btree ("event_type");--> statement-breakpoint
CREATE INDEX "goal_user_id_idx" ON "goal" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "goal_user_target_date_idx" ON "goal" USING btree ("user_id","target_date");--> statement-breakpoint
CREATE UNIQUE INDEX "goal_current_user_unique" ON "goal" USING btree ("user_id") WHERE "goal"."state" in ('pending', 'active');--> statement-breakpoint
CREATE INDEX "import_source_user_enabled_idx" ON "import_source" USING btree ("user_id","enabled");--> statement-breakpoint
CREATE INDEX "import_source_enabled_checked_idx" ON "import_source" USING btree ("enabled","last_checked_at");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_user_share_unique" ON "import_source" USING btree ("user_id","share_host","share_token_key");--> statement-breakpoint
CREATE INDEX "import_source_item_user_checked_idx" ON "import_source_item" USING btree ("user_id","last_checked_at");--> statement-breakpoint
CREATE INDEX "import_source_item_source_status_idx" ON "import_source_item" USING btree ("source_id","status");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_item_source_key_unique" ON "import_source_item" USING btree ("source_id","remote_key");--> statement-breakpoint
CREATE INDEX "import_source_item_source_hash_idx" ON "import_source_item" USING btree ("source_id","content_hash");--> statement-breakpoint
CREATE INDEX "password_reset_rate_limit_reset_idx" ON "password_reset_rate_limit" USING btree ("reset_at");--> statement-breakpoint
CREATE INDEX "password_reset_token_user_requested_idx" ON "password_reset_token" USING btree ("user_id","requested_at");--> statement-breakpoint
CREATE INDEX "password_reset_token_expires_idx" ON "password_reset_token" USING btree ("expires_at");--> statement-breakpoint
CREATE UNIQUE INDEX "password_reset_token_hash_unique" ON "password_reset_token" USING btree ("token_hash");--> statement-breakpoint
CREATE INDEX "plan_adjustment_user_created_idx" ON "plan_adjustment" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "plan_adjustment_plan_created_idx" ON "plan_adjustment" USING btree ("plan_id","created_at");--> statement-breakpoint
CREATE INDEX "plan_adjustment_workout_created_idx" ON "plan_adjustment" USING btree ("workout_id","created_at");--> statement-breakpoint
CREATE UNIQUE INDEX "plan_adjustment_active_decision_unique" ON "plan_adjustment" USING btree ("user_id","trigger_id","workout_id") WHERE "plan_adjustment"."trigger_type" = 'decision' and "plan_adjustment"."trigger_id" is not null and "plan_adjustment"."reversed_at" is null;--> statement-breakpoint
CREATE INDEX "training_plan_user_status_idx" ON "training_plan" USING btree ("user_id","status");--> statement-breakpoint
CREATE INDEX "training_plan_goal_id_idx" ON "training_plan" USING btree ("goal_id");--> statement-breakpoint
CREATE UNIQUE INDEX "training_plan_active_user_unique" ON "training_plan" USING btree ("user_id") WHERE "training_plan"."status" = 'active';--> statement-breakpoint
CREATE UNIQUE INDEX "training_week_plan_week_unique" ON "training_week" USING btree ("plan_id","week_number");--> statement-breakpoint
CREATE INDEX "training_week_user_start_idx" ON "training_week" USING btree ("user_id","start_date");--> statement-breakpoint
CREATE INDEX "workout_user_date_idx" ON "workout" USING btree ("user_id","scheduled_date");--> statement-breakpoint
CREATE INDEX "workout_user_plan_date_idx" ON "workout" USING btree ("user_id","plan_id","scheduled_date");--> statement-breakpoint
CREATE INDEX "workout_plan_week_idx" ON "workout" USING btree ("plan_id","week_id");--> statement-breakpoint
CREATE INDEX "workout_status_idx" ON "workout" USING btree ("status");--> statement-breakpoint
CREATE INDEX "workout_feedback_user_created_idx" ON "workout_feedback" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE UNIQUE INDEX "workout_feedback_workout_unique" ON "workout_feedback" USING btree ("workout_id");--> statement-breakpoint
CREATE INDEX "account_userId_idx" ON "account" USING btree ("user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "account_provider_account_unique" ON "account" USING btree ("provider_id","account_id");--> statement-breakpoint
CREATE INDEX "passkey_userId_idx" ON "passkey" USING btree ("user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "passkey_credentialID_unique" ON "passkey" USING btree ("credential_id");--> statement-breakpoint
CREATE INDEX "session_userId_idx" ON "session" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "twoFactor_secret_idx" ON "two_factor" USING btree ("secret");--> statement-breakpoint
CREATE INDEX "twoFactor_userId_idx" ON "two_factor" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "verification_identifier_idx" ON "verification" USING btree ("identifier");