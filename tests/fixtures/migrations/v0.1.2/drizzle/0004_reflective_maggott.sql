CREATE TABLE "import_source" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"type" text DEFAULT 'nextcloud_share' NOT NULL,
	"label" text NOT NULL,
	"share_host" text NOT NULL,
	"share_token" text NOT NULL,
	"share_password_secret" text NOT NULL,
	"enabled" boolean DEFAULT true NOT NULL,
	"sync_interval_minutes" integer DEFAULT 5 NOT NULL,
	"last_checked_at" timestamp,
	"last_success_at" timestamp,
	"last_imported_at" timestamp,
	"last_error" text,
	"created_at" timestamp DEFAULT now() NOT NULL,
	"updated_at" timestamp DEFAULT now() NOT NULL,
	CONSTRAINT "import_source_interval_positive" CHECK ("import_source"."sync_interval_minutes" > 0)
);
--> statement-breakpoint
CREATE TABLE "import_source_item" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"source_id" uuid NOT NULL,
	"remote_href" text NOT NULL,
	"etag" text,
	"content_length" integer,
	"last_modified_at" timestamp,
	"content_hash" text,
	"status" text NOT NULL,
	"activity_id" uuid,
	"first_seen_at" timestamp DEFAULT now() NOT NULL,
	"last_checked_at" timestamp DEFAULT now() NOT NULL,
	"imported_at" timestamp,
	"error_summary" text
);
--> statement-breakpoint
ALTER TABLE "import_source" ADD CONSTRAINT "import_source_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_source_id_import_source_id_fk" FOREIGN KEY ("source_id") REFERENCES "public"."import_source"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "import_source_item" ADD CONSTRAINT "import_source_item_activity_id_activity_id_fk" FOREIGN KEY ("activity_id") REFERENCES "public"."activity"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "import_source_user_enabled_idx" ON "import_source" USING btree ("user_id","enabled");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_user_share_unique" ON "import_source" USING btree ("user_id","share_host","share_token");--> statement-breakpoint
CREATE INDEX "import_source_item_user_checked_idx" ON "import_source_item" USING btree ("user_id","last_checked_at");--> statement-breakpoint
CREATE INDEX "import_source_item_source_status_idx" ON "import_source_item" USING btree ("source_id","status");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_item_source_href_unique" ON "import_source_item" USING btree ("source_id","remote_href");--> statement-breakpoint
CREATE UNIQUE INDEX "import_source_item_source_hash_unique" ON "import_source_item" USING btree ("source_id","content_hash");
