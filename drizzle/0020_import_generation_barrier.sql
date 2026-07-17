ALTER TABLE "athlete_profile" ADD COLUMN "activity_import_generation" integer DEFAULT 0 NOT NULL;
--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_import_generation_nonnegative" CHECK ("athlete_profile"."activity_import_generation" >= 0);
