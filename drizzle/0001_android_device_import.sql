CREATE TABLE "android_device" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"label" text NOT NULL,
	"token_hash" text NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"last_seen_at" timestamp with time zone,
	"last_imported_at" timestamp with time zone,
	"revoked_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "android_device_id_user_unique" UNIQUE("id","user_id"),
	CONSTRAINT "android_device_label_length" CHECK (length(trim("android_device"."label")) between 1 and 60),
	CONSTRAINT "android_device_expiry_after_creation" CHECK ("android_device"."expires_at" > "android_device"."created_at")
);
--> statement-breakpoint
CREATE TABLE "android_import_request" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"device_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"content_key" text NOT NULL,
	"state" text DEFAULT 'processing' NOT NULL,
	"result" text,
	"reason" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone,
	CONSTRAINT "android_import_request_state_known" CHECK ("android_import_request"."state" in ('processing', 'completed')),
	CONSTRAINT "android_import_request_result_known" CHECK ("android_import_request"."result" is null or "android_import_request"."result" in ('imported', 'duplicate', 'quarantined')),
	CONSTRAINT "android_import_request_completion_consistent" CHECK (("android_import_request"."state" = 'processing' and "android_import_request"."result" is null and "android_import_request"."completed_at" is null) or ("android_import_request"."state" = 'completed' and "android_import_request"."result" is not null and "android_import_request"."completed_at" is not null))
);
--> statement-breakpoint
CREATE TABLE "android_pairing_request" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"code_hash" text NOT NULL,
	"expires_at" timestamp with time zone NOT NULL,
	"consumed_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "android_pairing_request_expiry_after_creation" CHECK ("android_pairing_request"."expires_at" > "android_pairing_request"."created_at")
);
--> statement-breakpoint
ALTER TABLE "android_device" ADD CONSTRAINT "android_device_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "android_import_request" ADD CONSTRAINT "android_import_request_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "android_import_request" ADD CONSTRAINT "android_import_request_device_user_fk" FOREIGN KEY ("device_id","user_id") REFERENCES "public"."android_device"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "android_pairing_request" ADD CONSTRAINT "android_pairing_request_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "android_device_token_hash_unique" ON "android_device" USING btree ("token_hash");--> statement-breakpoint
CREATE INDEX "android_device_user_active_idx" ON "android_device" USING btree ("user_id","revoked_at");--> statement-breakpoint
CREATE UNIQUE INDEX "android_import_request_device_request_unique" ON "android_import_request" USING btree ("device_id","request_id");--> statement-breakpoint
CREATE INDEX "android_import_request_user_created_idx" ON "android_import_request" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "android_import_request_device_updated_idx" ON "android_import_request" USING btree ("device_id","updated_at");--> statement-breakpoint
CREATE UNIQUE INDEX "android_pairing_request_code_hash_unique" ON "android_pairing_request" USING btree ("code_hash");--> statement-breakpoint
CREATE INDEX "android_pairing_request_user_created_idx" ON "android_pairing_request" USING btree ("user_id","created_at");--> statement-breakpoint
CREATE INDEX "android_pairing_request_expires_idx" ON "android_pairing_request" USING btree ("expires_at");