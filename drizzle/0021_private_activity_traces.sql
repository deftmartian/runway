ALTER TABLE "athlete_profile" ADD COLUMN "route_data_mode" text DEFAULT 'discard' NOT NULL;
--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "heart_rate_series" jsonb;
--> statement-breakpoint
ALTER TABLE "activity" ADD COLUMN "route_trace" jsonb;
--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_route_data_mode_known" CHECK ("athlete_profile"."route_data_mode" in ('discard', 'private'));
