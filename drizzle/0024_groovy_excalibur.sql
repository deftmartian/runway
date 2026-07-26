ALTER TYPE "public"."activity_source" ADD VALUE 'health_connect';--> statement-breakpoint
CREATE TABLE "health_connect_connection" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"device_id" uuid NOT NULL,
	"connected_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_synced_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
	,CONSTRAINT "health_connect_connection_id_user_unique" UNIQUE("id","user_id")
);
--> statement-breakpoint
CREATE TABLE "health_connect_external_activity" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"connection_id" uuid NOT NULL,
	"external_key" text NOT NULL,
	"origin_key" text NOT NULL,
	"origin_label" text NOT NULL,
	"fingerprint" text NOT NULL,
	"activity_id" uuid,
	"pending_action" text DEFAULT 'none' NOT NULL,
	"pending_activity" jsonb,
	"duplicate_candidate_activity_id" uuid,
	"deleted_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "health_connect_external_activity_pending_action_known" CHECK ("health_connect_external_activity"."pending_action" in ('none', 'correction', 'source_delete')),
	CONSTRAINT "health_connect_external_activity_pending_state_consistent" CHECK (("health_connect_external_activity"."pending_action" = 'none' and "health_connect_external_activity"."pending_activity" is null) or ("health_connect_external_activity"."pending_action" = 'correction' and "health_connect_external_activity"."pending_activity" is not null) or ("health_connect_external_activity"."pending_action" = 'source_delete' and "health_connect_external_activity"."pending_activity" is null))
);
--> statement-breakpoint
CREATE TABLE "health_connect_request_receipt" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"device_id" uuid NOT NULL,
	"request_id" uuid NOT NULL,
	"payload_key" text NOT NULL,
	"result" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "health_connect_tombstone" (
	"id" uuid PRIMARY KEY DEFAULT gen_random_uuid() NOT NULL,
	"user_id" text NOT NULL,
	"external_key" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "health_connect_connection" ADD CONSTRAINT "health_connect_connection_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_connection" ADD CONSTRAINT "health_connect_connection_device_user_fk" FOREIGN KEY ("device_id","user_id") REFERENCES "public"."android_device"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_external_activity" ADD CONSTRAINT "health_connect_external_activity_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_external_activity" ADD CONSTRAINT "health_connect_external_activity_connection_user_fk" FOREIGN KEY ("connection_id","user_id") REFERENCES "public"."health_connect_connection"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_external_activity" ADD CONSTRAINT "health_connect_external_activity_activity_fk" FOREIGN KEY ("activity_id") REFERENCES "public"."activity"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_external_activity" ADD CONSTRAINT "health_connect_external_activity_duplicate_fk" FOREIGN KEY ("duplicate_candidate_activity_id") REFERENCES "public"."activity"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_request_receipt" ADD CONSTRAINT "health_connect_request_receipt_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_request_receipt" ADD CONSTRAINT "health_connect_request_receipt_device_user_fk" FOREIGN KEY ("device_id","user_id") REFERENCES "public"."android_device"("id","user_id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "health_connect_tombstone" ADD CONSTRAINT "health_connect_tombstone_user_id_user_id_fk" FOREIGN KEY ("user_id") REFERENCES "public"."user"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "health_connect_connection_device_unique" ON "health_connect_connection" USING btree ("device_id");--> statement-breakpoint
CREATE INDEX "health_connect_connection_user_idx" ON "health_connect_connection" USING btree ("user_id");--> statement-breakpoint
CREATE UNIQUE INDEX "health_connect_external_activity_connection_key_unique" ON "health_connect_external_activity" USING btree ("connection_id","external_key");--> statement-breakpoint
CREATE INDEX "health_connect_external_activity_user_fingerprint_idx" ON "health_connect_external_activity" USING btree ("user_id","fingerprint");--> statement-breakpoint
CREATE UNIQUE INDEX "health_connect_request_receipt_device_request_unique" ON "health_connect_request_receipt" USING btree ("device_id","request_id");--> statement-breakpoint
CREATE UNIQUE INDEX "health_connect_tombstone_user_key_unique" ON "health_connect_tombstone" USING btree ("user_id","external_key");
