CREATE TYPE "public"."plan_adjustment_trigger" AS ENUM('feedback', 'manual', 'import_match', 'import_extra', 'link');--> statement-breakpoint
CREATE TABLE "activity_deletion_tombstone" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"file_hash" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
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
ALTER TABLE "activity" DROP CONSTRAINT "activity_workout_id_workout_id_fk";
--> statement-breakpoint
ALTER TABLE "activity_import" DROP CONSTRAINT "activity_import_activity_id_activity_id_fk";
--> statement-breakpoint
ALTER TABLE "import_source_item" DROP CONSTRAINT "import_source_item_source_id_import_source_id_fk";
--> statement-breakpoint
ALTER TABLE "import_source_item" DROP CONSTRAINT "import_source_item_activity_id_activity_id_fk";
--> statement-breakpoint
ALTER TABLE "training_plan" DROP CONSTRAINT "training_plan_goal_id_goal_id_fk";
--> statement-breakpoint
ALTER TABLE "training_week" DROP CONSTRAINT "training_week_plan_id_training_plan_id_fk";
--> statement-breakpoint
ALTER TABLE "workout" DROP CONSTRAINT "workout_plan_id_training_plan_id_fk";
--> statement-breakpoint
ALTER TABLE "workout" DROP CONSTRAINT "workout_week_id_training_week_id_fk";
--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP CONSTRAINT "workout_feedback_workout_id_workout_id_fk";
--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "priority" SET DATA TYPE text;--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "priority" SET DEFAULT 'finish_healthy'::text;--> statement-breakpoint
DROP TYPE "public"."goal_priority";--> statement-breakpoint
CREATE TYPE "public"."goal_priority" AS ENUM('finish_healthy', 'consistency');--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "priority" SET DEFAULT 'finish_healthy'::"public"."goal_priority";--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "priority" SET DATA TYPE "public"."goal_priority" USING "priority"::"public"."goal_priority";--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "status" SET DATA TYPE text;--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "status" SET DEFAULT 'planned'::text;--> statement-breakpoint
DROP TYPE "public"."workout_status";--> statement-breakpoint
CREATE TYPE "public"."workout_status" AS ENUM('planned', 'done', 'skipped', 'shortened');--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "status" SET DEFAULT 'planned'::"public"."workout_status";--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "status" SET DATA TYPE "public"."workout_status" USING "status"::"public"."workout_status";--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "type" SET DATA TYPE text;--> statement-breakpoint
DROP TYPE "public"."workout_type";--> statement-breakpoint
CREATE TYPE "public"."workout_type" AS ENUM('easy', 'long', 'recovery', 'rest', 'race');--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "type" SET DATA TYPE "public"."workout_type" USING "type"::"public"."workout_type";--> statement-breakpoint
DROP INDEX "import_source_item_source_href_unique";--> statement-breakpoint
DROP INDEX "import_source_user_share_unique";--> statement-breakpoint
ALTER TABLE "activity" ALTER COLUMN "occurred_at" SET DATA TYPE timestamp with time zone;--> statement-breakpoint
ALTER TABLE "activity" ALTER COLUMN "created_at" SET DATA TYPE timestamp with time zone;--> statement-breakpoint
ALTER TABLE "activity" ALTER COLUMN "created_at" SET DEFAULT now();--> statement-breakpoint
ALTER TABLE "athlete_profile" ALTER COLUMN "current_runs_per_week" SET DEFAULT 0;--> statement-breakpoint
ALTER TABLE "athlete_profile" ALTER COLUMN "experience" SET DEFAULT 'unspecified';--> statement-breakpoint
ALTER TABLE "athlete_profile" ALTER COLUMN "preferred_long_run_day" DROP DEFAULT;--> statement-breakpoint
ALTER TABLE "athlete_profile" ALTER COLUMN "preferred_long_run_day" DROP NOT NULL;--> statement-breakpoint
ALTER TABLE "athlete_profile" ALTER COLUMN "availability" SET DEFAULT '[]'::jsonb;--> statement-breakpoint
ALTER TABLE "athlete_profile" DROP CONSTRAINT "athlete_profile_age_range";--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "activity_date" date NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "felt_hard" boolean DEFAULT false NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "pain" boolean DEFAULT false NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "extra_plan_impact_confirmed" boolean DEFAULT false NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "consequence" jsonb;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD COLUMN "time_zone" text;--> statement-breakpoint
ALTER TABLE "import_source" ADD COLUMN "share_token_secret" text NOT NULL;--> statement-breakpoint
ALTER TABLE "import_source" ADD COLUMN "share_token_key" text NOT NULL;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD COLUMN "remote_key" text NOT NULL;--> statement-breakpoint
ALTER TABLE "training_plan" ADD COLUMN "completed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "training_plan" ADD COLUMN "archived_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "training_plan" ADD COLUMN "lifecycle_reason" text;--> statement-breakpoint
-- Composite tenant foreign keys require their referenced unique indexes to exist first.
CREATE UNIQUE INDEX "activity_id_user_unique" ON "activity" USING btree ("id","user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "goal_id_user_unique" ON "goal" USING btree ("id","user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_id_user_unique" ON "import_source" USING btree ("id","user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "training_plan_id_user_unique" ON "training_plan" USING btree ("id","user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "training_week_id_user_plan_unique" ON "training_week" USING btree ("id","user_id","plan_id");--> statement-breakpoint
CREATE UNIQUE INDEX "workout_id_user_unique" ON "workout" USING btree ("id","user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "workout_id_user_plan_unique" ON "workout" USING btree ("id","user_id","plan_id");--> statement-breakpoint
ALTER TABLE "activity_deletion_tombstone" ADD CONSTRAINT "activity_deletion_tombstone_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "plan_adjustment" ADD CONSTRAINT "plan_adjustment_workout_user_plan_fk" FOREIGN KEY ("workout_id","user_id","plan_id") REFERENCES "public"."workout"("id","user_id","plan_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "activity_deletion_tombstone_user_hash_unique" ON "activity_deletion_tombstone" USING btree ("user_id","file_hash");--> statement-breakpoint
CREATE INDEX "activity_deletion_tombstone_user_created_idx" ON "activity_deletion_tombstone" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "plan_adjustment_user_created_idx" ON "plan_adjustment" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "plan_adjustment_plan_created_idx" ON "plan_adjustment" USING btree ("plan_id","created_at");--> statement-breakpoint
CREATE INDEX "plan_adjustment_workout_created_idx" ON "plan_adjustment" USING btree ("workout_id","created_at");--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_workout_user_fk" FOREIGN KEY ("workout_id","user_id") REFERENCES "public"."workout"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "activity_import" ADD CONSTRAINT "activity_import_activity_user_fk" FOREIGN KEY ("activity_id","user_id") REFERENCES "public"."activity"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_source_user_fk" FOREIGN KEY ("source_id","user_id") REFERENCES "public"."import_source"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_activity_user_fk" FOREIGN KEY ("activity_id","user_id") REFERENCES "public"."activity"("id","user_id") ON DELETE no action ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_goal_user_fk" FOREIGN KEY ("goal_id","user_id") REFERENCES "public"."goal"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_plan_user_fk" FOREIGN KEY ("plan_id","user_id") REFERENCES "public"."training_plan"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_week_user_plan_fk" FOREIGN KEY ("week_id","user_id","plan_id") REFERENCES "public"."training_week"("id","user_id","plan_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_workout_user_fk" FOREIGN KEY ("workout_id","user_id") REFERENCES "public"."workout"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "activity_user_date_idx" ON "activity" USING btree ("user_id","activity_date");--> statement-breakpoint
CREATE INDEX "audit_event_created_idx" ON "audit_event" USING btree ("created_at");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_item_source_key_unique" ON "import_source_item" USING btree ("source_id","remote_key");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_user_share_unique" ON "import_source" USING btree ("user_id","share_host","share_token_key");--> statement-breakpoint
ALTER TABLE "activity_import" DROP COLUMN "file_name";--> statement-breakpoint
ALTER TABLE "import_source" DROP COLUMN "share_token";--> statement-breakpoint
ALTER TABLE "import_source_item" DROP COLUMN "remote_href";--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP COLUMN "perceived_effort";--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP COLUMN "pain_area";--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP COLUMN "note";--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_time_zone_nonempty" CHECK ("athlete_profile"."time_zone" is null or length(trim("athlete_profile"."time_zone")) between 1 and 255);--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_age_range" CHECK ("athlete_profile"."age_years" is null or "athlete_profile"."age_years" between 18 and 100);--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_lifecycle_reason_known" CHECK ("training_plan"."lifecycle_reason" is null or "training_plan"."lifecycle_reason" in ('completed', 'changed_goal', 'abandoned'));--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_lifecycle_status_consistent" CHECK ((
				("training_plan"."status" in ('active', 'draft') and "training_plan"."archived_at" is null and "training_plan"."completed_at" is null and "training_plan"."lifecycle_reason" is null)
				or
				("training_plan"."status" = 'archived' and "training_plan"."archived_at" is not null and "training_plan"."lifecycle_reason" is not null)
			));--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_completed_reason_consistent" CHECK ((
				("training_plan"."completed_at" is null and "training_plan"."lifecycle_reason" is distinct from 'completed')
				or
				("training_plan"."completed_at" is not null and "training_plan"."lifecycle_reason" = 'completed')
			));--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_lifecycle_chronology" CHECK ("training_plan"."completed_at" is null or "training_plan"."archived_at" is null or "training_plan"."completed_at" <= "training_plan"."archived_at");
