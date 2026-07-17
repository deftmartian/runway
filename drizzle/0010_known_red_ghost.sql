ALTER TABLE "activity" ADD COLUMN "max_heart_rate" integer;--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "heart_rate_summary" jsonb;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD COLUMN "age_years" integer;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD COLUMN "weight_kilograms" real;--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD COLUMN "heart_rate_settings" jsonb;--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_average_heart_rate_range" CHECK ("activity"."average_heart_rate" is null or "activity"."average_heart_rate" between 30 and 240);--> statement-breakpoint
ALTER TABLE "activity" ADD CONSTRAINT "activity_max_heart_rate_range" CHECK ("activity"."max_heart_rate" is null or "activity"."max_heart_rate" between 30 and 260);--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_age_range" CHECK ("athlete_profile"."age_years" is null or "athlete_profile"."age_years" between 10 and 100);--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_weight_range" CHECK ("athlete_profile"."weight_kilograms" is null or "athlete_profile"."weight_kilograms" between 25 and 300);