DROP INDEX IF EXISTS "activity_import_hash_idx";--> statement-breakpoint
DROP INDEX IF EXISTS "training_week_plan_week_idx";--> statement-breakpoint
DROP INDEX IF EXISTS "passkey_credentialID_idx";--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "activity_import_user_hash_unique" ON "activity_import" USING btree ("user_id","file_hash");--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "training_plan_active_user_unique" ON "training_plan" USING btree ("user_id") WHERE "training_plan"."status" = 'active';--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "training_week_plan_week_unique" ON "training_week" USING btree ("plan_id","week_number");--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "account_provider_account_unique" ON "account" USING btree ("provider_id","account_id");--> statement-breakpoint
CREATE UNIQUE INDEX IF NOT EXISTS "passkey_credentialID_unique" ON "passkey" USING btree ("credential_id");--> statement-breakpoint
ALTER TABLE "activity" DROP CONSTRAINT IF EXISTS "activity_distance_nonnegative";--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_distance_nonnegative" CHECK ("activity"."distance_meters" >= 0);--> statement-breakpoint
ALTER TABLE "activity" DROP CONSTRAINT IF EXISTS "activity_duration_positive";--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_duration_positive" CHECK ("activity"."duration_seconds" is null or "activity"."duration_seconds" > 0);--> statement-breakpoint
ALTER TABLE "training_plan" DROP CONSTRAINT IF EXISTS "training_plan_weeks_range";--> statement-breakpoint
ALTER TABLE "training_plan" ADD CONSTRAINT "training_plan_weeks_range" CHECK ("training_plan"."weeks" between 1 and 52);--> statement-breakpoint
ALTER TABLE "training_week" DROP CONSTRAINT IF EXISTS "training_week_distance_nonnegative";--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_distance_nonnegative" CHECK ("training_week"."target_distance_meters" >= 0);--> statement-breakpoint
ALTER TABLE "training_week" DROP CONSTRAINT IF EXISTS "training_week_long_run_nonnegative";--> statement-breakpoint
ALTER TABLE "training_week" ADD CONSTRAINT "training_week_long_run_nonnegative" CHECK ("training_week"."long_run_meters" >= 0);--> statement-breakpoint
ALTER TABLE "workout" DROP CONSTRAINT IF EXISTS "workout_target_distance_nonnegative";--> statement-breakpoint
ALTER TABLE "workout" ADD CONSTRAINT "workout_target_distance_nonnegative" CHECK ("workout"."target_distance_meters" >= 0);--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP CONSTRAINT IF EXISTS "workout_feedback_completed_distance_nonnegative";--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_completed_distance_nonnegative" CHECK ("workout_feedback"."completed_distance_meters" is null or "workout_feedback"."completed_distance_meters" >= 0);--> statement-breakpoint
ALTER TABLE "workout_feedback" DROP CONSTRAINT IF EXISTS "workout_feedback_completed_duration_nonnegative";--> statement-breakpoint
ALTER TABLE "workout_feedback" ADD CONSTRAINT "workout_feedback_completed_duration_nonnegative" CHECK ("workout_feedback"."completed_duration_seconds" is null or "workout_feedback"."completed_duration_seconds" >= 0);
