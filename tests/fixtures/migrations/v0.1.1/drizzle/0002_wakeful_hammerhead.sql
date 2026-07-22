CREATE TABLE "import_operation_lease" (
	"user_id" text PRIMARY KEY NOT NULL,
	"token" uuid NOT NULL,
	"operation" text NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD COLUMN "browser_folder_generation" integer DEFAULT 0 NOT NULL;--> statement-breakpoint
ALTER TABLE "import_operation_lease" ADD CONSTRAINT "import_operation_lease_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "import_operation_lease_expires_idx" ON "import_operation_lease" USING btree ("expires_at");--> statement-breakpoint
ALTER TABLE "athlete_profile" ADD CONSTRAINT "athlete_profile_browser_folder_generation_nonnegative" CHECK ("athlete_profile"."browser_folder_generation" >= 0);