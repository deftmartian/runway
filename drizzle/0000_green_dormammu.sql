CREATE TYPE "public"."activity_source" AS ENUM('manual', 'gpx');--> statement-breakpoint
CREATE TYPE "public"."consequence_choice" AS ENUM('skip_continue', 'reduce_next');--> statement-breakpoint
CREATE TYPE "public"."goal_priority" AS ENUM('finish_healthy', 'consistency', 'time');--> statement-breakpoint
CREATE TYPE "public"."plan_status" AS ENUM('draft', 'active', 'archived');--> statement-breakpoint
CREATE TYPE "public"."race_distance" AS ENUM('5k', '10k', 'half', 'marathon');--> statement-breakpoint
CREATE TYPE "public"."risk_rating" AS ENUM('conservative', 'moderate', 'aggressive', 'unsafe');--> statement-breakpoint
CREATE TYPE "public"."workout_status" AS ENUM('planned', 'done', 'skipped', 'shortened', 'moved');--> statement-breakpoint
CREATE TYPE "public"."workout_type" AS ENUM('easy', 'long', 'quality', 'recovery', 'rest', 'race', 'cross_train');--> statement-breakpoint
CREATE TABLE "activity" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"workout_id" uuid,
	"source" "activity_source" NOT NULL,
	"occurred_at" timestamp NOT NULL,
	"distance_meters" integer NOT NULL,
	"duration_seconds" integer,
	"average_pace_seconds_per_km" real,
	"average_heart_rate" integer,
	"average_cadence" integer,
	"route_summary" jsonb DEFAULT '{"pointCount":0,"startEndRedacted":true,"hasElevation":false}'::jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "activity_import" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"activity_id" uuid,
	"file_name" text NOT NULL,
	"file_hash" text NOT NULL,
	"parser" text DEFAULT 'gpx' NOT NULL,
	"result" text NOT NULL,
	"metadata" jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "athlete_profile" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"units" text DEFAULT 'metric' NOT NULL,
	"current_weekly_distance_meters" integer DEFAULT 0 NOT NULL,
	"current_runs_per_week" integer DEFAULT 3 NOT NULL,
	"longest_recent_run_meters" integer DEFAULT 0 NOT NULL,
	"experience" text DEFAULT 'returning' NOT NULL,
	"preferred_long_run_day" integer DEFAULT 6 NOT NULL,
	"availability" jsonb DEFAULT '[1,2,4,6]'::jsonb NOT NULL,
	"injury_flags" jsonb DEFAULT '{"knee":false,"ankle":false,"other":""}'::jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
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
	"distance" "race_distance" NOT NULL,
	"target_date" date NOT NULL,
	"priority" "goal_priority" DEFAULT 'finish_healthy' NOT NULL,
	"target_time_seconds" integer,
	"notes" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "training_plan" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"goal_id" uuid NOT NULL,
	"status" "plan_status" DEFAULT 'active' NOT NULL,
	"start_date" date NOT NULL,
	"target_date" date NOT NULL,
	"weeks" integer NOT NULL,
	"risk" "risk_rating" NOT NULL,
	"ramp_summary" jsonb NOT NULL,
	"source_refs" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "training_week" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"plan_id" uuid NOT NULL,
	"week_number" integer NOT NULL,
	"start_date" date NOT NULL,
	"target_distance_meters" integer NOT NULL,
	"long_run_meters" integer NOT NULL,
	"risk" "risk_rating" NOT NULL,
	"is_down_week" boolean DEFAULT false NOT NULL,
	"is_taper" boolean DEFAULT false NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
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
	"target_distance_meters" integer DEFAULT 0 NOT NULL,
	"target_duration_seconds" integer,
	"intensity" text DEFAULT 'easy' NOT NULL,
	"purpose" text NOT NULL,
	"reason" text NOT NULL,
	"source_refs" jsonb DEFAULT '[]'::jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "workout_feedback" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"workout_id" uuid NOT NULL,
	"completed_distance_meters" integer,
	"completed_duration_seconds" integer,
	"perceived_effort" integer,
	"felt_hard" boolean DEFAULT false NOT NULL,
	"pain" boolean DEFAULT false NOT NULL,
	"pain_area" text,
	"note" text,
	"choice" "consequence_choice",
	"consequence" jsonb NOT NULL,
	"created_at" timestamp DEFAULT now() NOT NULL
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
ALTER TABLE "activity" ADD CONSTRAINT "activity_workout_id_workout_id_fk" FOREIGN KEY ("workout_id") REFERENCES "public"."workout"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_activity_id_activity_id_fk" FOREIGN KEY ("activity_id") REFERENCES "public"."activity"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "audit_event" ADD CONSTRAINT "audit_event_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "goal" ADD CONSTRAINT "goal_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_goal_id_goal_id_fk" FOREIGN KEY ("goal_id") REFERENCES "public"."goal"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_plan_id_training_plan_id_fk" FOREIGN KEY ("plan_id") REFERENCES "public"."training_plan"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_plan_id_training_plan_id_fk" FOREIGN KEY ("plan_id") REFERENCES "public"."training_plan"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_week_id_training_week_id_fk" FOREIGN KEY ("week_id") REFERENCES "public"."training_week"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_workout_id_workout_id_fk" FOREIGN KEY ("workout_id") REFERENCES "public"."workout"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "account" ADD CONSTRAINT "account_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "passkey" ADD CONSTRAINT "passkey_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "session" ADD CONSTRAINT "session_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "two_factor" ADD CONSTRAINT "two_factor_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "activity_user_occurred_idx" ON "activity" USING btree ("user_id","occurred_at");--> statement-breakpoint
CREATE INDEX "activity_workout_idx" ON "activity" USING btree ("workout_id");--> statement-breakpoint
CREATE INDEX "activity_import_user_created_idx" ON "activity_import" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "activity_import_hash_idx" ON "activity_import" USING btree ("file_hash");--> statement-breakpoint
CREATE UNIQUE INDEX "athlete_profile_user_id_unique" ON "athlete_profile" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "athlete_profile_user_id_idx" ON "athlete_profile" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "athlete_profile_updated_at_idx" ON "athlete_profile" USING btree ("updated_at");--> statement-breakpoint
CREATE INDEX "audit_event_user_created_idx" ON "audit_event" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "audit_event_type_idx" ON "audit_event" USING btree ("event_type");--> statement-breakpoint
CREATE INDEX "goal_user_id_idx" ON "goal" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "goal_user_target_date_idx" ON "goal" USING btree ("user_id","target_date");--> statement-breakpoint
CREATE INDEX "training_plan_user_status_idx" ON "training_plan" USING btree ("user_id","status");--> statement-breakpoint
CREATE INDEX "training_plan_goal_id_idx" ON "training_plan" USING btree ("goal_id");--> statement-breakpoint
CREATE INDEX "training_week_plan_week_idx" ON "training_week" USING btree ("plan_id","week_number");--> statement-breakpoint
CREATE INDEX "training_week_user_start_idx" ON "training_week" USING btree ("user_id","start_date");--> statement-breakpoint
CREATE INDEX "workout_user_date_idx" ON "workout" USING btree ("user_id","scheduled_date");--> statement-breakpoint
CREATE INDEX "workout_plan_week_idx" ON "workout" USING btree ("plan_id","week_id");--> statement-breakpoint
CREATE INDEX "workout_status_idx" ON "workout" USING btree ("status");--> statement-breakpoint
CREATE INDEX "workout_feedback_user_created_idx" ON "workout_feedback" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "workout_feedback_workout_idx" ON "workout_feedback" USING btree ("workout_id");--> statement-breakpoint
CREATE INDEX "account_userId_idx" ON "account" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "passkey_userId_idx" ON "passkey" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "passkey_credentialID_idx" ON "passkey" USING btree ("credential_id");--> statement-breakpoint
CREATE INDEX "session_userId_idx" ON "session" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "twoFactor_secret_idx" ON "two_factor" USING btree ("secret");--> statement-breakpoint
CREATE INDEX "twoFactor_userId_idx" ON "two_factor" USING btree ("user_id");--> statement-breakpoint
CREATE INDEX "verification_identifier_idx" ON "verification" USING btree ("identifier");
