CREATE TYPE "public"."start_mode" AS ENUM('established', 'foundation_to_goal', 'foundation_only', 'calibration');--> statement-breakpoint
ALTER TABLE "goal" ADD COLUMN "start_mode" "start_mode";--> statement-breakpoint
UPDATE "goal" SET "start_mode" = 'established'::"start_mode";--> statement-breakpoint
ALTER TABLE "goal" ALTER COLUMN "start_mode" SET NOT NULL;--> statement-breakpoint
ALTER TABLE "goal" ADD CONSTRAINT "goal_start_mode_consistent" CHECK (("goal"."start_mode" = 'foundation_only' and "goal"."kind" = 'foundation') or ("goal"."start_mode" <> 'foundation_only'));
