CREATE TYPE "public"."activity_review_state" AS ENUM('review', 'accepted');--> statement-breakpoint
CREATE TYPE "public"."deviation_classification" AS ENUM('near_plan', 'short', 'over', 'skipped', 'unplanned', 'not_applicable');--> statement-breakpoint
CREATE TYPE "public"."goal_kind" AS ENUM('race', 'foundation');--> statement-breakpoint
CREATE TYPE "public"."goal_state" AS ENUM('pending', 'active', 'completed', 'archived');--> statement-breakpoint
CREATE TYPE "public"."plan_decision" AS ENUM('keep_plan', 'reduce_next', 'next_rest', 'repeat_prescription', 'rebalance_week');--> statement-breakpoint
CREATE TYPE "public"."plan_phase" AS ENUM('distance', 'foundation', 'calibration');--> statement-breakpoint
CREATE TYPE "public"."workout_prescription_kind" AS ENUM('distance', 'timed', 'rest');--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "distance" DROP NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "review_state" "activity_review_state";--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "deviation" "deviation_classification" DEFAULT 'unplanned' NOT NULL;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "applied_decision" "plan_decision";--> statement-breakpoint
ALTER TABLE "goal" ADD COLUMN "kind" "goal_kind";--> statement-breakpoint
ALTER TABLE "goal" ADD COLUMN "state" "goal_state";--> statement-breakpoint
ALTER TABLE "training_plan" ADD COLUMN "phase" "plan_phase";--> statement-breakpoint
ALTER TABLE "training_plan" ADD COLUMN "plan_summary" jsonb;--> statement-breakpoint
ALTER TABLE "training_week" ADD COLUMN "target_duration_seconds" integer DEFAULT 0 NOT NULL;--> statement-breakpoint
ALTER TABLE "workout" ADD COLUMN "prescription_kind" "workout_prescription_kind";--> statement-breakpoint
ALTER TABLE "workout" ADD COLUMN "interval_structure" jsonb;--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD COLUMN "deviation" "deviation_classification";--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD COLUMN "applied_decision" "plan_decision";--> statement-breakpoint
UPDATE "activity"
SET "review_state" = CASE
	WHEN "source" = 'gpx' AND "workout_id" IS NULL AND "extra_plan_impact_confirmed" = false THEN 'review'::"activity_review_state"
	ELSE 'accepted'::"activity_review_state"
END;--> statement-breakpoint
UPDATE "goal"
SET
	"kind" = 'race'::"goal_kind",
	"state" = CASE
		WHEN EXISTS (
			SELECT 1 FROM "training_plan"
			WHERE "training_plan"."goal_id" = "goal"."id" AND "training_plan"."status" = 'active'
		) THEN 'active'::"goal_state"
		ELSE 'archived'::"goal_state"
	END;--> statement-breakpoint
UPDATE "training_plan"
SET
	"phase" = 'distance'::"plan_phase",
	"plan_summary" = jsonb_build_object('kind', 'distance') || "ramp_summary";--> statement-breakpoint
UPDATE "workout"
SET
	"prescription_kind" = CASE
		WHEN "type" = 'rest' THEN 'rest'::"workout_prescription_kind"
		WHEN "target_duration_seconds" IS NOT NULL THEN 'timed'::"workout_prescription_kind"
		ELSE 'distance'::"workout_prescription_kind"
	END,
	"interval_structure" = CASE
		WHEN "target_duration_seconds" IS NOT NULL THEN '[]'::jsonb
		ELSE NULL
	END;--> statement-breakpoint
UPDATE "workout_feedback"
SET "deviation" = CASE
	WHEN ("consequence" ->> 'deviation') IN ('near_plan', 'short', 'over', 'skipped', 'unplanned', 'not_applicable')
		THEN ("consequence" ->> 'deviation')::"deviation_classification"
	WHEN EXISTS (
		SELECT 1 FROM "workout"
		WHERE "workout"."id" = "workout_feedback"."workout_id" AND "workout"."status" = 'skipped'
	) THEN 'skipped'::"deviation_classification"
	ELSE 'not_applicable'::"deviation_classification"
END;--> statement-breakpoint
ALTER TABLE "activity" ALTER COLUMN "review_state" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "kind" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "state" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "training_plan" ALTER COLUMN "phase" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "training_plan" ALTER COLUMN "plan_summary" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "workout" ALTER COLUMN "prescription_kind" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "workout_feedback" ALTER COLUMN "deviation" SET NOT NULL;--> statement-breakpoint
CREATE UNIQUE INDEX "goal_current_user_unique" ON "goal" USING btree ("user_id") WHERE "goal"."state" in ('pending', 'active');--> statement-breakpoint
ALTER TABLE "training_plan" DROP COLUMN "ramp_summary";--> statement-breakpoint
ALTER TABLE "goal" ADD CONSTRAINT "goal_kind_distance_consistent" CHECK (("goal"."kind" = 'race' and "goal"."distance" is not null) or ("goal"."kind" = 'foundation' and "goal"."distance" is null));--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_duration_nonnegative" CHECK ("training_week"."target_duration_seconds" >= 0);--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_prescription_valid" CHECK ((
				("workout"."prescription_kind" = 'distance' and "workout"."target_distance_meters" > 0 and "workout"."target_duration_seconds" is null and "workout"."interval_structure" is null and "workout"."type" <> 'rest')
				or
				("workout"."prescription_kind" = 'timed' and "workout"."target_distance_meters" = 0 and "workout"."target_duration_seconds" > 0 and "workout"."interval_structure" is not null and "workout"."type" not in ('rest', 'race'))
				or
				("workout"."prescription_kind" = 'rest' and "workout"."target_distance_meters" = 0 and "workout"."target_duration_seconds" is null and "workout"."interval_structure" is null and "workout"."type" = 'rest')
			));
